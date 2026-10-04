package com.things.link.device.api.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 更新 desired 的请求。调用方必须先读取影子获取当前 version。
 *
 * @param desired JSON 格式的期望属性值 @param version 当前影子版本
 */
public record UpdateDesiredRequest(
        @NotBlank @Size(max = 65536) String desired,
        @Min(0) int version) { }
