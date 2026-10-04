package com.things.link.rule.domain;
import java.time.Instant;
import java.util.UUID;
/** 设备消息规则白名单，不含脚本、输入或责任身份。 */
public record DeviceMessageRuleActionItem(UUID id, UUID ruleId, UUID ruleVersionId, UUID messageId, String operationType, String status, UUID commandId, String failureCode, Instant createdAt, Instant completedAt) { }
