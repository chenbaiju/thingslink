package com.things.link.device.api.dto.request;

import com.things.link.device.domain.DeviceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 修改设备类型基础信息请求；S2-6 发布后同一接口会由服务层拒绝，不能仅依赖前端禁用。
 * @param typeKey 项目内稳定标识符
 * @param name 显示名称
 * @param deviceKind 设备分类
 * @param payloadProtocol 报文协议
 * @param networkType 通信方式
 */
public record UpdateDeviceTypeRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "[a-z][a-z0-9_]*") String typeKey,
        @NotBlank @Size(max = 128) String name,
        @NotNull DeviceType.DeviceKind deviceKind,
        @NotNull DeviceType.PayloadProtocol payloadProtocol,
        @NotNull DeviceType.NetworkType networkType) { }
