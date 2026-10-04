package com.things.link.enduser.infrastructure.security;

import com.things.link.enduser.application.AppAccessToken;
import com.things.link.enduser.application.AppAuthenticatedPrincipal;
import com.things.link.enduser.application.AppTokenIssuer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 基于 JWT 的 App 访问令牌签发。
 *
 * <h2>令牌里放什么、不放什么</h2>
 * 放：终端用户 ID（subject）、租户 ID、项目 ID。这三项是每次请求都要用的 —— 建 RLS
 * 上下文、写审计归属。不放：用户名、显示名、口令哈希、任何个人信息。JWT 只是 Base64
 * 编码而非加密，<b>任何拿到令牌的人都能读出全部内容</b>。
 *
 * <p><b>不承载项目角色</b>：与控制台令牌同一条 ADR 0012 的取舍 —— 角色放令牌里就会
 * 过期（管理员停用角色后对方手里的令牌在有效期内仍按旧角色放行），而且 App 令牌是
 * 单项目令牌，角色本就该每次按 (app_user, project) 回库复验。需要复验的落点是刷新，
 * 见 {@code AppSessionService#reverify}。
 *
 * <p>声明名 {@link AppTokenIssuer#CLAIM_TENANT_ID} / {@link AppTokenIssuer#CLAIM_PROJECT_ID}
 * 定义在 application 契约里，签发端（本类）与消费端（{@code AppScopeFilter}）共用同一
 * 来源，改名时编译器会在两侧同时报错。
 */
@Component
public class AppJwtTokenIssuer implements AppTokenIssuer {

    private final JwtEncoder jwtEncoder;
    private final AppJwtProperties properties;

    public AppJwtTokenIssuer(@Qualifier("appJwtEncoder") JwtEncoder jwtEncoder,
                             AppJwtProperties properties) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
    }

    @Override
    public AppAccessToken issue(AppAuthenticatedPrincipal principal) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.accessTokenTtl());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                // 新实时入口严格校aud；旧REST仍接受历史令牌，刷新后自然获得该声明。
                .audience(java.util.List.of(AUDIENCE))
                // subject 用 app_user_id。它是租户内稳定标识，改名/改显示名都不影响它
                .subject(principal.appUserId().toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim(CLAIM_TENANT_ID, principal.tenantId().toString())
                // App 令牌是单项目令牌，pid 必带（与控制台「未选项目」可缺席不同）
                .claim(CLAIM_PROJECT_ID, principal.projectId().toString())
                // 项目删除会单调递增代次；恢复后只接受新签发的当前代次凭据（ADR0073）。
                .claim(CLAIM_PROJECT_GENERATION, principal.projectGeneration())
                .build();

        String value = jwtEncoder
                .encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();

        return new AppAccessToken(value, expiresAt);
    }

}
