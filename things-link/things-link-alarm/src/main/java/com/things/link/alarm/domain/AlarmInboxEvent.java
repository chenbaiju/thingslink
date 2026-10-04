package com.things.link.alarm.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * ADR0093：显式标记前核对的ACTIVATED事件最小事实，不暴露报文或投递信息。
 * @param id 事件身份
 * @param receivedAt 平台接收时刻，用于请求窗口核对
 */
public record AlarmInboxEvent(UUID id, Instant receivedAt) {
}
