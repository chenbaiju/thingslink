package com.things.link.device.application;

import com.things.link.device.domain.Device;
import com.things.link.device.domain.DeviceRepository;
import com.things.link.device.domain.DeviceTopology;
import com.things.link.device.domain.DeviceTopologyRepository;
import com.things.link.device.domain.DeviceType;
import com.things.link.device.domain.DeviceTypeRepository;
import com.things.link.device.infrastructure.metrics.DeviceBatchIngestionMetrics;
import com.things.link.shared.message.GatewayBatchMessage;
import com.things.link.shared.message.SubDeviceReport;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 消费 {@code tc.device.batch} 的网关批量属性上报归属复核（S10-2b）。
 *
 * <p>与 {@link DeviceTopologyIngestionService} 一样，本服务的信任边界是<b>已认证网关</b>：{@code gatewayId}
 * 来自接入层确权，不是 payload 自报。逐条目按 {@code dev_topo} 权威事实复核「subDeviceKey 是否绑定到上报网关」，
 * 合法条目返回给 ingestion 拆到 normalized，非法条目记固定原因指标并静默丢弃（不重试、不进 DLQ）；
 * 结构层拒绝（{@code INVALID_MESSAGE_ID} / {@code DUPLICATE_MESSAGE_ID}）由信封携带，本服务只补记指标。</p>
 */
@Service
public class DeviceBatchIngestionService {

    /** 拒绝原因只进指标与日志，不把网关 payload 写入日志。 */
    private static final Logger log = LoggerFactory.getLogger(DeviceBatchIngestionService.class);

    /** 设备仓储。 */ private final DeviceRepository deviceRepository;
    /** 设备类型仓储。 */ private final DeviceTypeRepository typeRepository;
    /** 拓扑仓储。 */ private final DeviceTopologyRepository topologyRepository;
    /** 在当前事务连接上建立可信租户与项目 RLS 范围。 */
    private final TransactionLocalRlsScope transactionLocalRlsScope;
    /** 低基数拒绝与接收指标。 */ private final DeviceBatchIngestionMetrics metrics;

    /**
     * 创建批量归属复核服务。
     *
     * @param deviceRepository 设备仓储
     * @param typeRepository 设备类型仓储
     * @param topologyRepository 拓扑仓储
     * @param transactionLocalRlsScope 事务局部 RLS 范围建立器
     * @param metrics 批量摄入指标
     */
    public DeviceBatchIngestionService(DeviceRepository deviceRepository, DeviceTypeRepository typeRepository,
                                       DeviceTopologyRepository topologyRepository,
                                       TransactionLocalRlsScope transactionLocalRlsScope,
                                       DeviceBatchIngestionMetrics metrics) {
        this.deviceRepository = deviceRepository;
        this.typeRepository = typeRepository;
        this.topologyRepository = topologyRepository;
        this.transactionLocalRlsScope = transactionLocalRlsScope;
        this.metrics = metrics;
    }

    /**
     * 复核一条批量消息的全部条目并返回合法子设备上报。
     *
     * <p>只读，不抢占 inbox（幂等由子设备 messageId 在下游 normalized→telemetry 完成）；重投时
     * 重复拆分产生相同 messageId，由下游去重吸收。</p>
     *
     * @param message 批量消息信封
     * @return 通过归属复核、可拆到 normalized 的子设备上报列表
     */
    @Transactional(readOnly = true)
    public List<ResolvedSubDeviceReport> resolve(GatewayBatchMessage message) {
        transactionLocalRlsScope.establish(message.tenantId(), message.projectId());
        metrics.recordFrameReceived();
        List<ResolvedSubDeviceReport> accepted = new ArrayList<>();
        for (GatewayBatchMessage.Entry entry : message.entries()) {
            switch (entry) {
                case GatewayBatchMessage.Rejected rejected ->
                        metrics.recordRejected(rejected.reason(), rejected.rawBytes());
                case GatewayBatchMessage.Valid valid -> resolveValid(message, valid, accepted);
            }
        }
        return accepted;
    }

    /** 复核单个结构完整条目：子设备存在、类型匹配、绑定到上报网关。 */
    private void resolveValid(GatewayBatchMessage message, GatewayBatchMessage.Valid valid,
                              List<ResolvedSubDeviceReport> accepted) {
        SubDeviceReport report = valid.report();
        Device subDevice = deviceRepository.findByDeviceKey(message.projectId(), report.deviceKey()).orElse(null);
        if (subDevice == null) {
            reject(valid, GatewayBatchMessage.RejectReason.SUB_DEVICE_NOT_FOUND);
            return;
        }
        if (!isSubDeviceKind(message.projectId(), subDevice)) {
            reject(valid, GatewayBatchMessage.RejectReason.SUB_DEVICE_TYPE_MISMATCH);
            return;
        }
        DeviceTopology binding = topologyRepository.findActiveBySubDevice(message.projectId(), subDevice.id())
                .orElse(null);
        if (binding == null) {
            reject(valid, GatewayBatchMessage.RejectReason.SUB_DEVICE_NOT_BOUND);
            return;
        }
        if (!binding.gatewayDeviceId().equals(message.gatewayId())) {
            reject(valid, GatewayBatchMessage.RejectReason.SUB_DEVICE_GATEWAY_MISMATCH);
            return;
        }
        accepted.add(new ResolvedSubDeviceReport(subDevice.id(), report.messageId(), report.occurredAt(),
                report.modelVersion(),
                report.payload(), valid.rawBytes()));
        metrics.recordItemAccepted();
    }

    /** 校验设备存在且类型为 SUB_DEVICE；跨项目设备由 RLS 与项目参数共同隔离。 */
    private boolean isSubDeviceKind(UUID projectId, Device device) {
        if (device.deviceTypeId() == null) {
            return false;
        }
        DeviceType type = typeRepository.findById(projectId, device.deviceTypeId()).orElse(null);
        return type != null && type.deviceKind() == DeviceType.DeviceKind.SUB_DEVICE;
    }

    /** 记录业务拒绝：固定原因指标 + 不含 payload 的告警日志，随后正常返回（ack）。 */
    private void reject(GatewayBatchMessage.Valid valid, GatewayBatchMessage.RejectReason reason) {
        metrics.recordRejected(reason, valid.rawBytes());
        log.debug("批量上报条目被拒绝 reason={} deviceKey={}",
                reason.tagValue(), valid.report().deviceKey());
    }

}
