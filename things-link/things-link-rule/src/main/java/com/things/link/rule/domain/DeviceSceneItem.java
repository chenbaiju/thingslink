package com.things.link.rule.domain;
import java.time.Instant;
import java.util.UUID;
/** 设备场景只读投影，不含输入、配置或责任身份。 */
public record DeviceSceneItem(UUID id, String name, String status, UUID activeVersionId, long versionNumber, String relationScope, boolean usesConditionInput, boolean hasDeviceAction, Instant createdAt) { }
