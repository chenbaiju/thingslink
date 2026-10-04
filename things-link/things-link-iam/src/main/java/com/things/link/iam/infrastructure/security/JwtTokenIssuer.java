package com.things.link.iam.infrastructure.security;

import com.things.link.iam.application.AccessToken;
import com.things.link.iam.application.AuthenticatedPrincipal;
import com.things.link.iam.application.TokenIssuer;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 基于 JWT 的令牌签发。
 *
 * <h2>令牌里放什么、不放什么</h2>
 * 放：账号 ID、租户 ID、角色。这三项是每次请求都要用的（建立租户上下文、
 * 做权限判定），放进令牌可以避免每个请求都回查数据库。
 *
 * <p><b>不放</b>：邮箱、显示名、口令哈希、任何个人信息。JWT 只是 Base64 编码而非
 * 加密，**任何拿到令牌的人都能读出全部内容**。放进去的每一项都等于公开。
 *
 * <h2>令牌无法撤销</h2>
 * 无状态校验不查库，因此账号被停用后，已签发的令牌在过期前仍然有效。
 * 有效期（默认 15 分钟）就是这个风险窗口的上限。需要立即失效的场景
 * （例如管理员踢人）要另外引入黑名单，那是 S1 后续切片的事。
 */
@Component
public class JwtTokenIssuer implements TokenIssuer {

    /** 租户 ID 声明名。与解析端保持一致，改名要同时改两处。 */
    public static final String CLAIM_TENANT_ID = "tid";

    /**
     * 当前项目 ID 声明名。
     *
     * <p>它是 RLS 的输入：{@code TenantScopeFilter} 读到它之后写进
     * {@code TenantContext}，再由 {@code TenantAwareDataSource} 写成会话变量
     * {@code app.project_id}（ADR 0012）。
     *
     * <p><b>可以缺席</b> —— 还没选项目时就没有这个声明，届时受保护的表一行也
     * 读不到，这正是期望的 fail-closed 行为。
     */
    public static final String CLAIM_PROJECT_ID = "pid";

    /** 项目生命周期代次声明名；与{@code pid}同时出现，缺失时只兼容零代历史令牌。 */
    public static final String CLAIM_PROJECT_GENERATION = "pgv";

    /*
     * 曾经有一个 role 声明，装的是租户角色。**已删除**（ADR 0012 校准）。
     *
     * 没有改成「项目角色」而是直接去掉，理由是：角色放进令牌就会**过期**——
     * 管理员把某人从 ADMIN 降为 VIEWER 之后，对方手里的令牌在剩余有效期内
     * 仍然按 ADMIN 渲染菜单与按钮。而项目角色恰恰是最可能被临时调整的东西。
     *
     * 现在每次请求都按 (账号, 当前项目) 回库查一次。代价是一次索引命中的查询，
     * 换来的是角色变更立即生效 —— 与 /me、/refresh 已经在做的「回库复核」一致。
     */

    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;

    public JwtTokenIssuer(JwtEncoder jwtEncoder, JwtProperties properties) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
    }

    @Override
    public AccessToken issue(AuthenticatedPrincipal principal) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.accessTokenTtl());

        JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                // subject 用账号 ID 而非邮箱：邮箱可以修改，而 subject 应当是
                // 稳定标识；用邮箱的话改邮箱会让所有历史审计记录对不上人
                .subject(principal.accountId().toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim(CLAIM_TENANT_ID, principal.tenantId().toString());

        // 没选项目时**不写这个声明**，而不是写一个空串。
        // 空串会让解析端多一条「是不是空」的分支，而缺席本身就是最准确的表达
        if (principal.projectId() != null) {
            builder.claim(CLAIM_PROJECT_ID, principal.projectId().toString());
            // 代次是删除对旧能力的永久撤销边界；不能只写pid后在恢复时重新放活旧令牌。
            builder.claim(CLAIM_PROJECT_GENERATION, principal.projectLifecycleGeneration());
        }

        JwtClaimsSet claims = builder.build();

        String value = jwtEncoder
                .encode(JwtEncoderParameters.from(JwsHeader.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build(), claims))
                .getTokenValue();

        return new AccessToken(value, expiresAt);
    }

}
