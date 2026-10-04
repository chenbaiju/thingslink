package com.things.link.support.notification.delivery;

import com.things.link.shared.id.Uuid7;
import com.things.link.shared.net.SafeOutboundHost;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** 告警与规则共用的带项目隔离 HMAC、时间戳、nonce 与 deliveryId 的 HTTPS Webhook 发送器。 */
@Component(ExternalNotificationSender.WEBHOOK_BEAN)
public class ExternalWebhookNotificationSender implements ExternalNotificationSender {

    /** 接收端外部效果幂等键。 */
    public static final String DELIVERY_ID_HEADER = "X-ThingsLink-Delivery-Id";

    /** 接收端用于限制签名重放窗口的 UTC 秒。 */
    public static final String TIMESTAMP_HEADER = "X-ThingsLink-Timestamp";

    /** 每次 HTTP 尝试唯一随机数。 */
    public static final String NONCE_HEADER = "X-ThingsLink-Nonce";

    /** 项目派生 HMAC-SHA256 签名。 */
    public static final String SIGNATURE_HEADER = "X-ThingsLink-Signature";

    /** HMAC 算法固定为广泛支持的 SHA-256。 */
    private static final String ALGORITHM = "HmacSHA256";

    /**
     * Webhook 响应正文的硬读取上限。
     *
     * <p>D-052 要求不可信接收端不能用无界正文占用带宽与堆。业务从不消费响应正文，因此只为小响应连接复用
     * 有界排空 64 KiB；到达上限即关闭流，且不允许部署配置把保护放大为无限。</p>
     */
    static final int MAX_RESPONSE_BODY_BYTES = 64 * 1024;

    /** 有界丢弃使用固定缓冲区，避免按上限额外分配整块响应数组。 */
    private static final int RESPONSE_DISCARD_BUFFER_BYTES = 8 * 1024;

    /** 首次真实投递后缓存的无默认鉴权 HTTP 客户端；构造 Bean 时不得占用 selector 等宿主资源。 */
    private volatile RestClient restClient;

    /** HTTP 客户端工厂；初始化失败时不缓存，使独立通知重试可以再次创建。 */
    private final Supplier<RestClient> restClientFactory;

    /** 统一 JSON 序列化器。 */
    private final ObjectMapper objectMapper;

    /** 部署级主密钥，只在内存中派生项目密钥。 */
    private final byte[] signingSecret;

    /**
     * 创建带连接与读取上限的生产客户端。
     *
     * @param objectMapper 统一 JSON 序列化器
     * @param requestTimeout S6 冻结的单次请求上限
     * @param signingSecret 部署密钥系统注入的主密钥
     */
    @Autowired
    public ExternalWebhookNotificationSender(
            ObjectMapper objectMapper,
            @Value("${things-link.notification.request-timeout:10s}") Duration requestTimeout,
            @Value("${things-link.notification.webhook.signing-secret:}") String signingSecret) {
        this(null, () -> createRestClient(requestTimeout), objectMapper, signingSecret);
    }

    /**
     * 测试入口，允许确定性验证请求头与 HTTP 分类。
     *
     * @param restClient HTTP 客户端替身
     * @param objectMapper JSON 序列化器
     * @param signingSecret 测试主密钥
     */
    public ExternalWebhookNotificationSender(
            RestClient restClient,
            ObjectMapper objectMapper,
            String signingSecret) {
        this(restClient, null, objectMapper, signingSecret);
    }

    /**
     * 负向测试入口，证明客户端资源初始化失败不会阻断 Bean 构造，且后续尝试仍可重新初始化。
     *
     * @param restClientFactory 可重复调用的 HTTP 客户端工厂
     * @param objectMapper JSON 序列化器
     * @param signingSecret 测试主密钥
     */
    ExternalWebhookNotificationSender(
            Supplier<RestClient> restClientFactory,
            ObjectMapper objectMapper,
            String signingSecret) {
        this(null, restClientFactory, objectMapper, signingSecret);
    }

    /** 初始化生产与测试入口共享的不可变依赖。 */
    private ExternalWebhookNotificationSender(
            RestClient restClient,
            Supplier<RestClient> restClientFactory,
            ObjectMapper objectMapper,
            String signingSecret) {
        this.restClient = restClient;
        this.restClientFactory = restClientFactory;
        this.objectMapper = objectMapper;
        this.signingSecret = bytes(signingSecret);
    }

    /** {@inheritDoc} */
    @Override
    public String channel() {
        return "WEBHOOK";
    }

    /** {@inheritDoc} */
    @Override
    public String send(ExternalNotificationRequest request) {
        URI target = validate(request);
        long timestamp = Instant.now().getEpochSecond();
        String nonce = Uuid7.generate().toString();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("deliveryId", request.deliveryId());
        payload.putAll(request.webhookPayload());
        String body = objectMapper.writeValueAsString(payload);
        String signature = sign(request.projectId(), timestamp, nonce, request.deliveryId(), body);
        try {
            int status = client().post()
                    .uri(target)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(DELIVERY_ID_HEADER, request.deliveryId().toString())
                    .header(TIMESTAMP_HEADER, Long.toString(timestamp))
                    .header(NONCE_HEADER, nonce)
                    .header(SIGNATURE_HEADER, signature)
                    .body(body)
                    .exchange((_httpRequest, response) -> {
                        discardResponseBody(response.getBody());
                        return response.getStatusCode().value();
                    });
            classifyStatus(status);
            return "webhook:" + request.deliveryId();
        } catch (ResourceAccessException exception) {
            throw failure(ExternalNotificationException.Reason.WEBHOOK_NETWORK_ERROR, true, exception);
        } catch (UncheckedIOException exception) {
            // JDK HttpClient 创建 selector 时也可能遇到瞬时宿主网络资源故障；它与投递网络错误共享有限重试边界。
            throw failure(ExternalNotificationException.Reason.WEBHOOK_NETWORK_ERROR, true, exception);
        }
    }

    /**
     * 最多读取 64 KiB 后返回；RestClient 随后关闭响应，超限接收端不能继续推送正文。
     *
     * <p>响应正文不参与成功或失败语义，所以达到上限不是新的投递结果。若读取阶段本身发生 I/O 故障，
     * RestClient 按现有网络错误路径包装，仍由业务域的有限重试预算处理。</p>
     *
     * @param body 不可信远端响应流
     * @throws IOException 读取响应失败
     */
    private static void discardResponseBody(InputStream body) throws IOException {
        byte[] buffer = new byte[RESPONSE_DISCARD_BUFFER_BYTES];
        int remaining = MAX_RESPONSE_BODY_BYTES;
        while (remaining > 0) {
            int read = body.read(buffer, 0, Math.min(buffer.length, remaining));
            if (read <= 0) {
                return;
            }
            remaining -= read;
        }
    }

    /** 复用 S6/S9 已冻结的 HTTP 状态分类，不让响应体上限制造第二套错误语义。 */
    private static void classifyStatus(int status) {
        if (status < 400) {
            return;
        }
        if (status == 408) {
            throw failure(ExternalNotificationException.Reason.WEBHOOK_TIMEOUT, true, null);
        }
        if (status == 429) {
            throw failure(ExternalNotificationException.Reason.WEBHOOK_RATE_LIMITED, true, null);
        }
        if (status >= 500) {
            throw failure(ExternalNotificationException.Reason.WEBHOOK_SERVER_ERROR, true, null);
        }
        throw failure(ExternalNotificationException.Reason.WEBHOOK_CLIENT_ERROR, false, null);
    }

    /**
     * 首次投递时才创建客户端；失败不写入字段，通知状态机下一次尝试可以重新初始化。
     *
     * @return 已成功初始化并可复用的客户端
     */
    private RestClient client() {
        RestClient current = restClient;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            current = restClient;
            if (current == null) {
                current = restClientFactory.get();
                restClient = current;
            }
            return current;
        }
    }

    /** @return 带连接与读取上限、无默认鉴权信息的生产 HTTP 客户端 */
    private static RestClient createRestClient(Duration requestTimeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(requestTimeout).build());
        factory.setReadTimeout(requestTimeout);
        return RestClient.builder().requestFactory(factory).build();
    }

    /** 只接受完整 HTTPS 目标、至少 32 字节主密钥，且目标不命中保留/内网段。 */
    private URI validate(ExternalNotificationRequest request) {
        try {
            if (request == null || request.deliveryId() == null || request.projectId() == null
                    || signingSecret.length < 32) {
                throw new IllegalArgumentException("incomplete request");
            }
            URI target = URI.create(request.target());
            SafeOutboundHost.requirePublicTarget(target);
            return target;
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw failure(ExternalNotificationException.Reason.INVALID_DELIVERY, false, exception);
        } catch (UnknownHostException exception) {
            throw failure(ExternalNotificationException.Reason.WEBHOOK_NETWORK_ERROR, true, exception);
        }
    }

    /** @return {@code v1=<hex>} 项目隔离签名 */
    private String sign(UUID projectId, long timestamp, String nonce, UUID deliveryId, String body) {
        byte[] projectKey = hmac(signingSecret, "thingslink:webhook:v1:" + projectId);
        return WebhookSignatures.sign(projectKey, timestamp, nonce, deliveryId, body.getBytes(StandardCharsets.UTF_8));
    }

    /** @return HMAC 原始字节 */
    private static byte[] hmac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("无法初始化 Webhook HMAC", exception);
        }
    }

    /** @return UTF-8 密钥字节 */
    private static byte[] bytes(String value) {
        return value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
    }

    /** @return 不携带目标、正文或响应正文的固定失败 */
    private static ExternalNotificationException failure(
            ExternalNotificationException.Reason reason,
            boolean retryable,
            Throwable cause) {
        return new ExternalNotificationException(reason, retryable, cause);
    }
}
