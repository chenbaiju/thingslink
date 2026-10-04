package com.things.link.iam.api.support;

import com.things.link.iam.infrastructure.security.JwtTokenIssuer;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

/**
 * 从已通过校验的令牌中取出身份标识。
 *
 * <p>抽出来是为了让「subject 是账号 ID」「租户 ID 在哪个声明里」只有一处定义。
 * 各 Controller 各写一遍的话，改声明名时漏掉一处不会编译失败，也不会立刻报错 ——
 * 只会在那一个接口上表现为「登录了却查不到自己的数据」。
 *
 * <p>调用前提是令牌<b>已经过签名与有效期校验</b>（由 Spring Security 的
 * resource server 过滤链完成）。本类不做任何校验，只做解析。
 */
public final class JwtIdentity {

    private JwtIdentity() {
    }

    /**
     * 取账号 ID。
     *
     * @param jwt 已通过校验的令牌
     * @return 账号 ID
     */
    public static UUID accountId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    /**
     * 取当前项目 ID。
     *
     * <p><b>可以为 null</b>：用户还没选项目时令牌里就没有这个声明。
     * 调用方必须处理这种情况 —— 那时既没有项目角色，也读不到任何项目级数据。
     *
     * @param jwt 已通过校验的令牌
     * @return 项目 ID；未选择时为 {@code null}
     */
    public static UUID projectId(Jwt jwt) {
        String raw = jwt.getClaimAsString(JwtTokenIssuer.CLAIM_PROJECT_ID);
        return raw == null ? null : UUID.fromString(raw);
    }

    /**
     * 取当前租户 ID。
     *
     * @param jwt 已通过校验的令牌
     * @return 租户 ID
     */
    public static UUID tenantId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString(JwtTokenIssuer.CLAIM_TENANT_ID));
    }

}
