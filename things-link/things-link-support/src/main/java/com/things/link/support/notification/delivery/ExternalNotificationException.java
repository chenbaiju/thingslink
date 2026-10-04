package com.things.link.support.notification.delivery;

/** 共享渠道适配器的低敏失败；业务域负责决定剩余次数与最终死信。 */
public class ExternalNotificationException extends RuntimeException {

    /** 固定低基数失败原因。 */
    private final Reason reason;

    /** 是否可在业务域剩余次数内重试。 */
    private final boolean retryable;

    /**
     * @param reason 固定失败原因
     * @param retryable 是否可恢复
     * @param cause 底层异常；消息不得被持久化或记录为标签
     */
    public ExternalNotificationException(Reason reason, boolean retryable, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
        this.retryable = retryable;
    }

    /** @return 固定失败原因 */
    public Reason reason() {
        return reason;
    }

    /** @return 是否可恢复 */
    public boolean retryable() {
        return retryable;
    }

    /** S6 与 S9 共用的有限失败分类。 */
    public enum Reason {
        /** 冻结请求缺少渠道必需字段。 */
        INVALID_DELIVERY,
        /** 开发日志邮件发送器不能冒充真实送达。 */
        SMTP_UNCONFIGURED,
        /** SMTP 连接、认证或发送失败。 */
        SMTP_FAILURE,
        /** Webhook 本地或远端请求超时。 */
        WEBHOOK_TIMEOUT,
        /** Webhook 返回 429。 */
        WEBHOOK_RATE_LIMITED,
        /** Webhook 返回可恢复 5xx。 */
        WEBHOOK_SERVER_ERROR,
        /** Webhook 返回不可恢复普通 4xx。 */
        WEBHOOK_CLIENT_ERROR,
        /** Webhook DNS、连接或 TLS 故障。 */
        WEBHOOK_NETWORK_ERROR
    }
}
