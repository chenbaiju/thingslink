package com.things.link.rule.domain;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * 只追加的消息规则脚本版本事实。
 *
 * @param id 版本 ID
 * @param tenantId 项目所有者租户 ID
 * @param projectId 项目隔离 ID
 * @param ruleId 所属规则 ID
 * @param versionNumber 规则内单调版本号
 * @param source JavaScript 函数源码
 * @param sourceSha256 源码 UTF-8 SHA-256
 * @param actions 与源码同属一条不可变版本事实的动作配置 JSON 数组
 * @param createdBy 创建账号
 * @param createdAt 创建时刻
 */
public record MessageRuleVersion(
        UUID id,
        UUID tenantId,
        UUID projectId,
        UUID ruleId,
        long versionNumber,
        String source,
        String sourceSha256,
        JsonNode actions,
        UUID createdBy,
        Instant createdAt) {

    /** 防御性复制动作 JSON，禁止调用方在版本事实落库后继续修改；缺省为空数组。 */
    public MessageRuleVersion {
        actions = actions == null ? new ObjectMapper().createArrayNode() : actions.deepCopy();
    }

    /** 每次读取返回副本，保持版本事实不可变。 */
    @Override
    public JsonNode actions() {
        return actions.deepCopy();
    }

    /** 无动作版本兼容构造，冻结空动作数组。 */
    public MessageRuleVersion(
            UUID id, UUID tenantId, UUID projectId, UUID ruleId, long versionNumber,
            String source, String sourceSha256, UUID createdBy, Instant createdAt) {
        this(id, tenantId, projectId, ruleId, versionNumber, source, sourceSha256,
                new ObjectMapper().createArrayNode(), createdBy, createdAt);
    }
}
