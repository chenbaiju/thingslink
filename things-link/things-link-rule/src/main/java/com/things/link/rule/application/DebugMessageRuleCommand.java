package com.things.link.rule.application;

import java.util.UUID;

/**
 * S8-1C 无副作用规则调试命令。
 *
 * @param ruleId 规则定义 ID
 * @param versionId 要执行的不可变版本 ID；禁止用活动指针制造不可复现调试结果
 * @param inputJson 样例 JSON，不进入生产消息链路
 */
public record DebugMessageRuleCommand(UUID ruleId, UUID versionId, String inputJson) {
}
