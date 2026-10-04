package com.things.link.dashboard.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 看板目录的稳定持久事实。
 *
 * <p>目录名称服务于Console管理，画布展示内容只存在于草稿和不可变版本中。S12-1b2a仅映射
 * 数据库事实，不在领域记录内推导发布资格、运行授权或分享能力。</p>
 *
 * @param id 看板稳定ID
 * @param tenantId 项目所有者租户ID
 * @param projectId 看板所属项目ID
 * @param managementName Console目录管理名称
 * @param publicationRevision 发布状态CAS修订号
 * @param currentVersionId 当前发布版本；尚未发布或撤回时为空
 * @param createdBy 创建看板的Console账号ID
 * @param updatedBy 最近更新目录或发布状态的Console账号ID
 * @param createdAt 创建时刻
 * @param updatedAt 最近更新时刻
 * @param deletedAt 软删除时刻；未删除时为空
 */
public record DashboardCatalogEntry(
        UUID id,
        UUID tenantId,
        UUID projectId,
        String managementName,
        long publicationRevision,
        UUID currentVersionId,
        UUID createdBy,
        UUID updatedBy,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt) {

    /** 迁移与管理合同共同冻结的目录名称最大Unicode码点数。 */
    private static final int MAXIMUM_MANAGEMENT_NAME_CODE_POINTS = 80;

    /**
     * 校验目录事实的数据库可表达形状，阻止调用方构造非法发布指针或不可持久名称。
     */
    public DashboardCatalogEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(managementName, "managementName");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(updatedBy, "updatedBy");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        int managementNameLength = managementName.codePointCount(0, managementName.length());
        if (managementNameLength < 1 || managementNameLength > MAXIMUM_MANAGEMENT_NAME_CODE_POINTS
                || managementName.codePoints().allMatch(
                        codePoint -> Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint))
                || managementName.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("managementName必须是1至80码点的非空白纯文本且不得包含控制字符");
        }
        if (publicationRevision < 0) {
            throw new IllegalArgumentException("publicationRevision不得为负数");
        }
        if (currentVersionId != null && publicationRevision == 0) {
            throw new IllegalArgumentException("当前发布版本存在时publicationRevision必须为正数");
        }
    }
}
