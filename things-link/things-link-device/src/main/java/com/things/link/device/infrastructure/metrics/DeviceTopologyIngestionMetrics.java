package com.things.link.device.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * 拓扑消息摄入的低基数指标门面。
 *
 * <p>只使用代码冻结的拒绝原因作为标签；项目、网关与子设备 ID 都不能成为标签，否则设备规模会直接
 * 转化为 Prometheus 时序规模（与 {@code DeviceConnectionMetrics} 同一纪律）。</p>
 */
@Component
public class DeviceTopologyIngestionMetrics {

    /** 业务拒绝计数器名称；Prometheus 会追加 {@code _total}。 */
    public static final String REJECTIONS = "thingslink.device.topology.rejections";

    /** 网关掉线级联子设备 OFFLINE 计数器。 */
    public static final String CASCADED_OFFLINE = "thingslink.device.topology.cascaded_offline";

    /** 固定拒绝原因到计数器的映射。 */
    private final Map<RejectReason, Counter> rejections = new EnumMap<>(RejectReason.class);

    /** 级联下线计数器。 */
    private final Counter cascadedOffline;

    /**
     * 注册全部有限标签组合，避免第一次故障时才临时创建 meter。
     *
     * @param meterRegistry Micrometer 注册表
     */
    public DeviceTopologyIngestionMetrics(MeterRegistry meterRegistry) {
        for (RejectReason reason : RejectReason.values()) {
            rejections.put(reason, Counter.builder(REJECTIONS)
                    .description("拓扑消息被业务拒绝的次数")
                    .tag("reason", reason.tagValue)
                    .register(meterRegistry));
        }
        cascadedOffline = Counter.builder(CASCADED_OFFLINE)
                .description("网关掉线级联投影为 OFFLINE 的子设备数量")
                .register(meterRegistry);
    }

    /** 记录一条业务拒绝。 @param reason 固定拒绝原因 */
    public void recordRejected(RejectReason reason) {
        rejections.get(reason).increment();
    }

    /** 记录一条级联下线。 */
    public void recordCascadedOffline() {
        cascadedOffline.increment();
    }

    /** Prometheus 允许使用的固定拒绝原因。 */
    public enum RejectReason {
        /** 子设备不存在或不是 SUB_DEVICE 类型。 */
        SUB_DEVICE_INVALID("sub_device_invalid"),
        /** 网关不存在或不是 GATEWAY 类型。 */
        GATEWAY_INVALID("gateway_invalid"),
        /** 网关试图操作不属于自己的绑定。 */
        GATEWAY_MISMATCH("gateway_mismatch"),
        /** 项目设备配额已达硬限或降级阈值。 */
        QUOTA_EXCEEDED("quota_exceeded"),
        /** 注册的 deviceKey 已存在且绑定到其他网关。 */
        DEVICE_KEY_CONFLICT("device_key_conflict"),
        /** 注册指定的设备类型不是 SUB_DEVICE。 */
        TYPE_NOT_SUB_DEVICE("type_not_sub_device");

        /** 低基数标签值。 */
        private final String tagValue;

        /**
         * @param tagValue Prometheus 标签值
         */
        RejectReason(String tagValue) {
            this.tagValue = tagValue;
        }

        /** @return 低基数标签值，供指标与日志复用 */
        public String tagValue() {
            return tagValue;
        }
    }
}
