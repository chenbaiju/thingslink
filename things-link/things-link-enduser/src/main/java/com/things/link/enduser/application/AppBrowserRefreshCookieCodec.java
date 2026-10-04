package com.things.link.enduser.application;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;

/** S12-0a§2.1浏览器代次完整性封装；独立于access JWT，不负责HTTP Cookie或数据库轮换。 */
public interface AppBrowserRefreshCookieCodec {
    /** 封装真实刷新凭据和浏览器代次，到期向下截断至秒，绝不延长原期限。 */
    String encode(String browserEpoch, String refreshToken, Instant expiresAt);
    /** 完整性验证不先判断过期，调用方必须先比较期望代次以避免误清新会话。 */
    VerifiedCookie decode(String cookie);

    /** 验证后内部值不能用于日志或公开JSON，尤其刷新凭据不能泄露到响应。 */
    final class VerifiedCookie {
        /** 非凭据随机代次，仅供相等比较。 */
        @com.fasterxml.jackson.annotation.JsonProperty private final String browserEpoch;
        /** 刷新凭据只供后续数据库轮换，禁止默认序列化。 */
        @JsonIgnore private final String refreshToken;
        /** 原事实向下截断的秒级截止。 */
        @com.fasterxml.jackson.annotation.JsonProperty private final Instant expiresAt;
        /** 仅表达验证后的值；调用者不能把自行构造等价于密码学验证。 */
        public VerifiedCookie(String browserEpoch, String refreshToken, Instant expiresAt) {
            this.browserEpoch = java.util.Objects.requireNonNull(browserEpoch);
            this.refreshToken = java.util.Objects.requireNonNull(refreshToken);
            this.expiresAt = java.util.Objects.requireNonNull(expiresAt);
        }
        /** @return 验证后的非凭据代次 */
        public String browserEpoch() { return browserEpoch; }
        /** @return 内部刷新凭据，不能直接成为HTTP输出 */
        @JsonIgnore public String refreshToken() { return refreshToken; }
        /** @return 原期限向下截断后的截止 */
        public Instant expiresAt() { return expiresAt; }
        /** 日志误用也不输出代次、凭据或时刻。 */
        @Override public String toString() { return "VerifiedCookie[REDACTED]"; }
    }
}
