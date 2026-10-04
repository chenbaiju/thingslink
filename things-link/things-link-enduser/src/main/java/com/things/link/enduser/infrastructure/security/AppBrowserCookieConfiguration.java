package com.things.link.enduser.infrastructure.security;

import com.things.link.enduser.application.AppBrowserCookieBinding;
import com.things.link.enduser.application.AppBrowserProperties;
import com.things.link.enduser.application.AppBrowserRefreshCookieCodec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 启动时验证完整keyring与已配置其他用途的原始字节，缺配置绝不使用JWT默认密钥兜底。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AppBrowserProperties.class)
public class AppBrowserCookieConfiguration {
    /** 禁用时只提供不可签发的端口以保持装配，不创建开发Cookie密钥。 */
    @Bean
    public AppBrowserRefreshCookieCodec appBrowserRefreshCookieCodec(AppBrowserProperties properties, Environment environment) {
        if (!properties.enabled()) return new DisabledCodec();
        Map<String, byte[]> keys = new LinkedHashMap<>();
        keys.put(properties.cookieActiveKeyId(), key(properties.cookieActiveKeyBase64url()));
        boolean retiringId = properties.cookieRetiringKeyId() != null && !properties.cookieRetiringKeyId().isBlank();
        boolean retiringKey = properties.cookieRetiringKeyBase64url() != null && !properties.cookieRetiringKeyBase64url().isBlank();
        if (retiringId != retiringKey || retiringId && properties.cookieRetiringKeyId().equals(properties.cookieActiveKeyId())) throw invalid();
        if (retiringId) keys.put(properties.cookieRetiringKeyId(), key(properties.cookieRetiringKeyBase64url()));
        List<byte[]> otherKeys = new ArrayList<>();
        for (String name : List.of("things-link.security.jwt.secret", "things-link.security.app-jwt.secret",
                "things-link.security.broker-callback.secret", "things-link.notification.webhook.signing-secret")) {
            String value = environment.getProperty(name);
            if (value != null && !value.isEmpty()) otherKeys.add(value.getBytes(StandardCharsets.UTF_8));
        }
        Map<String, String> push = Binder.get(environment).bind("things-link.enduser.push-token-encryption.keys",
                Bindable.mapOf(String.class, String.class)).orElse(Map.of());
        try { for (String value : push.values()) otherKeys.add(Base64.getDecoder().decode(value)); }
        catch (IllegalArgumentException malformed) { throw invalid(); }
        for (byte[] candidate : keys.values()) {
            if (otherKeys.stream().anyMatch(other -> MessageDigest.isEqual(candidate, other))) throw invalid();
        }
        return new NimbusAppBrowserRefreshCookieCodec(properties.cookieActiveKeyId(), keys, properties.origin(), properties.allowLoopbackHttp());
    }

    /** 规范32字节Base64URL严格回编码，不能把文本形式不同当成密钥独立。 */
    private static byte[] key(String encoded) {
        if (!AppBrowserCookieBinding.canonical(encoded, 32)) throw invalid();
        return Base64.getUrlDecoder().decode(encoded);
    }
    /** 固定配置错误不泄露输入值。 */
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("浏览器Cookie密钥配置无效或用途冲突"); }
    /** 默认关闭实例的所有密码操作均失败，不创建可误用空密钥。 */
    private static final class DisabledCodec implements AppBrowserRefreshCookieCodec {
        /** 禁用入口不能签发。 */
        @Override public String encode(String epoch, String refresh, Instant expires) { throw new IllegalStateException("浏览器认证未启用"); }
        /** 禁用入口不能认证。 */
        @Override public VerifiedCookie decode(String cookie) { throw new IllegalStateException("浏览器认证未启用"); }
    }
}
