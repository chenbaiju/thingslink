package com.things.link.telemetry.application;

import com.things.link.telemetry.domain.DeviceCommand;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * App 数据面可见的命令结果投影。
 *
 * <p>从 domain {@link DeviceCommand} 映射而来，状态字符串化，输出载荷解析为 {@link JsonNode}，
 * 不泄露命令派发尝试等内部诊断细节。
 *
 * @param commandId     稳定 commandId
 * @param status        命令状态（ACCEPTED/DISPATCHED/ACKNOWLEDGED/SUCCEEDED/FAILED/TIMED_OUT）
 * @param commandKey    物模型命令键
 * @param response      设备成功回复的输出对象；尚未回复时为 null
 * @param acceptedAt    受理时刻
 * @param failureCode   失败或超时的稳定诊断码
 * @param failureMessage 失败诊断摘要
 */
public record AppCommandResult(UUID commandId, String status, String commandKey, JsonNode response,
                               Instant acceptedAt, String failureCode, String failureMessage) {

    /** @param command 领域命令 @param mapper JSON 映射器 @return App 数据面投影 */
    public static AppCommandResult from(DeviceCommand command, ObjectMapper mapper) {
        return new AppCommandResult(command.id(), command.status().name(), command.commandKey(),
                command.responseJson() == null ? null : mapper.readTree(command.responseJson()),
                command.acceptedAt(), command.failureCode(), command.failureMessage());
    }
}
