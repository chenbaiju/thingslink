package com.things.link.project.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 商业受限项目台账的持久化端口（S14-3c，S14-0 P4）。
 *
 * <p>sys_project 上的 {@code ARCHIVED} 同时被「用户主动归档」与「订阅到期商业受限」使用，二者
 * 状态本身不可区分。P4 要求续费/升级可恢复，恢复时只能恢复本片写入的商业受限，因此需要一个
 * 可追溯的台账：本端口是它的唯一读写入口。
 *
 * <p>写入端只有「登记一次受限」与「标记一次恢复」两个动作，没有 DELETE、也没有「改项目」：
 * 受限与恢复都是状态推进，历史行必须能累积。
 */
public interface ProjectCommercialRestrictionRepository {

    /**
     * 登记一条生效中的商业受限（幂等）。
     *
     * <p>幂等仲裁者是部分唯一索引 {@code sys_project_commercial_restriction_active_uk}
     * （每项目至多一条 ACTIVE）+ {@code ON CONFLICT DO NOTHING}，不是应用层「先查后插」。
     *
     * @param tenantId 项目归属租户
     * @param projectId 被限制的项目
     * @param subscriptionId 触发受限的订阅行
     * @param restrictedAt 受限时刻（UTC）
     * @return 本次确实登记了新受限时为 {@code true}；该项目已有生效中记录时为 {@code false}
     */
    boolean recordActive(UUID tenantId, UUID projectId, UUID subscriptionId, Instant restrictedAt);

    /** @return 有未删除商业受限项目的候选租户；恢复前必须持租户锁复验 */
    List<UUID> findPendingTenantIds();

    /** @param tenantId 真实租户 @return 锁下重读该租户仍ARCHIVED且未删除的生效限制 */
    List<ProjectCommercialRestriction> findActiveByTenant(UUID tenantId);

    /**
     * 把一条生效中的受限记录标记为已恢复。
     *
     * <p>{@code WHERE status = 'ACTIVE'} 让本语句自身即幂等仲裁：重复标记更新不到行。
     *
     * @param restrictionId 台账行 ID
     * @param liftedAt 恢复时刻（UTC）
     * @return 本次确实标记了恢复时为 {@code true}
     */
    boolean markLifted(UUID restrictionId, Instant liftedAt);
}
