package com.things.link.rule.api.dto.response;

import com.things.link.rule.domain.DeviceActionDeliverySummary;

import java.time.Instant;
import java.util.UUID;

/**
 * 场景执行详情里的一条设备命令/属性设置投递状态摘要响应。
 *
 * @param deviceId 目标设备 ID
 * @param operationType COMMAND 或 PROPERTY_SET
 * @param commandId telemetry 设备操作稳定 ID，受理前拒绝为空
 * @param status 设备动作投递状态
 * @param failureCode 受理拒绝或设备终态的稳定失败码
 * @param createdAt 动作受理 UTC 时刻
 * @param completedAt 永久拒绝或设备终态 UTC 时刻
 */
public record DeviceActionDeliverySummaryResponse(
        UUID deviceId,
        String operationType,
        UUID commandId,
        String status,
        String failureCode,
        Instant createdAt,
        Instant completedAt) {

    /** @return 领域投递摘要到 API 响应 */
    public static DeviceActionDeliverySummaryResponse from(DeviceActionDeliverySummary summary) {
        return new DeviceActionDeliverySummaryResponse(
                summary.deviceId(), summary.operationType(), summary.commandId(), summary.status(),
                summary.failureCode(), summary.createdAt(), summary.completedAt());
    }
}
