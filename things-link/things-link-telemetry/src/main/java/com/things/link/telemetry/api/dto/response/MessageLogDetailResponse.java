package com.things.link.telemetry.api.dto.response;

import com.things.link.shared.message.TransportProtocol;
import com.things.link.telemetry.domain.DeviceMessageLog;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 消息详情（接入合同 §7.2 的消息详情字段）。
 *
 * <p>摘要可按 {@code JSON} 或 {@code HEX} 展示：两种形式都来自**已脱敏**的摘要文本，十六进制只是同一份字节的另一种
 * 呈现，不会因为换格式而多出内容。标准结果以「消息类型＋协议＋方向」表达——平台今天不保存独立的规范化投影，
 * 因此不伪造一个"标准化结果"字段：需要的调用方按此三者解释摘要。</p>
 *
 * @param id 日志 ID
 * @param deviceId 设备 ID
 * @param messageId 消息 ID
 * @param protocol 传输协议
 * @param direction 方向
 * @param messageType 消息类型；未知时为空
 * @param payloadSummary 已脱敏摘要（{@code format=JSON} 时）
 * @param payloadHex 已脱敏摘要的十六进制（{@code format=HEX} 时）
 * @param format 本次返回的摘要格式
 * @param normalizedResult 标准化结果摘要（类型／协议／方向），非独立投影
 * @param rawBytes 原始报文字节数
 * @param errorCode 处理错误码；无错误时为空
 * @param truncated 摘要是否被截断
 * @param sampled 是否因采样只留标记
 * @param ts 消息发生时刻
 * @param receivedAt 平台接收时刻
 * @param acceptedAt 受理时刻
 * @param parsedAt 解析时刻；未记录时为空
 * @param processedAt 处理完成时刻
 * @param deliveredAt 投递时刻；上行不适用时为空
 * @param repliedAt 回复时刻；无回复时为空
 * @param traceId 链路标识
 */
public record MessageLogDetailResponse(UUID id, UUID deviceId, UUID messageId, TransportProtocol protocol,
                                       DeviceMessageLog.Direction direction, String messageType,
                                       String payloadSummary, String payloadHex, String format,
                                       String normalizedResult, int rawBytes, String errorCode, boolean truncated,
                                       boolean sampled, Instant ts, Instant receivedAt, Instant acceptedAt,
                                       Instant parsedAt, Instant processedAt, Instant deliveredAt,
                                       Instant repliedAt, String traceId) {

    /** 摘要格式：JSON 文本。 */
    public static final String FORMAT_JSON = "JSON";

    /** 摘要格式：十六进制字节。 */
    public static final String FORMAT_HEX = "HEX";

    /**
     * 由日志事实构造详情。
     *
     * @param log 已脱敏的日志事实
     * @param format 摘要格式；非 {@code HEX} 时按 JSON 处理
     * @return 详情响应
     */
    public static MessageLogDetailResponse from(DeviceMessageLog log, String format) {
        boolean hex = FORMAT_HEX.equalsIgnoreCase(format);
        String summary = log.payloadSummary();
        return new MessageLogDetailResponse(log.id(), log.deviceId(), log.messageId(), log.protocol(),
                log.direction(), log.messageType(), hex ? null : summary,
                hex && summary != null
                        ? HexFormat.of().formatHex(summary.getBytes(StandardCharsets.UTF_8)) : null,
                hex ? FORMAT_HEX : FORMAT_JSON,
                log.messageType() == null ? null
                        : log.messageType() + "/" + log.protocol().name() + "/" + log.direction().name(),
                log.rawBytes(), log.errorCode(), log.truncated(), log.sampled(), log.ts(), log.receivedAt(),
                log.acceptedAt(), log.parsedAt(), log.processedAt(), log.deliveredAt(), log.repliedAt(),
                log.traceId());
    }
}
