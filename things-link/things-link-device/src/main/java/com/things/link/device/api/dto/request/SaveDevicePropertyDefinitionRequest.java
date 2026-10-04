package com.things.link.device.api.dto.request;

import com.things.link.device.domain.DevicePropertyDefinition;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * 创建或修改属性定义请求；数据类型专属字段由统一校验器清理并校验。
 * @param propertyKey 消息键 @param name 名称 @param accessType 流向 @param dataType 数据类型
 * @param unit 单位 @param decimalPlaces 精度 @param minimumValue 最小值 @param maximumValue 最大值
 * @param enumOptions 枚举值 @param onLabel 开启文字 @param offLabel 关闭文字
 * @param schema OBJECT/LIST 的 TC_PROPERTY_COMPOSITE_V1 Schema @param sortOrder 排序
 */
public record SaveDevicePropertyDefinitionRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_-]+") String propertyKey,
        @NotBlank @Size(max = 128) String name,
        @NotNull DevicePropertyDefinition.AccessType accessType,
        @NotNull DevicePropertyDefinition.DataType dataType,
        @Size(max = 32) String unit,
        @Min(0) @Max(10) Integer decimalPlaces,
        BigDecimal minimumValue,
        BigDecimal maximumValue,
        @Size(max = 100) List<@NotBlank @Size(max = 64) String> enumOptions,
        @Size(max = 32) String onLabel,
        @Size(max = 32) String offLabel,
        @Size(max = 65536) String schema,
        @Min(0) int sortOrder) { }
