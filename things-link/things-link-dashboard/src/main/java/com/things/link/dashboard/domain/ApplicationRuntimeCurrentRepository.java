package com.things.link.dashboard.domain;

import java.util.Optional;
import java.util.UUID;

/** 当前应用运行描述的普通项目RLS持久端口。 */
public interface ApplicationRuntimeCurrentRepository {

    /**
     * 用单次有界观察读取当前应用、不可变版本及其精确看板引用。
     *
     * @param tenantId 已建立事务局部RLS的租户ID
     * @param projectId 已建立事务局部RLS的项目ID
     * @param appKey 规范公开定位符
     * @return 应用不存在、软删、未发布或已撤回时为空；持久事实损坏时抛异常
     */
    Optional<CurrentApplicationRuntimeProjection> findCurrent(UUID tenantId, UUID projectId, String appKey);
}
