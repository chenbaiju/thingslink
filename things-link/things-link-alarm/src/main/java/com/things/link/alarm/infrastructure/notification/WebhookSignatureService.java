package com.things.link.alarm.infrastructure.notification;

import com.things.link.alarm.application.NotificationDeliveryProperties;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;

/** 从平台主密钥派生项目密钥并生成 HMAC-SHA256 Webhook 签名。 */
@Component
public class WebhookSignatureService {
    /** HMAC 算法固定为广泛支持的 SHA-256。 */
    private static final String ALGORITHM = "HmacSHA256";
    /** 平台通知配置。 */
    private final NotificationDeliveryProperties properties;

    /** @param properties 含部署级主密钥的通知配置 */
    public WebhookSignatureService(NotificationDeliveryProperties properties) {
        this.properties = properties;
    }

    /**
     * @return {@code v1=<hex>}；签名串固定为 timestamp、nonce、deliveryId 与完整请求体
     */
    public String sign(UUID projectId, long timestamp, String nonce, UUID deliveryId, String body) {
        byte[] projectKey = hmac(
                properties.webhook().requiredSecret(),
                "thingslink:webhook:v1:" + projectId);
        String canonical = timestamp + "\n" + nonce + "\n" + deliveryId + "\n" + body;
        return "v1=" + HexFormat.of().formatHex(hmac(projectKey, canonical));
    }

    /** @return HMAC 原始字节；任何异常都表示部署加密能力不可用，应让投递失败 */
    private static byte[] hmac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("无法初始化 Webhook HMAC", exception);
        }
    }
}
