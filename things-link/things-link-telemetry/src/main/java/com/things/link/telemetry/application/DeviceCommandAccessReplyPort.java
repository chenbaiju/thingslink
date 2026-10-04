package com.things.link.telemetry.application;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 协议无关的设备业务回复入口（接入合同 §5.4）。
 *
 * <p>MQTT 用 Topic 推导 commandId，新协议由设备在回复体中给出 commandId；两者最终都落到同一份命令事实与
 * 同一套终态。本端口只做三件事：确认命令属于该设备、把回复交给既有状态机、把「已应用／重放／同键异结果／
 * 找不到命令」如实分类，协议层据此映射 200／409／404（CoAP 与 TCP 同义）。</p>
 */
public interface DeviceCommandAccessReplyPort {

    /** 设备业务回复的状态；与接入合同 §3.1 冻结取值一致。 */
    enum Status {
        /** 已受理的中间态，命令不进入终态。 */
        ACK,
        /** 设备执行成功，命令进入 SUCCEEDED。 */
        SUCCESS,
        /** 设备执行失败，命令进入 FAILED。 */
        FAILED
    }

    /** 一次业务回复的处理结果。 */
    enum Outcome {
        /** 回复已改变命令事实。 */
        APPLIED,
        /** 同一 messageId 的迟到重放：首次结果不变。 */
        DUPLICATE,
        /** 同一 messageId 换了结果：必须拒绝，首次结果不变。 */
        CONFLICT,
        /** commandId 不存在或不属于该设备：不写入任何命令事实。 */
        NOT_FOUND
    }

    /**
     * 一次业务回复。
     *
     * @param tenantId 认证时权威租户
     * @param projectId 认证时权威项目，同时是 RLS 范围
     * @param deviceId 认证时权威设备，必须等于命令的目标与连接设备
     * @param commandId 被回复的命令 ID
     * @param messageId 设备为本次回复生成的 UUIDv7，用于至少一次去重
     * @param status 回复状态
     * @param output 可选输出对象，仅 SUCCESS 参与输出 Schema 校验
     * @param errorCode 可选失败码，长度不超过 64
     * @param message 可选失败说明，长度不超过 500
     * @param occurredAt 设备侧发生时刻
     * @param receivedAt 接入面收到本次回复的平台时刻
     * @param traceId 本次回复的链路标识
     */
    record Reply(UUID tenantId, UUID projectId, UUID deviceId, UUID commandId, UUID messageId, Status status,
                 Map<String, Object> output, String errorCode, String message, Instant occurredAt,
                 Instant receivedAt, String traceId) {

        /** 冻结回复的必填字段与长度边界。 */
        public Reply {
            if (tenantId == null || projectId == null || deviceId == null || commandId == null
                    || messageId == null || status == null || occurredAt == null || receivedAt == null
                    || traceId == null || traceId.isBlank()) {
                throw new IllegalArgumentException("业务回复的归属、标识、状态与链路不能为空");
            }
            if (messageId.version() != 7 || commandId.version() != 7) {
                throw new IllegalArgumentException("commandId 与 messageId 必须是 UUIDv7");
            }
            if (errorCode != null && errorCode.length() > 64) {
                throw new IllegalArgumentException("错误码长度不能超过 64");
            }
            if (message != null && message.length() > 500) {
                throw new IllegalArgumentException("错误说明长度不能超过 500");
            }
        }
    }

    /**
     * 应用一次业务回复。
     *
     * @param reply 已认证设备提交的回复
     * @return 处理结果；调用方不得把 NOT_FOUND 当成重放
     */
    Outcome apply(Reply reply);
}
