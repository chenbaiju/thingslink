package com.things.link.alarm.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** S6-3 外部通知的有界网络、重试与调度参数。 */
@ConfigurationProperties(prefix = "things-link.notification")
public record NotificationDeliveryProperties(
        Duration requestTimeout,
        Duration firstRetryDelay,
        Duration secondRetryDelay,
        int retryBatchSize,
        Duration retryLease,
        Webhook webhook) {

    /** 对缺省值和安全边界做一次集中校验，避免错误配置在运行期静默放大。 */
    public NotificationDeliveryProperties {
        requestTimeout = defaultDuration(requestTimeout, Duration.ofSeconds(10));
        firstRetryDelay = defaultDuration(firstRetryDelay, Duration.ofMinutes(1));
        secondRetryDelay = defaultDuration(secondRetryDelay, Duration.ofMinutes(5));
        retryLease = defaultDuration(retryLease, Duration.ofSeconds(30));
        retryBatchSize = retryBatchSize == 0 ? 50 : retryBatchSize;
        webhook = webhook == null ? new Webhook(null) : webhook;
        if (requestTimeout.isNegative() || requestTimeout.isZero()
                || requestTimeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("通知请求超时必须大于 0 且不超过 30 秒");
        }
        if (firstRetryDelay.isNegative() || firstRetryDelay.isZero()
                || secondRetryDelay.compareTo(firstRetryDelay) < 0) {
            throw new IllegalArgumentException("通知重试间隔必须为正数且第二次不早于第一次");
        }
        if (retryBatchSize < 1 || retryBatchSize > 100) {
            throw new IllegalArgumentException("通知重试批次必须在 1 到 100 之间");
        }
        if (retryLease.compareTo(Duration.ofSeconds(10)) < 0
                || retryLease.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("通知重试租约必须在 10 秒到 5 分钟之间");
        }
    }

    /** Webhook 平台主密钥；按项目派生后签名，不能直接发送或写日志。 */
    public record Webhook(String signingSecret) {
        /** @return 经过安全下限校验的主密钥字节 */
        public byte[] requiredSecret() {
            if (signingSecret == null || signingSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
                throw new IllegalStateException("Webhook 签名主密钥至少需要 32 字节");
            }
            return signingSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** @return 空配置使用设计基线，否则保留显式值 */
    private static Duration defaultDuration(Duration value, Duration fallback) {
        return value == null ? fallback : value;
    }
}
