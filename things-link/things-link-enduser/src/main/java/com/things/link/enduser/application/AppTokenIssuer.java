package com.things.link.enduser.application;

/**
 * App 访问令牌签发契约。
 *
 * <p>抽成接口让应用层不依赖具体 JWT 实现。App 令牌用独立 HMAC 密钥与 issuer
 * {@code things-link-app}，与控制台令牌密码学互斥（ADR 0036）—— 两端解码器各自校验
 * 自己的密钥，对方的令牌在签名校验阶段即失败。
 */
public interface AppTokenIssuer {

    /** ADR0099实时握手的明确App受众；首次登录及刷新统一由签发器写入。 */
    String AUDIENCE = "things-link-app";

    /** 租户 ID 声明名。与解析端 {@code AppScopeFilter} 保持一致，改名要同时改两处。 */
    String CLAIM_TENANT_ID = "tid";

    /**
     * 项目 ID 声明名。
     *
     * <p>它是 RLS 的输入：{@code AppScopeFilter} 读到它之后写进
     * {@code RlsScopeContext}，再由 {@code TenantAwareDataSource} 写成会话变量
     * {@code app.project_id}。App 令牌是单项目令牌，因此该声明<b>必带</b>（ADR 0036）。
     */
    String CLAIM_PROJECT_ID = "pid";

    /** 项目生命周期代次声明；ADR0073规定旧令牌缺失时按0解释。 */
    String CLAIM_PROJECT_GENERATION = "pgv";

    /**
     * 为已认证的终端用户签发 App 访问令牌。
     *
     * @param principal 认证成功后确定的身份与项目范围
     * @return 访问令牌
     */
    AppAccessToken issue(AppAuthenticatedPrincipal principal);

}
