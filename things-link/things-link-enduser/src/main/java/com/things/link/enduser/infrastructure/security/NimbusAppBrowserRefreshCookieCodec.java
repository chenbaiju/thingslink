package com.things.link.enduser.infrastructure.security;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.things.link.enduser.application.AppBrowserCookieBinding;
import com.things.link.enduser.application.AppBrowserRefreshCookieCodec;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** 标准HS256 JWS的有界闭集封装；S12-3a只交付原语，不自动装配生产Cookie端点。 */
public final class NimbusAppBrowserRefreshCookieCodec implements AppBrowserRefreshCookieCodec {
    /** 独立用途避免与access/Console/Push令牌互认。 */
    private static final String PURPOSE = "APP_BROWSER_REFRESH";
    /** 显式JOSE类型作为受签名保护的第二道用途边界。 */
    private static final String TYPE = "tc-app-refresh+jws";
    /** 严格JSON拒绝重复字段及尾随正文，解码前另检查UTF-8/BOM和字节预算。 */
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    /** 当前签发密钥只来自构造器受管配置。 */
    private final String activeKid;
    /** 最多当前与前一把独立32字节密钥，绝不解析远程URL。 */
    private final Map<String, byte[]> keys;
    /** 可信部署精确origin，不从请求Host/Origin推导。 */
    private final String origin;

    /** 构造器仅接受显式本地密钥和精确origin；生产接线另需证明不复用其他用途密钥。 */
    public NimbusAppBrowserRefreshCookieCodec(String activeKid, Map<String, byte[]> keys,
            String origin, boolean allowLoopbackHttp) {
        try {
            if (!validKid(activeKid) || keys == null || keys.isEmpty() || keys.size() > 2 || !keys.containsKey(activeKid)) throw invalid();
            Map<String, byte[]> copy = new HashMap<>();
            for (var entry : keys.entrySet()) {
                if (!validKid(entry.getKey()) || entry.getValue() == null || entry.getValue().length != 32
                        || copy.values().stream().anyMatch(key -> MessageDigest.isEqual(key, entry.getValue()))) throw invalid();
                copy.put(entry.getKey(), entry.getValue().clone());
            }
            URI parsed = URI.create(origin);
            boolean loopback = Set.of("localhost", "127.0.0.1", "[::1]").contains(parsed.getHost());
            if (parsed.getHost() == null || parsed.getRawUserInfo() != null || parsed.getRawQuery() != null
                    || parsed.getRawFragment() != null || !parsed.getRawPath().isEmpty()
                    || parsed.getPort() < -1 || parsed.getPort() == 0 || parsed.getPort() > 65535
                    || !origin.equals(parsed.toASCIIString())
                    || !("https".equals(parsed.getScheme()) || allowLoopbackHttp && loopback && "http".equals(parsed.getScheme()))) throw invalid();
            // 按最大合法秒数和固定凭据长度预先验证512字节预算，不设脱离编码合同的域名长度魔数。
            if (JSON.writeValueAsBytes(Map.of("v", 1, "purpose", PURPOSE, "origin", origin,
                    "browserEpoch", "be_" + "A".repeat(22), "refreshToken", "A".repeat(43),
                    "expiresAt", Long.MAX_VALUE)).length > 512) throw invalid();
            this.activeKid = activeKid;
            this.keys = Map.copyOf(copy);
            this.origin = origin;
        } catch (RuntimeException failure) { throw invalid(); }
    }

    /** 标准JWS签发，expiresAt秒级截断不延长数据库refresh期限。 */
    @Override public String encode(String browserEpoch, String refreshToken, Instant expiresAt) {
        try {
            if (!AppBrowserCookieBinding.validEpoch(browserEpoch) || !AppBrowserCookieBinding.canonical(refreshToken, 32)
                    || expiresAt == null || expiresAt.getEpochSecond() <= 0) throw invalid();
            String payload = JSON.writeValueAsString(Map.of("v", 1, "purpose", PURPOSE, "origin", origin,
                    "browserEpoch", browserEpoch, "refreshToken", refreshToken, "expiresAt", expiresAt.getEpochSecond()));
            if (payload.getBytes(StandardCharsets.UTF_8).length > 512) throw invalid();
            JWSObject jws = new JWSObject(new JWSHeader.Builder(JWSAlgorithm.HS256)
                    .type(new JOSEObjectType(TYPE)).keyID(activeKid).build(), new Payload(payload));
            jws.sign(new MACSigner(keys.get(activeKid)));
            String result = jws.serialize();
            if (result.length() > 1024) throw invalid();
            return result;
        } catch (Exception failure) { throw invalid(); }
    }

    /** 先限制所有解码预算并闭集检查，再使用Nimbus验签；不判断过期以保持代次比较优先。 */
    @Override public VerifiedCookie decode(String cookie) {
        try {
            if (cookie == null || cookie.length() > 1024 || !cookie.matches("[A-Za-z0-9_.-]+")) throw invalid();
            String[] parts = cookie.split("\\.", -1);
            if (parts.length != 3 || !AppBrowserCookieBinding.canonical(parts[2], 32)) throw invalid();
            JsonNode header = object(parts[0], 192, Set.of("alg", "typ", "kid"));
            if (!"HS256".equals(text(header, "alg")) || !TYPE.equals(text(header, "typ"))) throw invalid();
            String kid = text(header, "kid");
            if (!validKid(kid) || !keys.containsKey(kid)) throw invalid();
            JsonNode payload = object(parts[1], 512, Set.of("v", "purpose", "origin", "browserEpoch", "refreshToken", "expiresAt"));
            if (!payload.path("v").isIntegralNumber() || !"1".equals(payload.path("v").asString())
                    || !PURPOSE.equals(text(payload, "purpose")) || !origin.equals(text(payload, "origin"))) throw invalid();
            String epoch = text(payload, "browserEpoch");
            String refresh = text(payload, "refreshToken");
            JsonNode expires = payload.path("expiresAt");
            if (!AppBrowserCookieBinding.validEpoch(epoch) || !AppBrowserCookieBinding.canonical(refresh, 32)
                    || !expires.isIntegralNumber() || !expires.canConvertToLong() || expires.longValue() <= 0) throw invalid();
            Instant until = Instant.ofEpochSecond(expires.longValue());
            JWSObject jws = JWSObject.parse(cookie);
            if (!jws.verify(new MACVerifier(keys.get(kid)))) throw invalid();
            return new VerifiedCookie(epoch, refresh, until);
        } catch (Exception failure) { throw invalid(); }
    }

    /** 严格UTF-8与重编码拒绝填充、非规范尾比特、BOM和宽松JSON解析差异。 */
    private static JsonNode object(String encoded, int maximumBytes, Set<String> fields) throws Exception {
        if (encoded.isEmpty() || encoded.length() > (maximumBytes * 8 + 5) / 6 || !encoded.matches("[A-Za-z0-9_-]+")) throw invalid();
        byte[] bytes = Base64.getUrlDecoder().decode(encoded);
        if (bytes.length > maximumBytes || !Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(encoded)) throw invalid();
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        if (text.startsWith("\ufeff")) throw invalid();
        JsonNode node = JSON.readTree(text);
        if (node == null || !node.isObject() || !node.propertyNames().equals(fields)) throw invalid();
        return node;
    }
    /** 必填字段禁止数字、布尔或null到字符串的宽松转换。 */
    private static String text(JsonNode node, String field) {
        if (!node.path(field).isString()) throw invalid();
        return node.path(field).asString();
    }
    /** 本地固定kid空间，不解析任意路径或密钥发现URL。 */
    private static boolean validKid(String value) { return value != null && value.matches("[a-z0-9][a-z0-9_-]{0,31}"); }
    /** 禁止凭据、输入或底层解析消息进入异常及其cause。 */
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("浏览器刷新Cookie封装无效"); }
}
