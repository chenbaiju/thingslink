package com.things.link.rule.domain;
import java.time.Instant;
import java.util.UUID;
/** 设备场景只读投影，不含输入、配置或责任身份。 */
public record DeviceSceneExecutionItem(UUID id, UUID sceneId, UUID sceneVersionId, String status, long deviceActionRecordCount, Instant occurredAt, Instant createdAt, Instant completedAt) { }
