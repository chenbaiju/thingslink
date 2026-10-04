package com.things.link.rule.domain;
import java.time.Instant;
import java.util.UUID;
/** 设备消息规则白名单，不含脚本、输入或责任身份。 */
public record DeviceMessageRuleExecutionItem(UUID id, UUID ruleId, UUID ruleVersionId, UUID messageId, int attempt, String status, String resultCode, long durationMillis, int inputBytes, int outputBytes, Instant createdAt) { }
