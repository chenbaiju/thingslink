package com.things.link.rule.application;

/** 渠道发送器的低敏失败分类；异常正文不得携带目标地址、正文或远端响应体。 */
public class RuleNotificationSendException extends RuntimeException {

    /** 固定低基数失败原因。 */
    private final Reason reason;

    /** 是否允许状态机在剩余次数内重试。 */
    private final boolean retryable;

    /**
     * 创建只暴露固定原因名的异常。
     *
     * @param reason 固定低基数失败原因
     * @param retryable 是否可恢复
     * @param cause 底层异常，仅保留堆栈，不使用可能含敏感数据的消息
     */
    public RuleNotificationSendException(Reason reason, boolean retryable, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
        this.retryable = retryable;
    }

    /** @return 固定失败原因 */
    public Reason reason() {
        return reason;
    }

    /** @return 是否可在剩余次数内重试 */
    public boolean retryable() {
        return retryable;
    }

    /** 数据库、指标与日志共同使用的有限原因集合。 */
    public enum Reason {
        /** 规则动作声明了未支持渠道。 */
        INVALID_CHANNEL,
        /** 收件目标、主题或正文不满足渠道冻结契约。 */
        INVALID_DELIVERY,
        /** 开发日志邮件发送器不能冒充真实送达。 */
        SMTP_UNCONFIGURED,
        /** SMTP 连接、认证或发送失败。 */
        SMTP_FAILURE,
        /** Webhook 请求达到本地超时上限。 */
        WEBHOOK_TIMEOUT,
        /** Webhook 服务返回 429。 */
        WEBHOOK_RATE_LIMITED,
        /** Webhook 服务返回可恢复 5xx。 */
        WEBHOOK_SERVER_ERROR,
        /** Webhook 服务返回不可恢复普通 4xx。 */
        WEBHOOK_CLIENT_ERROR,
        /** Webhook DNS、连接或 TLS 失败。 */
        WEBHOOK_NETWORK_ERROR,
        /** 未预期运行时错误；按有限重试处理但不泄露异常正文。 */
        INTERNAL_ERROR
    }
}
