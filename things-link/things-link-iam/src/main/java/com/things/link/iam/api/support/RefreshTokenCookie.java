package com.things.link.iam.api.support;

import com.things.link.iam.application.SessionProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

/**
 * 刷新令牌 Cookie 的读写（决策 #8 / ADR 0010）。
 *
 * <h2>每个属性都在挡一类具体的攻击</h2>
 * <ul>
 *   <li><b>HttpOnly</b> —— JavaScript 读不到。这是选 Cookie 方案的<b>全部理由</b>：
 *       页面上任何一段被注入的脚本都偷不走这个长期凭据。放 localStorage 的话，
 *       一次 XSS 就等于一次长期账号接管</li>
 *   <li><b>Secure</b> —— 只在 HTTPS 下发送，防明文网络里被嗅探</li>
 *   <li><b>SameSite=Strict</b> —— 跨站发起的请求不携带它，这是本方案下
 *       <b>CSRF 的主要防线</b>，见下</li>
 *   <li><b>Path=/api/v1/auth</b> —— 浏览器只在请求认证接口时带上它。
 *       设成 {@code /} 功能上没差别，但会让每个业务请求都携带这个长期凭据，
 *       平白扩大暴露面（访问日志、代理、前端错误上报都可能记录请求头）</li>
 * </ul>
 *
 * <h2>为什么没有额外的 CSRF 令牌</h2>
 * 这里要修正一个之前写在 {@code SecurityConfiguration} 里的判断：
 * 「引入 Cookie 就必须为刷新接口重新启用 CSRF」——结论对得不完整，值得说清代价。
 *
 * <p>先看 CSRF 在这里能得到什么：攻击者可以诱导浏览器向 {@code /refresh} 发请求，
 * 浏览器会带上 Cookie，但<b>攻击者读不到响应</b>（跨源响应被同源策略挡住），
 * 因此拿不到新的访问令牌。他能造成的唯一后果是让令牌轮换一次，进而使真用户手里的
 * 旧令牌触发复用检测、整族被作废——即<b>强制登出</b>，一种骚扰，不是账号接管。
 *
 * <p>而 {@code SameSite=Strict} 已经让跨站请求根本带不上这个 Cookie，
 * 上述场景在所有现代浏览器上都不成立。因此当前不引入 CSRF 令牌，收益极小而要求
 * 前端读写 {@code XSRF-TOKEN} 并回传，是实打实的复杂度。
 *
 * <p><b>但这个结论有一个硬前提</b>：控制台与 API 必须同站。一旦拆成
 * {@code console.example.com} 与 {@code api.example.com} 这样的跨站部署，
 * {@code SameSite=Strict} 会让 Cookie 连正常请求都带不上，届时必须改成
 * {@code SameSite=None}——那时 CSRF 令牌就<b>不再是可选项</b>。见 ADR 0010。
 */
@Component
public class RefreshTokenCookie {

    private final SessionProperties properties;

    public RefreshTokenCookie(SessionProperties properties) {
        this.properties = properties;
    }

    /**
     * 构造携带刷新令牌的 Cookie。
     *
     * @param rawToken  令牌明文
     * @param expiresAt 过期时刻
     * @return 可直接写进 Set-Cookie 响应头的值
     */
    public ResponseCookie issue(String rawToken, Instant expiresAt) {
        return baseBuilder(rawToken)
                // Max-Age 与服务端记录的过期时刻对齐。即便客户端改大它也没用 ——
                // 服务端仍会按 sys_refresh_token.expires_at 拒绝，浏览器这边只是少发一次
                // 注定失败的请求
                .maxAge(Duration.between(Instant.now(), expiresAt))
                .build();
    }

    /**
     * 构造用于清除的 Cookie。
     *
     * <p>属性必须与签发时<b>逐项一致</b>（尤其是 Path），否则浏览器会认为这是另一个
     * Cookie，于是新增一条空值的、把旧的原样留着。表现是「退出登录后刷新页面又登回去了」。
     *
     * @return Max-Age 为 0 的同名 Cookie
     */
    public ResponseCookie clear() {
        return baseBuilder("").maxAge(0).build();
    }

    /**
     * 从请求中取出刷新令牌。
     *
     * @param request 当前请求
     * @return 令牌明文
     */
    public Optional<String> read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(c -> properties.cookieName().equals(c.getName()))
                .map(Cookie::getValue)
                .filter(v -> v != null && !v.isBlank())
                .findFirst();
    }

    /**
     * 公共属性。签发与清除必须完全一致，所以只写一处。
     *
     * @param value Cookie 值
     * @return 已设好属性的构造器
     */
    private ResponseCookie.ResponseCookieBuilder baseBuilder(String value) {
        return ResponseCookie.from(properties.cookieName(), value)
                .httpOnly(true)
                .secure(properties.cookieSecure())
                .sameSite(properties.cookieSameSite())
                .path(properties.cookiePath());
    }

}
