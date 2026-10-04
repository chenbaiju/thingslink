package com.things.link.dashboard.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 看板草稿、发布指针和已分配版本号三条轴的一致观察。
 *
 * <p>该状态不是发布资格或授权凭据。发布服务仍须在同一事务锁定看板、比较草稿与发布双revision，
 * 并完成外部模型、宿主组件和内置资源验证。</p>
 *
 * @param dashboardId 看板ID
 * @param tenantId 项目所有者租户ID
 * @param projectId 看板所属项目ID
 * @param draftRevision 当前草稿revision
 * @param publicationRevision 当前发布状态revision
 * @param currentVersionId 当前发布版本；未发布或撤回时为空
 * @param latestVersionNumber 已分配的最大看板版本号；尚无版本时为0
 * @param deletedAt 看板软删除时刻；未删除时为空
 */
public record DashboardPublicationState(
        UUID dashboardId,
        UUID tenantId,
        UUID projectId,
        long draftRevision,
        long publicationRevision,
        UUID currentVersionId,
        long latestVersionNumber,
        Instant deletedAt) {

    /** 校验三个版本轴的持久最小一致性。 */
    public DashboardPublicationState {
        Objects.requireNonNull(dashboardId, "dashboardId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        if (draftRevision < 0) {
            throw new IllegalArgumentException("draftRevision不得为负数");
        }
        if (publicationRevision < 0) {
            throw new IllegalArgumentException("publicationRevision不得为负数");
        }
        if (latestVersionNumber < 0) {
            throw new IllegalArgumentException("latestVersionNumber不得为负数");
        }
        if (currentVersionId != null && (publicationRevision == 0 || latestVersionNumber == 0)) {
            throw new IllegalArgumentException("当前发布版本存在时发布revision和最大版本号必须为正数");
        }
    }
}
