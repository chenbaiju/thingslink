package com.things.link.rule.application;

import java.time.Instant;
import java.util.UUID;

/**
 * 无 UI 应用服务返回的不可变脚本版本投影。
 *
 * @param id 版本 ID
 * @param versionNumber 展示版本号
 * @param source JavaScript 函数源码
 * @param sourceSha256 源码摘要
 * @param createdBy 创建账号
 * @param createdAt 创建时刻
 * @param actions 完整不可变动作配置
 */
public record MessageRuleVersionView(
        UUID id,
        long versionNumber,
        String source,
        String sourceSha256,
        UUID createdBy,
        Instant createdAt, tools.jackson.databind.JsonNode actions) {
}
