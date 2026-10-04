package com.things.link.dashboard.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 发布事务需要一次读取的应用状态快照。
 *
 * <p>该快照并非授权凭据，也不代表发布资格已经成立。后续发布服务仍须在同一事务锁定应用、
 * 比较草稿与发布双revision，并验证Schema、组件、资源及精确Dashboard版本引用。</p>
 *
 * @param applicationId 应用ID
 * @param tenantId 项目所有者租户ID
 * @param projectId 应用所属项目ID
 * @param draftRevision 当前草稿revision
 * @param publicationRevision 当前发布状态revision
 * @param currentVersionId 当前发布版本；未发布或撤回时为空
 * @param latestVersionNumber 已分配的最大应用版本号；尚无版本时为0
 * @param deletedAt 应用软删除时刻；未删除时为空
 */
public record ApplicationPublicationState(
        UUID applicationId,
        UUID tenantId,
        UUID projectId,
        long draftRevision,
        long publicationRevision,
        UUID currentVersionId,
        long latestVersionNumber,
        Instant deletedAt) {

    /**
     * 校验状态快照各版本轴的最小一致性，不把当前指针相等误当作完整CAS资格。
     */
    public ApplicationPublicationState {
        Objects.requireNonNull(applicationId, "applicationId");
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
