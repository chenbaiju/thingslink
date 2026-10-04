package com.things.link.dashboard.domain;

import java.util.Optional;
import java.util.UUID;

/** App运行入口读取单个精确Dashboard Schema的普通RLS持久端口。 */
public interface ApplicationRuntimeSchemaRepository {

    /**
     * 在同一语句观察中复核当前应用上下文、精确关系、看板目录和目标版本。
     *
     * @param tenantId 已建立事务局部RLS的租户ID
     * @param projectId 已建立事务局部RLS的项目ID
     * @param appKey 规范应用公开键
     * @param applicationVersionId 请求绑定的当前应用版本ID
     * @param expectedPublicationRevision 请求绑定的当前发布代次
     * @param dashboardVersionId 请求读取的精确看板版本ID
     * @return 应用上下文或引用不匹配时为空；引用存在但看板不可运行时返回带状态投影
     */
    Optional<RuntimeDashboardSchemaProjection> findSchema(
            UUID tenantId,
            UUID projectId,
            String appKey,
            UUID applicationVersionId,
            long expectedPublicationRevision,
            UUID dashboardVersionId);
}
