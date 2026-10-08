package com.things.link.telemetry.domain;

import java.time.Instant;
import java.util.UUID;

/** 发生事实低敏读取投影，参数保持数据库十进制JSON原文。 */
public record DeviceEventHistoryItem(UUID messageId, UUID deviceId, UUID deviceTypeId, String eventKey, String level,
                                    UUID thingModelVersionId, String modelVersion, String eligibility,
                                    Instant occurredAt, Instant receivedAt, Instant acceptedAt, String params, boolean paramsRedacted) { }
