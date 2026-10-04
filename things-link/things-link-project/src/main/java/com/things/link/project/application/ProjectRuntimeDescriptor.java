package com.things.link.project.application;

import java.util.Objects;
import java.util.UUID;

/**
 * App运行入口跨领域编排所需的最小项目描述。
 *
 * <p>S12-2a2a只允许project域向enduser编排公开可信归属与稳定{@code projectKey}；本值不包含
 * 项目名称、区域、成员或角色，也不是运行访问授权。消费方仍须在每个受保护请求重新核验用户与grant。</p>
 *
 * @param tenantId 项目权威归属租户
 * @param projectId 项目身份
 * @param projectKey 对外稳定项目键
 */
public record ProjectRuntimeDescriptor(UUID tenantId, UUID projectId, String projectKey) {

    /** 权威投影缺字段属于服务端完整性故障，不能降格为应用运行入口不可见。 */
    public ProjectRuntimeDescriptor {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(projectKey, "projectKey");
        if (projectKey.isBlank()) {
            throw new IllegalArgumentException("项目运行描述的projectKey不能为空");
        }
    }
}
