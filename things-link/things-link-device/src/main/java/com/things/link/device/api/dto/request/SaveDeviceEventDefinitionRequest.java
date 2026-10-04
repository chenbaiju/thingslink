package com.things.link.device.api.dto.request;

import com.things.link.device.domain.DeviceEventDefinition;
import com.things.link.device.domain.DevicePropertyDefinition;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建或修改事件定义请求。
 * @param eventKey method 标识符 @param name 名称 @param level 级别 @param description 说明
 * @param sortOrder 排序 @param parameters 参数 Schema
 */
public record SaveDeviceEventDefinitionRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_-]+") String eventKey,
        @NotBlank @Size(max = 128) String name,
        @NotNull DeviceEventDefinition.Level level,
        @Size(max = 500) String description,
        @Min(0) int sortOrder,
        @Size(max = 100) List<@Valid ParameterRequest> parameters) {
    /**
     * 单个事件参数请求。
     * @param parameterKey 参数键 @param name 名称 @param dataType 类型 @param required 是否必填
     * @param enumOptions 枚举选项 @param sortOrder 排序
     */
    public record ParameterRequest(
            @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_-]+") String parameterKey,
            @NotBlank @Size(max = 128) String name,
            @NotNull DevicePropertyDefinition.DataType dataType,
            boolean required,
            @Size(max = 100) List<@NotBlank @Size(max = 64) String> enumOptions,
            @Min(0) int sortOrder) {
        /** @return 应用服务参数草稿 */
        public DeviceEventDefinition.ParameterDraft toDraft() {
            return new DeviceEventDefinition.ParameterDraft(parameterKey, name, dataType, required,
                    enumOptions, sortOrder);
        }
    }
}
