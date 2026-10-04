package com.things.link.device.api.dto.request;

import com.things.link.device.domain.DeviceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建设备类型请求。
 *
 * <p>G1-C1b 起删除 `createDefaultDataStream`/`defaultDataStreamFormat`：自定义数据流 V1 控制面已下线
 * （PS-039 TECHNICAL_DEFERRED），设备类型创建不得再写出永不被消费的数据流行。旧客户端继续发送这两个字段时按
 * ADR 0042 忽略（成功、不回显、不落库）。</p>
 *
 * @param typeKey 稳定标识符，只允许小写字母、数字和下划线
 * @param name 显示名称
 * @param deviceKind 设备分类
 * @param payloadProtocol 报文协议
 * @param networkType 通信方式
 */
public record CreateDeviceTypeRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "[a-z][a-z0-9_]*") String typeKey,
        @NotBlank @Size(max = 128) String name,
        @NotNull DeviceType.DeviceKind deviceKind,
        @NotNull DeviceType.PayloadProtocol payloadProtocol,
        @NotNull DeviceType.NetworkType networkType) { }
