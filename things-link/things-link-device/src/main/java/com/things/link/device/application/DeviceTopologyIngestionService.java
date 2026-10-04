package com.things.link.device.application;

import com.things.link.device.domain.Device;
import com.things.link.device.domain.DeviceRepository;
import com.things.link.device.domain.DeviceTopology;
import com.things.link.device.domain.DeviceTopologyRepository;
import com.things.link.device.domain.DeviceType;
import com.things.link.device.domain.DeviceTypeRepository;
import com.things.link.device.infrastructure.metrics.DeviceTopologyIngestionMetrics;
import com.things.link.project.application.EffectiveQuotaPolicyProvider;
import com.things.link.project.application.ProjectQuotaService;
import com.things.link.project.application.ProjectService;
import com.things.link.project.application.QuotaStatus;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.DeviceTopologyMessage;
import com.things.link.shared.message.TopologyReplyMessage;
import com.things.link.support.outbox.OutboxEvent;
import com.things.link.support.outbox.TransactionalOutboxRepository;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

/**
 * 消费 {@code tc.device.topo} 拓扑消息的在线状态机（S10-2a/S10-3）。
 *
 * <p>与 {@link DeviceTopologyService} 的控制面绑定不同，本服务的信任边界是<b>已认证网关</b>（消息信封中的
 * gatewayId 来自接入层确权，不是 payload 自报）。网关只能操作自身关系：绑定/解绑预先存在的子设备、上报子设备
 * 上下线、注册新子设备。业务拒绝记固定原因指标并静默丢弃（不重试、不进 DLQ）；系统故障照常抛出以触发重试。</p>
 *
 * <p>S10-3 起，{@code topo/add|delete} 与 {@code sub/register} 在处理后经 Outbox 向网关回执
 * {@code down/topo/reply}（成功与失败都回；网关身份非法时无从回执）。{@code sub/login|logout} 是状态上报，
 * 不回执。</p>
 */
@Service
public class DeviceTopologyIngestionService {

    /** 拒绝原因只进指标与日志，不把网关 payload 写入日志。 */
    private static final Logger log = LoggerFactory.getLogger(DeviceTopologyIngestionService.class);

    /** 设备仓储。 */ private final DeviceRepository deviceRepository;
    /** 设备类型仓储。 */ private final DeviceTypeRepository typeRepository;
    /** 拓扑仓储。 */ private final DeviceTopologyRepository topologyRepository;
    /** 拓扑 inbox 使用的业务 JDBC 入口。 */ private final JdbcTemplate jdbcTemplate;
    /** 在当前事务连接上建立可信租户与项目 RLS 范围。 */
    private final TransactionLocalRlsScope transactionLocalRlsScope;
    /** 注册子设备受项目设备配额约束，复用控制面同款配额端口。 */
    private final EffectiveQuotaPolicyProvider quotaPolicyProvider;
    /** 项目共享配额服务。 */ private final ProjectQuotaService quotaService;
    /** 低基数拒绝与级联指标。 */ private final DeviceTopologyIngestionMetrics metrics;
    /** 回执发往网关前需要稳定的 MQTT projectKey。 */ private final ProjectService projectService;
    /** 拓扑回执与拓扑事务同提交的通用 Outbox。 */ private final TransactionalOutboxRepository outboxRepository;
    /** 回执信封序列化。 */ private final ObjectMapper objectMapper;
    /** ADR0057：数据面类型锁冲突必须保留为瞬时异常，不能确认inbox或写失败回执。 */
    private final DeviceTopologyRoleGuard roleGuard;
    private final DevicePresenceWebhookSource webhookSource;

    /**
     * 创建拓扑消息摄入状态机。
     *
     * @param deviceRepository 设备仓储
     * @param typeRepository 设备类型仓储
     * @param topologyRepository 拓扑仓储
     * @param jdbcTemplate 拓扑 inbox 业务 JDBC 访问入口
     * @param transactionLocalRlsScope 事务局部 RLS 范围建立器
     * @param quotaPolicyProvider 配额策略端口
     * @param quotaService 项目配额服务
     * @param metrics 拓扑摄入指标
     * @param projectService 项目路由端口
     * @param outboxRepository 事务 Outbox
     * @param objectMapper JSON 映射器
     * @param roleGuard 同事务类型锁与有效角色保护
     */
    public DeviceTopologyIngestionService(DeviceRepository deviceRepository, DeviceTypeRepository typeRepository,
                                          DeviceTopologyRepository topologyRepository, JdbcTemplate jdbcTemplate,
                                          TransactionLocalRlsScope transactionLocalRlsScope,
                                          EffectiveQuotaPolicyProvider quotaPolicyProvider,
                                          ProjectQuotaService quotaService,
                                          DeviceTopologyIngestionMetrics metrics,
                                          ProjectService projectService,
                                          TransactionalOutboxRepository outboxRepository,
                                          ObjectMapper objectMapper, DeviceTopologyRoleGuard roleGuard,DevicePresenceWebhookSource webhookSource) {
        this.deviceRepository = deviceRepository;
        this.typeRepository = typeRepository;
        this.topologyRepository = topologyRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionLocalRlsScope = transactionLocalRlsScope;
        this.quotaPolicyProvider = quotaPolicyProvider;
        this.quotaService = quotaService;
        this.metrics = metrics;
        this.projectService = projectService;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.roleGuard = roleGuard;
        this.webhookSource = webhookSource;
    }

    /**
     * 摄入一条拓扑消息；重复消息（相同 messageId）幂等跳过。
     *
     * @param message 拓扑消息信封
     */
    @Transactional
    public void ingest(DeviceTopologyMessage message) {
        transactionLocalRlsScope.establish(message.tenantId(), message.projectId());
        if (!tryAcquireMessage(message.messageId(), message.projectId(), message.receivedAt())) {
            return;
        }
        var presenceGeneration=webhookSource.capture(message.tenantId(),message.projectId());
        switch (message.type()) {
            case TOPO_ADD -> addTopology(message);
            case TOPO_DELETE -> deleteTopology(message,presenceGeneration);
            case SUB_DEVICE_LOGIN -> updateOnline(message, DeviceTopology.OnlineStatus.ONLINE,presenceGeneration);
            case SUB_DEVICE_LOGOUT -> updateOnline(message, DeviceTopology.OnlineStatus.OFFLINE,presenceGeneration);
            case SUB_DEVICE_REGISTER -> registerSubDevice(message);
        }
    }

    /**
     * 网关掉线时级联其全部有效子设备投影为 OFFLINE（ADR 0033）。
     *
     * <p>子设备在线态以 {@code dev_topo} 为权威；CAS 用网关断开的平台时间，晚于该时间的子设备 login
     * 事件不会被旧级联覆盖。</p>
     *
     * @param tenantId 网关归属租户
     * @param projectId 网关归属项目
     * @param gatewayId 掉线的网关设备 ID
     * @param receivedAt 网关断开时刻（平台时间）
     */
    @Transactional
    public void cascadeGatewayOffline(UUID tenantId, UUID projectId, UUID gatewayId, Instant receivedAt) {
        transactionLocalRlsScope.establish(tenantId, projectId);
        var presenceGeneration=webhookSource.capture(tenantId,projectId);
        // EMQX外层事务可能已持网关NO KEY UPDATE，不能升级成排他锁形成互相等待。
        Device gateway = deviceRepository.findByIdForKeyShare(projectId, gatewayId).orElse(null);
        if (gateway == null) {
            return;
        }
        // 已有漂移先失败，不进入多设备锁；EMQX已持设备锁时不能阻塞等待类型编辑者。
        DeviceType currentType = roleGuard.findTypeForDataPlane(projectId, gateway.deviceTypeId()).orElse(null);
        roleGuard.requireDeviceRole(projectId, gatewayId, currentType == null ? null : currentType.deviceKind());
        roleGuard.requireGatewayComponent(projectId, gatewayId);
        var bindings = topologyRepository.findActiveByGateway(projectId, gatewayId).stream()
                .sorted(Comparator.comparing(binding -> binding.subDeviceId().toString())).toList();
        for (DeviceTopology snapshot : bindings) {
            Device child = deviceRepository.findByIdForUpdate(projectId, snapshot.subDeviceId()).orElse(null);
            if (child == null) {
                continue;
            }
            DeviceTopology current = topologyRepository.findActiveBySubDevice(projectId, child.id()).orElse(null);
            if (current == null || !current.gatewayDeviceId().equals(gatewayId)) {
                continue; // 等待子设备锁期间可能已换绑；旧网关不能再更新新关系。
            }
            if (topologyRepository.updateOnlineStatus(projectId, child.id(),
                    DeviceTopology.OnlineStatus.OFFLINE, receivedAt)) {
                deviceRepository.setStatus(projectId, child.id(), Device.Status.OFFLINE, null);
                webhookSource.append(tenantId,projectId,child.id(),presenceGeneration,child.status().name(),"OFFLINE",
                        "TOPOLOGY","GATEWAY_DISCONNECTED",null,gatewayId,receivedAt);
                metrics.recordCascadedOffline();
            }
        }
    }

    /** {@code up/topo/add}：绑定预先存在的子设备到上报网关；已绑定自身则幂等确认，不得抢占他人子设备。 */
    private void addTopology(DeviceTopologyMessage message) {
        Device gateway = requireKind(message.projectId(), message.gatewayId(), DeviceType.DeviceKind.GATEWAY)
                .orElse(null);
        if (gateway == null) {
            reject(message, DeviceTopologyIngestionMetrics.RejectReason.GATEWAY_INVALID, "网关不存在或不是网关类型");
            return;
        }
        Device subDevice = resolveSubDevice(message.projectId(), message.subDeviceKey()).orElse(null);
        if (subDevice == null) {
            rejectAndReply(message, gateway, DeviceTopologyIngestionMetrics.RejectReason.SUB_DEVICE_INVALID,
                    "子设备不存在或不是子设备类型");
            return;
        }
        DeviceTopology existing = topologyRepository.findActiveBySubDevice(message.projectId(), subDevice.id())
                .orElse(null);
        if (existing != null && existing.gatewayDeviceId().equals(message.gatewayId())) {
            reply(message, gateway, TopologyReplyMessage.Status.SUCCESS, null, null); // 已绑定到自身，幂等确认
            return;
        }
        if (existing != null) {
            rejectAndReply(message, gateway, DeviceTopologyIngestionMetrics.RejectReason.GATEWAY_MISMATCH,
                    "网关不得抢占其他网关的子设备");
            return;
        }
        bind(message.projectId(), subDevice.tenantId(), message.gatewayId(), subDevice);
        reply(message, gateway, TopologyReplyMessage.Status.SUCCESS, null, null);
    }

    /** {@code up/topo/delete}：解除上报网关自己的绑定；未绑定则幂等确认。 */
    private void deleteTopology(DeviceTopologyMessage message,java.util.OptionalLong presenceGeneration) {
        Device gateway = requireKind(message.projectId(), message.gatewayId(), DeviceType.DeviceKind.GATEWAY)
                .orElse(null);
        if (gateway == null) {
            reject(message, DeviceTopologyIngestionMetrics.RejectReason.GATEWAY_INVALID, "网关不存在或不是网关类型");
            return;
        }
        Device subDevice = resolveSubDevice(message.projectId(), message.subDeviceKey()).orElse(null);
        if (subDevice == null) {
            rejectAndReply(message, gateway, DeviceTopologyIngestionMetrics.RejectReason.SUB_DEVICE_INVALID,
                    "子设备不存在或不是子设备类型");
            return;
        }
        DeviceTopology existing = topologyRepository.findActiveBySubDevice(message.projectId(), subDevice.id())
                .orElse(null);
        if (existing == null) {
            reply(message, gateway, TopologyReplyMessage.Status.SUCCESS, null, null); // 无有效绑定，幂等确认
            return;
        }
        if (!existing.gatewayDeviceId().equals(message.gatewayId())) {
            rejectAndReply(message, gateway, DeviceTopologyIngestionMetrics.RejectReason.GATEWAY_MISMATCH,
                    "网关不得解除其他网关的绑定");
            return;
        }
        topologyRepository.closeActive(message.projectId(), subDevice.id(), null);
        deviceRepository.setGatewayId(message.projectId(), subDevice.id(), null);
        deviceRepository.setStatus(message.projectId(), subDevice.id(), subDevice.statusWithoutGatewayReachability(), null);
        webhookSource.append(subDevice.tenantId(),message.projectId(),subDevice.id(),presenceGeneration,subDevice.status().name(),
                subDevice.statusWithoutGatewayReachability().name(),"TOPOLOGY","TOPOLOGY_UNBOUND",message.messageId(),
                existing.gatewayDeviceId(),message.receivedAt(),message.traceId());
        reply(message, gateway, TopologyReplyMessage.Status.SUCCESS, null, null);
    }

    /** {@code up/sub/login|logout}：CAS 更新子设备权威在线态并投影到统一可达性状态面。 */
    private void updateOnline(DeviceTopologyMessage message, DeviceTopology.OnlineStatus target,java.util.OptionalLong presenceGeneration) {
        // 认证只确认接入身份；类型可能已被旧版本改坏，仍须按当前事实校验网关角色。
        if (requireKind(message.projectId(), message.gatewayId(), DeviceType.DeviceKind.GATEWAY).isEmpty()) {
            reject(message, DeviceTopologyIngestionMetrics.RejectReason.GATEWAY_INVALID, "网关不存在或不是网关类型");
            return;
        }
        Device subDevice = resolveSubDevice(message.projectId(), message.subDeviceKey()).orElse(null);
        if (subDevice == null) {
            reject(message, DeviceTopologyIngestionMetrics.RejectReason.SUB_DEVICE_INVALID, "子设备不存在或不是子设备类型");
            return;
        }
        DeviceTopology existing = topologyRepository.findActiveBySubDevice(message.projectId(), subDevice.id())
                .orElse(null);
        if (existing == null || !existing.gatewayDeviceId().equals(message.gatewayId())) {
            reject(message, DeviceTopologyIngestionMetrics.RejectReason.GATEWAY_MISMATCH, "子设备未绑定到上报网关");
            return;
        }
        if (!topologyRepository.updateOnlineStatus(message.projectId(), subDevice.id(), target,
                message.receivedAt())) {
            return; // 晚于现有状态变更时间，属陈旧事件，不覆盖
        }
        deviceRepository.setStatus(message.projectId(), subDevice.id(), mapStatus(target),
                target == DeviceTopology.OnlineStatus.ONLINE ? message.receivedAt() : null);
        webhookSource.append(subDevice.tenantId(),message.projectId(),subDevice.id(),presenceGeneration,subDevice.status().name(),
                mapStatus(target).name(),"TOPOLOGY",target==DeviceTopology.OnlineStatus.ONLINE?"TOPOLOGY_LOGIN":"TOPOLOGY_LOGOUT",
                message.messageId(),message.gatewayId(),message.receivedAt(),message.traceId());
    }

    /** {@code up/sub/register}：网关注册新子设备（仅创建设备身份并绑定，无 MQTT 密钥，幂等、受配额约束）。 */
    private void registerSubDevice(DeviceTopologyMessage message) {
        // 在任何existing-key成功分支前确认信封owner二元组，不能让伪造tenant复用真实绑定取得SUCCESS。
        UUID ownerTenantId = quotaPolicyProvider.resolveTrustedDeviceProject(
                message.tenantId(), message.projectId()).tenantId();
        Device gateway = requireKind(message.projectId(), message.gatewayId(), DeviceType.DeviceKind.GATEWAY)
                .orElse(null);
        if (gateway == null) {
            reject(message, DeviceTopologyIngestionMetrics.RejectReason.GATEWAY_INVALID, "网关不存在或不是网关类型");
            return;
        }
        DeviceType type = typeRepository.findByTypeKey(message.projectId(), message.deviceTypeKey())
                .flatMap(found -> roleGuard.findTypeForDataPlane(message.projectId(), found.id())).orElse(null);
        // 标识可在草稿编辑中变更：锁后也核对key，不能使用锁前命中的旧标识注册到已改名类型。
        if (type == null || !type.typeKey().equals(message.deviceTypeKey())
                || type.deviceKind() != DeviceType.DeviceKind.SUB_DEVICE) {
            rejectAndReply(message, gateway, DeviceTopologyIngestionMetrics.RejectReason.TYPE_NOT_SUB_DEVICE,
                    "注册指定的设备类型不是子设备类型");
            return;
        }
        // owner租户锁把跨项目控制面和网关注册串行化；等待后才查key和存量，避免两个请求共同越限，
        // 也避免同key的后到请求撞唯一键后进入PostgreSQL已失效事务，无法提交幂等回执。
        deviceRepository.lockTenantDeviceQuota(ownerTenantId);
        QuotaStatus quotaStatus = quotaService.deviceQuotaStatus(ownerTenantId, message.projectId());
        Device existing = deviceRepository.findByDeviceKey(message.projectId(), message.subDeviceKey())
                .flatMap(device -> deviceRepository.findByIdForUpdate(message.projectId(), device.id())).orElse(null);
        if (existing != null) {
            DeviceType existingType = roleGuard.findTypeForDataPlane(message.projectId(), existing.deviceTypeId())
                    .orElse(null);
            if (existingType == null || existingType.deviceKind() != DeviceType.DeviceKind.SUB_DEVICE
                    || roleGuard.hasIncompatibleDeviceRole(message.projectId(), existing.id(), existingType.deviceKind())) {
                rejectAndReply(message, gateway, DeviceTopologyIngestionMetrics.RejectReason.SUB_DEVICE_INVALID,
                        "已有设备不是有效子设备，不能确认注册成功");
                return;
            }
            DeviceTopology binding = topologyRepository.findActiveBySubDevice(message.projectId(), existing.id())
                    .orElse(null);
            if (binding != null && binding.gatewayDeviceId().equals(message.gatewayId())) {
                reply(message, gateway, TopologyReplyMessage.Status.SUCCESS, null, null); // 已注册并绑定，幂等确认
                return;
            }
            rejectAndReply(message, gateway, DeviceTopologyIngestionMetrics.RejectReason.DEVICE_KEY_CONFLICT,
                    "子设备标识已存在");
            return;
        }
        if (quotaStatus == QuotaStatus.HARD_LIMIT || quotaStatus == QuotaStatus.DEGRADED) {
            rejectAndReply(message, gateway, DeviceTopologyIngestionMetrics.RejectReason.QUOTA_EXCEEDED,
                    "租户共享设备额度已用尽");
            return;
        }

        Device subDevice = new Device(Uuid7.generate(), ownerTenantId, message.projectId(), type.id(),
                message.gatewayId(), message.subDeviceKey(), message.name(), null, Device.Status.INACTIVE,
                null, null, Instant.now());
        try {
            deviceRepository.create(subDevice);
        } catch (DuplicateKeyException exception) {
            rejectAndReply(message, gateway, DeviceTopologyIngestionMetrics.RejectReason.DEVICE_KEY_CONFLICT,
                    "子设备标识已存在");
            return;
        }
        bind(message.projectId(), ownerTenantId, message.gatewayId(), subDevice);
        reply(message, gateway, TopologyReplyMessage.Status.SUCCESS, null, null);
    }

    /** 同事务双写 dev_topo 绑定与 gateway_id 投影。 */
    private void bind(UUID projectId, UUID tenantId, UUID gatewayId, Device subDevice) {
        DeviceTopology topology = new DeviceTopology(Uuid7.generate(), tenantId, projectId, gatewayId, subDevice.id(),
                DeviceTopology.BindSource.GATEWAY_REPORTED, DeviceTopology.OnlineStatus.UNKNOWN, null, null,
                null, Instant.now(), null, null, 1, Instant.now());
        topologyRepository.create(topology);
        if (!deviceRepository.setGatewayId(projectId, subDevice.id(), gatewayId)) {
            throw new IllegalStateException("已锁定子设备的网关投影未写入");
        }
        deviceRepository.setStatus(projectId, subDevice.id(), subDevice.statusWithoutGatewayReachability(), null);
    }

    /** 解析子设备，要求其存在且设备类型为 SUB_DEVICE。 */
    private Optional<Device> resolveSubDevice(UUID projectId, String deviceKey) {
        Device device = deviceRepository.findByDeviceKey(projectId, deviceKey).orElse(null);
        if (device == null) {
            return Optional.empty();
        }
        return requireKind(projectId, device.id(), DeviceType.DeviceKind.SUB_DEVICE);
    }

    /** 校验设备存在且类型匹配指定分类，非法时返回空，避免泄露跨项目设备存在性。 */
    private Optional<Device> requireKind(UUID projectId, UUID deviceId, DeviceType.DeviceKind expected) {
        // 网关锁先于子设备锁；锁后才检查存在性、类型与关系，覆盖等待期间的删除/换绑。
        Device device = (expected == DeviceType.DeviceKind.GATEWAY
                ? deviceRepository.findByIdForKeyShare(projectId, deviceId)
                : deviceRepository.findByIdForUpdate(projectId, deviceId)).orElse(null);
        if (device == null || device.deviceTypeId() == null) {
            return Optional.empty();
        }
        DeviceType type = roleGuard.findTypeForDataPlane(projectId, device.deviceTypeId()).orElse(null);
        if (type == null || type.deviceKind() != expected
                || roleGuard.hasIncompatibleDeviceRole(projectId, device.id(), type.deviceKind())) {
            return Optional.empty();
        }
        return Optional.of(device);
    }

    /** 记录业务拒绝：固定原因指标 + 不含 payload 的告警日志，随后正常返回（ack）。 */
    private void reject(DeviceTopologyMessage message, DeviceTopologyIngestionMetrics.RejectReason reason,
                        String detail) {
        metrics.recordRejected(reason);
        log.warn("拓扑消息被拒绝 reason={} type={} gatewayId={} subDeviceKey={} traceId={} detail={}",
                reason.tagValue(), message.type(), message.gatewayId(), message.subDeviceKey(),
                message.traceId(), detail);
    }

    /** 记录业务拒绝并向合法网关回执 FAILED。 */
    private void rejectAndReply(DeviceTopologyMessage message, Device gateway,
                                DeviceTopologyIngestionMetrics.RejectReason reason, String detail) {
        reject(message, reason, detail);
        reply(message, gateway, TopologyReplyMessage.Status.FAILED, reason, detail);
    }

    /** 与拓扑事务同提交的网关回执 Outbox（ADR 0031 的 down/topo/reply）。 */
    private void reply(DeviceTopologyMessage message, Device gateway, TopologyReplyMessage.Status status,
                       DeviceTopologyIngestionMetrics.RejectReason reason, String detail) {
        String projectKey = projectService.requireRoutingContext(message.projectId()).projectKey();
        TopologyReplyMessage reply = new TopologyReplyMessage(message.messageId(), message.tenantId(),
                message.projectId(), message.gatewayId(), projectKey, gateway.deviceKey(), message.subDeviceKey(),
                status, status == TopologyReplyMessage.Status.FAILED ? reason.tagValue() : null,
                status == TopologyReplyMessage.Status.FAILED ? detail : null, Instant.now(), message.traceId());
        outboxRepository.append(new OutboxEvent(Uuid7.generate(), message.tenantId(), message.projectId(),
                "DEVICE_TOPOLOGY", message.gatewayId(), TopologyReplyMessage.EVENT_TYPE,
                message.gatewayId().toString(), objectMapper.writeValueAsString(reply), message.traceId(),
                Instant.now()));
    }

    /** 以 messageId 抢占 inbox；相同 messageId 的重复消息返回 false。 */
    private boolean tryAcquireMessage(UUID messageId, UUID projectId, Instant receivedAt) {
        return jdbcTemplate.update("""
                INSERT INTO sys_inbox_message (message_id, project_id, received_at)
                VALUES (?, ?, ?)
                ON CONFLICT (message_id) DO NOTHING
                """, messageId, projectId, Timestamp.from(receivedAt)) == 1;
    }

    /** 子设备权威在线态到统一可达性状态面的映射（ADR 0033）；S10-2a 只产生 ONLINE/OFFLINE。 */
    private static Device.Status mapStatus(DeviceTopology.OnlineStatus onlineStatus) {
        return onlineStatus == DeviceTopology.OnlineStatus.ONLINE ? Device.Status.ONLINE : Device.Status.OFFLINE;
    }
}
