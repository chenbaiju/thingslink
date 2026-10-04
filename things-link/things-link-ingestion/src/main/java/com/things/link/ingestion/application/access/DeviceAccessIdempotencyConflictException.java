package com.things.link.ingestion.application.access;

import java.util.UUID;

/**
 * 表示同一个 {@code messageId} 被换上了不同载荷。
 *
 * <p>接入合同 §4.4 冻结：同键同摘要返回首次结果，同键异摘要必须拒绝且保持首次事实不变。这不是临时故障，
 * 重放相同请求永远不会成功，因此与 {@code InvalidUplinkMessageException} 一样属于不可重试错误；协议层据此
 * 映射 {@code IDEMPOTENCY_CONFLICT}（HTTP 409／CoAP 4.09／TCP ERROR）。错误消息只带消息标识，不带载荷。</p>
 */
public class DeviceAccessIdempotencyConflictException extends RuntimeException {

    /** 发生冲突的设备消息标识。 */
    private final UUID messageId;

    /**
     * 创建同键异载荷冲突异常。
     *
     * @param messageId 发生冲突的设备消息标识
     */
    public DeviceAccessIdempotencyConflictException(UUID messageId) {
        super("同一 messageId 的载荷与首次受理不一致: " + messageId);
        this.messageId = messageId;
    }

    /**
     * 返回冲突涉及的消息标识。
     *
     * @return 设备消息标识
     */
    public UUID messageId() {
        return messageId;
    }
}
