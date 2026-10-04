package com.things.link.alarm.application;

/** 渠道适配器的脱敏失败分类；异常文本不得写入数据库或指标标签。 */
public class NotificationSendException extends RuntimeException {
    /** 有界错误分类。 */
    private final Reason reason;
    /** 是否允许按 delivery 快照继续重试。 */
    private final boolean retryable;

    /** 创建一个不携带目标地址或响应正文的渠道失败。 */
    public NotificationSendException(Reason reason, boolean retryable, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
        this.retryable = retryable;
    }

    /** @return 固定失败原因 */
    public Reason reason() {
        return reason;
    }

    /** @return 是否可重试 */
    public boolean retryable() {
        return retryable;
    }

    /** 指标、数据库和告警规则共同使用的低基数原因。 */
    public enum Reason {
        /** 生产未装配 SMTP，开发日志实现不能冒充送达。 */
        SMTP_UNCONFIGURED,
        /** SMTP 连接、认证或发送失败。 */
        SMTP_FAILURE,
        /** Webhook 服务明确或本地读取超时。 */
        WEBHOOK_TIMEOUT,
        /** Webhook 服务返回 429 限流。 */
        WEBHOOK_RATE_LIMITED,
        /** Webhook 服务返回可恢复的 5xx。 */
        WEBHOOK_SERVER_ERROR,
        /** Webhook 服务返回不可恢复的普通 4xx。 */
        WEBHOOK_CLIENT_ERROR,
        /** DNS、连接或 TLS 等网络错误。 */
        WEBHOOK_NETWORK_ERROR,
        /** 当前运行环境没有真实 PUSH provider，确定性桩也不得冒充真实送达。 */
        PUSH_PROVIDER_UNAVAILABLE,
        /** MOCK provider 返回可恢复失败，用于证明安装实例级有限重试。 */
        PUSH_PROVIDER_TRANSIENT,
        /** MOCK provider 永久拒绝当前目标，用于证明安装实例级死信隔离。 */
        PUSH_PROVIDER_REJECTED,
        /** 投递快照或渠道装配不满足冻结契约。 */
        INVALID_DELIVERY
    }
}
