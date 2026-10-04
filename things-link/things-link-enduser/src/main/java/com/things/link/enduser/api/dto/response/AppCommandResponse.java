package com.things.link.enduser.api.dto.response;

import com.things.link.telemetry.application.AppCommandResult;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

/**
 * App 命令结果响应。
 *
 * @param commandId      稳定 commandId
 * @param status         命令状态
 * @param commandKey     物模型命令键
 * @param response       设备成功回复的输出对象；尚未回复时为 null
 * @param acceptedAt     受理时刻（RFC3339 UTC）
 * @param failureCode    失败或超时的稳定诊断码
 * @param failureMessage 失败诊断摘要
 */
@Schema(description = "App 命令结果")
public record AppCommandResponse(
        @Schema(description = "命令 ID") String commandId,
        @Schema(description = "命令状态", example = "ACCEPTED") String status,
        @Schema(description = "物模型命令键") String commandKey,
        @Schema(description = "设备成功回复的输出对象；尚未回复时为 null") JsonNode response,
        @Schema(description = "受理时刻（RFC3339 UTC）") String acceptedAt,
        @Schema(description = "失败或超时的稳定诊断码") String failureCode,
        @Schema(description = "失败诊断摘要") String failureMessage) {

    /** @param result telemetry 模块数据面投影 @return App 响应 */
    public static AppCommandResponse from(AppCommandResult result) {
        return new AppCommandResponse(
                result.commandId().toString(),
                result.status(),
                result.commandKey(),
                result.response(),
                result.acceptedAt() == null ? null : result.acceptedAt().toString(),
                result.failureCode(),
                result.failureMessage());
    }
}
