package com.things.link.project.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * 当前已选项目归属租户的套餐摘要只读端口（S14-2c，架构文档 §3.2 / §6）。
 *
 * <p>授权口径与既有配额概览一致且更窄：实现按调用方已授权且已选中的项目 ID 收窄，并要求
 * 账户在该项目归属租户里有 ACTIVE 成员关系 —— 架构文档 §6 明确「租户成员只能读取本租户
 * 套餐摘要」，跨租户协作者在项目页只保留既有共享池投影。两个参数都必须来自服务端已验证的
 * {@code TenantScope}（JWT 已选项目与账号），不接受任何客户端传来的 tenantId；租户 ID 既不
 * 来自参数，也不进入响应。
 *
 * <p>没有 ACTIVE 订阅（例如未回填的存量租户）时返回空，而不是伪造一份零额度摘要：读取面
 * 必须能区分「没有订阅事实」与「有订阅但额度为零」。
 */
public interface PlanSummaryRepository {

    /**
     * 读取指定项目归属租户的套餐摘要。
     *
     * @param projectId 已通过成员校验且等于 JWT 已选项目的项目 ID
     * @param callerAccountId 当前已认证账号 ID；必须是项目归属租户的 ACTIVE 成员
     * @return 调用者属于项目归属租户且该租户存在 ACTIVE 订阅时的摘要；否则为空
     */
    Optional<TenantPlanSummary> findForCurrentProject(UUID projectId, UUID callerAccountId);
}
