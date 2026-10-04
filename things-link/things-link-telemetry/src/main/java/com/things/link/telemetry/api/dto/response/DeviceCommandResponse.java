package com.things.link.telemetry.api.dto.response;

import com.things.link.telemetry.application.DeviceCommandService.DeviceCommandDetails;
import com.things.link.telemetry.domain.DeviceCommand;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 命令事实与尝试响应，所有时间由 Jackson 输出 RFC3339 UTC。 */
public record DeviceCommandResponse(UUID id, UUID deviceId, UUID connectionDeviceId, String commandKey,
                                    JsonNode input, JsonNode output, String status,
                                    int attemptCount, int maxAttempts, Integer timeoutSeconds,
                                    String failureCode, String failureMessage, Instant acceptedAt,
                                    Instant dispatchedAt, Instant acknowledgedAt, Instant completedAt,
                                    List<DeviceCommandAttemptResponse> attempts) {
    /** 创建提交响应；首次受理时尝试列表不必额外查询。 */
    public static DeviceCommandResponse from(DeviceCommand value, ObjectMapper mapper) {
        return from(value, List.of(), mapper);
    }
    /** 创建带尝试明细的查询响应。 */
    public static DeviceCommandResponse from(DeviceCommandDetails details, ObjectMapper mapper) {
        return from(details.command(), details.attempts().stream()
                .map(DeviceCommandAttemptResponse::from).toList(), mapper);
    }
    /** 统一 JSON 反序列化，数据库内容由本应用写入，失败表示内部数据损坏。 */
    private static DeviceCommandResponse from(DeviceCommand value,
                                               List<DeviceCommandAttemptResponse> attempts,
                                               ObjectMapper mapper) {
        return new DeviceCommandResponse(value.id(), value.targetDeviceId(), value.connectionDeviceId(),
                value.commandKey(), mapper.readTree(value.requestJson()),
                value.responseJson() == null ? null : mapper.readTree(value.responseJson()), value.status().name(),
                value.attemptCount(), value.maxAttempts(), value.timeoutSeconds(), value.failureCode(),
                value.failureMessage(), value.acceptedAt(), value.dispatchedAt(), value.acknowledgedAt(),
                value.completedAt(), attempts);
    }
}
