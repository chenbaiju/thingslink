package com.things.link.ingestion.application.access;

import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import com.things.link.telemetry.application.DeviceCommandAccessReplyPort;
import com.things.link.telemetry.application.DeviceCommandClaimPort;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 设备面三协议的**共享报文形状与校验**（接入合同 §3.1／§5.2）。
 *
 * <p>HTTP、TCP、CoAP 三个平面必须产出逐字段相同的响应体、并按同一套规则校验入参；把这段逻辑留在各自的控制器里，
 * 三个协议就会各自漂移（例如某个平面漏掉 {@code attempt} 或把 {@code input} 序列化成字符串）。因此这里只放
 * "形状与校验"，不放传输：各平面仍自己决定状态码／响应码与错误映射。</p>
 */
public final class DeviceAccessAccessPayloads {

    /** 领取请求的两个可选字段；越界值由领取用例夹到冻结区间。 */
    public record ClaimRequest(Integer limit, Long leaseSeconds) {
    }

    /**
     * 业务回复报文；字段集与 §3.1 一致，{@code commandId} 为必填。
     *
     * @param commandId 被回复的命令 ID
     * @param messageId 设备为本次回复生成的消息 ID
     * @param occurredAt 设备侧发生时刻
     * @param status 回复状态
     * @param output 可选输出对象
     * @param errorCode 可选失败码
     * @param message 可选失败说明
     */
    public record ReplyRequest(UUID commandId, UUID messageId, Instant occurredAt,
                               DeviceCommandAccessReplyPort.Status status, Map<String, Object> output,
                               String errorCode, String message) {
    }

    private DeviceAccessAccessPayloads() {
    }

    /**
     * 解析领取请求；空体表示全部取默认档。
     *
     * @param body 请求体；空数组表示空体
     * @param objectMapper 统一 JSON 映射器
     * @return 领取请求
     * @throws IllegalArgumentException 字段越界或 JSON 非法
     */
    public static ClaimRequest parseClaim(byte[] body, ObjectMapper objectMapper) {
        if (body == null || body.length == 0) {
            return new ClaimRequest(null, null);
        }
        ClaimRequest parsed = objectMapper.readValue(body, ClaimRequest.class);
        if (parsed.limit() != null && parsed.limit() < 1) {
            throw new IllegalArgumentException("领取条数必须为正数");
        }
        if (parsed.leaseSeconds() != null && parsed.leaseSeconds() < 1) {
            throw new IllegalArgumentException("租约必须为正数");
        }
        return parsed;
    }

    /**
     * 属性上报受理体（HTTP 202／CoAP 2.04 共用同一字段集）。
     *
     * @param messageId 消息 ID
     * @param receivedAt 受理时刻
     * @return 响应体
     */
    public static Map<String, Object> acceptedBody(UUID messageId, Instant receivedAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageId", messageId.toString());
        payload.put("receivedAt", receivedAt.toString());
        payload.put("status", "ACCEPTED");
        return payload;
    }

    /**
     * 命令领取体；{@code attempt} 只是诊断序号，不是幂等键（§5.2）。
     *
     * @param claimed 本次领取到的命令
     * @param lease 本次租约时长
     * @return 响应体
     */
    public static Map<String, Object> claimBody(List<DeviceCommandClaimPort.Claimed> claimed, Duration lease) {
        List<Map<String, Object>> commands = new ArrayList<>(claimed.size());
        for (DeviceCommandClaimPort.Claimed command : claimed) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("commandId", command.commandId().toString());
            entry.put("commandKey", command.commandKey());
            entry.put("input", command.input());
            entry.put("attempt", command.attempt());
            entry.put("leaseExpiresAt", command.leaseExpiresAt() == null ? null
                    : command.leaseExpiresAt().toString());
            commands.add(entry);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("commands", commands);
        payload.put("pollAfterMillis", lease.toMillis());
        return payload;
    }

    /**
     * 业务回复受理体；受理只代表平台已接管提交，终态由命令事实追踪。
     *
     * @param commandId 被回复的命令 ID
     * @param messageId 回复消息 ID
     * @param receivedAt 受理时刻
     * @return 响应体
     */
    public static Map<String, Object> replyAcceptedBody(UUID commandId, UUID messageId, Instant receivedAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageId", messageId.toString());
        payload.put("receivedAt", receivedAt.toString());
        payload.put("status", "ACCEPTED");
        payload.put("commandId", commandId.toString());
        return payload;
    }

    /**
     * 把解析出的回复报文装配成统一入口的 {@link DeviceCommandAccessReplyPort.Reply}。
     *
     * <p>归属一律取认证身份，绝不取报文自报字段；标识形状、长度与状态取值都在统一入口再校验一次。</p>
     *
     * @param identity 已认证设备身份
     * @param parsed 解析出的回复报文
     * @param receivedAt 平台受理时刻
     * @param traceId 链路标识
     * @return 统一入口的回复
     * @throws IllegalArgumentException 契约字段非法
     */
    public static DeviceCommandAccessReplyPort.Reply createReply(AuthenticatedDeviceIdentity identity,
                                                                 ReplyRequest parsed, Instant receivedAt,
                                                                 String traceId) {
        return new DeviceCommandAccessReplyPort.Reply(identity.tenantId(), identity.projectId(),
                identity.deviceId(), parsed.commandId(), parsed.messageId(), parsed.status(), parsed.output(),
                parsed.errorCode(), parsed.message(), parsed.occurredAt(), receivedAt, traceId);
    }
}
