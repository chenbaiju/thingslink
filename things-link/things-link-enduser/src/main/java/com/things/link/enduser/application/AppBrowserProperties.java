package com.things.link.enduser.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** ADR0107独立浏览器配置；密钥内容永不进入record默认诊断。 */
@ConfigurationProperties(prefix = "things-link.security.app-browser")
public record AppBrowserProperties(boolean enabled, String origin, boolean allowLoopbackHttp,
        String cookieActiveKeyId, String cookieActiveKeyBase64url,
        String cookieRetiringKeyId, String cookieRetiringKeyBase64url) {
    /** 密钥配置包括禁用状态也不允许被日志默认展开。 */
    @Override public String toString() { return "AppBrowserProperties[enabled=" + enabled + ", keys=REDACTED]"; }
}
