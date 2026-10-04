package com.things.link.device.api.dto.response;

import com.things.link.device.domain.ModbusPointMapping;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Modbus 点位映射响应。 */
public record ModbusPointMappingResponse(UUID id, UUID deviceId, UUID subDeviceId, String propertyKey,
                                        int slaveAddress, ModbusPointMapping.FunctionCode functionCode,
                                        int registerAddress, ModbusPointMapping.DataType dataType,
                                        ModbusPointMapping.ByteOrder byteOrder, BigDecimal scale, BigDecimal offset,
                                        int pollingIntervalMs, int version, ModbusPointMapping.Status status,
                                        Instant createdAt, Instant updatedAt) {
    /** @param point 领域对象 @return API 响应 */
    public static ModbusPointMappingResponse from(ModbusPointMapping point) {
        return new ModbusPointMappingResponse(point.id(), point.deviceId(), point.subDeviceId(), point.propertyKey(),
                point.slaveAddress(), point.functionCode(), point.registerAddress(), point.dataType(),
                point.byteOrder(), point.scale(), point.offset(), point.pollingIntervalMs(), point.version(),
                point.status(), point.createdAt(), point.updatedAt());
    }
}
