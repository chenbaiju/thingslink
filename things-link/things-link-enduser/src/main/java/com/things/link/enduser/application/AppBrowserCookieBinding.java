package com.things.link.enduser.application;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

/** 先比较浏览器代次再处理过期；纯判定无Cookie清除、族撤销或其他副作用。 */
public final class AppBrowserCookieBinding {
    /** 只允许经标准完整性验证的内部值参与判定。 */
    private final AppBrowserRefreshCookieCodec codec;
    /** 装配独立封装端口，不从当前HTTP请求推导可信origin。 */
    public AppBrowserCookieBinding(AppBrowserRefreshCookieCodec codec) { this.codec = Objects.requireNonNull(codec); }
    /** 非法期望代次先拒绝；不匹配即止，不能先由旧Cookie过期状态触发删除新Cookie。 */
    public Result verify(String expectedEpoch, String cookie, Instant now) {
        Objects.requireNonNull(now, "now");
        if (!validEpoch(expectedEpoch)) return new Result(Status.INVALID_EPOCH, null);
        if (cookie == null) return new Result(Status.MISSING, null);
        AppBrowserRefreshCookieCodec.VerifiedCookie verified;
        try { verified = codec.decode(cookie); }
        catch (IllegalArgumentException invalid) { return new Result(Status.INVALID_COOKIE, null); }
        if (!expectedEpoch.equals(verified.browserEpoch())) return new Result(Status.EPOCH_MISMATCH, null);
        if (!now.isBefore(verified.expiresAt())) return new Result(Status.EXPIRED, verified);
        return new Result(Status.VALID, verified);
    }
    /** 16字节随机代次必须为规范无填充Base64URL，尾部未使用比特不能产生别名。 */
    public static boolean validEpoch(String value) {
        return value != null && value.length() == 25 && value.startsWith("be_") && canonical(value.substring(3), 16);
    }
    /** 固定字节长度与重编码同时限制解码开销并拒绝宽松Base64别名。 */
    public static boolean canonical(String value, int bytes) {
        if (value == null || value.length() != (bytes * 8 + 5) / 6 || !value.matches("[A-Za-z0-9_-]+")) return false;
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            return decoded.length == bytes && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value);
        } catch (IllegalArgumentException invalid) { return false; }
    }
    /** 判定不等同于HTTP处理；INVALID_EPOCH独立于Cookie事实。 */
    public enum Status {
        /** 无Cookie；退出可幂等，刷新不能恢复。 */ MISSING,
        /** 无法验证归属，不能清除可能属于新代次的Cookie。 */ INVALID_COOKIE,
        /** 已验证但属于其他代次，在任何会话副作用之前拒绝。 */ EPOCH_MISMATCH,
        /** 仅匹配代次后才能判定过期，后续HTTP决定是否清除。 */ EXPIRED,
        /** 仅允许进入原数据库授权流程，不代表数据库会话仍有效。 */ VALID,
        /** 请求代次形状错误，优先于所有凭据读取。 */ INVALID_EPOCH
    }
    /** 仅VALID/EXPIRED携带已匹配的内部凭据，禁止序列化整个内部值。 */
    public record Result(Status status, @JsonIgnore AppBrowserRefreshCookieCodec.VerifiedCookie cookie) {
        /** @return 已匹配内部凭据；不允许通用JSON输出 */
        @Override @JsonIgnore public AppBrowserRefreshCookieCodec.VerifiedCookie cookie() { return cookie; }
        /** 诊断只输出低基数结果，不输出任何身份或凭据。 */
        @Override public String toString() { return "CookieBinding[" + status + "]"; }
    }
}
