package com.things.link.enduser.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * App 下发命令请求。
 *
 * @param commandKey 物模型命令标识
 * @param input      命令参数对象
 */
@Schema(description = "App 下发命令请求")
public record AppSubmitCommandRequest(
        @Schema(description = "物模型命令标识", example = "restart")
        @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String commandKey,
        @Schema(description = "命令参数对象")
        @NotNull JsonNode input) {
}
