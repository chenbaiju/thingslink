package com.things.link.ingestion.application;

import com.things.link.ingestion.infrastructure.websocket.RealtimeWebSocketProperties;
import com.things.link.project.application.EffectiveQuotaPolicyProvider;
import com.things.link.shared.error.BusinessException;
import com.things.link.enduser.application.WebAppRuntimeContext;
import com.things.link.enduser.application.AppRealtimeAlarmSubscription;
import com.things.link.dashboard.application.DashboardRuntimeDeviceRequest;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ADR0099：App和Console共用的有界失效提示核心。
 * 每连接只有一个发送者，只有dirty键进入内存；Redis回调不读数据库，不保存属性值或事件时间。
 * 一次订阅后不可替换，发送前重新核完整设备集合，任一撤权关闭整个连接。
 */
@Service
public class DashboardRealtimeRegistry {
    /** 严格拒绝重复字段，避免客户端与服务端对相同帧作不同解释。 */
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    /** 合同§6控制与提示UTF-8总字节上限。 */
    private static final int MAX_FRAME_BYTES = 32 * 1024;
    /** 新协议独立索引，连接ID由容器生成，不接受客户端覆盖。 */
    private final Map<String, State> sessions = new ConcurrentHashMap<>();
    /** 只保护本机准入及总订阅预算，不在此锁中查库或发送。 */
    private final Object admission = new Object();
    /** 包括正在关闭的物理连接，只有外部资源释放结束后归还名额。 */
    private final Semaphore connectionSlots;
    /** 每次实际发送的权威授权端口。 */
    private final DashboardRealtimeAccessService access;
    /** 复用已冻结本机资源上限，不新增可无限放大的默认。 */
    private final RealtimeWebSocketProperties limits;
    /** 与旧实时链共用跨实例租户连接租约。 */
    private final TenantConnectionLease leases;
    /** 分享必须使用跨实例两连接租约，不能继承账号故障降级。 */
    private final DashboardShareConnectionLease shareLeases;
    /** 只使用确权后的真实项目归属取得套餐。 */
    private final EffectiveQuotaPolicyProvider policies;
    /** 与旧实时链共享低基数连接、订阅与故障指标。 */
    private final RealtimeMetrics metrics;
    /** 每连接至多一个虚拟线程；会话总数受准入限制。 */
    private final ExecutorService senders;
    /** 传输关闭有独立有界工作池，不被发送/授权阻塞；队列同样有限。 */
    private final ExecutorService closers;
    /** Redis续租独立有界，阻塞时不占截止看门狗或传输关闭资源。 */
    private final ExecutorService renewers;
    /** 硬截止看门狗独立于数据库与发送线程，不让阻塞调用延长令牌生命。 */
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("dashboard-hint-deadline").factory());
    /** 关闭后拒绝新注册，防止Spring销毁阶段遗漏会话。 */
    private volatile boolean stopping;

    /** 装配同核提示、授权和有限租约，不承担遥测事务。 */
    public DashboardRealtimeRegistry(DashboardRealtimeAccessService access, RealtimeWebSocketProperties limits,
                                      TenantConnectionLease leases, EffectiveQuotaPolicyProvider policies, RealtimeMetrics metrics, DashboardShareConnectionLease shareLeases) {
        this.access = access;
        this.limits = limits;
        this.leases = leases;
        this.policies = policies;
        this.metrics = metrics;
        this.shareLeases = shareLeases;
        connectionSlots = new Semaphore(limits.maxGlobalConnections());
        senders = new ThreadPoolExecutor(0, limits.maxGlobalConnections(), 60, TimeUnit.SECONDS,
                new SynchronousQueue<>(), Thread.ofVirtual().name("dashboard-hint-", 0).factory());
        closers = new ThreadPoolExecutor(1, Math.min(16, limits.maxGlobalConnections()), 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(limits.maxGlobalConnections()), Thread.ofVirtual().name("dashboard-close-", 0).factory());
        renewers = new ThreadPoolExecutor(1, Math.min(16, limits.maxGlobalConnections()), 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(limits.maxGlobalConnections()), Thread.ofVirtual().name("dashboard-lease-", 0).factory());
        watchdog.scheduleWithFixedDelay(this::expire, 100, 100, TimeUnit.MILLISECONDS);
    }

    /** 握手后再次确权并原子准入；尚未SUBSCRIBE的连接也计入本机物理预算。 */
    public void register(DashboardRealtimeConnection connection) {
        State state = new State(connection);
        try {
            // 身份复验放入有界发送者，不能让数据库慢查询占无限容器回调线程。
            boolean rejected;
            synchronized (admission) {
                long accountCount = sessions.values().stream().filter(other -> sameSubject(other, connection.principal())).count();
                rejected = stopping || sessions.size() >= limits.maxGlobalConnections()
                        || (!state.principal.share() && accountCount >= limits.maxAccountConnections())
                        || sessions.containsKey(connection.id())
                        || (state.principal.share() && (connection.shareLease() == null
                        || !connection.shareLease().shareId().equals(state.principal.sharePrincipal().shareId())
                        || sessions.values().stream().filter(other -> state.principal.projectId().equals(
                        other.principal.projectId())).count() >= limits.maxProjectConnections()));
                if (!rejected) rejected = !connectionSlots.tryAcquire();
                if (!rejected) { state.admitted = true; sessions.put(connection.id(), state); metrics.sessionOpened(); }
            }
            if (rejected) {
                state.closeCode = 1013; state.closeReason = "connection limit";
                releaseTransport(state); return;
            }
            try { senders.execute(() -> sendLoop(state)); }
            catch (RejectedExecutionException saturated) {
                close(state, 1013, "worker limit");
                releaseTransport(state);
            }
        } catch (RuntimeException failure) {
            close(state, 1011, "registration unavailable");
        }
    }

    /**
     * 唯一SUBSCRIBE控制帧；语法/重复/超预算直接关闭，不发送可被反复堆积的错误响应。
     * 只有完整确权及租约准入后才排一个ACK，因此控制队列至多1项，严于合同上限16项。
     */
    public void receive(String id, String payload) {
        State state = sessions.get(id);
        if (state == null) return;
        synchronized (state) {
            if (state.closed || state.requested) {
                close(state, 1008, "subscription already fixed");
                return;
            }
            state.requested = true;
        }
        if (payload == null || payload.getBytes(StandardCharsets.UTF_8).length > MAX_FRAME_BYTES) {
            close(state, state.principal.share() ? 1008 : 1009, "frame limit");
            return;
        }
        synchronized (state) {
            if (!state.closed) state.pending = payload;
            state.notifyAll();
        }
    }

    /** 唯一发送者消费订阅与授权，阻塞工作也占受限工作槽，超时重连不能累计无界线程。 */
    private void subscribe(State state, String payload) {
        Subscription request;
        try { request = parse(payload, state.principal, state.connection.dashboardV2()); }
        catch (RuntimeException invalid) { close(state, 1008, "invalid subscription"); return; }
        TenantConnectionLease.ConnectionLease lease = null;
        boolean installed = false;
        try {
            state.operationDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(limits.sendTimeoutMillis());
            DashboardRealtimePrincipal authorized = state.connection.dashboardV2()
                    ? access.requireDashboardRuntime(state.principal, request.context(), request.devices(), request.alarms())
                    : requireDevices(state.principal, request.projectId(), request.devices(), request.context());
            var policy = policies.resolveTrustedDeviceProject(authorized.tenantId(), authorized.projectId());
            if (!authorized.tenantId().equals(policy.tenantId())) throw new IllegalStateException("配额返回错误租户");
            if (authorized.share()) {
                // 分享在注册确权后、SUBSCRIBE前已计入租户池，不重复申请。
                lease = state.lease;
                if (!renewShareLeases(state)) return;
            } else if (policy.websocketConnectionLimit() == null) {
                // 显式不限仍受本机物理保护，不制造永远续不到的Redis租约和虚假LOST指标。
                metrics.recordQuotaDecision(RealtimeMetrics.QuotaDecision.UNLIMITED);
            } else {
                lease = leases.create(authorized.tenantId(), "dashboard:" + state.connection.id());
                var decision = leases.acquire(lease, policy.websocketConnectionLimit());
                metrics.recordQuotaDecision(switch (decision) {
                    case ACQUIRED -> RealtimeMetrics.QuotaDecision.ACQUIRED;
                    case REJECTED -> RealtimeMetrics.QuotaDecision.REJECTED;
                    case UNAVAILABLE -> RealtimeMetrics.QuotaDecision.FAIL_OPEN;
                });
                if (decision == TenantConnectionLease.LeaseDecision.REJECTED) {
                    close(state, 1013, "tenant connection limit");
                    return;
                }
            }
            // Redis不可用按既有实时连接策略回退有限本机额度，不影响事实事务。
            synchronized (admission) {
                synchronized (state) {
                    if (state.closed) return;
                    long projectCount = sessions.values().stream()
                            .filter(other -> other != state && authorized.projectId().equals(other.principal.projectId())
                                    && (other.principal.share() || other.subscriptionId != null)).count();
                    int accountKeys = sessions.values().stream().filter(other -> sameSubject(other, authorized))
                            .mapToInt(other -> other.count).sum();
                    int projectKeys = sessions.values().stream().filter(other -> authorized.projectId().equals(other.principal.projectId()))
                            .mapToInt(other -> other.count).sum();
                    if (projectCount >= limits.maxProjectConnections()
                            || (!authorized.share() && accountKeys + request.count() > limits.maxAccountSubscriptions())
                            || projectKeys + request.count() > limits.maxProjectSubscriptions()) {
                        close(state, 1013, "subscription limit");
                        return;
                    }
                    state.principal = authorized;
                    state.context = request.context();
                    state.lease = lease;
                    state.devices = request.devices();
                    state.alarms = request.alarms();
                    state.count = request.count();
                    metrics.subscriptionsChanged(state.count);
                    state.subscriptionId = UUID.randomUUID().toString();
                    Map<String, Object> acknowledgment = new LinkedHashMap<>(Map.of("type", "SUBSCRIBED", "requestId", request.requestId(),
                            "subscriptionId", state.subscriptionId, "count", request.propertyCount()));
                    if (state.connection.dashboardV2()) acknowledgment.put("alarmCount", state.alarms.size());
                    state.controls.add(JSON.writeValueAsString(acknowledgment));
                    installed = true;
                    state.notifyAll();
                }
            }
        } finally {
            // 一切异常路径释放已申请但未移交会话的租约，外部I/O不在admission锁内执行。
            state.operationDeadline = Long.MAX_VALUE;
            if (!installed && lease != null && !state.principal.share()) leases.release(lease);
        }
    }

    /** Redis频道/正文项目互证后只传键；重复、迟到、等时消息均只能合并为再次读取提示。 */
    public void invalidate(UUID projectId, UUID deviceId, Set<String> propertyKeys) {
        for (State state : sessions.values()) {
            synchronized (state) {
                if (state.closed || !projectId.equals(state.principal.projectId())) continue;
                Set<String> subscribed = state.devices.get(deviceId);
                if (subscribed == null) continue;
                for (String key : propertyKeys) {
                    if (subscribed.contains(key)) state.dirty.computeIfAbsent(deviceId, ignored -> new LinkedHashSet<>()).add(key);
                }
                state.notifyAll();
            }
        }
    }

    /** ADR0109所有告警迁移仅按设备命中冻结组，不按迁移后过滤条件漏掉旧行移除。 */
    public void invalidateAlarms(UUID projectId, UUID deviceId) {
        for (State state : sessions.values()) {
            synchronized (state) {
                if (state.closed || !state.connection.dashboardV2() || !projectId.equals(state.principal.projectId())) continue;
                for (AppRealtimeAlarmSubscription alarm : state.alarms) {
                    if (alarm.devices().stream().anyMatch(device -> device.deviceId().equals(deviceId)))
                        state.dirtyAlarms.add(alarm.queryKey());
                }
                state.notifyAll();
            }
        }
    }

    /** 容器关闭回调不回收其他代次或其他连接。 */
    public void unregister(String id) {
        State state = sessions.get(id);
        if (state != null) close(state, 1000, "connection closed");
    }

    /** 单发送者交换dirty集合；网络在途新提示留给下一次，不由旧发送完成清除。 */
    private void sendLoop(State state) {
        try {
            if (state.closed) return;
            state.operationDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(limits.sendTimeoutMillis());
            access.requireIdentity(state.principal);
            state.lastAuthorizationNanos = System.nanoTime();
            if (state.principal.share() && !admitShareTenant(state)) return;
            state.operationDeadline = Long.MAX_VALUE;
            while (!state.closed) {
                String payload = null;
                String pending;
                synchronized (state) { pending = state.pending; state.pending = null; }
                if (pending != null) { subscribe(state, pending); continue; }
                boolean hint = false;
                synchronized (state) {
                    long now = System.nanoTime();
                    if (!state.controls.isEmpty()) payload = state.controls.removeFirst();
                    else if ((!state.dirty.isEmpty() || !state.dirtyAlarms.isEmpty()) && now - state.lastHintNanos >= TimeUnit.SECONDS.toNanos(1)) {
                        List<Map<String, Object>> devices = new ArrayList<>();
                        state.dirty.forEach((id, keys) -> devices.add(Map.of("deviceId", id.toString(), "propertyKeys", List.copyOf(keys))));
                        state.dirty.clear();
                        Map<String, Object> invalidation = new LinkedHashMap<>(Map.of("type", "INVALIDATE", "subscriptionId", state.subscriptionId, "devices", devices));
                        if (state.connection.dashboardV2()) invalidation.put("alarmQueryKeys", List.copyOf(state.dirtyAlarms));
                        state.dirtyAlarms.clear();
                        payload = JSON.writeValueAsString(invalidation);
                        hint = true;
                    }
                    if (payload == null) {
                        state.wait(100);
                        continue;
                    }
                }
                // 授权与物理发送共用硬截止，慢数据库不能留下永久占用的会话。
                state.operationDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(limits.sendTimeoutMillis());
                if (state.principal.share() && System.nanoTime() - state.lastAuthorizationNanos
                        > TimeUnit.SECONDS.toNanos(10)) {
                    close(state, 1011, "authorization deadline"); return;
                }
                if (state.connection.dashboardV2()) access.requireDashboardRuntime(state.principal, state.context, state.devices, state.alarms);
                else requireDevices(state.principal, state.principal.projectId(), state.devices, state.context);
                state.lastAuthorizationNanos = System.nanoTime();
                if (state.principal.share() && !renewShareLeases(state)) return;
                if (state.closed) return;
                if (!Instant.now().isBefore(state.principal.expiresAt())) {
                    close(state, 1008, "token expired");
                    return;
                }
                if (payload.getBytes(StandardCharsets.UTF_8).length > MAX_FRAME_BYTES) {
                    close(state, state.principal.share() ? 1008 : 1009, "frame limit");
                    return;
                }
                if (state.principal.share() && !state.frameBudget.reserve(
                        payload.getBytes(StandardCharsets.UTF_8).length, System.nanoTime())) {
                    close(state, 1008, "frame budget"); return;
                }
                state.connection.sendText(payload);
                if (hint) { synchronized (state) { state.lastHintNanos = System.nanoTime(); } }
                state.operationDeadline = Long.MAX_VALUE;
            }
        } catch (BusinessException denied) {
            closeAuthorizationFailure(state, denied);
        } catch (RealtimeProjectAccessDeniedException denied) {
            close(state, 1008, "authorization revoked");
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            close(state, 1001, "service stopping");
        } catch (Exception failure) {
            close(state, 1011, "delivery unavailable");
        } finally {
            releaseTransport(state);
        }
    }

    /** 看门狗只检测时钟并异步关闭；Redis续期不能阻塞其他会话截止。 */
    private void expire() {
        long now = System.nanoTime();
        for (State state : sessions.values()) {
            if (!Instant.now().isBefore(state.principal.expiresAt())) close(state, 1008, "token expired");
            else if (now >= state.operationDeadline) close(state, 1011, "operation deadline");
            else if (state.subscriptionId == null && now - state.createdNanos > TimeUnit.SECONDS.toNanos(5)) {
                close(state, 1008, "subscription deadline");
            } else if (state.principal.share()) {
                if (now - state.lastAuthorizationNanos > TimeUnit.SECONDS.toNanos(10)) {
                    close(state, 1011, "authorization deadline");
                } else if (now - state.lastShareCheckNanos >= TimeUnit.SECONDS.toNanos(5)) {
                    scheduleShareCheck(state, now);
                }
            } else if (state.lease != null && now - state.lastLeaseNanos > TimeUnit.SECONDS.toNanos(10)) {
                state.lastLeaseNanos = now;
                if (state.renewing.compareAndSet(false, true)) {
                    try { renewers.execute(() -> {
                        try { if (!state.closed) {
                            var result = leases.renew(state.lease);
                            if (result != TenantConnectionLease.RenewDecision.RENEWED)
                                metrics.recordQuotaDecision(result == TenantConnectionLease.RenewDecision.LOST
                                        ? RealtimeMetrics.QuotaDecision.LEASE_LOST : RealtimeMetrics.QuotaDecision.FAIL_OPEN);
                        } }
                        finally { state.renewing.set(false); }
                    }); } catch (RejectedExecutionException saturated) { state.renewing.set(false); }
                }
            }
        }
    }

    /** 分享即使未提交订阅也消耗租户池；关闭竞争中未移交租约由当前调用回收。 */
    private boolean admitShareTenant(State state) {
        var policy = policies.resolveTrustedDeviceProject(state.principal.tenantId(), state.principal.projectId());
        if (!state.principal.tenantId().equals(policy.tenantId())) throw new IllegalStateException("配额返回错误租户");
        if (!renewShareLeases(state)) return false;
        if (policy.websocketConnectionLimit() == null) return !state.closed;
        var lease = leases.create(state.principal.tenantId(), "dashboard:" + state.connection.id());
        boolean installed = false;
        try {
            var decision = leases.acquire(lease, policy.websocketConnectionLimit());
            if (decision != TenantConnectionLease.LeaseDecision.ACQUIRED) {
                close(state, decision == TenantConnectionLease.LeaseDecision.REJECTED ? 1013 : 1011,
                        "tenant lease unavailable");
                return false;
            }
            synchronized (state) {
                if (state.closed) return false;
                state.lease = lease; installed = true;
            }
            return true;
        } finally { if (!installed) leases.release(lease); }
    }

    /** 分享租约及适用的租户租约均必须确认续期；显式不限仍需分享租约。 */
    private boolean renewShareLeases(State state) {
        try {
            if (!shareLeases.renew(state.connection.shareLease()) || (state.lease != null
                    && leases.renew(state.lease) != TenantConnectionLease.RenewDecision.RENEWED)) {
                close(state, 1011, "lease unavailable"); return false;
            }
            return !state.closed;
        } catch (RuntimeException failure) {
            close(state, 1011, "lease unavailable"); return false;
        }
    }

    /** 静默连接也复验，慢数据库只占有界池；看门狗独立执行十秒硬截止。 */
    private void scheduleShareCheck(State state, long now) {
        if (!state.renewing.compareAndSet(false, true)) return;
        state.lastShareCheckNanos = now;
        try {
            renewers.execute(() -> {
                try {
                    if (state.closed) return;
                    if (state.devices.isEmpty()) access.requireIdentity(state.principal);
                    else requireDevices(state.principal, state.principal.projectId(), state.devices, state.context);
                    state.lastAuthorizationNanos = System.nanoTime();
                    renewShareLeases(state);
                } catch (BusinessException failure) { closeAuthorizationFailure(state, failure); }
                catch (RealtimeProjectAccessDeniedException failure) { close(state, 1008, "authorization revoked"); }
                catch (RuntimeException failure) { close(state, 1011, "authorization unavailable"); }
                finally { state.renewing.set(false); }
            });
        } catch (RejectedExecutionException saturated) {
            state.renewing.set(false); close(state, 1011, "authorization worker limit");
        }
    }

    /** 分享直接进入权威DATA事务，避免通用入口先占连接后挂起再借第二条。 */
    private DashboardRealtimePrincipal requireDevices(DashboardRealtimePrincipal principal, UUID projectId,
            Map<UUID, Set<String>> devices, WebAppRuntimeContext context) {
        if (principal.share()) {
            if (context != null) throw invalid();
            return access.requireShareDevices(principal, projectId, devices);
        }
        return access.requireDevices(principal, projectId, devices, context);
    }

    /** 分享基础设施错误60055不能伪装成撤权；所有关闭原因保持固定词汇。 */
    private void closeAuthorizationFailure(State state, BusinessException failure) {
        if (state.principal.share() && failure.errorCode().code() == 60055)
            close(state, 1011, "authorization unavailable");
        else close(state, 1008, "authorization revoked");
    }

    /** 幂等清理先移除索引/dirty，再在虚拟线程释放外部资源，不持全局锁做网络调用。 */
    private void close(State state, int code, String reason) {
        synchronized (state) {
            if (state.closed) return;
            state.closeCode = code;
            state.closeReason = reason;
            state.closed = true;
            if (state.admitted) { metrics.sessionClosed(); metrics.subscriptionsChanged(-state.count); }
            state.pending = null;
            sessions.remove(state.connection.id(), state);
            state.dirty.clear();
            state.dirtyAlarms.clear();
            state.controls.clear();
            state.notifyAll();
        }
        try { closers.execute(() -> releaseTransport(state)); }
        catch (RejectedExecutionException saturated) {
            // 发送者finally仍负责关闭；关闭池饱和不会再派生无限线程。
        }
    }

    /** 并发关闭只由一方执行外部I/O，发送者退出仍保留占用槽直到自己的清理完成。 */
    private void releaseTransport(State state) {
        if (state.transportClosing.compareAndSet(false, true)) {
            try { state.connection.close(state.closeCode, state.closeReason); }
            finally {
                try { if (state.lease != null) leases.release(state.lease); }
                finally {
                    try { if (state.connection.shareLease() != null) shareLeases.release(state.connection.shareLease()); }
                    finally { if (state.admitted) connectionSlots.release(); }
                }
            }
        }
    }

    /** 非法字段、重复设备/键和超预算不会被静默截断为一部分订阅。 */
    private Subscription parse(String payload, DashboardRealtimePrincipal principal, boolean dashboardV2) {
        if (payload == null || payload.getBytes(StandardCharsets.UTF_8).length > MAX_FRAME_BYTES) throw invalid();
        JsonNode root = JSON.readTree(payload);
        if (dashboardV2 && (!principal.app() || principal.share())) throw invalid();
        requireFields(root, dashboardV2 ? Set.of("type", "requestId", "devices", "runtimeContext", "alarms") : principal.share() ? Set.of("type", "requestId", "devices")
                : principal.app() ? Set.of("type", "requestId", "devices", "runtimeContext")
                : Set.of("type", "requestId", "devices", "projectId"));
        if (!"SUBSCRIBE".equals(text(root.get("type"), 16))) throw invalid();
        String requestId = text(root.get("requestId"), 128);
        UUID project = principal.app() || principal.share() ? principal.projectId() : uuid(root.get("projectId"));
        JsonNode array = root.get("devices");
        if (!array.isArray() || (!dashboardV2 && array.isEmpty()) || array.size() > 20) throw invalid();
        Map<UUID, Set<String>> devices = new LinkedHashMap<>();
        int count = 0;
        for (JsonNode item : array) {
            requireFields(item, Set.of("deviceId", "propertyKeys"));
            UUID device = uuid(item.get("deviceId"));
            JsonNode keys = item.get("propertyKeys");
            if (!keys.isArray() || keys.isEmpty() || keys.size() > 50 || devices.containsKey(device)) throw invalid();
            Set<String> normalized = new LinkedHashSet<>();
            for (JsonNode key : keys) {
                String value = text(key, 64);
                if (!value.matches("[A-Za-z0-9_-]{1,64}") || !normalized.add(value)) throw invalid();
            }
            count += normalized.size();
            if (count > Math.min(200, limits.maxSessionSubscriptions())) throw invalid();
            devices.put(device, Set.copyOf(normalized));
        }
        int propertyCount = count;
        List<AppRealtimeAlarmSubscription> alarms = new ArrayList<>();
        Set<UUID> allDevices = new LinkedHashSet<>(devices.keySet());
        if (dashboardV2) {
            JsonNode groups = root.get("alarms");
            if (!groups.isArray() || groups.size() > 20) throw invalid();
            Set<String> queryKeys = new LinkedHashSet<>();
            for (JsonNode group : groups) {
                requireFields(group, Set.of("queryKey", "devices", "conditionStates", "ackStates", "severities"));
                String queryKey = text(group.get("queryKey"), 64);
                if (!queryKey.matches("[A-Za-z0-9_-]{1,64}") || !queryKeys.add(queryKey)) throw invalid();
                JsonNode members = group.get("devices");
                if (!members.isArray() || members.isEmpty() || members.size() > 20) throw invalid();
                Set<UUID> groupDevices = new LinkedHashSet<>();
                List<DashboardRuntimeDeviceRequest> requests = new ArrayList<>();
                for (JsonNode member : members) {
                    requireFields(member, Set.of("deviceId", "expectedModelVersionId"));
                    UUID device = uuid(member.get("deviceId"));
                    if (!groupDevices.add(device)) throw invalid();
                    allDevices.add(device);
                    requests.add(new DashboardRuntimeDeviceRequest(device, uuid(member.get("expectedModelVersionId")), List.of()));
                }
                count += requests.size();
                if (allDevices.size() > 20 || count > Math.min(200, limits.maxSessionSubscriptions())) throw invalid();
                alarms.add(new AppRealtimeAlarmSubscription(queryKey, List.copyOf(requests),
                        enumSet(group.get("conditionStates"), Set.of("PENDING", "ACTIVE", "CLEARED")),
                        enumSet(group.get("ackStates"), Set.of("UNACKNOWLEDGED", "ACKNOWLEDGED")),
                        enumSet(group.get("severities"), Set.of("CRITICAL", "MAJOR", "MINOR", "WARNING", "INFO"))));
            }
            if (devices.isEmpty() && alarms.isEmpty()) throw invalid();
        }
        WebAppRuntimeContext context = null;
        if (principal.app()) {
            JsonNode value = root.get("runtimeContext");
            requireFields(value, Set.of("appKey", "applicationVersionId", "publicationRevision", "dashboardVersionId"));
            String appKey = text(value.get("appKey"), 36);
            if (!appKey.matches("app_[0-9a-f]{32}")) throw invalid();
            String revision = text(value.get("publicationRevision"), 19);
            if (!revision.matches("[1-9][0-9]*")) throw invalid();
            context = new WebAppRuntimeContext(appKey, uuid(value.get("applicationVersionId")),
                    Long.parseLong(revision), uuid(value.get("dashboardVersionId")));
        }
        return new Subscription(requestId, project, Map.copyOf(devices), count, propertyCount, context, List.copyOf(alarms));
    }

    /** 告警过滤遵循REST枚举且非空，重复枚举不得被Set静默折叠。 */
    private static Set<String> enumSet(JsonNode values, Set<String> allowed) {
        if (values == null || !values.isArray() || values.isEmpty() || values.size() > allowed.size()) throw invalid();
        Set<String> result = new LinkedHashSet<>();
        for (JsonNode value : values) {
            String text = text(value, 32);
            if (!allowed.contains(text) || !result.add(text)) throw invalid();
        }
        return Set.copyOf(result);
    }

    /** 严格闭合字段，未知字段不能成为新的隐式授权参数。 */
    private static void requireFields(JsonNode value, Set<String> fields) {
        if (value == null || !value.isObject() || !value.propertyNames().equals(fields)) throw invalid();
    }

    /** 只接受规范小写UUID，拒绝Java宽松短段解析。 */
    private static UUID uuid(JsonNode node) {
        String value = text(node, 36);
        UUID id = UUID.fromString(value);
        if (!id.toString().equals(value)) throw invalid();
        return id;
    }

    /** 控制文本有界且不接受JSON强制类型转换。 */
    private static String text(JsonNode value, int max) {
        if (value == null || !value.isString() || value.stringValue().isBlank() || value.stringValue().length() > max) throw invalid();
        return value.stringValue();
    }

    /** App与Console同UUID也不共享账号计数身份。 */
    private static boolean sameSubject(State state, DashboardRealtimePrincipal principal) {
        return state.principal.app() == principal.app() && state.principal.share() == principal.share()
                && state.principal.getName().equals(principal.getName());
    }

    /** 固定错误消息不回显请求或凭据。 */
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("非法实时订阅"); }

    /** Spring销毁时关闭所有连接并终止本核心后台线程。 */
    @PreDestroy
    public void shutdown() {
        stopping = true;
        sessions.values().forEach(state -> close(state, 1001, "service stopping"));
        watchdog.shutdownNow();
        senders.shutdownNow();
        renewers.shutdownNow();
        closers.shutdown();
    }

    /** @param requestId 有界关联值 @param projectId 选中项目 @param devices 冻结键集合 @param count 总键数 @param context App精确版本上下文 */
    private record Subscription(String requestId, UUID projectId, Map<UUID, Set<String>> devices, int count, int propertyCount, WebAppRuntimeContext context, List<AppRealtimeAlarmSubscription> alarms) { }

    /** 每个会话只保留有界订阅、dirty与一个ACK，字段发布通过volatile或会话监视器。 */
    private static final class State {
        /** 唯一物理发送端口。 */ final DashboardRealtimeConnection connection;
        /** 订阅后冻结真实范围。 */ volatile DashboardRealtimePrincipal principal;
        /** 最长5秒等待首个订阅。 */ final long createdNanos = System.nanoTime();
        /** 物理连接名额只由唯一外部清理者归还。 */ boolean admitted;
        /** 已关闭立即隔离迟到回调。 */ volatile boolean closed;
        /** 唯一待处理控制帧，最大32KiB。 */ String pending;
        /** App运行入口与发布/grant持续复验上下文。 */ WebAppRuntimeContext context;
        /** 外部关闭操作只能执行一次。 */ final AtomicBoolean transportClosing = new AtomicBoolean();
        /** Redis慢时每连接至多一个续租任务。 */ final AtomicBoolean renewing = new AtomicBoolean();
        /** 固定关闭代码供发送者finally使用。 */ volatile int closeCode = 1000;
        /** 固定诊断原因，无请求原文。 */ volatile String closeReason = "connection closed";
        /** 首次控制帧占用订阅机会，错误不重试。 */ boolean requested;
        /** 不可变订阅范围。 */ volatile Map<UUID, Set<String>> devices = Map.of();
        /** v2冻结告警组；纯告警不伪造属性键。 */ volatile List<AppRealtimeAlarmSubscription> alarms = List.of();
        /** 告警dirty至多20个已授权queryKey。 */ final Set<String> dirtyAlarms = new LinkedHashSet<>();
        /** 原子预算统计需要跨会话可见。 */ volatile int count;
        /** 本连接唯一订阅代次。 */ volatile String subscriptionId;
        /** 单控制ACK队列，禁止响应任意PING堆积。 */ final ArrayDeque<String> controls = new ArrayDeque<>();
        /** 总量不超过已订阅200键，不含属性值。 */ final Map<UUID, Set<String>> dirty = new LinkedHashMap<>();
        /** 单调时间避免系统时钟回退突破一秒合并。 */ long lastHintNanos;
        /** 单次查库及发送硬截止。 */ volatile long operationDeadline = Long.MAX_VALUE;
        /** 分享编码文本预算只占固定61桶。 */ final DashboardShareFrameBudget frameBudget = new DashboardShareFrameBudget();
        /** 最近成功完成权威读取的单调时间；不能用请求开始时间延长授权。 */
        volatile long lastAuthorizationNanos = System.nanoTime();
        /** 每五秒尝试主动复验，不受热点提示发送频率影响。 */
        volatile long lastShareCheckNanos = System.nanoTime();
        /** 租户跨实例租约。 */ volatile TenantConnectionLease.ConnectionLease lease;
        /** 续租间隔独立于提示热度。 */ volatile long lastLeaseNanos = System.nanoTime();
        /** @param connection 握手完成的物理连接 */
        State(DashboardRealtimeConnection connection) { this.connection = connection; this.principal = connection.principal(); }
    }
}
