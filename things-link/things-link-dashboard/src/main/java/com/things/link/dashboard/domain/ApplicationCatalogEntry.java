package com.things.link.dashboard.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * WebApp应用目录事实。
 *
 * <p>目录只保存稳定定位、Console管理名称、发布指针和删除状态；公开展示名称属于草稿与
 * 不可变版本内容，不能从管理名称推导。S12-1a1b只表达持久事实，不在此类型内执行发布或授权。</p>
 *
 * @param id 应用内部ID
 * @param tenantId 项目所有者租户ID
 * @param projectId 应用所属项目ID
 * @param appKey 创建后不可修改的公开定位符
 * @param managementName Console目录管理名称
 * @param publicationRevision 发布状态CAS修订号
 * @param currentVersionId 当前发布版本；尚未发布或撤回时为空
 * @param createdBy 创建应用的Console账号ID
 * @param updatedBy 最近更新目录或发布状态的Console账号ID
 * @param createdAt 创建时刻
 * @param updatedAt 最近更新时刻
 * @param deletedAt 软删除时刻；未删除时为空
 */
public record ApplicationCatalogEntry(
        UUID id,
        UUID tenantId,
        UUID projectId,
        String appKey,
        String managementName,
        long publicationRevision,
        UUID currentVersionId,
        UUID createdBy,
        UUID updatedBy,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt) {

    /** ADR0096冻结的公开定位符语法；定位符不是凭据。 */
    private static final Pattern APP_KEY = Pattern.compile("^app_[0-9a-f]{32}$");
    /** Console管理名称允许的最大Unicode码点数，与迁移varchar/check约束保持一致。 */
    private static final int MAXIMUM_MANAGEMENT_NAME_CODE_POINTS = 80;

    /**
     * 校验目录事实的本地形状，防止持久适配器之外的调用方构造数据库不可能出现的状态。
     */
    public ApplicationCatalogEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(appKey, "appKey");
        Objects.requireNonNull(managementName, "managementName");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(updatedBy, "updatedBy");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (!APP_KEY.matcher(appKey).matches()) {
            throw new IllegalArgumentException("appKey必须是app_加32位小写十六进制");
        }
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
