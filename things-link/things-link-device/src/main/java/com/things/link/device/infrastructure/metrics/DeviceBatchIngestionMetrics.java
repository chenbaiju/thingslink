package com.things.link.device.infrastructure.metrics;

import com.things.link.shared.message.GatewayBatchMessage.RejectReason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * 网关批量属性上报摄入的低基数指标门面。
 *
 * <p>只使用代码冻结的拒绝原因作为标签；项目、网关与子设备 ID 都不能成为标签，否则设备规模会直接
 * 转化为 Prometheus 时序规模（与 {@code DeviceTopologyIngestionMetrics} 同一纪律）。字节计量按
 * 「原始条目数 + 原始顺序」确定性均分，合法条目进入 normalized、拒绝条目计入 {@code rejected_bytes}，
 * 二者合计恒等于帧原始字节。</p>
 */
@Component
public class DeviceBatchIngestionMetrics {

    /** 批量帧到达 device 复核端口的总数；Prometheus 会追加 {@code _total}。 */
    public static final String FRAME_RECEIVED = "device_batch_frame_received";

    /** 通过归属复核、进入 normalized 的条目总数。 */
    public static final String ITEM_ACCEPTED = "device_batch_item_accepted";

    /** 被拒绝的条目总数，按固定原因标签。 */
    public static final String ITEM_REJECTED = "device_batch_item_rejected";

    /** 被拒绝条目对应的原始字节份额，按固定原因标签。 */
    public static final String REJECTED_BYTES = "device_batch_rejected_bytes";

    /** 帧到达计数器。 */
    private final Counter frameReceived;

    /** 条目接受计数器。 */
    private final Counter itemAccepted;

    /** 固定拒绝原因到条目计数器的映射。 */
    private final Map<RejectReason, Counter> itemRejected = new EnumMap<>(RejectReason.class);

    /** 固定拒绝原因到字节计数器的映射。 */
    private final Map<RejectReason, Counter> rejectedBytes = new EnumMap<>(RejectReason.class);

    /**
     * 注册全部有限标签组合，避免第一次故障时才临时创建 meter。
     *
     * @param meterRegistry Micrometer 注册表
     */
    public DeviceBatchIngestionMetrics(MeterRegistry meterRegistry) {
        frameReceived = Counter.builder(FRAME_RECEIVED)
                .description("到达归属复核的网关批量属性上报帧数")
                .register(meterRegistry);
        itemAccepted = Counter.builder(ITEM_ACCEPTED)
                .description("通过归属复核进入 normalized 的子设备条目数")
                .register(meterRegistry);
        for (RejectReason reason : RejectReason.values()) {
            itemRejected.put(reason, Counter.builder(ITEM_REJECTED)
                    .description("批量上报被拒绝的条目数")
                    .tag("reason", reason.tagValue())
                    .register(meterRegistry));
            rejectedBytes.put(reason, Counter.builder(REJECTED_BYTES)
                    .description("批量上报被拒绝条目的原始字节份额")
                    .tag("reason", reason.tagValue())
                    .register(meterRegistry));
        }
    }

    /** 记录一帧到达归属复核。 */
    public void recordFrameReceived() {
        frameReceived.increment();
    }

    /** 记录一个条目通过归属复核。 */
    public void recordItemAccepted() {
        itemAccepted.increment();
    }

    /**
     * 记录一个条目被拒绝，同时计入其原始字节份额。
     *
     * @param reason 固定拒绝原因
     * @param rawBytes 按原始顺序均分的字节份额
     */
    public void recordRejected(RejectReason reason, int rawBytes) {
        itemRejected.get(reason).increment();
        rejectedBytes.get(reason).increment(rawBytes);
    }
}
