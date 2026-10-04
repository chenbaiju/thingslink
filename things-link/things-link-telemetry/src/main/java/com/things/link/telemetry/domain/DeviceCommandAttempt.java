package com.things.link.telemetry.domain;

import java.time.Instant;
import java.util.UUID;

/** 单次命令派发尝试。 */
public record DeviceCommandAttempt(UUID id, UUID tenantId, UUID projectId, UUID commandId,
                                   int attemptNo, UUID outboxEventId, UUID connectionDeviceId,
                                   String topic, Status status, Instant deadlineAt, Instant createdAt) {
    /** 单次尝试状态。 */
    public enum Status { /** 等待 Outbox。 */ PENDING, /** EMQX 已接受。 */ PUBLISHED,
        /** 设备 ACK。 */ ACKNOWLEDGED, /** 设备成功。 */ SUCCEEDED,
        /** 本次派发、平台冻结拒绝或设备执行失败；派发失败用 {@code error_code} 细分（DISPATCH_*），设备失败用设备侧码。 */ FAILED,
        /** 响应超时（仅进入设备响应窗口后超时；派发失败不标 TIMED_OUT）。 */ TIMED_OUT }
}
