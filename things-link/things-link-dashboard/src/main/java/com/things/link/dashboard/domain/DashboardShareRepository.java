package com.things.link.dashboard.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** ADR0101分享签发、只读管理与零变化撤销的本域持久端口。 */
public interface DashboardShareRepository {
    /** @return 当前物理连接的clock_timestamp，必须在项目和Dashboard锁后读取 */
    Instant databaseNow();
    /** @param tenantId 租户 @param projectId 项目 @param dashboardId 看板 @param accountId 操作者
     * @param idempotencyKeyDigest 幂等摘要 @return 同一主体与键的已创建结果，永不含secret */
    Optional<DashboardShareCreationResult> findCreationResult(UUID tenantId, UUID projectId, UUID dashboardId,
            UUID accountId, String idempotencyKeyDigest);
    /** @param projectId 项目 @param dashboardId 看板 @param now 锁后DB时刻 @return 未撤销未到期数量 */
    long countActive(UUID projectId, UUID dashboardId, Instant now);
    /** @param token 完整分享事实 @param scopes 有界变量候选 @param creation 原事务创建仲裁
     * @throws IllegalStateException 未加入非只读事务，或各事实身份不一致 */
    void create(DashboardShareToken token, List<DashboardShareVariableScope> scopes, DashboardShareCreationResult creation);
    /** @param projectId 项目 @param dashboardId 看板 @param shareId 分享 @return 精确身份事实 */
    Optional<DashboardShareToken> find(UUID projectId, UUID dashboardId, UUID shareId);
    /** @param projectId 项目 @param dashboardId 看板 @param beforeAt 可空游标时刻
     * @param beforeId 可空游标ID @param fetchLimit 1..51行（管理页limit+1） @return DB时间派生状态的倒序页 */
    List<DashboardShareSummary> page(UUID projectId, UUID dashboardId, Instant beforeAt, UUID beforeId, int fetchLimit);
    /** @param projectId 项目 @param dashboardId 看板 @param shareId 分享 @param accountId 当前撤销账号
     * @return 首次撤销返回完整事实；已撤销或未命中返回空且零写 */
    Optional<DashboardShareToken> revoke(UUID projectId, UUID dashboardId, UUID shareId, UUID accountId);
}
