package com.things.link.iam.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * 会话与刷新令牌 Cookie 的配置（决策 #8 / ADR 0010）。
 *
 * @param refreshTokenTtl 刷新令牌有效期。它是「多久不用就必须重新登录」的上限。
 *                        调长会延长凭据被盗后的可用窗口，调短会让用户频繁重登；
 *                        7 天是常见折中，且轮换机制已经把单个令牌的实际寿命压到
 *                        「到下次刷新为止」
 * @param cookieName      Cookie 名
 * @param cookiePath      Cookie 路径。<b>刻意收窄到认证接口</b>：浏览器只会在请求这些
 *                        路径时携带它，其余接口连见都见不到这个凭据。设成 {@code /}
 *                        不会有任何功能差异，但会让每一个业务请求都带上它，
 *                        平白扩大暴露面（日志、代理、错误上报都可能记录请求头）
 * @param cookieSecure    是否只在 HTTPS 下发送。<b>生产必须为 true。</b>
 *                        默认 true；本机开发若浏览器拒绝在 http://localhost 上接受
 *                        Secure Cookie，再显式置 false，而不是反过来默认不安全
 * @param cookieSameSite  SameSite 策略。默认 {@code Strict}，理由见 ADR 0010：
 *                        它是本方案下 CSRF 的主要防线
 * @param allowedOrigins  允许携带凭据的跨源来源，精确匹配，不支持通配。
 *                        <b>默认空</b>，即不允许任何跨源请求 —— 开发走 Vite 代理是
 *                        同源的，生产按 ADR 0010 要求控制台与 API 同站，两种情况
 *                        都不需要它。真要填时必须写完整来源（含协议与端口）
 */
@ConfigurationProperties(prefix = "things-link.security.session")
public record SessionProperties(
        Duration refreshTokenTtl,
        String cookieName,
        String cookiePath,
        Boolean cookieSecure,
        String cookieSameSite,
        List<String> allowedOrigins) {

    /**
     * 紧凑构造器：为可选项提供默认值。
     *
     * <p>默认值一律取<b>更安全</b>的那一侧：漏配一项时的表现应当是「太严格导致不工作」，
     * 而不是「悄悄放松了限制」——前者会立刻被发现，后者不会。
     */
    public SessionProperties {
        refreshTokenTtl = refreshTokenTtl == null ? Duration.ofDays(7) : refreshTokenTtl;
        cookieName = cookieName == null || cookieName.isBlank() ? "tc_refresh" : cookieName;
        cookiePath = cookiePath == null || cookiePath.isBlank() ? "/api/v1/auth" : cookiePath;
        cookieSecure = cookieSecure == null || cookieSecure;
        cookieSameSite = cookieSameSite == null || cookieSameSite.isBlank() ? "Strict" : cookieSameSite;
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }

}
