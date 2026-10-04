package com.things.link.telemetry.application;

import com.things.link.device.application.DeviceAccessSessionPort;
import com.things.link.device.application.DeviceMqttCapabilityPort;
import com.things.link.device.application.DeviceCommandReceiverPort;
import com.things.link.device.application.DeviceCommandReceiverRoute;
import com.things.link.device.application.DeviceIngestionService;
import com.things.link.device.application.DeviceIngestionService.DeviceCommandRoute;
import com.things.link.device.application.DeviceIngestionService.DevicePropertySetRoute;
import com.things.link.project.application.ProjectService;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.DeviceCommandDispatch;
import com.things.link.shared.message.DeviceCommandDispatchFailure;
import com.things.link.shared.message.DeviceCommandReply;
import com.things.link.shared.message.DeviceCommandTerminalEvent;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.outbox.OutboxEvent;
import com.things.link.support.outbox.CorruptedOutboxEventException;
import com.things.link.support.outbox.TransactionalOutboxRepository;
import com.things.link.support.outbox.TransactionalOutboxReader;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import com.things.link.support.trace.TraceContext;
import com.things.link.telemetry.domain.DeviceCommand;
import com.things.link.telemetry.domain.DeviceCommandAttempt;
import com.things.link.telemetry.domain.DeviceCommandErrorCode;
import com.things.link.telemetry.domain.DeviceCommandRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;

/** 命令受理、查询与设备回复状态机应用服务。 */
@Service
public class DeviceCommandService {
    /** 下行 Kafka 主题已由 deploy 固定为十二分区。 */
    public static final String DOWNLINK_TOPIC = "tc.device.downlink";
    /** Outbox 发布器只允许映射已知事件类型。 */
    public static final String DISPATCH_EVENT_TYPE = "DEVICE_COMMAND_DISPATCH";
    /** 设备终态只以低敏事件回写其他领域。 */
    public static final String TERMINAL_EVENT_TYPE = DeviceCommandTerminalEvent.EVENT_TYPE;
    /** 派发重试耗尽终态码：三次派发均未交给 Broker，区别于设备响应超时 RESPONSE_TIMEOUT（D-037）。 */
    public static final String DISPATCH_RETRY_EXHAUSTED = "DISPATCH_RETRY_EXHAUSTED";
    /** ADR0156：非MQTT属性设置的稳定后台拒绝码，不占用HTTP错误码。 */
    public static final String PROPERTY_SET_PROTOCOL_UNSUPPORTED = "PROPERTY_SET_PROTOCOL_UNSUPPORTED";
    /** ADR0196：原接收关系失效的持久终态原因，不是HTTP错误码。 */
    public static final String COMMAND_ROUTE_UNAVAILABLE = "COMMAND_ROUTE_UNAVAILABLE";
    /** ADR 0021 固定首次加两次重试。 */
    private static final int MAX_ATTEMPTS = 3;

    /** 命令事实仓储。 */ private final DeviceCommandRepository repository;
    /** 通用事务 Outbox。 */ private final TransactionalOutboxRepository outboxRepository;
    /** 设备域可信路由与 Schema 校验端口。 */ private final DeviceIngestionService deviceService;
    /** 项目角色实时授权。 */ private final ProjectService projectService;
    /** JSON 映射器。 */ private final ObjectMapper objectMapper;
    /** JDBC 访问器仅用于核对控制台写事务的连接隔离级。 */ private final JdbcTemplate jdbcTemplate;
    /** S12-2a1d 集中保证后台命令事务使用完整租户与项目 RLS 范围。 */
    private final TransactionLocalRlsScope transactionLocalRlsScope;
    /** ADR0070：新受理、续重与外发准入在原短事务持有项目SHARE。 */
    private final ProjectLifecycleAccessService lifecycle;
    /** 原不可变派发信封只能经support窄端口读取，禁止telemetry跨域查sys表。 */
    private final TransactionalOutboxReader outboxReader;
    /** 命令受理、派发失败与终态指标。 */ private final DeviceCommandMetrics metrics;
    /** 只记录标识与调试日志失败，不打印载荷。 */
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(DeviceCommandService.class);
    /** 派发失败后的短退避间隔，避免失败重试与 Outbox/Kafka 传递叠加。 */ private final Duration dispatchRetryBackoff;
    /** 单设备待发命令预算（§6 默认 100）；超出只打点，绝不丢单。 */ private final int pendingCommandBudget;
    /** 承载设备的接入平面：决定命令按推送还是领取形态投递。 */
    private final DeviceAccessSessionPort deviceAccessSessionPort;
    /** 属性设置实际接收设备的锁后MQTT许可。 */
    private final DeviceMqttCapabilityPort mqttCapability;
    /** 原目标及连接设备关系的持锁许可。 */
    private final DeviceCommandReceiverPort receivers;
    /** 原事务公开命令终态来源。 */
    private final CommandWebhookSource webhookSource;
    /** 调试消息日志写入器：尽力而为，绝不把调试面耦合进业务事务。 */
    private final DeviceMessageDebugLogWriter debugLogWriter;

    /**
     * 创建命令应用服务。
     *
     * @param repository 命令事实仓储
     * @param outboxRepository 通用事务 Outbox
     * @param deviceService 设备路由与 Schema 校验端口
     * @param projectService 项目授权服务
     * @param lifecycle 项目写许可服务
     * @param outboxReader 原始派发信封读取端口
     * @param objectMapper JSON 映射器
     * @param jdbcTemplate 控制台事务隔离级核对入口
     * @param transactionLocalRlsScope 后台命令事务局部 RLS 范围组件
     * @param metrics 命令指标门面
     * @param dispatchRetryBackoff 派发失败短退避间隔
     * @param pendingCommandBudget 单设备待发命令预算
     * @param deviceAccessSessionPort 承载设备的接入平面端口
     * @param mqttCapability 属性设置设备锁与当前MQTT能力
     * @param webhookSource 原事务公开终态来源
     * @param receivers 原目标及原接收者当前关系许可
     * @param debugLogWriter 调试消息日志写入器
     */
    public DeviceCommandService(DeviceCommandRepository repository,
                                TransactionalOutboxRepository outboxRepository,
                                DeviceIngestionService deviceService,
                                ProjectService projectService,
                                ProjectLifecycleAccessService lifecycle,
                                TransactionalOutboxReader outboxReader,
                                ObjectMapper objectMapper,
                                JdbcTemplate jdbcTemplate,
                                TransactionLocalRlsScope transactionLocalRlsScope,
                                DeviceCommandMetrics metrics,
                                @Value("${things-link.command.dispatch-retry-backoff:5s}") Duration dispatchRetryBackoff,
                                @Value("${things-link.access.budget.pending-per-device:100}") int pendingCommandBudget,
                                DeviceAccessSessionPort deviceAccessSessionPort,
                                DeviceMessageDebugLogWriter debugLogWriter, DeviceMqttCapabilityPort mqttCapability,
                                DeviceCommandReceiverPort receivers, CommandWebhookSource webhookSource) {
        this.repository = repository; this.outboxRepository = outboxRepository; this.deviceService = deviceService;
        this.lifecycle = Objects.requireNonNull(lifecycle); this.outboxReader = Objects.requireNonNull(outboxReader);
        this.projectService = projectService; this.objectMapper = objectMapper; this.jdbcTemplate = jdbcTemplate;
        this.transactionLocalRlsScope = Objects.requireNonNull(transactionLocalRlsScope);
        this.metrics = metrics; this.dispatchRetryBackoff = dispatchRetryBackoff;
        this.pendingCommandBudget = pendingCommandBudget;
        this.deviceAccessSessionPort = deviceAccessSessionPort;
        this.mqttCapability = Objects.requireNonNull(mqttCapability);
        this.receivers = Objects.requireNonNull(receivers);
        this.webhookSource = Objects.requireNonNull(webhookSource);
        this.debugLogWriter = debugLogWriter;
    }

    /**
     * 在同一事务中保存命令、首次 attempt 与 Outbox；事务回滚时三者一个都不可见。
     *
     * @param projectId 项目 @param deviceId 目标设备 @param idempotencyKey 业务幂等键
     * @param commandKey 物模型命令键 @param input 请求对象
     * @return 新建或同幂等键的原命令
     */
    @Transactional
    public DeviceCommand submit(UUID projectId, UUID deviceId, String idempotencyKey,
                                String commandKey, JsonNode input) {
        requireConsoleWriteTransaction();
        requireControl(projectId);
        UUID ownerTenant = projectService.requireProjectTenant(projectId);
        lifecycle.requireActiveForWrite(ownerTenant, projectId);
        // ADR0070：项目成员变更持排他锁；SHARE等待后必须重新读角色，不能沿锁前OWNER/OPERATOR放行。
        requireControl(projectId);
        TenantScope caller = TenantContext.current()
                .orElseThrow(() -> new IllegalStateException("没有租户上下文"));
        return submitTrusted(projectId, deviceId, idempotencyKey, commandKey, input, caller.accountId(), null);
    }

    /**
     * 无控制台成员/租户上下文校验的受信核心。
     *
     * <p>这是 App 数据面（S11-2b）跨模块端口：enduser 先经 {@code app_user_device} 校验绑定与控制
     * 关系角色，再调本方法，因此本方法不做 {@code requireControl}。发起者两种身份互斥 —— 控制台/
     * 任务/规则命令 {@code requestedBy} 有值、{@code appUserId} 为 null，App 命令反之（ADR 0035 第二类
     * 身份），由数据库外键与可空性共同表达。</p>
     *
     * @param requestedBy 发起控制台账号；App 命令为 null
     * @param appUserId   发起终端用户；控制台/任务/规则命令为 null
     */
    @Transactional
    public DeviceCommand submitTrusted(UUID projectId, UUID deviceId, String idempotencyKey,
                                       String commandKey, JsonNode input, UUID requestedBy, UUID appUserId) {
        String key = requireIdempotencyKey(idempotencyKey);
        if (key.startsWith("task:") || key.startsWith("rule:") || key.startsWith("open:"))
            throw new BusinessException(DeviceCommandErrorCode.COMMAND_INPUT_INVALID,
                    "Idempotency-Key 使用了平台保留前缀");
        return submitCore(projectId,deviceId,key,commandKey,input,requestedBy,appUserId,null);
    }

    /** ADR0175公开专用内部入口；外层必须在同事务证明Key当前控制许可。 */
    @Transactional
    public DeviceCommand submitPublicTrusted(UUID projectId,UUID deviceId,UUID keyId,String clientKey,
            String commandKey,JsonNode input,UUID issuer){
        return submitCore(projectId,deviceId,PublicCommandIdentity.internalKey(keyId,clientKey),commandKey,input,issuer,null,keyId);
    }

    /** 原子受理共享核心，来源字段仅公开专用路径写入。 */
    private DeviceCommand submitCore(UUID projectId,UUID deviceId,String key,String commandKey,JsonNode input,
            UUID requestedBy,UUID appUserId,UUID publicKeyId){
        Instant startedAt = Instant.now();
        if (input == null || !input.isObject())
            throw new BusinessException(DeviceCommandErrorCode.COMMAND_INPUT_INVALID);
        if ((requestedBy == null) == (appUserId == null))
            throw new IllegalArgumentException("设备命令必须且只能有一种发起主体");
        DeviceCommand existing = repository.findByIdempotencyKey(projectId, key).orElse(null);
        if (existing != null) {
            requirePublicOrigin(existing,publicKeyId);
            return requireSameSubmission(existing, deviceId, DeviceCommandDispatch.OperationType.COMMAND,
                    commandKey, input, requestedBy, appUserId);
        }

        DeviceCommandRoute route = deviceService.resolveCommandRoute(projectId, deviceId, commandKey, input);
        Instant now = Instant.now();
        UUID commandId = Uuid7.generate();
        String traceId = TraceContext.resolve(TraceContext.current());
        DeviceCommand command = new DeviceCommand(commandId, route.tenantId(), projectId,
                route.targetDeviceId(), route.connectionDeviceId(), route.commandDefinitionId(),
                DeviceCommandDispatch.OperationType.COMMAND, route.commandKey(),
                route.inputSchema(), route.outputSchema(), objectMapper.writeValueAsString(input), null,
                DeviceCommand.Status.ACCEPTED, key, requestedBy, appUserId, route.timeoutSeconds(), 0, MAX_ATTEMPTS,
                now, null, null, null, traceId, now, null, null, null, now);
        if (!(publicKeyId==null?repository.create(command):repository.createPublic(command,publicKeyId))) {
            DeviceCommand concurrent = repository.findByIdempotencyKey(projectId, key)
                    .orElseThrow(() -> new IllegalStateException("命令幂等冲突后无法回读"));
            requirePublicOrigin(concurrent,publicKeyId);
            return requireSameSubmission(concurrent, deviceId, DeviceCommandDispatch.OperationType.COMMAND,
                    commandKey, input, requestedBy, appUserId);
        }
        if (pushShaped(route.tenantId(), projectId, route.connectionDeviceId())) {
            createAttemptAndOutbox(command, route, input, 1, now);
        }
        // 领取型协议（HTTP／CoAP）**不**创建推送尝试与下行 Outbox：受理把 attempt_count 推进为 1 却不写领取行时，
        // 领取 SQL（attempt_count = 0 或存在同号领取行）将永远取不到这条命令，命令只会退避到 TIMED_OUT（D-189）。
        recordPendingQueueBudget(projectId, deviceId);
        DeviceCommand accepted = repository.findById(projectId, deviceId, commandId).orElseThrow();
        metrics.recordAccepted(Duration.between(startedAt, Instant.now()));
        return accepted;
    }

    /** 旧Console前缀或另一Key不能由新收据认领。 */
    private void requirePublicOrigin(DeviceCommand command,UUID keyId){
        if(keyId!=null&&!repository.hasPublicOrigin(command.projectId(),command.id(),keyId))
            throw new BusinessException(CommonErrorCode.RESOURCE_STATE_CONFLICT);
    }

    /**
     * 只允许完整客户端候选重放同一命令事实。
     *
     * <p>S12-0d/D-143：项目级唯一键只负责原子仲裁，不能证明设备、动作、输入和发起主体相同。
     * 连接设备、定义、Schema与超时是首次受理的冻结路由快照，不参与重试比较，否则模型或拓扑变更会破坏合法重试。
     * 任一客户端候选不同均返回通用10009，且不携带旧命令字段。</p>
     *
     * @param existing 首次或并发胜出的命令事实 @param deviceId 本次目标设备
     * @param operationType 本次命令或属性设置类型 @param commandKey 本次命令键 @param input 本次JSON输入
     * @param requestedBy 本次Console账号 @param appUserId 本次App用户
     * @return 确认属于同一候选的原命令
     */
    private DeviceCommand requireSameSubmission(DeviceCommand existing, UUID deviceId,
                                                DeviceCommandDispatch.OperationType operationType,
                                                String commandKey, JsonNode input, UUID requestedBy, UUID appUserId) {
        JsonNode existingInput;
        try {
            existingInput = (existing.idempotencyKey().startsWith("open:")
                    ? objectMapper.rebuild().enable(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build()
                    : objectMapper).readTree(existing.requestJson());
        } catch (JacksonException corruptedFact) {
            // 这是持久事实损坏而非客户端候选冲突；保留内部故障以便告警和修复，同时绝不返回旧命令。
            throw new IllegalStateException("持久设备命令请求JSON损坏", corruptedFact);
        }
        boolean same = existing.operationType() == operationType
                && Objects.equals(existing.targetDeviceId(), deviceId)
                && Objects.equals(existing.commandKey(), commandKey)
                && Objects.equals(existingInput, input)
                && Objects.equals(existing.requestedBy(), requestedBy)
                && Objects.equals(existing.appUserId(), appUserId);
        if (!same) {
            throw new BusinessException(CommonErrorCode.RESOURCE_STATE_CONFLICT,
                    "Idempotency-Key 已用于其他设备操作请求");
        }
        return existing;
    }

    /**
     * 受理由任务调度器触发的单设备命令。
     *
     * <p>后台调度线程没有 HTTP {@link TenantContext}，因此不复用面向控制台的 {@link #submit}：
     * 身份与租户归属全部由 {@link DeviceIngestionService#resolveCommandRoute} 和项目路由权威解析，
     * 幂等键严格固定为 {@code task:{executionId}:{deviceId}}。Outbox 分区键仍由真实连接设备 ID 决定。</p>
     *
     * @param request 已冻结且受 task 模块租约保护的任务命令请求
     * @return 命令 ID 与当前状态；重放同一 execution/device 返回原命令
     */
    @Transactional
    public TaskDeviceCommandResult submitTask(TaskDeviceCommandRequest request) {
        String key = "task:" + request.executionId() + ":" + request.deviceId();
        DeviceCommand existing = repository.findByIdempotencyKey(request.projectId(), key).orElse(null);
        if (existing != null) {
            return taskResult(requireSameSubmission(existing, request.deviceId(),
                    DeviceCommandDispatch.OperationType.COMMAND, request.commandKey(), request.input(),
                    request.requestedBy(), null));
        }
        DeviceCommandRoute route;
        try {
            route = deviceService.resolveCommandRoute(
                    request.projectId(), request.deviceId(), request.commandKey(), request.input());
        } catch (BusinessException exception) {
            // 设备已删除、命令未定义或 Schema 不匹配都是该目标的永久错误；在本事务方法内部
            // 转成显式拒绝，避免异常穿过 Spring 事务拦截器后把外层任务批次标记 rollback-only。
            return new TaskDeviceCommandResult(null, TaskDeviceCommandResult.Status.REJECTED, true,
                    "目标设备不支持该命令或命令参数不合法");
        }
        Instant now = Instant.now();
        UUID commandId = Uuid7.generate();
        String traceId = TraceContext.resolve(TraceContext.current());
        DeviceCommand command = new DeviceCommand(commandId, route.tenantId(), request.projectId(),
                route.targetDeviceId(), route.connectionDeviceId(), route.commandDefinitionId(),
                DeviceCommandDispatch.OperationType.COMMAND, route.commandKey(),
                route.inputSchema(), route.outputSchema(), objectMapper.writeValueAsString(request.input()), null,
                DeviceCommand.Status.ACCEPTED, key, request.requestedBy(), null, route.timeoutSeconds(), 0, MAX_ATTEMPTS,
                now, null, null, null, traceId, now, null, null, null, now);
        if (!repository.create(command)) {
            DeviceCommand concurrent = repository.findByIdempotencyKey(request.projectId(), key)
                    .orElseThrow(() -> new IllegalStateException("任务命令幂等冲突后缺少命令事实"));
            return taskResult(requireSameSubmission(concurrent, request.deviceId(),
                    DeviceCommandDispatch.OperationType.COMMAND, request.commandKey(), request.input(),
                    request.requestedBy(), null));
        }
        createAttemptAndOutbox(command, route, request.input(), 1, now);
        return taskResult(command);
    }

    /**
     * 受理由规则动作触发的命令或属性设置，复用 S4 命令事实、attempt、Outbox 与有限超时状态机。
     * @param request 已冻结规则动作
     * @return 稳定命令 ID 或永久拒绝码
     */
    @Transactional
    public RuleDeviceActionResult submitRuleAction(RuleDeviceActionRequest request) {
        String key = "rule:" + request.actionId();
        try {
            DeviceCommand existing = repository.findByIdempotencyKey(request.projectId(), key).orElse(null);
            if (existing != null) {
                DeviceCommand same = requireSameSubmission(existing, request.deviceId(), request.operationType(),
                        request.commandKey(), request.input(), request.requestedBy(), null);
                return new RuleDeviceActionResult(same.id(), true, null);
            }
            Instant now = Instant.now();
            UUID commandId = Uuid7.generate();
            String traceId = TraceContext.resolve(TraceContext.current());
            DeviceCommand command;
            DeviceCommandDispatch dispatch;
            if (request.operationType() == DeviceCommandDispatch.OperationType.COMMAND) {
                DeviceCommandRoute route = deviceService.resolveCommandRoute(
                        request.projectId(), request.deviceId(), request.commandKey(), request.input());
                command = new DeviceCommand(commandId, route.tenantId(), request.projectId(), route.targetDeviceId(),
                        route.connectionDeviceId(), route.commandDefinitionId(), request.operationType(), route.commandKey(),
                        route.inputSchema(), route.outputSchema(), objectMapper.writeValueAsString(request.input()), null,
                        DeviceCommand.Status.ACCEPTED, key, request.requestedBy(), null, route.timeoutSeconds(), 0, MAX_ATTEMPTS,
                        now, null, null, null, traceId, now, null, null, null, now);
                dispatch = ruleDispatch(command, route.projectKey(), route.targetDeviceKey(),
                        route.connectionDeviceKey(), request.input(), now);
            } else {
                DevicePropertySetRoute route = deviceService.resolvePropertySetRoute(
                        request.projectId(), request.deviceId(), request.input());
                if (!lifecycle.lockActiveForWrite(route.tenantId(), request.projectId())) {
                    return new RuleDeviceActionResult(null, false, "PROJECT_FROZEN");
                }
                if (!mqttCapability.lockCurrent(route.tenantId(), request.projectId(), route.connectionDeviceId())) {
                    return new RuleDeviceActionResult(null, false, PROPERTY_SET_PROTOCOL_UNSUPPORTED);
                }
                command = new DeviceCommand(commandId, route.tenantId(), request.projectId(), route.targetDeviceId(),
                        route.connectionDeviceId(), null, request.operationType(), null, null, null,
                        objectMapper.writeValueAsString(request.input()), null, DeviceCommand.Status.ACCEPTED, key,
                        request.requestedBy(), null, 30, 0, MAX_ATTEMPTS, now, null, null, null, traceId,
                        now, null, null, null, now);
                dispatch = ruleDispatch(command, route.projectKey(), route.targetDeviceKey(),
                        route.connectionDeviceKey(), request.input(), now);
            }
            if (!repository.create(command)) {
                DeviceCommand concurrent = repository.findByIdempotencyKey(request.projectId(), key)
                        .orElseThrow(() -> new IllegalStateException("规则设备动作幂等冲突后缺少命令事实"));
                DeviceCommand same = requireSameSubmission(concurrent, request.deviceId(), request.operationType(),
                        request.commandKey(), request.input(), request.requestedBy(), null);
                return new RuleDeviceActionResult(same.id(), true, null);
            }
            // ADR0156 / D-189：HTTP和CoAP等待设备领取，不伪造首次推送尝试。
            if (pushShaped(command.tenantId(), command.projectId(), command.connectionDeviceId())) {
                createAttemptAndOutbox(command, dispatch, now);
            }
            recordPendingQueueBudget(command.projectId(), command.targetDeviceId());
            return new RuleDeviceActionResult(command.id(), true, null);
        } catch (BusinessException exception) {
            return new RuleDeviceActionResult(null, false, Integer.toString(exception.errorCode().code()));
        }
    }

    /**
     * 返回任务执行归并终态时所需的命令状态。
     *
     * <p>任务域只能读取该公开投影，不能查询 {@code ts_device_command}；找不到命令通常说明历史清理
     * 或数据异常，调用方必须把目标标记为失败而不能无限等待。</p>
     *
     * @param projectId 项目隔离轴
     * @param commandId 命令事实 ID
     * @return 命令状态；不存在时为空
     */
    @Transactional(readOnly = true)
    public java.util.Optional<TaskDeviceCommandResult> findTaskCommand(UUID projectId, UUID commandId) {
        return repository.findByCommandId(projectId, commandId)
                .map(DeviceCommandService::taskResult);
    }

    /** @param command 命令领域事实 @return 不泄露领域类型的任务状态投影 */
    private static TaskDeviceCommandResult taskResult(DeviceCommand command) {
        return new TaskDeviceCommandResult(command.id(),
                TaskDeviceCommandResult.Status.valueOf(command.status().name()), command.terminal(), null);
    }

    /** 查询单条命令和尝试，仍要求控制权限，避免 VIEWER 读取控制参数与设备诊断。 */
    @Transactional(readOnly = true)
    public DeviceCommandDetails get(UUID projectId, UUID deviceId, UUID commandId) {
        requireControl(projectId);
        return findTrusted(projectId, deviceId, commandId)
                .orElseThrow(() -> new BusinessException(DeviceCommandErrorCode.COMMAND_NOT_FOUND));
    }

    /**
     * 无控制权限校验的命令状态回读核心（App 数据面，S11-2b）。
     *
     * <p>enduser 先经 {@code app_user_device} 校验绑定，再调本方法回读命令状态；因此这里不复用
     * {@link #get} 的 {@code requireControl}（App 请求没有控制台 {@code accountId}）。不存在时返回空，
     * 由调用方映射为「资源不存在」语义，不泄露是否存在。</p>
     */
    @Transactional(readOnly = true)
    public java.util.Optional<DeviceCommandDetails> findTrusted(UUID projectId, UUID deviceId, UUID commandId) {
        return repository.findById(projectId, deviceId, commandId)
                .map(command -> new DeviceCommandDetails(command, repository.findAttempts(projectId, commandId)));
    }

    /**
     * ADR0070：短事务只产生本次外发资格或冻结终态，提交后consumer才允许预处理及外部I/O。
     * 项目许可先于command行锁；当前PENDING须与原Outbox完整信封匹配，损坏消息不能变成冻结事实。
     */
    @Transactional
    public boolean admitDispatch(DeviceCommandDispatch dispatch) {
        Objects.requireNonNull(dispatch, "命令派发信封不能为空");
        configureMessageScope(dispatch.tenantId(), dispatch.projectId());
        DeviceCommand observed = repository.findByCommandId(dispatch.projectId(), dispatch.commandId())
                .orElseThrow(() -> new InvalidCommandDispatchException("设备命令事实不存在"));
        requireCommandIdentity(observed, dispatch.tenantId(), dispatch.projectId(), dispatch.commandId());
        if (observed.terminal()) return false;
        OptionalLong generation = webhookSource.capture(observed.tenantId(), observed.projectId());
        boolean allowed = lifecycle.lockActiveForWrite(observed.tenantId(), observed.projectId());
        boolean capability = !allowed || observed.operationType() != DeviceCommandDispatch.OperationType.PROPERTY_SET
                || mqttCapability.lockCurrent(observed.tenantId(), observed.projectId(), observed.connectionDeviceId());
        var receiver = allowed && capability ? receivers.lockCurrent(observed.tenantId(), observed.projectId(),
                observed.targetDeviceId(), observed.connectionDeviceId()) : java.util.Optional.<DeviceCommandReceiverRoute>empty();
        DeviceCommand command = repository.lockByIdentity(observed.tenantId(), observed.projectId(), observed.id()).orElse(null);
        if (command == null || command.terminal() || command.attemptCount() != dispatch.attemptNo()) return false;
        DeviceCommandAttempt attempt = currentDispatchAttempt(command, dispatch);
        if (attempt.status() != DeviceCommandAttempt.Status.PENDING) return false;
        if (command.status() != DeviceCommand.Status.ACCEPTED) {
            throw new IllegalStateException("PENDING尝试与命令状态不一致");
        }
        requireOriginalDispatch(command, attempt, dispatch);
        Instant now = repository.databaseNow();
        if (!allowed) {
            stopFrozen(command, now, generation);
            return false;
        }
        if (!capability) {
            stopUnsupportedProperty(command, now, generation);
            return false;
        }
        if (receiver.isEmpty() || !receiver.get().projectKey().equals(dispatch.projectKey())
                || !receiver.get().targetDeviceKey().equals(dispatch.targetDeviceKey())
                || !receiver.get().connectionDeviceKey().equals(dispatch.connectionDeviceKey())) {
            stopUnavailableReceiver(command, now, generation);
            return false;
        }
        return command.deadlineAt().isAfter(now) && attempt.deadlineAt().isAfter(now);
    }

    /** EMQX HTTP API 接受报文后推进 DISPATCHED；重复 Kafka 记录不会重复推进。 */
    @Transactional
    public boolean markDispatched(DeviceCommandDispatch dispatch, Instant publishedAt) {
        configureMessageScope(dispatch.tenantId(), dispatch.projectId());
        DeviceCommand command = repository.lockByIdentity(dispatch.tenantId(), dispatch.projectId(), dispatch.commandId()).orElse(null);
        if (command == null || command.terminal() || command.attemptCount() != dispatch.attemptNo()) return false;
        DeviceCommandAttempt attempt = currentDispatchAttempt(command, dispatch);
        if (attempt.status() != DeviceCommandAttempt.Status.PENDING) return false;
        if (command.status() != DeviceCommand.Status.ACCEPTED) throw new IllegalStateException("PENDING尝试与命令状态不一致");
        if (!repository.markDispatched(dispatch.projectId(), dispatch.commandId(), dispatch.attemptNo(), publishedAt)) {
            throw new IllegalStateException("命令派发回执CAS失败");
        }
        // 调试时间线的"投递"阶段：只有真正交给承载传输之后才写，写失败不影响命令事实。
        logDownlinkDelivered(dispatch, publishedAt);
        return true;
    }

    /**
     * 记录一次下行派发失败并推进命令状态机（D-037）。
     *
     * <p>ingestion 在 {@code publisher.publish} 失败后只通过本 application 入口报告结果，不直接写
     * {@code ts_device_command*}；本方法单事务内把当前 attempt 由 PENDING CAS 为 FAILED，未耗尽时
     * 安排短退避重试（仓库 scheduleDispatchRetry 语义），耗尽时推进 TIMED_OUT（DISPATCH_RETRY_EXHAUSTED）。
     * 发布异常由调用方决定不再向 Kafka 抛回，避免 Kafka 重试与命令业务重试叠加；只有本事务自身失败才由
     * 调用方抛回 Kafka 保护事实落盘。</p>
     *
     * @param dispatch 派发信封，携带 tenant/project/command/attempt 归属
     * @param failure  稳定派发失败分类（只入事实与指标，不作为 HTTP 错误响应）
     * @param at       失败 UTC 时刻
     */
    @Transactional
    public void recordDispatchFailure(DeviceCommandDispatch dispatch, DeviceCommandDispatchFailure failure, Instant at) {
        configureMessageScope(dispatch.tenantId(), dispatch.projectId());
        OptionalLong generation = webhookSource.capture(dispatch.tenantId(), dispatch.projectId());
        DeviceCommand command = repository.lockByIdentity(dispatch.tenantId(), dispatch.projectId(), dispatch.commandId()).orElse(null);
        if (command == null || command.terminal() || command.attemptCount() != dispatch.attemptNo()) return;
        DeviceCommandAttempt attempt = currentDispatchAttempt(command, dispatch);
        if (attempt.status() != DeviceCommandAttempt.Status.PENDING) return;
        if (command.status() != DeviceCommand.Status.ACCEPTED) throw new IllegalStateException("PENDING尝试与命令状态不一致");
        failPendingAttempt(command, failure, at, generation);
    }

    /** 调用方已持当前command锁，失败、退避或终态必须原子提交。 */
    private void failPendingAttempt(DeviceCommand command, DeviceCommandDispatchFailure failure, Instant at, OptionalLong generation) {
        boolean marked = repository.markAttemptDispatchFailed(command.projectId(), command.id(),
                command.attemptCount(), failure.code(), dispatchFailureMessage(failure), at);
        if (!marked) throw new IllegalStateException("命令派发失败尝试CAS失败");
        metrics.recordDispatchFailure();
        if (failure == DeviceCommandDispatchFailure.DISPATCH_PENDING_TIMEOUT) metrics.recordPendingTimeout();
        if (command.attemptCount() >= command.maxAttempts()) {
            boolean terminal = repository.terminalDispatchRetryExhausted(command.projectId(), command.id(),
                    command.attemptCount(), at);
            if (!terminal) throw new IllegalStateException("命令派发耗尽终态CAS失败");
            metrics.recordTerminal(DeviceCommandMetrics.REASON_DISPATCH_RETRY_EXHAUSTED);
            appendTerminal(command, DeviceCommandTerminalEvent.Status.TIMED_OUT, DISPATCH_RETRY_EXHAUSTED, at, generation);
        } else {
            if (!repository.scheduleDispatchRetry(command.projectId(), command.id(), command.attemptCount(),
                    at.plus(dispatchRetryBackoff))) throw new IllegalStateException("命令派发退避CAS失败");
        }
    }

    /** @param failure 派发失败分类 @return 不泄露密钥/报文的稳定诊断摘要 */
    private static String dispatchFailureMessage(DeviceCommandDispatchFailure failure) {
        return switch (failure) {
            case DISPATCH_CREDENTIALS_MISSING -> "缺少 EMQX 下行发布 API 凭据";
            case DISPATCH_TIMEOUT -> "EMQX 发布 API 超时";
            case DISPATCH_CONNECTION_FAILED -> "EMQX 发布 API 连接失败";
            case DISPATCH_BULKHEAD_REJECTED -> "EMQX 命令发布并发已达上限";
            case DISPATCH_CIRCUIT_OPEN -> "EMQX 命令发布断路器已打开";
            case DISPATCH_HTTP_CLIENT_ERROR -> "EMQX 发布 API 客户端请求被拒绝";
            case DISPATCH_HTTP_RATE_LIMITED -> "EMQX 发布 API 触发限流";
            case DISPATCH_HTTP_SERVER_ERROR -> "EMQX 发布 API 服务端失败";
            case DISPATCH_HTTP_REJECTED -> "EMQX 发布 API 拒绝发布";
            case DISPATCH_DEVICE_OFFLINE -> "设备没有可用的会话内推送通道";
            case DISPATCH_PENDING_TIMEOUT -> "本次尝试截止前未取得成功交付回执";
            case DISPATCH_FAILED -> "平台下行派发失败";
        };
    }

    /**
     * 处理设备 ACK/SUCCESS/FAILED；reply messageId 唯一约束和状态 CAS 共同吸收重复与乱序。
     */
    @Transactional
    public boolean applyReply(DeviceCommandReply reply) {
        configureMessageScope(reply.tenantId(), reply.projectId());
        OptionalLong generation = webhookSource.capture(reply.tenantId(), reply.projectId());
        DeviceCommand command = repository.lockByIdentity(reply.tenantId(), reply.projectId(), reply.commandId()).orElse(null);
        if (command == null || command.terminal() || !command.connectionDeviceId().equals(reply.connectionDeviceId())) return false;
        // 回复只有 connectionId；下方 repository CAS 会同时核对它。不存在与终态都按幂等 no-op 处理。
        String output = reply.outputJson() == null ? "{}" : reply.outputJson();
        if (reply.status() == DeviceCommandReply.Status.SUCCESS && command != null) {
            try {
                if (command.operationType() == DeviceCommandDispatch.OperationType.COMMAND) {
                    deviceService.validateCommandOutput(command.outputSchema(), objectMapper.readTree(output));
                }
            } catch (RuntimeException exception) {
                boolean changed = repository.applyReply(reply.projectId(), reply.commandId(), reply.connectionDeviceId(),
                        reply.messageId(), "FAILED", "{}", "OUTPUT_SCHEMA_INVALID",
                        "命令响应不符合输出 Schema", reply.receivedAt());
                if (changed) appendTerminal(command, DeviceCommandTerminalEvent.Status.FAILED,
                        "OUTPUT_SCHEMA_INVALID", reply.receivedAt(), generation);
                return changed;
            }
        }
        boolean changed;
        try {
            changed = repository.applyReply(reply.projectId(), reply.commandId(), reply.connectionDeviceId(),
                    reply.messageId(), reply.status().name(), output, reply.errorCode(), reply.message(), reply.receivedAt());
        } catch (DuplicateKeyException exception) {
            return false;
        }
        if (changed) {
            // 调试时间线的"回复"阶段：如实记录设备回复的状态码，不因为回复是 ACK 就省略。
            logReplyReceived(reply, output);
        }
        if (changed && reply.status() != DeviceCommandReply.Status.ACK && command != null) {
            appendTerminal(command, reply.status() == DeviceCommandReply.Status.SUCCESS
                    ? DeviceCommandTerminalEvent.Status.SUCCEEDED : DeviceCommandTerminalEvent.Status.FAILED,
                    reply.errorCode(), reply.receivedAt(), generation);
        }
        return changed;
    }

    /**
     * 对一条已领取的到期命令执行续重或超时迁移（D-037）。
     *
     * <p>ADR0143以领取时冻结的claimKind区分待发超时、响应超时、失败退避和领取型最终超时；
     * 消费CAS重查同一分支，不能根据后来状态把旧身份解释为另一种工作。</p>
     */
    @Transactional
    public void processDue(DeviceCommandRepository.DueCommand due) {
        if (due == null || due.tenantId() == null || due.projectId() == null || due.commandId() == null
                || due.retryToken() == null || due.expectedAttempt() < 1 || due.claimKind() == null) {
            throw new IllegalArgumentException("命令重试领取身份不完整");
        }
        configureMessageScope(due.tenantId(), due.projectId());
        DeviceCommand observed = repository.findByCommandId(due.projectId(), due.commandId()).orElse(null);
        if (observed == null) return;
        requireCommandIdentity(observed, due.tenantId(), due.projectId(), due.commandId());
        if (observed.terminal()) return;
        OptionalLong generation = webhookSource.capture(observed.tenantId(), observed.projectId());
        boolean allowed = lifecycle.lockActiveForWrite(observed.tenantId(), observed.projectId());
        boolean capability = !allowed || observed.operationType() != DeviceCommandDispatch.OperationType.PROPERTY_SET
                || mqttCapability.lockCurrent(observed.tenantId(), observed.projectId(), observed.connectionDeviceId());
        boolean pushClaim = due.claimKind() != DeviceCommandRepository.ClaimKind.PULL_FINAL_TIMEOUT;
        var receiver = allowed && capability && pushClaim ? receivers.lockCurrent(observed.tenantId(), observed.projectId(),
                observed.targetDeviceId(), observed.connectionDeviceId()) : java.util.Optional.<DeviceCommandReceiverRoute>empty();
        DeviceCommand command = repository.lockByIdentity(due.tenantId(), due.projectId(), due.commandId()).orElse(null);
        if (command == null || command.terminal() || !repository.consumeRetryClaim(due)) return;
        Instant now = repository.databaseNow();
        if (!capability) {
            stopUnsupportedProperty(command, now, generation);
            return;
        }
        if (allowed && pushClaim && receiver.isEmpty()) {
            stopUnavailableReceiver(command, now, generation);
            return;
        }
        if (due.claimKind() == DeviceCommandRepository.ClaimKind.PUSH_PENDING_TIMEOUT) {
            if (!allowed) stopFrozen(command, now, generation);
            else failPendingAttempt(command, DeviceCommandDispatchFailure.DISPATCH_PENDING_TIMEOUT, now, generation);
            return;
        }
        if (due.claimKind() == DeviceCommandRepository.ClaimKind.PULL_FINAL_TIMEOUT) {
            if (!repository.timeoutFinalClaim(command.projectId(), command.id(), command.attemptCount(), now)) {
                throw new IllegalStateException("领取型命令最终超时CAS失败");
            }
            metrics.recordTerminal(DeviceCommandMetrics.REASON_RESPONSE_TIMEOUT);
            appendTerminal(command, DeviceCommandTerminalEvent.Status.TIMED_OUT, "RESPONSE_TIMEOUT", now, generation);
            return;
        }
        if (due.claimKind() == DeviceCommandRepository.ClaimKind.PUSH_DISPATCH_RETRY) {
            if (command.attemptCount() >= command.maxAttempts()) {
                if (!repository.terminalDispatchRetryExhausted(command.projectId(), command.id(), command.attemptCount(), now)) {
                    throw new IllegalStateException("命令派发耗尽终态CAS失败");
                }
                metrics.recordTerminal(DeviceCommandMetrics.REASON_DISPATCH_RETRY_EXHAUSTED);
                appendTerminal(command, DeviceCommandTerminalEvent.Status.TIMED_OUT, DISPATCH_RETRY_EXHAUSTED, now, generation);
            } else if (!allowed) {
                stopFrozen(command, now, generation);
            } else {
                createNextAttempt(command, receiver.orElseThrow(), now);
            }
            return;
        }
        if (!allowed && command.attemptCount() < command.maxAttempts()) {
            stopFrozen(command, now, generation);
            return;
        }
        DeviceCommandRepository.RetryDecision decision = repository.prepareRetry(command.projectId(), command.id(),
                command.attemptCount(), command.maxAttempts(), now);
        if (decision == DeviceCommandRepository.RetryDecision.TIMED_OUT) {
            metrics.recordTerminal(DeviceCommandMetrics.REASON_RESPONSE_TIMEOUT);
            appendTerminal(command, DeviceCommandTerminalEvent.Status.TIMED_OUT, "RESPONSE_TIMEOUT", now, generation);
        } else if (decision == DeviceCommandRepository.RetryDecision.RETRY) {
            createNextAttempt(command, receiver.orElseThrow(), now);
        } else {
            // 已消费有效领取且持command锁，不能把必要状态更新失败当作已成功处理而吞掉领取。
            throw new IllegalStateException("有效命令领取的超时CAS失败");
        }
    }

    /**
     * 承载设备是否按**推送**形态投递（MQTT／TCP／无配置行的存量 MQTT 默认档）。
     *
     * <p>领取型协议（HTTP／CoAP）由设备主动领取，受理阶段不得替它产生一次"推送尝试"：否则 attempt_count 会被
     * 推进而没有对应的领取行，命令永久不可领取（D-189）。协议判定取自接入配置端口，不猜、不由请求方声明。</p>
     *
     * @param tenantId 已确权租户
     * @param projectId 已确权项目
     * @param connectionDeviceId 实际承载连接的设备（§5.5：直连设备下等于目标设备）
     * @return 是否按推送形态投递
     */
    private boolean pushShaped(UUID tenantId, UUID projectId, UUID connectionDeviceId) {
        return deviceAccessSessionPort.config(tenantId, projectId, connectionDeviceId)
                .map(config -> config.protocol() == TransportProtocol.MQTT
                        || config.protocol() == TransportProtocol.TCP)
                .orElse(true);
    }

    /**
     * 待发队列预算（§6 每设备 100 条）：超出只打点，**绝不**丢弃已受理的命令，逾期按终态处理。
     *
     * <p>放在受理点而不是领取点：推送形态（MQTT／TCP）根本不领取，若只在领取时打点，这类设备永远看不到信号。</p>
     */
    private void recordPendingQueueBudget(UUID projectId, UUID deviceId) {
        if (repository.countPending(projectId, deviceId) > pendingCommandBudget) {
            metrics.recordPendingOverBudget();
        }
    }

    /** 写一行下行"已投递"调试日志；失败只影响调试面。 */
    private void logDownlinkDelivered(DeviceCommandDispatch dispatch, Instant publishedAt) {
        writeDebugLog(dispatch.tenantId(), dispatch.projectId(),
                new DeviceMessageLogCommand(dispatch.projectId(),
                dispatch.connectionDeviceId(), dispatch.attemptId(), protocolOf(dispatch.tenantId(),
                        dispatch.projectId(), dispatch.connectionDeviceId()),
                com.things.link.telemetry.domain.DeviceMessageLog.Direction.DOWN,
                "tc/v1/%s/%s/down/command/%s".formatted(dispatch.projectKey(), dispatch.connectionDeviceKey(),
                        dispatch.commandId()),
                dispatch.inputJson(), dispatch.inputJson() == null ? 0 : dispatch.inputJson().length(), null,
                publishedAt, publishedAt, dispatch.traceId(),
                dispatch.operationType() == null ? "COMMAND" : dispatch.operationType().name(),
                publishedAt, null));
    }

    /** 写一行设备"业务回复"调试日志；失败只影响调试面。 */
    private void logReplyReceived(DeviceCommandReply reply, String output) {
        writeDebugLog(reply.tenantId(), reply.projectId(),
                new DeviceMessageLogCommand(reply.projectId(), reply.connectionDeviceId(),
                reply.messageId(), protocolOf(reply.tenantId(), reply.projectId(), reply.connectionDeviceId()),
                com.things.link.telemetry.domain.DeviceMessageLog.Direction.UP,
                "up/command/reply/" + reply.commandId(), output, output == null ? 0 : output.length(),
                reply.errorCode(), reply.occurredAt(), reply.receivedAt(), reply.traceId(), "COMMAND_REPLY",
                null, reply.receivedAt()));
    }

    /**
     * 在调试日志写入器的独立事务之外捕获失败：调试面少一行可以，业务事实受损不可以。
     *
     * @param tenantId 权威租户
     * @param projectId 权威项目
     * @param command 日志命令
     */
    private void writeDebugLog(UUID tenantId, UUID projectId, DeviceMessageLogCommand command) {
        try {
            debugLogWriter.write(tenantId, projectId, command);
        } catch (RuntimeException exception) {
            LOGGER.warn("调试消息日志写入失败（不影响业务事实） messageId={}", command.messageId(), exception);
        }
    }

    /**
     * 调试日志里如实记录承载协议：读取失败或没有配置行的存量设备按 MQTT 默认档。
     *
     * <p>只是调试事实，失败不影响命令链路。</p>
     */
    private TransportProtocol protocolOf(UUID tenantId, UUID projectId, UUID deviceId) {
        try {
            return deviceAccessSessionPort.config(tenantId, projectId, deviceId)
                    .map(DeviceAccessSessionPort.Config::protocol)
                    .orElse(TransportProtocol.MQTT);
        } catch (RuntimeException exception) {
            return TransportProtocol.MQTT;
        }
    }

    /** 冻结是平台永久拒绝，不能冒充设备失败或把未耗尽尝试谎报成TIMED_OUT。 */
    private void stopFrozen(DeviceCommand command, Instant at, OptionalLong generation) {
        if (!repository.stopForProjectFreeze(command.tenantId(), command.projectId(), command.id(), command.attemptCount(), at)) {
            throw new IllegalStateException("命令冻结终态CAS失败");
        }
        appendTerminal(command, DeviceCommandTerminalEvent.Status.FAILED, "PROJECT_FROZEN", at, generation);
    }

    /** 原信封或有效领取已核验；当前能力拒绝必须与终态Outbox原子提交。 */
    private void stopUnsupportedProperty(DeviceCommand command, Instant at, OptionalLong generation) {
        if (!repository.stopForPropertyCapability(command.tenantId(), command.projectId(), command.id(),
                command.attemptCount(), at)) {
            throw new IllegalStateException("属性设置能力拒绝终态CAS失败");
        }
        appendTerminal(command, DeviceCommandTerminalEvent.Status.FAILED, PROPERTY_SET_PROTOCOL_UNSUPPORTED, at, generation);
    }

    /** 原信封或领取已核实；关系拒绝与终态Outbox必须同事务提交。 */
    private void stopUnavailableReceiver(DeviceCommand command, Instant at, OptionalLong generation) {
        if (!repository.stopForUnavailableReceiver(command.tenantId(), command.projectId(), command.id(),
                command.attemptCount(), at)) {
            throw new IllegalStateException("命令接收关系拒绝终态CAS失败");
        }
        appendTerminal(command, DeviceCommandTerminalEvent.Status.FAILED, COMMAND_ROUTE_UNAVAILABLE, at, generation);
    }

    /** 原请求及副作用语义不可重新解释，只从本次持锁原关系取得一致的服务器键。 */
    private void createNextAttempt(DeviceCommand command, DeviceCommandReceiverRoute route, Instant now) {
        DeviceCommandDispatch dispatch = new DeviceCommandDispatch(Uuid7.generate(), command.tenantId(),
                command.projectId(), command.id(), Uuid7.generate(), command.attemptCount() + 1,
                command.targetDeviceId(), route.targetDeviceKey(), command.connectionDeviceId(),
                route.connectionDeviceKey(), route.projectKey(), command.operationType(), command.commandKey(),
                command.requestJson(), now.plusSeconds(command.timeoutSeconds()), command.traceId());
        createAttemptAndOutbox(command, dispatch, now);
    }

    /** 创建一次尝试以及与其同事务的 Outbox。 */
    private void createAttemptAndOutbox(DeviceCommand command, DeviceCommandRoute route, JsonNode input,
                                        int attemptNo, Instant now) {
        UUID attemptId = Uuid7.generate();
        UUID eventId = Uuid7.generate();
        Instant deadline = now.plusSeconds(command.timeoutSeconds());
        String topic = "tc/v1/%s/%s/down/command/%s".formatted(
                route.projectKey(), route.connectionDeviceKey(), command.id());
        DeviceCommandDispatch dispatch = new DeviceCommandDispatch(eventId, command.tenantId(), command.projectId(),
                command.id(), attemptId, attemptNo, command.targetDeviceId(), route.targetDeviceKey(),
                command.connectionDeviceId(), route.connectionDeviceKey(), route.projectKey(), command.commandKey(),
                objectMapper.writeValueAsString(input), deadline, command.traceId());
        repository.createAttempt(new DeviceCommandAttempt(attemptId, command.tenantId(), command.projectId(),
                command.id(), attemptNo, eventId, command.connectionDeviceId(), topic,
                DeviceCommandAttempt.Status.PENDING, deadline, now));
        outboxRepository.append(new OutboxEvent(eventId, command.tenantId(), command.projectId(), "DEVICE_COMMAND",
                command.id(), DISPATCH_EVENT_TYPE, command.connectionDeviceId().toString(),
                objectMapper.writeValueAsString(dispatch), command.traceId(), now));
    }

    /** 规则设备操作使用已冻结信封写首次 attempt 与 Outbox。 */
    private void createAttemptAndOutbox(DeviceCommand command, DeviceCommandDispatch dispatch, Instant now) {
        repository.createAttempt(new DeviceCommandAttempt(dispatch.attemptId(), command.tenantId(), command.projectId(),
                command.id(), dispatch.attemptNo(), dispatch.eventId(), command.connectionDeviceId(), mqttTopic(dispatch),
                DeviceCommandAttempt.Status.PENDING, dispatch.deadlineAt(), now));
        outboxRepository.append(new OutboxEvent(dispatch.eventId(), command.tenantId(), command.projectId(),
                "DEVICE_COMMAND", command.id(), DISPATCH_EVENT_TYPE, command.connectionDeviceId().toString(),
                objectMapper.writeValueAsString(dispatch), command.traceId(), now));
    }

    /** 构造规则动作首次派发信封。 */
    private DeviceCommandDispatch ruleDispatch(DeviceCommand command, String projectKey, String targetDeviceKey,
                                                String connectionDeviceKey, JsonNode input, Instant now) {
        return new DeviceCommandDispatch(Uuid7.generate(), command.tenantId(), command.projectId(), command.id(),
                Uuid7.generate(), 1, command.targetDeviceId(), targetDeviceKey, command.connectionDeviceId(),
                connectionDeviceKey, projectKey, command.operationType(), command.commandKey(),
                objectMapper.writeValueAsString(input), now.plusSeconds(command.timeoutSeconds()), command.traceId());
    }

    /** @return 与操作类型一致的 MQTT Topic。 */
    private static String mqttTopic(DeviceCommandDispatch dispatch) {
        return dispatch.operationType() == DeviceCommandDispatch.OperationType.PROPERTY_SET
                ? "tc/v1/%s/%s/down/property/set".formatted(dispatch.projectKey(), dispatch.connectionDeviceKey())
                : "tc/v1/%s/%s/down/command/%s".formatted(
                dispatch.projectKey(), dispatch.connectionDeviceKey(), dispatch.commandId());
    }

    /** 与设备状态 CAS 同事务追加终态 Outbox；规则域等消费者按 commandId 幂等回写。 */
    private void appendTerminal(DeviceCommand command, DeviceCommandTerminalEvent.Status status,
                                String failureCode, Instant completedAt, OptionalLong generation) {
        UUID eventId = Uuid7.generate();
        DeviceCommandTerminalEvent event = new DeviceCommandTerminalEvent(eventId, command.tenantId(),
                command.projectId(), command.id(), command.operationType(), status, failureCode,
                completedAt, command.traceId());
        outboxRepository.append(new OutboxEvent(eventId, command.tenantId(), command.projectId(),
                "DEVICE_COMMAND", command.id(), TERMINAL_EVENT_TYPE, command.id().toString(),
                objectMapper.writeValueAsString(event), command.traceId(), completedAt));
        webhookSource.append(event, generation);
    }

    /** Console成员锁后复核依赖RC/RU的新语句快照，禁止静默继承RR并沿旧成员快照放行。 */
    private void requireConsoleWriteTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("Console命令必须在实际非只读事务中受理");
        }
        Integer isolation = jdbcTemplate.execute((ConnectionCallback<Integer>) Connection::getTransactionIsolation);
        if (isolation == null || (isolation != Connection.TRANSACTION_READ_COMMITTED
                && isolation != Connection.TRANSACTION_READ_UNCOMMITTED)) {
            throw new IllegalStateException("Console命令只支持READ COMMITTED或READ UNCOMMITTED事务");
        }
    }

    /** 锁前观察只定位可信归属，不能让消息自报tenant访问另一个租户的命令。 */
    private static void requireCommandIdentity(DeviceCommand command, UUID tenantId, UUID projectId, UUID commandId) {
        if (!command.tenantId().equals(tenantId) || !command.projectId().equals(projectId) || !command.id().equals(commandId)) {
            throw new InvalidCommandDispatchException("命令请求与持久身份不匹配");
        }
    }

    /** 持command锁后核验当前attempt身份；错误信封不能标记他人的尝试失败或成功。 */
    private DeviceCommandAttempt currentDispatchAttempt(DeviceCommand command, DeviceCommandDispatch dispatch) {
        requireCommandIdentity(command, dispatch.tenantId(), dispatch.projectId(), dispatch.commandId());
        DeviceCommandAttempt attempt = repository.findAttempts(command.projectId(), command.id()).stream()
                .filter(value -> value.attemptNo() == command.attemptCount()).findFirst()
                .orElseThrow(() -> new IllegalStateException("当前命令尝试事实不存在"));
        if (!attempt.id().equals(dispatch.attemptId()) || !attempt.outboxEventId().equals(dispatch.eventId())
                || !attempt.tenantId().equals(command.tenantId()) || !attempt.projectId().equals(command.projectId())
                || !attempt.commandId().equals(command.id()) || !attempt.connectionDeviceId().equals(dispatch.connectionDeviceId())
                || !command.connectionDeviceId().equals(dispatch.connectionDeviceId())
                || !command.targetDeviceId().equals(dispatch.targetDeviceId())
                || command.operationType() != dispatch.operationType() || !Objects.equals(command.commandKey(), dispatch.commandKey())
                || !dispatchJson(command.requestJson()).equals(dispatchJson(dispatch.inputJson()))) {
            throw new InvalidCommandDispatchException("命令派发与当前尝试事实不匹配");
        }
        return attempt;
    }

    /** 原Outbox载荷保留纳秒精度；只将关系列截止时刻作微秒舍入容忍，不削弱原信封比较。 */
    private void requireOriginalDispatch(DeviceCommand command, DeviceCommandAttempt attempt, DeviceCommandDispatch dispatch) {
        OutboxEvent outbox;
        try {
            outbox = outboxReader.findByIdentity(command.tenantId(), command.projectId(), dispatch.eventId())
                    .orElseThrow(() -> new InvalidCommandDispatchException("命令原Outbox事实不存在"));
        } catch (CorruptedOutboxEventException failure) {
            throw new InvalidCommandDispatchException("命令原Outbox元数据损坏", failure);
        }
        if (!outbox.id().equals(dispatch.eventId()) || !outbox.tenantId().equals(command.tenantId())
                || !outbox.projectId().equals(command.projectId()) || !outbox.aggregateId().equals(command.id())
                || !"DEVICE_COMMAND".equals(outbox.aggregateType()) || !DISPATCH_EVENT_TYPE.equals(outbox.eventType())
                || !DOWNLINK_TOPIC.equals(outbox.destinationTopic())
                || !dispatch.connectionDeviceId().toString().equals(outbox.partitionKey())
                || !dispatch.traceId().equals(outbox.traceId()) || !mqttTopic(dispatch).equals(attempt.topic())
                || Duration.between(attempt.deadlineAt(), dispatch.deadlineAt()).abs().compareTo(Duration.ofNanos(1_000)) > 0) {
            throw new InvalidCommandDispatchException("命令派发与原Outbox元数据不匹配");
        }
        DeviceCommandDispatch original;
        try { original = objectMapper.readValue(outbox.payload(), DeviceCommandDispatch.class); }
        catch (JacksonException failure) { throw new InvalidCommandDispatchException("命令原Outbox信封无法解析", failure); }
        // 只替换input的文本表示后比较整个record，JSON语义相同不因空格或属性顺序被误拒。
        DeviceCommandDispatch semantic = new DeviceCommandDispatch(dispatch.eventId(), dispatch.tenantId(), dispatch.projectId(),
                dispatch.commandId(), dispatch.attemptId(), dispatch.attemptNo(), dispatch.targetDeviceId(), dispatch.targetDeviceKey(),
                dispatch.connectionDeviceId(), dispatch.connectionDeviceKey(), dispatch.projectKey(), dispatch.operationType(),
                dispatch.commandKey(), original.inputJson(), dispatch.deadlineAt(), dispatch.traceId());
        if (!original.equals(semantic) || !dispatchJson(original.inputJson()).equals(dispatchJson(dispatch.inputJson()))) {
            throw new InvalidCommandDispatchException("命令派发与原Outbox信封不匹配");
        }
    }

    /** 只将JSON语法错误归为永久信封拒绝，数据库与事务异常不进入此分类。 */
    private JsonNode dispatchJson(String json) {
        try {
            JsonNode value = objectMapper.readTree(json);
            if (value == null || !value.isObject()) throw new InvalidCommandDispatchException("命令派发输入必须为JSON对象");
            return value;
        } catch (JacksonException failure) { throw new InvalidCommandDispatchException("命令派发输入无法解析", failure); }
    }

    /** Controller 之外的第二层授权，OPERATOR 可控，VIEWER 拒绝。 */
    private void requireControl(UUID projectId) {
        ProjectRole role = projectService.requireRoleInProject(projectId);
        if (role == ProjectRole.VIEWER)
            throw new BusinessException(DeviceCommandErrorCode.COMMAND_CONTROL_FORBIDDEN);
    }

    /** 命令业务幂等键是必填契约，不依赖通用 Filter 的可选语义。 */
    private static String requireIdempotencyKey(String value) {
        if (value == null || value.isBlank() || value.strip().length() > 128)
            throw new BusinessException(DeviceCommandErrorCode.COMMAND_INPUT_INVALID, "Idempotency-Key 不合法");
        return value.strip();
    }

    /**
     * 用持久命令或已领取信封的可信身份建立后台事务范围。
     *
     * <p>S12-2a1d：调用点必须位于真实事务且早于首条受 RLS 保护的业务 SQL；集中组件负责确认
     * 当前物理连接与事务绑定，避免两条分散 {@code set_config} 留下半范围。</p>
     *
     * @param tenantId 持久事实或领取结果中的租户ID
     * @param projectId 与租户来自同一可信事实的项目ID
     */
    private void configureMessageScope(UUID tenantId, UUID projectId) {
        transactionLocalRlsScope.establish(tenantId, projectId);
    }

    /** @param command 命令事实 @param attempts 派发尝试 */
    public record DeviceCommandDetails(DeviceCommand command, List<DeviceCommandAttempt> attempts) {
    }
}
