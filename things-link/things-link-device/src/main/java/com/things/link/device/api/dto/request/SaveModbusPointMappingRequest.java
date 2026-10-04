package com.things.link.device.api.dto.request;

import com.things.link.device.domain.ModbusPointMapping;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 创建或修改 Modbus 点位映射请求。
 *
 * @param subDeviceId 目标子设备 ID
 * @param propertyKey 子设备物模型属性键
 * @param slaveAddress 从站地址
 * @param functionCode 读功能码
 * @param registerAddress 寄存器起始地址
 * @param dataType 数据类型
 * @param byteOrder 字节序
 * @param scale 缩放系数
 * @param offset 偏移量
 * @param pollingIntervalMs 轮询周期
 */
public record SaveModbusPointMappingRequest(
        @NotNull UUID subDeviceId,
        @NotBlank @Size(max = 64) String propertyKey,
        @Min(1) @Max(247) int slaveAddress,
        @NotNull ModbusPointMapping.FunctionCode functionCode,
        @Min(0) int registerAddress,
        @NotNull ModbusPointMapping.DataType dataType,
        @NotNull ModbusPointMapping.ByteOrder byteOrder,
        BigDecimal scale,
        BigDecimal offset,
        @Min(1) int pollingIntervalMs) { }
