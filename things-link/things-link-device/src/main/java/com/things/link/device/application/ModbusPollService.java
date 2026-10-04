package com.things.link.device.application;

import com.things.link.device.domain.Device;
import com.things.link.device.domain.DeviceRepository;
import com.things.link.device.domain.DeviceType;
import com.things.link.device.domain.DeviceTypeRepository;
import com.things.link.device.domain.ModbusPointMapping;
import com.things.link.device.domain.ModbusPoll;
import com.things.link.device.domain.ModbusPollRepository;
import com.things.link.device.infrastructure.metrics.ModbusPollMetrics;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.ModbusRequest;
import com.things.link.shared.message.ModbusResponse;
import com.things.link.support.outbox.OutboxEvent;
import com.things.link.support.outbox.TransactionalOutboxRepository;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import com.things.link.support.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Modbus 平台驱动轮询引擎（S10-4c）。
 *
 * <p>从 {@code dev_modbus_poll} 领取到期轮询、发送读请求（Outbox → Kafka → EMQX {@code down/modbus/request}），
 * 响应按 requestId 关联、解码为子设备属性值并推进下一周期；超时按有限次数重试，耗尽后等待下一周期。</p>
 */
@Service
public class ModbusPollService {

    /** 单次轮询周期内的最大尝试次数。 */
    private static final int MAX_ATTEMPTS = 3;

    /** 不打印寄存器原始值。 */
    private static final Logger log = LoggerFactory.getLogger(ModbusPollService.class);

    /** 轮询状态仓储。 */ private final ModbusPollRepository repository;
    /** 事务 Outbox。 */ private final TransactionalOutboxRepository outboxRepository;
    /** JSON 序列化器。 */ private final ObjectMapper objectMapper;
    /** 低基数指标。 */ private final ModbusPollMetrics metrics;
    /** 在每个已领取poll的独立事务内建立可信租户/项目二轴范围。 */
    private final TransactionLocalRlsScope transactionLocalRlsScope;
    /** 每个poll独占一个READ COMMITTED事务，禁止同事务跨项目改写RLS范围。 */
    private final TransactionTemplate pollTransaction;
    /** 读取当前有效设备在线事实，历史物化不能代替当前资格。 */
    private final DeviceRepository deviceRepository;
    /** ADR0034由当前协议决定执行位置，切换为本地轮询后不得继续平台发送。 */
    private final DeviceTypeRepository typeRepository;
    /** ADR0064：后台意图与响应接纳须在原事务持有项目写许可，删除提交后不再新增业务。 */
    private final ProjectLifecycleAccessService projectLifecycle;

    /**
     * @param repository 轮询状态仓储
     * @param outboxRepository 事务 Outbox
     * @param objectMapper JSON 序列化器
     * @param metrics 轮询指标
     * @param transactionLocalRlsScope 事务局部可信租户/项目范围入口
     * @param transactionManager 每个poll独立提交或回滚的事务管理器
     * @param deviceRepository 当前设备事实
     * @param typeRepository 当前分类与协议事实
     * @param projectLifecycle 可信项目归属的原事务生命周期许可
     */
    public ModbusPollService(ModbusPollRepository repository, TransactionalOutboxRepository outboxRepository,
                             ObjectMapper objectMapper, ModbusPollMetrics metrics,
                             TransactionLocalRlsScope transactionLocalRlsScope,
                             PlatformTransactionManager transactionManager,
                             DeviceRepository deviceRepository, DeviceTypeRepository typeRepository,
                             ProjectLifecycleAccessService projectLifecycle) {
        this.repository = repository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.transactionLocalRlsScope = transactionLocalRlsScope;
        this.deviceRepository = deviceRepository;
        this.typeRepository = typeRepository;
        this.projectLifecycle = projectLifecycle;
        this.pollTransaction = new TransactionTemplate(transactionManager);
        this.pollTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // D-117要求网关锁后取得新快照，强隔离级别会破坏该并发判定。
        this.pollTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /**
     * 逐个独立事务领取到期轮询并发送读请求。
     *
     * @param limit 单次最多处理的poll数，同时作为每次领取的候选网关上限
     * @return 已发送请求数
     */
    public int scanDue(int limit) {
        if (limit < 1) {
            return 0;
        }
        int sent = 0;
        for (int processed = 0; processed < limit; processed++) {
            DueScanOutcome outcome = Objects.requireNonNull(
                    pollTransaction.execute(status -> processOneDue(limit)),
                    "Modbus到期轮询事务不得返回null");
            if (!outcome.claimed()) {
                break;
            }
            if (outcome.sent()) {
                sent++;
            }
        }
        return sent;
    }

    /**
     * 逐个独立事务领取租约过期请求，有限重试或释放。
     *
     * @param limit 单次最多处理的poll数，同时作为每次领取的候选行上限
     * @return 已处理数
     */
    public int scanTimeout(int limit) {
        if (limit < 1) {
            return 0;
        }
        int handled = 0;
        for (; handled < limit; handled++) {
            boolean claimed = Boolean.TRUE.equals(
                    pollTransaction.execute(status -> processOneTimeout(limit)));
            if (!claimed) {
                break;
            }
        }
        return handled;
    }

    /** @param candidateLimit 候选网关上限 @return 本事务的领取和发送结果 */
    private DueScanOutcome processOneDue(int candidateLimit) {
        Optional<ModbusPoll> claimed = repository.claimOneDue(candidateLimit);
        if (claimed.isEmpty()) {
            return DueScanOutcome.NONE;
        }
        ModbusPoll poll = claimed.get();
        // 领取结果是本入口唯一可信归属；必须在任何设备、项目或Outbox SQL之前建立同连接范围。
        transactionLocalRlsScope.establish(poll.tenantId(), poll.projectId());
        return sendRequest(poll, 1) ? DueScanOutcome.SENT : DueScanOutcome.PAUSED;
    }

    /** @param candidateLimit 过期候选行上限 @return 是否领取并处理了一个poll */
    private boolean processOneTimeout(int candidateLimit) {
        Optional<ModbusPoll> claimed = repository.claimOneExpiredInFlight(candidateLimit);
        if (claimed.isEmpty()) {
            return false;
        }
        ModbusPoll poll = claimed.get();
        // 超时行同样来自全局领取；先建立其可信双轴范围，再触碰RLS业务事实。
        transactionLocalRlsScope.establish(poll.tenantId(), poll.projectId());
        if (poll.attempt() >= MAX_ATTEMPTS) {
            repository.release(poll.id(), nextPollAt(poll));
            metrics.recordFailed();
            log.debug("Modbus 轮询重试耗尽 gatewayId={} propertyKey={}", poll.deviceId(), poll.propertyKey());
        } else {
            sendRequest(poll, poll.attempt() + 1);
        }
        return true;
    }

    /**
     * 处理网关 Modbus 响应：按 requestId 关联、解码为属性值、推进下一周期。
     *
     * <p>ADR0063：本方法只完成状态与解码；生产只允许可靠接管服务在其外层事务内调用，
     * MANDATORY 会拒绝无事务独立调用，避免请求完成脱离 normalized 可靠交付。
     *
     * @param response 网关响应
     * @return 解码后的子设备属性值；重复/过期响应或 ERROR 时为空
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ResolvedModbusValue> handleResponse(ModbusResponse response) {
        ModbusPoll poll = repository.findByRequestId(response.requestId()).orElse(null);
        if (poll == null) {
            return Optional.empty(); // 重复或已完成的响应，幂等吸收
        }
        // D-121 / 架构§5.2、§7：requestId由设备payload提供，可信发送者仍须匹配请求归属。
        if (!poll.tenantId().equals(response.tenantId()) || !poll.projectId().equals(response.projectId())
                || !poll.deviceId().equals(response.gatewayId())) {
            return Optional.empty();
        }
        // D-121三轴核验通过后，才用数据库轮询事实的权威二元组建立范围；
        // 未知请求或错误归属不得产生范围副作用。
        transactionLocalRlsScope.establish(poll.tenantId(), poll.projectId());
        // ADR0064决策3：范围建立后才申请资格，失效响应不能推进CAS或产生normalized。
        if (!projectLifecycle.lockActiveForWrite(poll.tenantId(), poll.projectId())) {
            return Optional.empty();
        }
        if (response.status() == ModbusResponse.Status.ERROR) {
            if (repository.completeRequest(poll.id(), response.requestId(), nextPollAt(poll))) {
                metrics.recordFailed();
            }
            return Optional.empty();
        }
        // D-120：读取后重试或另一响应可能已获胜，只有仍持有该请求关联才能产出属性结果。
        if (!repository.completeRequest(poll.id(), response.requestId(), nextPollAt(poll))) {
            return Optional.empty();
        }
        // 先吸收失效响应；当前响应解码失败仍在本事务回滚完成更新，保留后续重试资格。
        Object value = decode(poll.dataType(), poll.byteOrder(), poll.scale(), poll.offset(), response.data());
        metrics.recordSuccess();
        return Optional.of(new ResolvedModbusValue(poll.subDeviceId(), poll.deviceId(), poll.propertyKey(),
                response.requestId(), value));
    }

    /** ADR0062：复核当前在线平台网关后发送；不合格则暂停，返回值仅计实际新增意图。 */
    private boolean sendRequest(ModbusPoll poll, int attempt) {
        if (isEligibleGateway(poll)
                && projectLifecycle.lockActiveForWrite(poll.tenantId(), poll.projectId())) {
            appendRequest(poll, attempt);
            return true;
        }
        // 不撤回已提交Outbox；只清本地关联并按数据库同源时钟等待下一周期。
        repository.pauseUntilNextInterval(poll.id());
        return false;
    }

    /** 只在poll项目范围内读取；普通查询是资格观察点，不保证之后不再断线或更换协议。 */
    private boolean isEligibleGateway(ModbusPoll poll) {
        Device gateway = deviceRepository.findById(poll.projectId(), poll.deviceId()).orElse(null);
        if (gateway == null || gateway.status() != Device.Status.ONLINE || gateway.deviceTypeId() == null
                || !poll.tenantId().equals(gateway.tenantId()) || !poll.projectId().equals(gateway.projectId())) {
            return false;
        }
        DeviceType type = typeRepository.findById(poll.projectId(), gateway.deviceTypeId()).orElse(null);
        return type != null && type.deviceKind() == DeviceType.DeviceKind.GATEWAY
                && type.payloadProtocol() == DeviceType.PayloadProtocol.MODBUS_RTU_CLOUD_GATEWAY
                && poll.tenantId().equals(type.tenantId()) && poll.projectId().equals(type.projectId());
    }

    /** 资格满足后在原事务生成关联和Outbox；任何查询/写入故障都交由外层回滚，不能当离线跳过。 */
    private void appendRequest(ModbusPoll poll, int attempt) {
        UUID requestId = Uuid7.generate();
        repository.markRequest(poll.id(), requestId, attempt);
        ModbusRequest request = new ModbusRequest(requestId, poll.tenantId(), poll.projectId(), poll.deviceId(),
                poll.projectKey(), poll.gatewayKey(), poll.slaveAddress(), poll.functionCode().name(),
                poll.registerAddress(), poll.dataType().width());
        outboxRepository.append(new OutboxEvent(Uuid7.generate(), poll.tenantId(), poll.projectId(),
                "DEVICE_MODBUS", poll.deviceId(), ModbusRequest.EVENT_TYPE, poll.deviceId().toString(),
                objectMapper.writeValueAsString(request), TraceContext.resolve(TraceContext.current()), Instant.now()));
    }

    /** @param poll 轮询行 @return 下一轮询时刻 */
    private static Instant nextPollAt(ModbusPoll poll) {
        return Instant.now().plusMillis(poll.pollingIntervalMs());
    }

    /** 把原始寄存器值按数据类型/字节序组装成属性值，并应用缩放/偏移。 */
    private static Object decode(ModbusPointMapping.DataType dataType, ModbusPointMapping.ByteOrder byteOrder,
                                 BigDecimal scale, BigDecimal offset, List<Integer> data) {
        if (dataType == ModbusPointMapping.DataType.BIT) {
            return !data.isEmpty() && data.get(0) != 0;
        }
        double raw = switch (dataType) {
            case INT16 -> (short) data.get(0).intValue();
            case UINT16 -> data.get(0);
            case INT32 -> int32(data, byteOrder);
            case UINT32 -> uint32(data, byteOrder);
            case FLOAT32 -> float32(data, byteOrder);
            case BIT -> throw new IllegalStateException("BIT 不在数值分支");
        };
        double scaleFactor = scale == null ? 1.0 : scale.doubleValue();
        double offsetValue = offset == null ? 0.0 : offset.doubleValue();
        return raw * scaleFactor + offsetValue;
    }

    /** 两个 16 位寄存器按字节序合成 32 位有符号整数。 */
    private static int int32(List<Integer> data, ModbusPointMapping.ByteOrder order) {
        int high = order == ModbusPointMapping.ByteOrder.BIG_ENDIAN ? data.get(0) : data.get(1);
        int low = order == ModbusPointMapping.ByteOrder.BIG_ENDIAN ? data.get(1) : data.get(0);
        return (high << 16) | (low & 0xFFFF);
    }

    /** 两个 16 位寄存器按字节序合成 32 位无符号整数。 */
    private static long uint32(List<Integer> data, ModbusPointMapping.ByteOrder order) {
        int high = order == ModbusPointMapping.ByteOrder.BIG_ENDIAN ? data.get(0) : data.get(1);
        int low = order == ModbusPointMapping.ByteOrder.BIG_ENDIAN ? data.get(1) : data.get(0);
        return ((long) high << 16) | (low & 0xFFFFL);
    }

    /** 两个 16 位寄存器按字节序合成 IEEE-754 单精度浮点。 */
    private static float float32(List<Integer> data, ModbusPointMapping.ByteOrder order) {
        return Float.intBitsToFloat(int32(data, order));
    }

    /** 单个到期poll在独立事务内的处理结果。 */
    private enum DueScanOutcome {
        /** 有界候选中没有可领取poll，当前扫描应停止。 */
        NONE(false, false),
        /** 已领取但当前资格失效，已暂停到下一周期。 */
        PAUSED(true, false),
        /** 已领取并原子追加请求Outbox。 */
        SENT(true, true);

        /** 是否实际领取了poll。 */
        private final boolean claimed;
        /** 是否实际追加了请求Outbox。 */
        private final boolean sent;

        /** @param claimed 是否领取 @param sent 是否发送 */
        DueScanOutcome(boolean claimed, boolean sent) {
            this.claimed = claimed;
            this.sent = sent;
        }

        /** @return 是否实际领取了poll */
        private boolean claimed() {
            return claimed;
        }

        /** @return 是否实际追加了请求Outbox */
        private boolean sent() {
            return sent;
        }
    }

    /**
     * 解码后的子设备属性值，供消费端构造 {@code StandardUplinkMessage} 写入 normalized。
     *
     * @param subDeviceId 子设备 ID
     * @param gatewayId 网关 ID
     * @param propertyKey 属性键
     * @param messageId 稳定消息 ID（沿用 requestId，保证重投幂等）
     * @param value 属性值
     */
    public record ResolvedModbusValue(UUID subDeviceId, UUID gatewayId, String propertyKey,
                                      UUID messageId, Object value) {
    }
}
