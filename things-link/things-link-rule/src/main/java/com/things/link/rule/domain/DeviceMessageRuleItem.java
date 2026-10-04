package com.things.link.rule.domain;
import java.time.Instant;
import java.util.UUID;
/** 设备消息规则白名单，不含脚本、输入或责任身份。 */
public record DeviceMessageRuleItem(UUID id, String name, String status, UUID activeVersionId, long versionNumber, String relationScope, boolean hasDeviceAction, Instant createdAt) { }
