package com.things.link.iam.application;

import java.util.UUID;

/**
 * 认证成功后确定下来的身份与租户范围。
 *
 * <p>本类型位于 {@code application} 包，因此是 iam 模块的**对外契约**
 * （架构文档 10.4 规则 2）—— 其他模块只能引用这里的类型，不能碰 domain。
 *
 * @param accountId 账号 ID
 * @param tenantId  当前租户 ID
 * @param projectId 当前选中的项目 ID。<b>可以为 null</b> —— 刚注册或还没选项目时
 *                  就是这个状态。此时受项目 RLS 保护的表一行也读不到
 *                  （fail-closed），用户应当先去项目列表选一个（ADR 0012）
 * @param projectLifecycleGeneration 当前项目签发时冻结的生命周期代次；未选项目时固定为0
 *
 * <p>曾经还有一个租户角色字段，<b>已删除</b>（ADR 0012 校准）：租户层现在只表达
 * 归属关系，真正的授权依据是项目角色，而项目角色不进令牌 —— 它每次请求回库查，
 * 这样角色变更能立即生效（见 {@code JwtTokenIssuer}）。
 */
public record AuthenticatedPrincipal(
        UUID accountId,
        UUID tenantId,
        UUID projectId,
        long projectLifecycleGeneration) {

    /**
     * 兼容生命周期代次引入前的无项目登录及零代项目构造。
     * @param accountId 账号ID
     * @param tenantId 当前租户ID
     * @param projectId 当前项目ID，可为空
     */
    public AuthenticatedPrincipal(UUID accountId, UUID tenantId, UUID projectId) {
        this(accountId, tenantId, projectId, 0L);
    }

    /** 项目代次必须非负，且没有项目上下文的令牌不能携带项目代次。 */
    public AuthenticatedPrincipal {
        if (projectLifecycleGeneration < 0 || projectId == null && projectLifecycleGeneration != 0) {
            throw new IllegalArgumentException("控制台身份的项目生命周期代次不合法");
        }
    }
}
