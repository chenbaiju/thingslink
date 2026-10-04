package com.things.link.rule.application;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * application 层不可变版本投影。
 *
 * @param id 版本 ID
 * @param versionNumber 场景内单调版本号
 * @param conditions 有序条件节点数组
 * @param actions 有序动作节点数组
 * @param createdBy 创建账号
 * @param createdAt 创建时刻
 */
public record RuleSceneVersionView(
        UUID id,
        long versionNumber,
        JsonNode conditions,
        JsonNode actions,
        UUID createdBy,
        Instant createdAt) {
}
