package com.things.link.iam.application;

/**
 * 访问令牌签发契约。
 *
 * <p>抽成接口是为了让 {@link AuthenticationService} 不依赖具体的 JWT 实现 ——
 * 认证逻辑（校验口令、检查状态、确定租户）与令牌格式是两件独立演进的事，
 * S3 换成 RSA 签名、或将来引入 Spring Authorization Server 都不该改动认证逻辑。
 */
public interface TokenIssuer {

    /**
     * 为已认证的身份签发访问令牌。
     *
     * @param principal 认证成功后确定的身份与租户范围
     * @return 访问令牌
     */
    AccessToken issue(AuthenticatedPrincipal principal);

}
