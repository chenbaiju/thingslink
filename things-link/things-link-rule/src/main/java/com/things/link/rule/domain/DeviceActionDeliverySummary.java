package com.things.link.rule.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 场景执行详情里的一条设备命令/属性设置投递状态摘要。
 *
 * <p>只暴露操作类型、命令 ID 与设备终态，不复制命令/回复正文。</p>
 *
 * @param deviceId 目标设备 ID
 * @param operationType COMMAND 或 PROPERTY_SET
 * @param commandId telemetry 设备操作稳定 ID，受理前拒绝为空
 * @param status ACCEPTED / REJECTED / SUCCEEDED / FAILED / TIMED_OUT
 * @param failureCode 受理拒绝或设备终态的稳定失败码，不含异常正文
 * @param createdAt 动作受理 UTC 时刻
 * @param completedAt 永久拒绝或设备终态 UTC 时刻
 */
public record DeviceActionDeliverySummary(
        UUID deviceId,
        String operationType,
        UUID commandId,
        String status,
        String failureCode,
        Instant createdAt,
        Instant completedAt) {
}
