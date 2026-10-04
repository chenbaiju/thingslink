package com.things.link.support.query;

import com.things.link.shared.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 数据运行合同§3.3/3.5：跨身份/过滤分页不可重用，密钥轮换和畸形游标不能静默回首页。 */
class SignedQueryCursorCodecTests {
    /** 独立测试密钥，不代表部署配置或可使用的令牌。 */
    private final SignedQueryCursorCodec codec = new SignedQueryCursorCodec("cursor-unit-test-key-32-characters-only");
    /** 保留PG微秒排序锚点，不能降成毫秒而跳过同一窗口内的记录。 */
    private static final Instant TIME = Instant.parse("2026-09-07T10:00:00.123456Z");
    /** 固定排序ID用于真实编码解码比较，授权身份单独放入binding。 */
    private static final UUID ID = UUID.fromString("01991611-1111-7111-8111-111111111111");

    /** 独立support没有HTTP认证配置时不创建无关游标组件；配置错误则不能静默提供默认密钥。 */
    @Test
    void registersOnlyForConfiguredHostAndRejectsInvalidKey() {
        var context = new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(SignedQueryCursorCodec.class);
        context.run(result -> assertThat(result).hasNotFailed().doesNotHaveBean(SignedQueryCursorCodec.class));
        context.withPropertyValues("things-link.security.jwt.secret=cursor-unit-test-key-32-characters-only")
                .run(result -> assertThat(result).hasNotFailed().hasSingleBean(SignedQueryCursorCodec.class));
        context.withPropertyValues("things-link.security.jwt.secret=short")
                .run(result -> assertThat(result).hasFailed());
    }

    /** 首页只接受null；正常分页保留完整排序精度且输出受限ASCII，不泄露身份原文。 */
    @Test
    void roundTripPreservesMicrosecondAnchorWithoutPlainIdentity() {
        assertThat(codec.decode(null, "APP_DEVICE_CATALOG", "tenant-project-secret-user")).isEmpty();
        String cursor = codec.encode("APP_DEVICE_CATALOG", "tenant-project-secret-user", TIME, ID);
        assertThat(cursor).hasSizeLessThanOrEqualTo(2048).matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
        assertThat(codec.decode(cursor, "APP_DEVICE_CATALOG", "tenant-project-secret-user"))
                .contains(new SignedQueryCursorCodec.Anchor(TIME, ID));
        assertThat(new String(Base64.getUrlDecoder().decode(cursor.split("\\.")[0]),
                java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("tenant-project-secret-user");
    }

    /** 用户、过滤、排序或用途变化必须重新首页查询，不能从其他身份结果继续分页。 */
    @Test
    void changedIdentityFiltersPurposeAndKeyAreRejected() {
        String cursor = codec.encode("APP_ALARM_QUERY", "identity-and-normalized-filters", TIME, ID);
        assertThatThrownBy(() -> codec.decode(cursor, "APP_ALARM_QUERY", "other-identity"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> codec.decode(cursor, "CONSOLE_ALARM_QUERY", "identity-and-normalized-filters"))
                .isInstanceOf(BusinessException.class);
        SignedQueryCursorCodec rotated = new SignedQueryCursorCodec("rotated-cursor-unit-key-32-characters");
        assertThatThrownBy(() -> rotated.decode(cursor, "APP_ALARM_QUERY", "identity-and-normalized-filters"))
                .isInstanceOf(BusinessException.class);
    }

    /** 任意payload篡改或签名替换即使仍是合法Base64也不能产生已验证锚点。 */
    @Test
    void tamperingWithPayloadOrSignatureIsRejected() {
        String cursor = codec.encode("APP_ALARM_QUERY", "binding", TIME, ID);
        String[] parts = cursor.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[0]), java.nio.charset.StandardCharsets.UTF_8);
        String changed = Base64.getUrlEncoder().withoutPadding().encodeToString(
                payload.replace("123456", "123457").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> codec.decode(changed + "." + parts[1], "APP_ALARM_QUERY", "binding"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> codec.decode(parts[0] + ".AAAAAAAA", "APP_ALARM_QUERY", "binding"))
                .isInstanceOf(BusinessException.class);
    }

    /** 空串、Unicode、额外分段和填充字符不属于冻结的规范ASCII游标。 */
    @ParameterizedTest
    @ValueSource(strings = {"", "中文", "a.b.c", "a.b=", "a", " ", "a..b", "a.b"})
    void rejectsMalformedCursor(String cursor) {
        assertThatThrownBy(() -> codec.decode(cursor, "APP_DEVICE_CATALOG", "binding"))
                .isInstanceOf(BusinessException.class);
    }

    /** 大输入先拒绝，不能在验签前触发无界解码分配。 */
    @Test
    void rejectsOversizedCursor() {
        assertThatThrownBy(() -> codec.decode("a".repeat(2049) + ".b", "APP_DEVICE_CATALOG", "binding"))
                .isInstanceOf(BusinessException.class);
    }
}
