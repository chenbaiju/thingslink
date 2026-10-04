package com.things.link.ingestion.application;

import com.things.link.ingestion.infrastructure.websocket.RealtimeWebSocketProperties;
import com.things.link.project.application.EffectiveQuotaPolicy;
import com.things.link.project.application.EffectiveQuotaPolicyProvider;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 本机 WebSocket 会话、订阅索引与有界异步发送队列。
 *
 * <p>ADR 0016 将跨实例同步交给 Redis Pub/Sub，本类只负责本机扇出。Redis 监听器收到项目频道
 * 消息后调用 {@link #fanout(RealtimePropertyBatch)} 即可；本类绝不反向依赖 Redis，因此 Redis
 * 故障不可能阻塞事实事务或导致数据库写入回滚。</p>
 */
@Service
public class RealtimeSubscriptionRegistry {

    /** RFC 6455 1013：服务暂时过载，客户端可稍后重新连接。 */
    private static final int SLOW_CONSUMER_CLOSE_CODE = 1013;

    /** 租户共享套餐额度拒绝原因；控制台据此与认证失败及本机物理上限区分。 */
    private static final String TENANT_CONNECTION_QUOTA_CLOSE_REASON = "tenant connection quota exceeded";

    /** 本机固定物理连接上限拒绝原因；该保护不应被展示为租户套餐已用尽。 */
    private static final String LOCAL_CONNECTION_LIMIT_CLOSE_REASON = "realtime connection limit";

    /** 控制帧严格限量，避免恶意 SUBSCRIBE/PING 用 ACK 与错误响应耗尽内存。 */
    private static final int MAX_CONTROL_FRAMES = 16;

    /** ADR0072：一次成功权威复核最多允许后续发送十秒。 */
    private static final Duration AUTHORIZATION_PERIOD = Duration.ofSeconds(10);

    /** 每五秒提前复核，为一次瞬时数据库故障保留原授权期内的恢复机会。 */
    private static final Duration AUTHORIZATION_RECHECK = Duration.ofSeconds(5);

    /** Redis连接租约仍按原十秒心跳续期，与数据库授权复核线程相互隔离。 */
    private static final Duration TENANT_LEASE_HEARTBEAT = Duration.ofSeconds(10);

    /** 确定成员失权或项目删除使用策略违规关闭码。 */
    private static final int AUTHORIZATION_REVOKED_CLOSE_CODE = 1008;

    /** 权威数据库持续不可用直到授权期耗尽属于服务端故障。 */
    private static final int AUTHORIZATION_UNAVAILABLE_CLOSE_CODE = 1011;

    /** 全局锁同时保护多个限额计数与会话替换，避免并发下先检查后超额。 */
    private final Object monitor = new Object();

    /** 统一 JSON 序列化器，保证 fanout payload 和控制响应使用同一配置。 */
    private final ObjectMapper objectMapper;

    /** 不可信浏览器的硬资源配额。 */
    private final RealtimeWebSocketProperties properties;

    /** WebSocket 会话、订阅和有界队列的低基数运行指标。 */
    private final RealtimeMetrics metrics;

    /** project 模块提供的有效租户策略；owner tenant 必须由可信 projectId 派生。 */
    private final EffectiveQuotaPolicyProvider quotaPolicyProvider;

    /** 租户共享连接数的跨实例 Redis 租约。 */
    private final TenantConnectionLease tenantConnectionLease;

    /** 项目成员资格权威复核；Redis fanout线程从不直接调用。 */
    private final RealtimeAuthorizationService authorizationService;

    /** JWT和授权截止统一使用UTC时钟。 */
    private final Clock clock;

    /** 每会话一个虚拟线程，可容忍慢网络而不占用平台线程池。 */
    private final ExecutorService senderExecutor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("realtime-ws-sender-", 0).factory());

    /** 单线程维护数据库授权，避免每个会话各占一个定时任务；守护线程不阻止JVM退出。 */
    private final ScheduledExecutorService maintenanceExecutor = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("realtime-ws-maintenance").factory());

    /** Redis租约续期使用独立守护线程，慢数据库复核不能阻塞租约心跳。 */
    private final ScheduledExecutorService leaseHeartbeatExecutor = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("realtime-ws-lease-heartbeat").factory());

    /** 本实例所有活跃会话。 */
    private final Map<String, ClientState> sessions = new HashMap<>();

    /** 单账号会话计数。 */
    private final Map<UUID, Integer> accountConnections = new HashMap<>();

    /** 单项目会话计数。 */
    private final Map<UUID, Integer> projectConnections = new HashMap<>();

    /** 单账号所有会话的去重属性订阅总数。 */
    private final Map<UUID, Integer> accountSubscriptions = new HashMap<>();

    /** 单项目所有会话的去重属性订阅总数。 */
    private final Map<UUID, Integer> projectSubscriptions = new HashMap<>();

    /**
     * 创建本机订阅注册表。
     *
     * @param objectMapper 平台 JSON 序列化器
     * @param properties 固定资源上限
     * @param metrics 低基数运行指标
     * @param quotaPolicyProvider 有效租户策略提供者
     * @param tenantConnectionLease 跨实例租户连接租约
     * @param authorizationService 项目成员资格权威复核
     */
    @Autowired
    public RealtimeSubscriptionRegistry(ObjectMapper objectMapper, RealtimeWebSocketProperties properties,
                                        RealtimeMetrics metrics, EffectiveQuotaPolicyProvider quotaPolicyProvider,
                                        TenantConnectionLease tenantConnectionLease,
                                        RealtimeAuthorizationService authorizationService) {
        this(objectMapper, properties, metrics, quotaPolicyProvider, tenantConnectionLease,
                authorizationService, Clock.systemUTC());
    }

    /**
     * 可替换时钟的内部构造器，为截止边界提供确定性测试而不增加运行配置。
     *
     * @param objectMapper 统一JSON序列化器
     * @param properties 固定资源上限
     * @param metrics 低基数运行指标
     * @param quotaPolicyProvider 有效租户策略提供者
     * @param tenantConnectionLease 跨实例租户连接租约
     * @param authorizationService 项目成员资格权威复核
     * @param clock UTC时钟
     */
    RealtimeSubscriptionRegistry(ObjectMapper objectMapper, RealtimeWebSocketProperties properties,
                                 RealtimeMetrics metrics, EffectiveQuotaPolicyProvider quotaPolicyProvider,
                                 TenantConnectionLease tenantConnectionLease,
                                 RealtimeAuthorizationService authorizationService, Clock clock) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.metrics = metrics;
        this.quotaPolicyProvider = quotaPolicyProvider;
        this.tenantConnectionLease = tenantConnectionLease;
        this.authorizationService = authorizationService;
        this.clock = clock;
        maintenanceExecutor.scheduleAtFixedRate(this::maintainAuthorizationsAndLeases,
                AUTHORIZATION_RECHECK.toMillis(), AUTHORIZATION_RECHECK.toMillis(), TimeUnit.MILLISECONDS);
        leaseHeartbeatExecutor.scheduleAtFixedRate(this::renewTenantLeases,
                TENANT_LEASE_HEARTBEAT.toMillis(), TENANT_LEASE_HEARTBEAT.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * 登记已通过握手认证的会话并启动专属发送 worker。
     *
     * <p>本入口只接受握手拦截器已经验签并检查JWT时效的连接，同时再次权威回查项目，避免握手与
     * 登记之间的撤权窗口。首个十秒发送截止严格从本次回查成功返回时计算。</p>
     *
     * @param connection 真实连接适配器
     * @return 是否在全部连接上限内被接受；拒绝时本方法已关闭连接
     */
    public boolean register(RealtimeConnection connection) {
        if (connection.principal().expiredAt(clock.instant())) {
            connection.close(AUTHORIZATION_REVOKED_CLOSE_CODE, "realtime token expired");
            return false;
        }
        RealtimeMetrics.LimitScope localLimit = localConnectionLimit(connection);
        if (localLimit != null) {
            return rejectConnection(connection, localLimit);
        }

        Instant authorizationCheckedAt;
        try {
            // register是独立公开入口，不能只相信调用方声称握手已经完成；此处重新读取当前项目资格。
            authorizationService.requireProjectAccess(connection.principal());
            authorizationCheckedAt = clock.instant();
        } catch (RealtimeProjectAccessDeniedException denied) {
            connection.close(AUTHORIZATION_REVOKED_CLOSE_CODE, "realtime authorization revoked");
            return false;
        } catch (RuntimeException unavailable) {
            connection.close(AUTHORIZATION_UNAVAILABLE_CLOSE_CODE, "realtime authorization unavailable");
            return false;
        }
        // 权威查询期间JWT可能到期；成功结果不能越过令牌的绝对截止建立会话。
        if (connection.principal().expiredAt(authorizationCheckedAt)) {
            connection.close(AUTHORIZATION_REVOKED_CLOSE_CODE, "realtime token expired");
            return false;
        }
        Instant authorizationUntil = authorizationCheckedAt.plus(AUTHORIZATION_PERIOD);

        EffectiveQuotaPolicy policy = null;
        try {
            // WebSocket线程没有Bearer过滤器；显式范围让provider由项目事实解析owner tenant与共享套餐。
            policy = authorizationService.inPrincipalScope(connection.principal(), () ->
                    quotaPolicyProvider.resolveTrustedProject(connection.principal().projectId()));
        } catch (RuntimeException exception) {
            // owner tenant 无法判定时不能猜测或创建错误租约；架构 8.5 要求 WebSocket 使用本机有限安全默认。
            metrics.recordQuotaDecision(RealtimeMetrics.QuotaDecision.SAFE_DEFAULT);
        }
        TenantConnectionLease.ConnectionLease lease = null;
        if (policy != null) {
            Long connectionLimit = policy.websocketConnectionLimit();
            if (connectionLimit != null && connectionLimit == 0L) {
                metrics.recordQuotaDecision(RealtimeMetrics.QuotaDecision.REJECTED);
                return rejectConnection(connection, RealtimeMetrics.LimitScope.TENANT_CONNECTION);
            }
            if (connectionLimit == null) {
                // 只有成功加载策略后得到的显式 NULL 才是不限；仍保留本机 global/account/project 物理上限。
                metrics.recordQuotaDecision(RealtimeMetrics.QuotaDecision.UNLIMITED);
            } else {
                lease = tenantConnectionLease.create(policy.tenantId(), connection.id());
                TenantConnectionLease.LeaseDecision leaseDecision = tenantConnectionLease.acquire(
                        lease, connectionLimit);
                if (leaseDecision == TenantConnectionLease.LeaseDecision.REJECTED) {
                    metrics.recordQuotaDecision(RealtimeMetrics.QuotaDecision.REJECTED);
                    return rejectConnection(connection, RealtimeMetrics.LimitScope.TENANT_CONNECTION);
                }
                if (leaseDecision == TenantConnectionLease.LeaseDecision.UNAVAILABLE) {
                    // 本机上限已通过；Redis 故障只能失去共享精度，不能变成无界连接。
                    metrics.recordQuotaDecision(RealtimeMetrics.QuotaDecision.FAIL_OPEN);
                } else {
                    metrics.recordQuotaDecision(RealtimeMetrics.QuotaDecision.ACQUIRED);
                }
            }
        }

        ClientState state = new ClientState(connection, lease, authorizationUntil);
        Instant registrationAt = clock.instant();
        if (connection.principal().expiredAt(registrationAt)) {
            state.releaseLease();
            connection.close(AUTHORIZATION_REVOKED_CLOSE_CODE, "realtime token expired");
            return false;
        }
        if (state.authorizationExpired(registrationAt)) {
            // 配额或Redis依赖耗尽了刚取得的十秒授权期时必须重新连接，不能登记一个已失效状态。
            state.releaseLease();
            connection.close(AUTHORIZATION_UNAVAILABLE_CLOSE_CODE, "realtime authorization unavailable");
            return false;
        }
        RealtimeMetrics.LimitScope postLeaseLimit;
        synchronized (monitor) {
            // Redis 往返期间另一线程可能刚登记本机会话，因此进入索引前必须重新检查物理上限。
            postLeaseLimit = localConnectionLimitLocked(connection);
            if (postLeaseLimit == null) {
                RealtimePrincipal principal = connection.principal();
                sessions.put(connection.id(), state);
                increase(accountConnections, principal.accountId(), 1);
                increase(projectConnections, principal.projectId(), 1);
                metrics.sessionOpened();
            }
        }
        if (postLeaseLimit != null) {
            // Redis I/O 永远不持有本机会话锁，否则依赖抖动会连带阻塞所有会话清理与 fanout。
            state.releaseLease();
            return rejectConnection(connection, postLeaseLimit);
        }
        senderExecutor.submit(() -> sendLoop(state));
        return true;
    }

    /**
     * 断开时清理索引、计数与发送 worker。
     *
     * @param connectionId 本机 WebSocket 会话 ID
     */
    public void unregister(String connectionId) {
        detach(connectionId);
    }

    /**
     * 成功权威项目复核后，把当前会话的发送截止延长一个固定授权期。
     *
     * @param connectionId 本机会话ID
     */
    public void refreshAuthorization(String connectionId) {
        ClientState state;
        synchronized (monitor) {
            state = sessions.get(connectionId);
        }
        if (state != null) {
            // SUBSCRIBE查询也可能跨过旧截止；共用同一门防止查询返回后复活已过期会话。
            refreshIfCurrent(state, clock.instant());
        }
    }

    /**
     * 先原子移除索引并清空队列、释放租约，再关闭确定失权或故障超时的连接。
     *
     * @param connectionId 本机会话ID
     * @param statusCode RFC 6455稳定关闭码
     * @param reason 不含业务数据的稳定原因
     */
    public void revoke(String connectionId, int statusCode, String reason) {
        ClientState state = detach(connectionId);
        if (state != null) {
            state.connection.close(statusCode, reason);
        }
    }

    /** 删除会话全部本机事实并返回原状态；所有Redis I/O都在全局锁外执行。 */
    private ClientState detach(String connectionId) {
        return detach(connectionId, null);
    }

    /**
     * 仅当ID仍指向期望状态时删除；expected为null表示删除当前状态。
     *
     * @param connectionId 本机会话ID
     * @param expected 异步回调最初观察的状态，null表示不做代次比较
     * @return 真正删除的状态，已更换或不存在返回null
     */
    private ClientState detach(String connectionId, ClientState expected) {
        ClientState state;
        synchronized (monitor) {
            state = sessions.get(connectionId);
            if (state == null || (expected != null && state != expected)) {
                return null;
            }
            sessions.remove(connectionId);
            RealtimePrincipal principal = state.connection.principal();
            increase(accountConnections, principal.accountId(), -1);
            increase(projectConnections, principal.projectId(), -1);
            int subscriptions = state.subscriptionCount();
            increase(accountSubscriptions, principal.accountId(), -subscriptions);
            increase(projectSubscriptions, principal.projectId(), -subscriptions);
            metrics.sessionClosed();
            metrics.subscriptionsChanged(-subscriptions);
        }
        state.stop();
        state.releaseLease();
        return state;
    }

    /**
     * 原子替换整个会话订阅集合；重复 device + property 自动去重。
     *
     * @param connectionId 会话 ID
     * @param requested 按设备分组的属性键集合
     * @throws IllegalArgumentException 会话不存在、参数非法或全局订阅限额已满
     */
    public void replaceSubscriptions(String connectionId, Map<UUID, Set<String>> requested) {
        Map<UUID, Set<String>> normalized = normalizeSubscriptions(requested);
        int replacementCount = subscriptionCount(normalized);
        if (replacementCount > properties.maxSessionSubscriptions()) {
            metrics.recordLimitRejected(RealtimeMetrics.LimitScope.SESSION_SUBSCRIPTION);
            throw new IllegalArgumentException("单会话属性订阅超过上限");
        }
        synchronized (monitor) {
            ClientState state = requireState(connectionId);
            RealtimePrincipal principal = state.connection.principal();
            int previousCount = state.subscriptionCount();
            int accountAfter = count(accountSubscriptions, principal.accountId()) - previousCount + replacementCount;
            int projectAfter = count(projectSubscriptions, principal.projectId()) - previousCount + replacementCount;
            if (accountAfter > properties.maxAccountSubscriptions()) {
                metrics.recordLimitRejected(RealtimeMetrics.LimitScope.ACCOUNT_SUBSCRIPTION);
                throw new IllegalArgumentException("账号属性订阅超过上限");
            }
            if (projectAfter > properties.maxProjectSubscriptions()) {
                metrics.recordLimitRejected(RealtimeMetrics.LimitScope.PROJECT_SUBSCRIPTION);
                throw new IllegalArgumentException("项目属性订阅超过上限");
            }
            state.replaceSubscriptions(normalized);
            increase(accountSubscriptions, principal.accountId(), replacementCount - previousCount);
            increase(projectSubscriptions, principal.projectId(), replacementCount - previousCount);
            metrics.subscriptionsChanged(replacementCount - previousCount);
        }
    }

    /**
     * 将已经提交的属性增量投递给本机匹配订阅。
     *
     * <p>本入口只消费项目、设备、属性三元组，不查询数据库也不检查 Redis。慢客户端的旧属性会在
     * 每会话队列中按 device + property 合并；仍满时只关闭该会话，绝不阻塞调用它的 Redis 监听线程。</p>
     *
     * @param batch 已提交的属性增量
     */
    public void fanout(RealtimePropertyBatch batch) {
        List<ClientState> targets;
        synchronized (monitor) {
            targets = new ArrayList<>(sessions.values());
        }
        for (ClientState state : targets) {
            if (!state.connection.principal().projectId().equals(batch.projectId())) {
                continue;
            }
            Set<String> subscribed = state.propertiesFor(batch.deviceId());
            for (Map.Entry<String, JsonNode> property : batch.properties().entrySet()) {
                if (!subscribed.contains(property.getKey())) {
                    continue;
                }
                if (!state.enqueueProperty(new PropertyUpdate(batch.projectId(), batch.deviceId(), property.getKey(),
                        property.getValue(), batch.occurredAt(), batch.shadowVersion(),
                        batch.reportedRevisions().containsKey(property.getKey())
                                ? Long.valueOf(batch.reportedRevisions().get(property.getKey())) : null, batch.thingModelVersionId()))) {
                    closeSlowConsumer(state);
                    break;
                }
            }
        }
    }

    /**
     * 在会话的有界控制队列中发送订阅确认、错误或心跳。
     *
     * @param connectionId 会话 ID
     * @param payload 文本协议 payload
     */
    public void sendControl(String connectionId, String payload) {
        ClientState state;
        synchronized (monitor) {
            state = sessions.get(connectionId);
        }
        if (state != null && !state.enqueueControl(payload)) {
            closeSlowConsumer(state);
        }
    }

    /** @return 当前本机活跃连接数，供单元测试与指标适配器读取。 */
    public int connectionCount() {
        synchronized (monitor) {
            return sessions.size();
        }
    }

    /** 进程退出时停止 sender 虚拟线程并关闭本机会话，避免容器重启遗留等待线程。 */
    @PreDestroy
    void shutdown() {
        List<ClientState> current;
        synchronized (monitor) {
            current = new ArrayList<>(sessions.values());
            for (ClientState state : current) {
                metrics.sessionClosed();
                metrics.subscriptionsChanged(-state.subscriptionCount());
            }
            sessions.clear();
            accountConnections.clear();
            projectConnections.clear();
            accountSubscriptions.clear();
            projectSubscriptions.clear();
        }
        current.forEach(state -> {
            state.stop();
            state.releaseLease();
        });
        maintenanceExecutor.shutdownNow();
        leaseHeartbeatExecutor.shutdownNow();
        senderExecutor.shutdownNow();
    }

    /**
     * ADR0072/0073：按唯一账号、项目与授权代次组合复核权威资格。
     *
     * <p>数据库异常不延长原发送截止；五秒后仍可重试一次，十秒截止耗尽才以1011收束。
     * 方法名为兼容包级测试保留，Redis租约已由独立十秒守护线程续期，慢数据库查询不会阻塞它。</p>
     */
    void maintainAuthorizationsAndLeases() {
        Map<AuthorizationKey, List<ClientState>> groups = new LinkedHashMap<>();
        List<ClientState> current;
        synchronized (monitor) {
            current = new ArrayList<>(sessions.values());
        }
        Instant beforeCheck = clock.instant();
        for (ClientState state : current) {
            if (!isCurrent(state)) {
                continue;
            }
            if (state.connection.principal().expiredAt(beforeCheck)) {
                revokeIfCurrent(state, AUTHORIZATION_REVOKED_CLOSE_CODE, "realtime token expired");
                continue;
            }
            if (state.authorizationExpired(beforeCheck)) {
                revokeIfCurrent(state, AUTHORIZATION_UNAVAILABLE_CLOSE_CODE,
                        "realtime authorization unavailable");
                continue;
            }
            RealtimePrincipal principal = state.connection.principal();
            AuthorizationKey key = new AuthorizationKey(
                    principal.accountId(), principal.projectId(), principal.projectGeneration());
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(state);
        }
        for (List<ClientState> group : groups.values()) {
            // 前一组查询可能已耗时，故每组访问数据库前再次剔除已过JWT或授权截止的连接。
            List<ClientState> candidates = authorizationCandidates(group, clock.instant());
            if (candidates.isEmpty()) {
                continue;
            }
            RealtimePrincipal representative = candidates.getFirst().connection.principal();
            try {
                authorizationService.requireProjectAccess(representative);
                Instant checkedAt = clock.instant();
                // 成功查询也先过旧截止和JWT截止；已到期状态不能凭较晚返回的结果复活。
                candidates.forEach(state -> refreshIfCurrent(state, checkedAt));
            } catch (RealtimeProjectAccessDeniedException denied) {
                candidates.forEach(state -> revokeIfCurrent(
                        state, AUTHORIZATION_REVOKED_CLOSE_CODE, "realtime authorization revoked"));
            } catch (RuntimeException unavailable) {
                // 不延长截止；本轮剩余授权期内仍可由下一次五秒复核恢复，耗尽才关闭。
                authorizationCandidates(candidates, clock.instant());
            }
        }
    }

    /** 查询成功后仍须先过旧截止；仅当前对象可以续期，旧回调不能复活同ID后继会话。 */
    private void refreshIfCurrent(ClientState state, Instant checkedAt) {
        if (state.connection.principal().expiredAt(checkedAt)) {
            revokeIfCurrent(state, AUTHORIZATION_REVOKED_CLOSE_CODE, "realtime token expired");
            return;
        }
        if (state.authorizationExpired(checkedAt)) {
            revokeIfCurrent(state, AUTHORIZATION_UNAVAILABLE_CLOSE_CODE,
                    "realtime authorization unavailable");
            return;
        }
        synchronized (monitor) {
            if (sessions.get(state.connection.id()) == state) {
                state.refreshAuthorization(checkedAt);
            }
        }
    }

    /**
     * 筛出仍可使用原授权期的当前状态，并立即收束已到期状态。
     *
     * @param states 同一账号与项目的候选状态
     * @param now 本轮边界判断时刻
     * @return 仍登记且JWT、授权期都未到期的状态
     */
    private List<ClientState> authorizationCandidates(List<ClientState> states, Instant now) {
        List<ClientState> candidates = new ArrayList<>();
        for (ClientState state : states) {
            if (!isCurrent(state)) {
                continue;
            }
            if (state.connection.principal().expiredAt(now)) {
                revokeIfCurrent(state, AUTHORIZATION_REVOKED_CLOSE_CODE, "realtime token expired");
            } else if (state.authorizationExpired(now)) {
                revokeIfCurrent(state, AUTHORIZATION_UNAVAILABLE_CLOSE_CODE,
                        "realtime authorization unavailable");
            } else {
                candidates.add(state);
            }
        }
        return candidates;
    }

    /** @param state 异步维护观察到的状态 @return 是否仍为该连接ID当前登记的对象 */
    private boolean isCurrent(ClientState state) {
        synchronized (monitor) {
            return sessions.get(state.connection.id()) == state;
        }
    }

    /** 仅收束仍登记的同一状态对象，避免旧复核结果关闭同ID新连接。 */
    private void revokeIfCurrent(ClientState state, int statusCode, String reason) {
        ClientState detached = detach(state.connection.id(), state);
        if (detached != null) {
            detached.connection.close(statusCode, reason);
        }
    }

    /**
     * 在 Redis I/O 前快速检查本机物理上限；正式登记前还会在锁内复验一次。
     *
     * @param connection 待登记连接
     * @return 命中的本机限制，未命中返回 null
     */
    private RealtimeMetrics.LimitScope localConnectionLimit(RealtimeConnection connection) {
        synchronized (monitor) {
            return localConnectionLimitLocked(connection);
        }
    }

    /** 调用方持有 monitor 时判定本机全局、账号和项目物理上限。 */
    private RealtimeMetrics.LimitScope localConnectionLimitLocked(RealtimeConnection connection) {
        RealtimePrincipal principal = connection.principal();
        if (sessions.containsKey(connection.id()) || sessions.size() >= properties.maxGlobalConnections()) {
            return RealtimeMetrics.LimitScope.GLOBAL_CONNECTION;
        }
        if (count(accountConnections, principal.accountId()) >= properties.maxAccountConnections()) {
            return RealtimeMetrics.LimitScope.ACCOUNT_CONNECTION;
        }
        if (count(projectConnections, principal.projectId()) >= properties.maxProjectConnections()) {
            return RealtimeMetrics.LimitScope.PROJECT_CONNECTION;
        }
        return null;
    }

    /**
     * 为当前会话续租。续租失败只记录低基数 fail-open，已有连接不因辅助计数故障被踢下线。
     */
    void renewTenantLeases() {
        List<ClientState> current;
        synchronized (monitor) {
            current = new ArrayList<>(sessions.values());
        }
        for (ClientState state : current) {
            TenantConnectionLease.RenewDecision decision;
            try {
                decision = state.renewLease();
            } catch (RuntimeException exception) {
                // 一个适配器异常不能终止 ScheduledExecutor 的全部后续心跳；已有连接继续由本机硬上限保护。
                decision = TenantConnectionLease.RenewDecision.UNAVAILABLE;
            }
            if (decision == TenantConnectionLease.RenewDecision.UNAVAILABLE) {
                metrics.recordQuotaDecision(RealtimeMetrics.QuotaDecision.FAIL_OPEN);
            } else if (decision == TenantConnectionLease.RenewDecision.LOST) {
                metrics.recordQuotaDecision(RealtimeMetrics.QuotaDecision.LEASE_LOST);
            }
        }
    }

    /** 发送 worker 串行调用单会话底层 send，Spring WebSocketSession 不允许多线程并发发送。 */
    private void sendLoop(ClientState state) {
        while (true) {
            OutboundFrame frame = state.takeNext(objectMapper);
            if (frame == null) {
                return;
            }
            if (frame.propertyFrame()) {
                Instant now = clock.instant();
                if (state.connection.principal().expiredAt(now)) {
                    revokeIfCurrent(state, AUTHORIZATION_REVOKED_CLOSE_CODE, "realtime token expired");
                    return;
                }
                if (state.authorizationExpired(now)) {
                    revokeIfCurrent(state, AUTHORIZATION_UNAVAILABLE_CLOSE_CODE,
                            "realtime authorization unavailable");
                    return;
                }
            }
            try {
                state.connection.sendText(frame.payload());
            } catch (Exception exception) {
                metrics.recordSendFailure();
                revokeIfCurrent(state, 1011, "realtime send failed");
                return;
            }
        }
    }

    /** 队列满只切断慢会话，先删除索引以确保之后不再继续入队。 */
    private void closeSlowConsumer(ClientState state) {
        revokeIfCurrent(state, SLOW_CONSUMER_CLOSE_CODE, "realtime slow consumer");
    }

    /**
     * 以稳定关闭码拒绝连接，并按固定配额层级记录低基数指标。
     *
     * @param connection 被拒绝的连接
     * @param scope 命中的固定资源层级
     * @return 恒为 false，方便登记入口直接返回
     */
    private boolean rejectConnection(RealtimeConnection connection, RealtimeMetrics.LimitScope scope) {
        metrics.recordLimitRejected(scope);
        String reason = scope == RealtimeMetrics.LimitScope.TENANT_CONNECTION
                ? TENANT_CONNECTION_QUOTA_CLOSE_REASON
                : LOCAL_CONNECTION_LIMIT_CLOSE_REASON;
        connection.close(1008, reason);
        return false;
    }

    /** 返回会话，未登记会话一律失败，避免过期回调重建索引。 */
    private ClientState requireState(String connectionId) {
        ClientState state = sessions.get(connectionId);
        if (state == null) {
            throw new IllegalArgumentException("实时会话不存在");
        }
        return state;
    }

    /** 深复制并去重调用方输入，防止其在替换完成后篡改订阅索引。 */
    private static Map<UUID, Set<String>> normalizeSubscriptions(Map<UUID, Set<String>> requested) {
        if (requested == null || requested.isEmpty()) {
            throw new IllegalArgumentException("订阅不能为空");
        }
        Map<UUID, Set<String>> normalized = new LinkedHashMap<>();
        requested.forEach((deviceId, propertyKeys) -> {
            if (deviceId == null || propertyKeys == null || propertyKeys.isEmpty()) {
                throw new IllegalArgumentException("设备与属性订阅不能为空");
            }
            Set<String> keys = new LinkedHashSet<>(propertyKeys);
            if (keys.stream().anyMatch(key -> key == null || !key.matches("[A-Za-z0-9_-]{1,64}"))) {
                throw new IllegalArgumentException("订阅包含非法属性标识符");
            }
            normalized.put(deviceId, Set.copyOf(keys));
        });
        return Map.copyOf(normalized);
    }

    /** 统计 device × property 数量。 */
    private static int subscriptionCount(Map<UUID, Set<String>> subscriptions) {
        return subscriptions.values().stream().mapToInt(Set::size).sum();
    }

    /** 获取不存在键时按零处理。 */
    private static int count(Map<UUID, Integer> counters, UUID key) {
        return counters.getOrDefault(key, 0);
    }

    /** 变更计数并在归零时移除，避免用户/项目历史 ID 造成无界 Map。 */
    private static void increase(Map<UUID, Integer> counters, UUID key, int delta) {
        int next = count(counters, key) + delta;
        if (next <= 0) {
            counters.remove(key);
        } else {
            counters.put(key, next);
        }
    }

    /** 单会话的订阅索引和两类有界待发数据。 */
    private final class ClientState {

        /** 底层连接。 */
        private final RealtimeConnection connection;
        /** 项目所有者租户的跨实例连接租约；权威策略显式不限时为 null。 */
        private final TenantConnectionLease.ConnectionLease tenantLease;
        /** 心跳与释放串行化，防止注销后并发心跳重新创建僵尸租约。 */
        private final Object leaseMonitor = new Object();
        /** 释放后永久关闭续租入口。 */
        private boolean leaseActive;
        /** 单会话锁只保护该会话发送队列，不阻塞其他连接的 fanout。 */
        private final Object queueMonitor = new Object();
        /** 控制回应在属性更新之前发送，容量同样受硬上限保护。 */
        private final ArrayDeque<String> controlFrames = new ArrayDeque<>();
        /** 属性更新以 device + property 为键合并，慢会话只保留每个键的最新事实。 */
        private final LinkedHashMap<SubscriptionKey, PropertyUpdate> pendingProperties = new LinkedHashMap<>();
        /** 仅保存当前有界订阅键的最高接受序号，出队后仍阻止迟到回退。 */
        private final Map<SubscriptionKey, Long> highestRevisions = new java.util.HashMap<>();
        /** 已注册的精确设备-属性订阅。 */
        private volatile Map<UUID, Set<String>> subscriptions = Map.of();
        /** 会话清理后 worker 必须退出。 */
        private boolean stopped;
        /** 最近一次权威项目复核允许属性帧出队到此时刻，等于截止即失效。 */
        private volatile Instant authorizedUntil;

        /**
         * @param connection 已认证的 WebSocket 连接
         * @param tenantLease 项目所有者租户的连接租约；显式不限时为 null
         * @param authorizedUntil 握手权威复核建立的首个发送截止
         */
        private ClientState(RealtimeConnection connection, TenantConnectionLease.ConnectionLease tenantLease,
                            Instant authorizedUntil) {
            this.connection = connection;
            this.tenantLease = tenantLease;
            this.leaseActive = tenantLease != null;
            this.authorizedUntil = authorizedUntil;
        }

        /** @param checkedAt 权威项目复核成功时刻 */
        private void refreshAuthorization(Instant checkedAt) {
            authorizedUntil = checkedAt.plus(AUTHORIZATION_PERIOD);
        }

        /** @param now 当前UTC时刻 @return 是否已耗尽最近一次成功复核赋予的授权期 */
        private boolean authorizationExpired(Instant now) {
            return !now.isBefore(authorizedUntil);
        }

        /** @return 仍登记时的续租结果；显式不限或已释放状态不产生 Redis I/O，也不算故障 */
        private TenantConnectionLease.RenewDecision renewLease() {
            synchronized (leaseMonitor) {
                if (!leaseActive) {
                    return TenantConnectionLease.RenewDecision.RENEWED;
                }
                TenantConnectionLease.RenewDecision decision = tenantConnectionLease.renew(tenantLease);
                if (decision == TenantConnectionLease.RenewDecision.LOST) {
                    // LOST 后禁止后续心跳复活成员；现有连接仅保留本机物理保护直到自然断开。
                    leaseActive = false;
                }
                return decision;
            }
        }

        /** 释放与续租共用同一把锁，确保最终发生的 Redis 操作一定是 release。 */
        private void releaseLease() {
            synchronized (leaseMonitor) {
                if (!leaseActive) {
                    return;
                }
                leaseActive = false;
                tenantConnectionLease.release(tenantLease);
            }
        }

        /** @return 当前订阅数 */
        private int subscriptionCount() {
            return RealtimeSubscriptionRegistry.subscriptionCount(subscriptions);
        }

        /** @param deviceId 设备 ID @return 已订阅属性；不存在时返回空集合 */
        private Set<String> propertiesFor(UUID deviceId) {
            return subscriptions.getOrDefault(deviceId, Set.of());
        }

        /** 替换时丢弃待发属性与水位；已经进入底层send的帧不能撤回，由客户端连接代次隔离。
         * @param replacement 原子替换的不可变订阅索引 */
        private void replaceSubscriptions(Map<UUID, Set<String>> replacement) {
            synchronized (queueMonitor) {
                metrics.queueDepthChanged(-pendingProperties.size());
                pendingProperties.clear();
                highestRevisions.clear();
                subscriptions = replacement;
            }
        }

        /** 控制帧被填满视作慢会话；不允许 ACK 堆积绕过属性队列限额。 */
        private boolean enqueueControl(String payload) {
            synchronized (queueMonitor) {
                if (stopped) {
                    return true;
                }
                if (controlFrames.size() >= MAX_CONTROL_FRAMES) {
                    metrics.recordQueueDropped();
                    return false;
                }
                controlFrames.addLast(payload);
                metrics.queueDepthChanged(1);
                queueMonitor.notifyAll();
                return true;
            }
        }

        /** 按属性接受序号拒绝倒退；出队保留水位，新键入队超限才判定慢客户端。 */
        private boolean enqueueProperty(PropertyUpdate update) {
            synchronized (queueMonitor) {
                if (stopped) {
                    return true;
                }
                if (!propertiesFor(update.deviceId).contains(update.propertyKey)) return true;
                SubscriptionKey key = new SubscriptionKey(update.deviceId, update.propertyKey);
                Long highest = highestRevisions.get(key);
                if (highest != null && (update.reportedRevision == null || update.reportedRevision <= highest)) {
                    return true;
                }
                if (update.reportedRevision != null) highestRevisions.put(key, update.reportedRevision);
                if (pendingProperties.containsKey(key)) {
                    pendingProperties.remove(key);
                    pendingProperties.put(key, update);
                    metrics.recordQueueMerged();
                    queueMonitor.notifyAll();
                    return true;
                }
                if (pendingProperties.size() >= properties.outboundQueueCapacity()) {
                    metrics.recordQueueDropped();
                    return false;
                }
                pendingProperties.put(key, update);
                metrics.queueDepthChanged(1);
                queueMonitor.notifyAll();
                return true;
            }
        }

        /** 取下一条控制或属性消息；停止时返回null，并保留类型供属性发送截止门判断。 */
        private OutboundFrame takeNext(ObjectMapper mapper) {
            try {
                synchronized (queueMonitor) {
                    while (!stopped && controlFrames.isEmpty() && pendingProperties.isEmpty()) {
                        queueMonitor.wait();
                    }
                    if (stopped) {
                        return null;
                    }
                    if (!controlFrames.isEmpty()) {
                        String payload = controlFrames.removeFirst();
                        metrics.queueDepthChanged(-1);
                        return new OutboundFrame(payload, false);
                    }
                    PropertyUpdate update = pendingProperties.entrySet().iterator().next().getValue();
                    pendingProperties.remove(new SubscriptionKey(update.deviceId, update.propertyKey));
                    metrics.queueDepthChanged(-1);
                    return new OutboundFrame(mapper.writeValueAsString(new PropertyBatchFrame(
                            "PROPERTY_BATCH", update.projectId, update.deviceId, update.occurredAt,
                            update.shadowVersion, Map.of(update.propertyKey, update.value),
                            update.reportedRevision == null ? Map.of()
                                    : Map.of(update.propertyKey, update.reportedRevision.toString()),
                            Map.of(update.propertyKey, update.thingModelVersionId))), true);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        /** 唤醒 sender 线程且丢弃无意义的待发增量。 */
        private void stop() {
            synchronized (queueMonitor) {
                stopped = true;
                metrics.queueDepthChanged(-(controlFrames.size() + pendingProperties.size()));
                controlFrames.clear();
                pendingProperties.clear();
                queueMonitor.notifyAll();
            }
        }
    }

    /** 属性队列合并键；项目由会话身份已固定，无需重复占用每条队列键。 */
    private record SubscriptionKey(UUID deviceId, String propertyKey) {
    }

    /** 待发送的单属性事实；发送时包装为允许单属性的 PROPERTY_BATCH。 */
    private record PropertyUpdate(UUID projectId, UUID deviceId, String propertyKey, JsonNode value,
                                  Instant occurredAt, int shadowVersion, Long reportedRevision, UUID thingModelVersionId) {
    }

    /** sender出队结果；只有属性帧受权威授权截止门约束。 */
    private record OutboundFrame(String payload, boolean propertyFrame) {
    }

    /** 周期权威复核的去重键；同账号、项目与授权代次的多个浏览器连接只查询一次。 */
    private record AuthorizationKey(UUID accountId, UUID projectId, long projectGeneration) {
    }

    /** 控制台冻结的属性批次帧。 */
    private record PropertyBatchFrame(String type, UUID projectId, UUID deviceId, Instant occurredAt,
                                      int shadowVersion, Map<String, JsonNode> properties,
                                      Map<String, String> reportedRevisions, Map<String, UUID> thingModelVersionIds) {
    }
}
