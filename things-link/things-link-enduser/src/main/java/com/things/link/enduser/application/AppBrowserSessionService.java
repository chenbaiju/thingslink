package com.things.link.enduser.application;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.nimbusds.jwt.JWTParser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/** 浏览器签发与Cookie编码共用原事务，编码失败不能留下撤旧却未交付新会话的提交。 */
@Service
public class AppBrowserSessionService {
    /** 原密码认证与跨账号原子替换用例。 */
    private final AppAuthenticationService authentication;
    /** 保持既有refresh用户互斥/复用整族撤销语义。 */
    private final AppSessionService sessions;
    /** 独立用途编码器，启动时已验证完整配置。 */
    private final AppBrowserRefreshCookieCodec codec;
    /** 依赖应用端口，不由HTTP控制器拼接两个独立提交。 */
    public AppBrowserSessionService(AppAuthenticationService authentication, AppSessionService sessions, AppBrowserRefreshCookieCodec codec) {
        this.authentication = authentication;
        this.sessions = sessions;
        this.codec = codec;
    }
    /** 旧hash仅由完整性验证后的Cookie派生，新签发及编码在同一原事务。 */
    @Transactional
    public Issued login(String projectKey, String username, String password, String clientIp, String epoch, byte[] oldHash) {
        return encode(epoch, authentication.loginReplacingBrowserSession(projectKey, username, password, clientIp, oldHash));
    }
    /** 不改变浏览器代次；原rotate的独立复用撤销事务继续生效。 */
    @Transactional
    public Issued refresh(String epoch, String rawRefresh) { return encode(epoch, sessions.rotate(rawRefresh)); }
    /** 只解析本机本次签发结果以形成公开身份，不把任意客户端JWT解析当认证。 */
    private Issued encode(String epoch, AppIssuedSession session) {
        try {
            var claims = JWTParser.parse(session.accessToken().value()).getJWTClaimsSet();
            UUID user = canonical(claims.getSubject());
            UUID project = canonical(claims.getStringClaim("pid"));
            String cookie = codec.encode(epoch, session.refreshToken(), session.refreshExpiresAt());
            return new Issued(session.accessToken().value(), session.accessToken().expiresAt(), user, project,
                    cookie, Instant.ofEpochSecond(session.refreshExpiresAt().getEpochSecond()));
        } catch (Exception failure) {
            // 原消息及嵌套cause可能含JWT/Cookie，保留类型与原始失败位置但不保留凭据文本。
            IllegalStateException sanitized = new IllegalStateException("原异常类型：" + failure.getClass().getName());
            sanitized.setStackTrace(failure.getStackTrace());
            throw new IllegalStateException("浏览器会话输出无法构造", sanitized);
        }
    }
    /** 签发器漂移不能静默变成另一个身份。 */
    private static UUID canonical(String value) {
        UUID id = UUID.fromString(value);
        if (!id.toString().equals(value)) throw new IllegalStateException("本地签发身份非规范");
        return id;
    }
    /** 内部载体不允许默认日志或JSON暴露Cookie，HTTP仅显式投影前四字段。 */
    public record Issued(String accessToken, Instant accessExpiresAt, UUID appUserId, UUID projectId,
                         @JsonIgnore String cookie, @JsonIgnore Instant refreshExpiresAt) {
        /** 任何诊断均不展开令牌或Cookie。 */
        @Override public String toString() { return "AppBrowserIssued[REDACTED]"; }
    }
}
