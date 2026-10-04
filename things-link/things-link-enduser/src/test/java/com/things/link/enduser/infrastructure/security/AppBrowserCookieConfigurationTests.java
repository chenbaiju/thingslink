package com.things.link.enduser.infrastructure.security;

import com.things.link.enduser.application.AppBrowserProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 启动装配证明不同编码形式不能隐藏JWT/PUSH密钥字节复用。 */
class AppBrowserCookieConfigurationTests {
    /** 仅测试常量，不能成为生产配置默认值。 */
    private static final byte[] KEY = "12345678901234567890123456789012".getBytes(StandardCharsets.UTF_8);
    /** JWT文本与CookieBase64URL解码后相等必须启动失败。 */
    @Test void rejectsJwtAndPushKeyReuseByActualBytes() {
        for (String jwt : new String[] {"things-link.security.jwt.secret", "things-link.security.app-jwt.secret"}) {
            assertThatThrownBy(() -> new AppBrowserCookieConfiguration().appBrowserRefreshCookieCodec(properties(),
                    new MockEnvironment().withProperty(jwt, new String(KEY, StandardCharsets.UTF_8))))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new AppBrowserCookieConfiguration().appBrowserRefreshCookieCodec(properties(),
                new MockEnvironment().withProperty("things-link.enduser.push-token-encryption.keys.test", Base64.getEncoder().encodeToString(KEY))))
                .isInstanceOf(IllegalArgumentException.class);
    }
    /** 默认关闭不生成隐式key，但误调用编码也必须失败。 */
    @Test void disabledCodecCannotIssueACookie() {
        var codec = new AppBrowserCookieConfiguration().appBrowserRefreshCookieCodec(
                new AppBrowserProperties(false, null, false, null, null, null, null), new MockEnvironment());
        assertThatThrownBy(() -> codec.encode("epoch", "refresh", Instant.now())).isInstanceOf(IllegalStateException.class);
    }
    /** 有效形状用于测试字节碰撞，不表示生产密钥。 */
    private static AppBrowserProperties properties() {
        return new AppBrowserProperties(true, "https://share.test", false, "active", Base64.getUrlEncoder().withoutPadding().encodeToString(KEY), null, null);
    }
}
