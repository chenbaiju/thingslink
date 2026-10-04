package com.things.link.dashboard.application;

import java.util.Objects;
import java.util.UUID;

/**
 * dashboard向运行编排层公开的最小应用定位身份。
 *
 * <p>该值来自受限数据库函数，只能用于建立随后普通RLS查询所需的事务范围；它本身不证明项目可读、
 * 应用仍发布或调用者已经获得数据访问授权。</p>
 *
 * @param tenantId 应用所属项目的租户ID
 * @param projectId 应用所属项目ID
 * @param applicationId 应用内部ID
 */
public record ApplicationRuntimeIdentity(UUID tenantId, UUID projectId, UUID applicationId) {

    /** 防止跨模块编排用残缺身份建立数据库隔离范围。 */
    public ApplicationRuntimeIdentity {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(applicationId, "applicationId");
    }
}
