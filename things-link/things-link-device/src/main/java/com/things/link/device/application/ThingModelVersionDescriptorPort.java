package com.things.link.device.application;

import java.util.Optional;
import java.util.UUID;

/**
 * 供其他业务域核验精确物模型版本引用的公开只读application端口。
 *
 * <p>端口按projectId和versionId同时限定查询，使不存在与错项目统一为空；实现仍受数据库RLS约束。
 * 它只公开不可变摘要元数据，不允许调用方绕过device领域直接读取{@code dev_*}表。</p>
 */
public interface ThingModelVersionDescriptorPort {

    /**
     * 按可信项目范围读取精确不可变模型版本描述。
     *
     * @param projectId 已由调用方完成自身授权的项目ID
     * @param versionId Schema声明的精确物模型版本ID
     * @return 同项目版本描述；不存在、错项目或RLS不可见时为空
     */
    Optional<ThingModelVersionDescriptor> find(UUID projectId, UUID versionId);
}
