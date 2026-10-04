package com.things.link.rule.domain;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * 只追加的自动化条件与动作版本事实。
 *
 * @param id 版本 ID
 * @param tenantId 项目所有者租户 ID
 * @param projectId 项目隔离 ID
 * @param automationId 所属自动化 ID
 * @param versionNumber 自动化内单调版本号
 * @param conditions 有序条件节点数组 {@code [{"nodeType","config"}]}，仅白名单纯计算节点
 * @param actions 有序动作节点数组 {@code [{"nodeType","config"}]}，随版本冻结
 * @param createdBy 创建账号
 * @param createdAt 创建时刻
 */
public record AutomationVersion(
        UUID id,
        UUID tenantId,
        UUID projectId,
        UUID automationId,
        long versionNumber,
        String triggerType,
        JsonNode triggerConfig,
        JsonNode conditions,
        JsonNode actions,
        UUID createdBy,
        Instant createdAt) {

    /** 防御性复制条件与动作 JSON，禁止调用方在版本事实落库后继续修改；缺省为空数组。 */
    public AutomationVersion {
        ObjectMapper mapper = new ObjectMapper();
        triggerConfig = java.util.Objects.requireNonNull(triggerConfig).deepCopy();
        conditions = conditions == null ? mapper.createArrayNode() : conditions.deepCopy();
        actions = actions == null ? mapper.createArrayNode() : actions.deepCopy();
    }

    @Override public JsonNode triggerConfig() { return triggerConfig.deepCopy(); }

    /** 每次读取返回副本，保持版本事实不可变。 */
    @Override
    public JsonNode conditions() {
        return conditions.deepCopy();
    }

    /** 每次读取返回副本，保持版本事实不可变。 */
    @Override
    public JsonNode actions() {
        return actions.deepCopy();
    }
}
