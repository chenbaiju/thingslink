package com.things.link.shared.message;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 网关批量属性上报的平台信封，发往 {@code tc.device.batch}。
 *
 * <p>由 ingestion 从 {@code up/batch/report} 解析出来：租户、项目与网关身份取自已认证的 Topic/连接，
 * 禁止从 payload 接受（ADR 0031 信任边界）。逐条目已按「原始顺序 + 原始条目数」完成字节均分
 * （见 {@link #frameBytes} 与 {@link Entry} 的 {@code rawBytes}），使重投时各条目字节归属确定、
 * 不受其他条目被拒绝的影响，也避免把非法条目流量成本转嫁给合法子设备。</p>
 *
 * <p>结构层只可能产生 {@link RejectReason#INVALID_MESSAGE_ID} 与 {@link RejectReason#DUPLICATE_MESSAGE_ID}
 * 两类拒绝；归属复核（{@code SUB_DEVICE_*}）由 device 应用端口在消费本信封时判定，不在结构层出现。</p>
 *
 * @param tenantId 网关所属租户
 * @param projectId 网关所属项目
 * @param gatewayId 已认证的发布网关设备 ID，同时是 Kafka 分区键
 * @param frameBytes 原始 MQTT payload 字节数，字节均分的总基数
 * @param receivedAt 平台接收消息的 UTC 时刻
 * @param traceId 接入层生成的链路追踪标识
 * @param entries 按原始顺序排列的子设备条目（合法或结构拒绝），非空
 */
public record GatewayBatchMessage(UUID tenantId, UUID projectId, UUID gatewayId, int frameBytes,
                                  Instant receivedAt, String traceId, List<Entry> entries) {

    /**
     * 批量帧内的一个子设备条目：合法上报，或结构层拒绝（携带其字节份额供 {@code rejected_bytes} 计量）。
     */
    @JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
    @JsonSubTypes({
            @JsonSubTypes.Type(Valid.class),
            @JsonSubTypes.Type(Rejected.class)
    })
    public sealed interface Entry permits Valid, Rejected {
    }

    /**
     * 结构完整、交由 device 端口复核归属的子设备上报。
     *
     * @param report 子设备上报条目
     * @param rawBytes 按原始顺序均分得到的字节份额
     */
    public record Valid(SubDeviceReport report, int rawBytes) implements Entry {
    }

    /**
     * 结构层拒绝的条目，不进入 normalized，其字节份额计入 {@code rejected_bytes}。
     *
     * @param reason 固定拒绝原因（仅 {@code INVALID_MESSAGE_ID} / {@code DUPLICATE_MESSAGE_ID}）
     * @param rawBytes 按原始顺序均分得到的字节份额
     */
    public record Rejected(RejectReason reason, int rawBytes) implements Entry {
    }

    /**
     * 批量条目拒绝的固定原因，供指标标签复用；只允许代码冻结的值，防止标签失控。
     */
    public enum RejectReason {
        /** 条目 messageId 缺失或不是 UUIDv7。 */
        INVALID_MESSAGE_ID("invalid_message_id"),
        /** 条目 messageId 与本帧更早的条目重复，保留首项。 */
        DUPLICATE_MESSAGE_ID("duplicate_message_id"),
        /** 子设备不存在。 */
        SUB_DEVICE_NOT_FOUND("sub_device_not_found"),
        /** 子设备存在但不是 SUB_DEVICE 类型。 */
        SUB_DEVICE_TYPE_MISMATCH("sub_device_type_mismatch"),
        /** 子设备存在但没有有效拓扑绑定。 */
        SUB_DEVICE_NOT_BOUND("sub_device_not_bound"),
        /** 子设备的有效绑定指向其他网关。 */
        SUB_DEVICE_GATEWAY_MISMATCH("sub_device_gateway_mismatch");

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

    /**
     * 冻结批量信封的必填字段与不可变性。
     */
    public GatewayBatchMessage {
        if (tenantId == null || projectId == null || gatewayId == null) {
            throw new IllegalArgumentException("批量消息归属不能为空");
        }
        if (frameBytes < 0) {
            throw new IllegalArgumentException("原始 payload 字节数不能为负数");
        }
        if (receivedAt == null || traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("接收时间与 traceId 不能为空");
        }
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("批量消息条目不能为空");
        }
        entries = List.copyOf(entries);
    }
}
