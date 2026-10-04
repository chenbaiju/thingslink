package com.things.link.project.domain;

import com.things.link.project.application.EffectiveQuotaPolicy;

import java.util.Optional;
import java.util.UUID;

/**
 * 有效租户配额策略的权威读取端口。
 *
 * <p>策略表属于 project 模块，其他模块必须通过 application 端口读取，不能跨模块拼接
 * {@code sys_tenant} 与 {@code sys_quota_policy}。
 */
public interface EffectiveQuotaPolicyRepository {

    /**
     * 按租户读取其当前绑定策略。
     *
     * @param tenantId 可信租户 ID
     * @return 存在且绑定策略有效时的完整快照
     */
    Optional<EffectiveQuotaPolicy> findByTenantId(UUID tenantId);

    /**
     * 商业生命周期内部权威投影，不作为请求身份授权入口。
     * 调用方必须已持有该租户生命周期锁；无需伪造账号JWT上下文，不经过缓存或故障默认。
     * @param tenantId 系统任务已锁定的真实租户
     * @return 当前绑定的完整商业额度；投影缺失为空，调用方拒绝状态变更
     */
    Optional<com.things.link.project.domain.plan.EffectivePlanQuota> findForCommercialLifecycle(UUID tenantId);

    /**
     * 仅从有效项目派生所有者租户后读取策略。
     *
     * @param projectId 已授权项目 ID
     * @return 项目存在且绑定策略有效时的完整快照
     */
    Optional<EffectiveQuotaPolicy> findByProjectId(UUID projectId);

    /**
     * 通过设备确权得到的 tenant/project 二元组读取有效策略。
     *
     * @param tenantId 设备确权解析的 owner tenant ID
     * @param projectId 同一次设备确权解析的 project ID
     * @return 二元组真实匹配且均有效时的完整策略快照
     */
    Optional<EffectiveQuotaPolicy> findByDeviceProject(UUID tenantId, UUID projectId);

    /**
     * 按产品修订版读取其绑定的配额模板（S14-1b）。
     *
     * <p>返回快照没有租户绑定：{@code tenantId} 为 {@code null}、{@code assignmentVersion}
     * 为 {@code 0}；{@code planQuota} 必定非空。调用方不得把它当作某个租户的有效策略。
     *
     * @param planRevisionId 产品修订版 ID
     * @return 修订版存在且已绑定模板时的快照
     */
    Optional<EffectiveQuotaPolicy> findByPlanRevision(UUID planRevisionId);
}
