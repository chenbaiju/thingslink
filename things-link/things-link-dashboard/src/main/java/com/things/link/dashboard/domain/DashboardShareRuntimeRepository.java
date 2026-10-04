package com.things.link.dashboard.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** ADR0101受限bootstrap定位与后续普通RLS事实读取，参数不接受匿名自报租户。 */
public interface DashboardShareRuntimeRepository {
    /** @param shareId 分享选择器 @param secretHash 规范secret的SHA-256 @return 仅可信tenant/project */
    Optional<DashboardShareRuntimeIdentity> locate(UUID shareId, String secretHash);
    /** @param tenantId 已由定位确立租户 @param projectId 同一可信项目 @param shareId 固定分享
     * @param secretHash 原凭据摘要 @return 每请求同语句重新读取的封存事实与资源状态 */
    Optional<DashboardShareRuntimeState> findState(UUID tenantId, UUID projectId, UUID shareId, String secretHash);
    /** @param tenantId 可信租户 @param projectId 可信项目 @param shareId 分享 @return 最多20变量及有界设备候选 */
    List<DashboardShareVariableScope> findScopes(UUID tenantId, UUID projectId, UUID shareId);
}
