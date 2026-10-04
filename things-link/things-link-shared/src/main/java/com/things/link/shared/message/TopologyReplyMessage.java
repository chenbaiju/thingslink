package com.things.link.shared.message;

import java.time.Instant;
import java.util.UUID;

/**
 * 平台对网关拓扑上报（{@code up/topo/add|delete}、{@code up/sub/register}）的处理结果，
 * 发往 {@code tc.device.topo.reply}，由 ingestion 编码为 {@code down/topo/reply} 回给网关。
 *
 * <p>这是设备侧契约：网关 SDK 用 {@code requestId} 关联原始上报，按 {@code status} 决定重试或告警。
 * {@code errorCode}/{@code message} 仅在 {@link Status#FAILED} 时携带，且只允许代码冻结的稳定值。
 * 归属字段（gateway/projectKey/gatewayKey）由 device 模块从可信事实解析，ingestion 不自行推导。</p>
 *
 * @param requestId 原始拓扑上报消息的 messageId，用于网关侧关联
 * @param tenantId 网关所属租户
 * @param projectId 网关所属项目
 * @param gatewayId 回执目标网关设备 ID，同时是 Kafka 分区键
 * @param projectKey MQTT 项目键
 * @param gatewayKey MQTT 网关设备键
 * @param subDeviceKey 被处理的子设备标识
 * @param status 处理结果
 * @param errorCode 失败原因稳定码，仅 FAILED 时非空
 * @param message 失败诊断摘要，仅 FAILED 时非空
 * @param repliedAt 平台产生回执的时刻
 * @param traceId 继承原始上报的链路追踪标识
 */
public record TopologyReplyMessage(UUID requestId, UUID tenantId, UUID projectId, UUID gatewayId,
                                   String projectKey, String gatewayKey, String subDeviceKey,
                                   Status status, String errorCode, String message,
                                   Instant repliedAt, String traceId) {

    /** Outbox 事件类型，由发布器映射到 {@code tc.device.topo.reply}。 */
    public static final String EVENT_TYPE = "DEVICE_TOPOLOGY_REPLY";

    /** 处理结果。 */
    public enum Status { /** 拓扑操作已生效（含幂等确认）。 */ SUCCESS, /** 拓扑操作被拒绝。 */ FAILED }

    /**
     * 冻结回执信封的必填字段与不可变性。
     */
    public TopologyReplyMessage {
        if (requestId == null || tenantId == null || projectId == null || gatewayId == null) {
            throw new IllegalArgumentException("拓扑回执归属不能为空");
        }
        if (projectKey == null || projectKey.isBlank() || gatewayKey == null || gatewayKey.isBlank()
                || subDeviceKey == null || subDeviceKey.isBlank() || status == null) {
            throw new IllegalArgumentException("拓扑回执路由与结果不完整");
        }
        if (repliedAt == null || traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("拓扑回执时间与 traceId 不能为空");
        }
        boolean failed = status == Status.FAILED;
        if (failed && (errorCode == null || errorCode.isBlank())) {
            throw new IllegalArgumentException("失败回执必须携带 errorCode");
        }
        if (!failed && (errorCode != null || message != null)) {
            throw new IllegalArgumentException("成功回执不得携带 errorCode 或 message");
        }
    }
}
