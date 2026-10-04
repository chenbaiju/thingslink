package com.things.link.device.api.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建或修改命令定义请求。
 *
 * @param commandKey 命令标识符
 * @param name 中文名称
 * @param description 用途说明
 * @param inputSchema JSON Schema 输入参数定义
 * @param outputSchema JSON Schema 输出/响应参数定义
 * @param timeoutSeconds 命令超时秒数
 * @param sortOrder 排序
 */
public record SaveDeviceCommandDefinitionRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_-]+") String commandKey,
        @NotBlank @Size(max = 128) String name,
        @Size(max = 500) String description,
        @Size(max = 65536) String inputSchema,
        @Size(max = 65536) String outputSchema,
        @Min(1) @Max(86400) int timeoutSeconds,
        @Min(0) int sortOrder) {
}
