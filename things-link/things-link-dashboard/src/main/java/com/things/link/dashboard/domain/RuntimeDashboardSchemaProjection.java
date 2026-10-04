package com.things.link.dashboard.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 单条普通RLS查询恢复的精确Schema内部投影。
 *
 * @param tenantId 应用所属租户ID
 * @param projectId 应用所属项目ID
 * @param applicationId 稳定应用ID
 * @param appKey 应用公开键
 * @param applicationVersionId 当前不可变应用版本ID
 * @param publicationRevision 当前应用发布代次
 * @param dashboardRunnable 稳定看板目录未软删且存在当前发布指针
 * @param dashboardVersion 精确不可变看板版本及其同域关系
 * @param navigationPages 应用快照封存的目标看板页面导航
 * @param schemaUtf8Bytes PostgreSQL规范Schema文本的UTF-8字节数
 */
public record RuntimeDashboardSchemaProjection(
        UUID tenantId,
        UUID projectId,
        UUID applicationId,
        String appKey,
        UUID applicationVersionId,
        long publicationRevision,
        boolean dashboardRunnable,
        DashboardVersion dashboardVersion,
        List<CurrentApplicationRuntimeProjection.Page> navigationPages,
        int schemaUtf8Bytes) {

    /** 内部投影必须保留完整身份与精确版本，运行状态只决定最终Optional。 */
    public RuntimeDashboardSchemaProjection {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(appKey, "appKey");
        Objects.requireNonNull(applicationVersionId, "applicationVersionId");
        Objects.requireNonNull(dashboardVersion, "dashboardVersion");
        navigationPages = List.copyOf(navigationPages);
    }
}
