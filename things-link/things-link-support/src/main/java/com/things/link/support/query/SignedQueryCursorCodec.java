package com.things.link.support.query;

import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.DateTimeException;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 数据运行合同§3.3/3.5的身份及过滤条件绑定游标，不接受客户端自行声明的授权范围。
 * 调用方先验证真实身份及规范化过滤，再提供确定的binding；本组件只验证完整性，不授予资源访问权。
 * 仅在既有JWT配置存在的HTTP宿主装配；独立support上下文不因此新增认证配置前置。
 * 实际数据接口仍强依赖本组件，缺配置不能降为无签名游标。
 */
@Component
@ConditionalOnProperty(name = "things-link.security.jwt.secret")
public final class SignedQueryCursorCodec {
    /** opaque游标不进入URL凭据字段，长度按冻结合同限制为2048个ASCII字符。 */
    private static final int MAX_CURSOR_LENGTH = 2048;
    /** 固定JSON实现避免业务ObjectMapper的命名策略改变游标协议。 */
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 封闭信封不容许未验字段承载第二组身份。 */
    private static final Set<String> FIELDS = Set.of("v", "purpose", "binding", "time", "id");
    /** URL安全编码不补等号；解码后重编码检查规范表示。 */
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    /** URL安全解码与签名原文字节成对。 */
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    /** 从既有服务端签名配置独立域派生，既不复用JWT令牌也不在payload暴露密钥。 */
    private final byte[] cursorKey;

    /** 复用既有JWT服务端配置的部署生命周期；密钥轮换使旧游标按10001失效，不新建恢复身份。 */
    public SignedQueryCursorCodec(@Value("${things-link.security.jwt.secret}") String secret) {
        Objects.requireNonNull(secret, "secret");
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("查询游标签名配置必须满足既有JWT密钥长度要求");
        }
        cursorKey = hmac(secret.getBytes(StandardCharsets.UTF_8),
                "things-link/query-cursor/v1".getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 对最后一条已返回事实的排序锚点签名，不保存任何数据库分页快照或客户端令牌。
     * @param purpose 固定服务端用途，如APP_DEVICE_CATALOG
     * @param binding 已认证身份和规范化过滤的无歧义表示，禁止传JWT
     * @param sortTime 已返回事实的权威排序时间
     * @param sortId 已返回事实的排序ID
     * @return 不含身份原文的签名游标
     */
    public String encode(String purpose, String binding, Instant sortTime, UUID sortId) {
        requireContext(purpose, binding);
        Objects.requireNonNull(sortTime, "sortTime");
        Objects.requireNonNull(sortId, "sortId");
        byte[] payload = JSON.writeValueAsBytes(JSON.createObjectNode().put("v", 1).put("purpose", purpose)
                .put("binding", fingerprint(binding)).put("time", sortTime.toString()).put("id", sortId.toString()));
        String encoded = ENCODER.encodeToString(payload);
        String result = encoded + "." + ENCODER.encodeToString(hmac(cursorKey, encoded.getBytes(StandardCharsets.US_ASCII)));
        if (result.length() > MAX_CURSOR_LENGTH) throw new IllegalStateException("服务端游标超过冻结大小限制");
        return result;
    }

    /**
     * 在可信当前身份/过滤下验证游标；null代表首页，空串、篡改或换身份均拒绝，不能默默回首页。
     * @param cursor 客户端游标或首页null
     * @param purpose 固定服务端用途
     * @param binding 当前已认证身份和规范化过滤
     * @return 已验证的keyset锚点，首页为空
     */
    public Optional<Anchor> decode(String cursor, String purpose, String binding) {
        requireContext(purpose, binding);
        if (cursor == null) return Optional.empty();
        if (cursor.isEmpty() || cursor.length() > MAX_CURSOR_LENGTH
                || !cursor.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")) throw invalid();
        try {
            String[] parts = cursor.split("\\.", -1);
            byte[] payload = DECODER.decode(parts[0]);
            byte[] signature = DECODER.decode(parts[1]);
            if (!ENCODER.encodeToString(payload).equals(parts[0])
                    || !ENCODER.encodeToString(signature).equals(parts[1])
                    || !MessageDigest.isEqual(signature, hmac(cursorKey, parts[0].getBytes(StandardCharsets.US_ASCII)))) {
                throw invalid();
            }
            JsonNode object = JSON.readTree(payload);
            if (object == null || !object.isObject() || !Set.copyOf(object.propertyNames()).equals(FIELDS)
                    || !object.path("v").isIntegralNumber() || object.path("v").asInt() != 1
                    || !purpose.equals(object.path("purpose").asString())
                    || !fingerprint(binding).equals(object.path("binding").asString())
                    || !object.path("time").isString() || !object.path("id").isString()) throw invalid();
            Instant time = Instant.parse(object.path("time").asString());
            UUID id = UUID.fromString(object.path("id").asString());
            if (!time.toString().equals(object.path("time").asString())
                    || !id.toString().equals(object.path("id").asString())) throw invalid();
            return Optional.of(new Anchor(time, id));
        } catch (IllegalArgumentException | DateTimeException | JacksonException exception) {
            throw invalid();
        }
    }

    /** 服务端用途和绑定缺失属于调用缺陷，不能被解释为合法匿名游标。 */
    private static void requireContext(String purpose, String binding) {
        if (purpose == null || !purpose.matches("[A-Z][A-Z0-9_]{0,63}") || binding == null || binding.isEmpty()) {
            throw new IllegalArgumentException("查询游标必须绑定服务端用途及可信身份过滤");
        }
    }

    /** payload只保存身份/过滤指纹；调用方字段排序决定相同过滤的稳定身份。 */
    private static String fingerprint(String binding) {
        try {
            return ENCODER.encodeToString(MessageDigest.getInstance("SHA-256").digest(binding.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("查询游标摘要算法不可用", exception);
        }
    }

    /** 每次使用独立Mac实例，避免Spring单例在并发分页时共享非线程安全加密状态。 */
    private static byte[] hmac(byte[] key, byte[] content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(content);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("查询游标签名算法不可用", exception);
        }
    }

    /** 对格式、篡改和身份过滤失配使用同一个公开错误，不暴露验证阶段。 */
    private static BusinessException invalid() {
        return new BusinessException(CommonErrorCode.INVALID_PARAMETER, "查询游标不合法或不适用于当前请求");
    }

    /** @param sortTime 权威排序时间 @param sortId 同时间排序ID */
    public record Anchor(Instant sortTime, UUID sortId) {
        /** 解码结果不包含授权身份；调用方仍在原事务重新查询真实权限。 */
        public Anchor {
            Objects.requireNonNull(sortTime, "sortTime");
            Objects.requireNonNull(sortId, "sortId");
        }
    }
}
