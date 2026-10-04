package com.things.link.telemetry.api.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import tools.jackson.databind.JsonNode;

/** @param commandKey 物模型命令标识 @param input 命令参数对象 */
public record SubmitDeviceCommandRequest(
        @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String commandKey,
        @NotNull JsonNode input) {
}
