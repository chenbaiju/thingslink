package com.things.link.enduser.infrastructure.security;

import com.things.link.enduser.application.AppBrowserCookieBinding;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 标准真实签验与恶意但正确签名的闭集反例；不以mock绕过密码学或编码解析。 */
class NimbusAppBrowserRefreshCookieCodecTests {
    /** 测试用途固定32字节，不作为生产密钥示例。 */
    private final byte[] key = new byte[32];
    /** 规范16字节随机值的确定性测试投影。 */
    private final String epoch = "be_" + "A".repeat(22);
    /** 规范32字节刷新凭据测试投影。 */
    private final String refresh = "A".repeat(43);
    /** 精确生产形状origin。 */
    private final String origin = "https://app.example.test";
    /** 秒以下精度只允许向下截断。 */
    private final Instant expires = Instant.parse("2026-09-07T12:00:00.999Z");
    /** 标准签验原语，不自动注册任何生产端点。 */
    private final NimbusAppBrowserRefreshCookieCodec codec = new NimbusAppBrowserRefreshCookieCodec("active", Map.of("active", key), origin, false);

    /** 真实HS256签名往返，decode不过早判过期，秘密不出日志/JSON。 */
    @Test
    void roundTripTruncatesDeadlineAndRedactsSecrets() {
        String encoded = codec.encode(epoch, refresh, expires);
        var decoded = codec.decode(encoded);
        assertThat(encoded).hasSizeLessThanOrEqualTo(1024);
        assertThat(decoded.browserEpoch()).isEqualTo(epoch);
        assertThat(decoded.refreshToken()).isEqualTo(refresh);
        assertThat(decoded.expiresAt()).isEqualTo(expires.minusNanos(999_000_000));
        assertThat(decoded.toString()).doesNotContain(epoch, refresh);
        assertThat(JsonMapper.builder().build().writeValueAsString(decoded)).doesNotContain(refresh);
    }

    /** 任何签名位变更都失败且异常不携原凭据或底层cause。 */
    @Test
    void tamperingHasFixedSanitizedFailure() {
        String encoded = codec.encode(epoch, refresh, expires);
        String[] parts = encoded.split("\\.");
        byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
        signature[0] ^= 1;
        reject(parts[0] + "." + parts[1] + "." + base64(signature));
        reject("secret-private-input");
    }

    /** 已知旧kid可验、只用active签发；未知kid与不同用途密钥拒绝。 */
    @Test
    void rolloverUsesOnlyTwoLocalDistinctKeys() {
        byte[] newer = new byte[32]; newer[0] = 1;
        var rotated = new NimbusAppBrowserRefreshCookieCodec("new", Map.of("active", key, "new", newer), origin, false);
        String old = codec.encode(epoch, refresh, expires);
        assertThat(rotated.decode(old).browserEpoch()).isEqualTo(epoch);
        String current = rotated.encode(epoch, refresh, expires);
        assertThat(new String(Base64.getUrlDecoder().decode(current.split("\\.")[0]), StandardCharsets.UTF_8)).contains("\"kid\":\"new\"");
        reject(current);
        assertThatThrownBy(() -> new NimbusAppBrowserRefreshCookieCodec("new", Map.of("active", key, "new", key), origin, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 构造后调用方改写map与byte[]不能替换已装配密钥。 */
    @Test
    void keyConfigurationIsDefensivelyCopied() {
        byte[] mutable = key.clone();
        Map<String, byte[]> map = new HashMap<>(); map.put("active", mutable);
        var copied = new NimbusAppBrowserRefreshCookieCodec("active", map, origin, false);
        mutable[0] = 42; map.clear();
        assertThat(codec.decode(copied.encode(epoch, refresh, expires)).refreshToken()).isEqualTo(refresh);
    }

    /** 即使MAC正确也不接受扩展header、算法替换或其他用途和来源。 */
    @Test
    void signedAlgorithmHeaderPurposeAndOriginSubstitutionsFail() throws Exception {
        for (String header : new String[] {
                "{\"alg\":\"none\",\"typ\":\"tc-app-refresh+jws\",\"kid\":\"active\"}",
                header().replace("HS256", "HS512"), header().replace("tc-app-refresh+jws", "JWT"),
                header().replace("active", "unknown"), header().replace("}", ",\"crit\":[]}"),
                header().replace("}", ",\"b64\":true}"), header().replace("}", ",\"jku\":\"https://evil.test\"}") }) {
            reject(sign(header.getBytes(StandardCharsets.UTF_8), payload().getBytes(StandardCharsets.UTF_8)));
        }
        for (String payload : new String[] {payload().replace("APP_BROWSER_REFRESH", "ACCESS"),
                payload().replace(origin, "https://other.example.test"), payload().replace("\"v\":1", "\"v\":2"),
                payload().replace("}", ",\"extra\":1}")}) reject(sign(header().getBytes(StandardCharsets.UTF_8), payload.getBytes(StandardCharsets.UTF_8)));
    }

    /** 重复键、尾随、BOM与非法UTF8在签名真实有效时仍拒绝。 */
    @Test
    void strictJsonAndUtf8RejectAmbiguousSignedPayloads() throws Exception {
        for (String payload : new String[] {payload().replace("{", "{\"v\":1,"), payload() + " {}", "\ufeff" + payload()}) {
            reject(sign(header().getBytes(StandardCharsets.UTF_8), payload.getBytes(StandardCharsets.UTF_8)));
        }
        reject(sign(header().replace("{", "{\"alg\":\"HS256\",").getBytes(StandardCharsets.UTF_8), payload().getBytes(StandardCharsets.UTF_8)));
        reject(sign(header().getBytes(StandardCharsets.UTF_8), new byte[] {(byte) 0xc3, (byte) 0x28}));
    }

    /** 分段预算在解析前执行，填充与未使用尾比特别名同样拒绝。 */
    @Test
    void encodingAndDecodedByteBudgetsAreHardBounds() throws Exception {
        String good = codec.encode(epoch, refresh, expires);
        reject(good + "=");
        reject("A".repeat(1025));
        reject(sign((header() + " ".repeat(193 - header().length())).getBytes(StandardCharsets.UTF_8), payload().getBytes(StandardCharsets.UTF_8)));
        reject(sign(header().getBytes(StandardCharsets.UTF_8), (payload() + " ".repeat(513 - payload().length())).getBytes(StandardCharsets.UTF_8)));
        assertThat(codec.decode(sign((header() + " ".repeat(192 - header().length())).getBytes(StandardCharsets.UTF_8),
                (payload() + " ".repeat(512 - payload().length())).getBytes(StandardCharsets.UTF_8))).browserEpoch()).isEqualTo(epoch);
        assertThat(AppBrowserCookieBinding.validEpoch("be_" + "A".repeat(21) + "B")).isFalse();
    }

    /** 密钥预算、非规范凭据及不可信origin不能成为可签发实例。 */
    @Test
    void configurationAndEncodeInputAreStrict() {
        for (String bad : new String[] {"http://app.example.test", "https://app.example.test/", "https://user@app.example.test", "https://app.example.test?q=1"}) {
            assertThatThrownBy(() -> new NimbusAppBrowserRefreshCookieCodec("active", Map.of("active", key), bad, false))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(new NimbusAppBrowserRefreshCookieCodec("active", Map.of("active", key), "http://127.0.0.1:3007", true)).isNotNull();
        assertThatThrownBy(() -> new NimbusAppBrowserRefreshCookieCodec("active", Map.of("active", new byte[31]), origin, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.encode(epoch, "A".repeat(42) + "B", expires)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.encode(epoch, refresh, Instant.EPOCH)).isInstanceOf(IllegalArgumentException.class);
    }

    /** 构造闭合集header；手工MAC仅用于生成标准库正常builder无法产生的恶意已签名测试输入。 */
    private String header() { return "{\"alg\":\"HS256\",\"typ\":\"tc-app-refresh+jws\",\"kid\":\"active\"}"; }
    /** 固定字段顺序便于局部攻击变形，不依赖生产序列化布局。 */
    private String payload() {
        return "{\"v\":1,\"purpose\":\"APP_BROWSER_REFRESH\",\"origin\":\"" + origin + "\",\"browserEpoch\":\"" + epoch
                + "\",\"refreshToken\":\"" + refresh + "\",\"expiresAt\":" + expires.getEpochSecond() + "}";
    }
    /** JCA标准HmacSHA256构造异常JSON/头字段的有效JWS签名以验证语法守卫。 */
    private String sign(byte[] header, byte[] payload) throws Exception {
        String input = base64(header) + "." + base64(payload);
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return input + "." + base64(mac.doFinal(input.getBytes(StandardCharsets.US_ASCII)));
    }
    /** 测试编码统一使用标准URL安全无填充形式。 */
    private static String base64(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    /** 拒绝只能产生固定异常，不包含原cookie或解析cause。 */
    private void reject(String cookie) {
        assertThatThrownBy(() -> codec.decode(cookie)).isInstanceOfSatisfying(IllegalArgumentException.class, failure -> {
            assertThat(failure.getMessage()).isEqualTo("浏览器刷新Cookie封装无效");
            assertThat(failure.getCause()).isNull();
        });
    }
}
