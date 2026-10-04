package com.things.link.device.application;

import java.util.Optional;
import java.util.UUID;

/** 提供不可变物模型版本单个顶层属性的最小跨领域只读资格投影。 */
public interface ThingModelPropertyFactsPort {

    /**
     * 在可信项目和版本范围内查询精确属性；不可见或不存在统一为空。
     *
     * @param projectId 已确权项目ID
     * @param versionId 不可变模型版本ID
     * @param propertyKey 精确顶层属性键
     * @return 类型及可选数值量程事实
     */
    Optional<ThingModelPropertyFacts> find(UUID projectId, UUID versionId, String propertyKey);
}
