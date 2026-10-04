package com.things.link.device.application;

import com.things.link.device.domain.Device;
import com.things.link.device.domain.DeviceRepository;
import com.things.link.device.domain.DeviceType;
import com.things.link.device.domain.DeviceTypeRepository;
import com.things.link.device.domain.ModbusPointMapping;
import com.things.link.device.domain.ModbusPointMappingRepository;
import com.things.link.device.domain.ModbusPollRepository;
import com.things.link.device.infrastructure.metrics.ModbusConfigMetrics;
import com.things.link.project.application.ProjectService;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.DeviceConfigPush;
import com.things.link.shared.message.DeviceConfigReply;
import com.things.link.support.outbox.OutboxEvent;
import com.things.link.support.outbox.TransactionalOutboxRepository;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import com.things.link.support.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Modbus 点位配置的可靠下发与版本诊断（S10-4b）。
 *
 * <p>把最新已发布点位配置（全量点位集 + 版本号）经 Outbox 推给网关；网关应用后回
 * {@code up/config/reply} 上报版本，落后于当前发布版本时重推最新并记诊断指标。下发经 Outbox → Kafka →
 * EMQX {@code down/config}，与命令下行同一可靠性底座。</p>
 */
@Service
public class ModbusConfigService {

    /** 只记录网关与版本，不打印点位详情。 */
    private static final Logger log = LoggerFactory.getLogger(ModbusConfigService.class);

    /** 点位仓储。 */ private final ModbusPointMappingRepository repository;
    /** 设备仓储。 */ private final DeviceRepository deviceRepository;
    /** 设备类型仓储。 */ private final DeviceTypeRepository typeRepository;
    /** 项目路由端口。 */ private final ProjectService projectService;
    /** 事务 Outbox。 */ private final TransactionalOutboxRepository outboxRepository;
    /** 平台轮询状态仓储。 */ private final ModbusPollRepository pollRepository;
    /** JSON 序列化器。 */ private final ObjectMapper objectMapper;
    /** 版本诊断指标。 */ private final ModbusConfigMetrics metrics;
    /** ADR0071：ACK落后重推必须在原事务内取得项目持续许可。 */
    private final ProjectLifecycleAccessService lifecycle;
    /** Kafka 线程无 HTTP 上下文，按可信回执归属建立事务级 RLS 范围。 */
    private final TransactionLocalRlsScope transactionLocalRlsScope;

    /**
     * @param repository 点位仓储
     * @param deviceRepository 设备仓储
     * @param typeRepository 设备类型仓储
     * @param projectService 项目路由端口
     * @param outboxRepository 事务 Outbox
     * @param pollRepository 平台轮询状态仓储
     * @param objectMapper JSON 序列化器
     * @param metrics 版本诊断指标
     * @param lifecycle 项目持续许可
     * @param transactionLocalRlsScope 事务局部 RLS 范围建立器
     */
    public ModbusConfigService(ModbusPointMappingRepository repository, DeviceRepository deviceRepository,
                               DeviceTypeRepository typeRepository, ProjectService projectService,
                               TransactionalOutboxRepository outboxRepository,
                               ModbusPollRepository pollRepository, ObjectMapper objectMapper,
                               ModbusConfigMetrics metrics, ProjectLifecycleAccessService lifecycle,
                               TransactionLocalRlsScope transactionLocalRlsScope) {
        this.repository = repository;
        this.deviceRepository = deviceRepository;
        this.typeRepository = typeRepository;
        this.projectService = projectService;
        this.outboxRepository = outboxRepository;
        this.pollRepository = pollRepository;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.lifecycle = java.util.Objects.requireNonNull(lifecycle, "项目生命周期许可不能为空");
        this.transactionLocalRlsScope = java.util.Objects.requireNonNull(
                transactionLocalRlsScope, "事务局部RLS范围建立器不能为空");
    }

    /**
     * 把最新已发布点位配置经 Outbox 推给网关；无已发布点或网关不存在时 no-op。
     *
     * @param projectId 项目 ID
     * @param deviceId 网关设备 ID
     */
    @Transactional
    public void pushConfig(UUID projectId, UUID deviceId) {
        pushConfigInternal(projectId, deviceId, repository.findPublished(projectId, deviceId));
    }

    /** 同一代理事务中的内部实现；ACK路径不能self-invocation假定重新开启事务。 */
    private void pushConfigInternal(UUID projectId, UUID deviceId, List<ModbusPointMapping> points) {
        if (points.isEmpty()) {
            return;
        }
        Device gateway = deviceRepository.findById(projectId, deviceId).orElse(null);
        if (gateway == null) {
            return;
        }
        String projectKey = projectService.requireRoutingContext(projectId).projectKey();
        List<DeviceConfigPush.Point> configPoints = new ArrayList<>();
        for (ModbusPointMapping point : points) {
            Device subDevice = deviceRepository.findById(projectId, point.subDeviceId()).orElse(null);
            if (subDevice == null) {
                continue; // 子设备已删除，跳过该点位
            }
            configPoints.add(new DeviceConfigPush.Point(subDevice.deviceKey(), point.propertyKey(),
                    point.slaveAddress(), point.functionCode().name(), point.registerAddress(),
                    point.dataType().name(), point.byteOrder().name(), point.scale(), point.offset(),
                    point.pollingIntervalMs()));
        }
        DeviceConfigPush push = new DeviceConfigPush(points.get(0).tenantId(), projectId, deviceId,
                projectKey, gateway.deviceKey(), DeviceConfigPush.CONFIG_TYPE, points.get(0).version(), configPoints);
        outboxRepository.append(new OutboxEvent(Uuid7.generate(), push.tenantId(), projectId,
                "DEVICE_CONFIG", deviceId, DeviceConfigPush.EVENT_TYPE, deviceId.toString(),
                objectMapper.writeValueAsString(push), TraceContext.resolve(TraceContext.current()), Instant.now()));
        // MODBUS_RTU_CLOUD_GATEWAY 由平台驱动轮询：物化轮询表，调度器据此发送读请求。
        DeviceType type = typeRepository.findById(projectId, gateway.deviceTypeId()).orElse(null);
        if (type != null && type.payloadProtocol() == DeviceType.PayloadProtocol.MODBUS_RTU_CLOUD_GATEWAY) {
            pollRepository.syncSchedule(projectId, deviceId, projectKey, gateway.deviceKey(), points);
        }
        log.debug("Modbus 配置已入 Outbox 下发 gatewayId={} version={} points={}",
                deviceId, push.version(), configPoints.size());
    }

    /**
     * 处理网关配置 ACK：应用成功且版本落后于当前发布版本时重推最新并记诊断；拒绝记指标。
     *
     * @param reply 网关配置回执
     */
    @Transactional
    public void processReply(DeviceConfigReply reply) {
        validateReply(reply);
        transactionLocalRlsScope.establish(reply.tenantId(), reply.projectId());
        if (reply.status() == DeviceConfigReply.Status.REJECTED) {
            metrics.recordRejected();
            log.warn("网关拒绝 Modbus 配置 gatewayId={} version={} errorCode={}",
                    reply.gatewayId(), reply.version(), reply.errorCode());
            return;
        }
        List<ModbusPointMapping> observed = repository.findPublished(reply.projectId(), reply.gatewayId());
        if (!isBehind(reply.version(), observed)) return;
        // 只让确实落后的APPLIED进入锁序；相等/未来版本与REJECTED不产生项目锁或写事实。
        if (!lifecycle.lockActiveForWrite(reply.tenantId(), reply.projectId())) return;
        List<ModbusPointMapping> current = repository.findPublished(reply.projectId(), reply.gatewayId());
        if (!isBehind(reply.version(), current)) return;
        metrics.recordMismatch();
        log.warn("网关 Modbus 配置版本落后 gatewayId={} applied={} current={}",
                reply.gatewayId(), reply.version(), current.getFirst().version());
        pushConfigInternal(reply.projectId(), reply.gatewayId(), current);
    }

    /** 锁前与锁后都用相同版本规则；空点位、相等或未来ACK均吸收。 */
    private static boolean isBehind(int appliedVersion, List<ModbusPointMapping> published) {
        return !published.isEmpty() && appliedVersion < published.getFirst().version();
    }

    /** record构造器已覆盖通用完整性，这里收紧本端口唯一受理的配置类型。 */
    private static void validateReply(DeviceConfigReply reply) {
        if (reply == null || reply.messageId() == null || reply.messageId().version() != 7
                || reply.tenantId() == null || reply.projectId() == null || reply.gatewayId() == null
                || reply.version() < 0 || reply.status() == null || reply.occurredAt() == null
                || reply.receivedAt() == null || reply.traceId() == null || reply.traceId().isBlank()) {
            throw new IllegalArgumentException("配置回执信封不完整");
        }
        if (!DeviceConfigReply.CONFIG_TYPE.equals(reply.configType())) {
            throw new IllegalArgumentException("配置回执类型不受支持");
        }
        boolean rejected = reply.status() == DeviceConfigReply.Status.REJECTED;
        if (rejected != (reply.errorCode() != null && !reply.errorCode().isBlank())
                || (!rejected && reply.message() != null)) {
            throw new IllegalArgumentException("配置回执状态与诊断字段不匹配");
        }
    }

}
