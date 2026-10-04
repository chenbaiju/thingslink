package com.things.link.enduser.application;

import java.util.UUID;

/**
 * 终端用户（App）认证成功后确定下来的身份与项目范围（ADR 0036）。
 *
 * <p>与控制台 {@code AuthenticatedPrincipal} 是同一职责的第二类身份：主体是
 * {@code appUserId} 而非 {@code accountId}。本类型位于 application 包，是 enduser 模块
 * 的对外契约（架构文档 10.4 规则 2）。
 *
 * @param tenantId  归属租户。登录时由 projectKey 唯一确定，不是客户端可提交的输入
 * @param projectId 会话绑定的项目。<b>App 令牌是单项目令牌</b>，因此这里不允许 null，
 *                  与控制台「未选项目」状态不同
 * @param appUserId 终端用户 ID，即 App JWT 的 subject
 * @param projectGeneration 签发时项目生命周期代次；删除后旧会话不得在恢复时复活
 */
public record AppAuthenticatedPrincipal(
        UUID tenantId,
        UUID projectId,
        UUID appUserId,
        long projectGeneration) {

    /**
     * 兼容项目代次上线前的调用；旧会话按ADR0073解释为代次0。
     *
     * @param tenantId 归属租户
     * @param projectId 会话项目
     * @param appUserId 终端用户
     */
    public AppAuthenticatedPrincipal(UUID tenantId, UUID projectId, UUID appUserId) {
        this(tenantId, projectId, appUserId, 0L);
    }
}
