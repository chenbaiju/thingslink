package com.things.link.device.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Modbus 点位映射（S10-4a，ADR 0032/0034）。
 *
 * <p>一个点位把「从站 + 功能码 + 寄存器地址」的 Modbus 原始值，经缩放/偏移变换后，写到某个具体
 * 子设备的物模型属性。点位挂在<b>网关设备</b>上：写端口校验该网关类型为 {@code STANDARD_GATEWAY} 或
 * {@code MODBUS_RTU_CLOUD_GATEWAY}（执行位置由 payloadProtocol 唯一推导，见 ADR 0034）。</p>
 *
 * @param id 点位 ID
 * @param tenantId 租户 ID
 * @param projectId 项目 ID（RLS 隔离轴）
 * @param deviceId 执行轮询的网关设备 ID
 * @param subDeviceId 绑定的目标子设备 ID
 * @param propertyKey 子设备物模型属性键
 * @param slaveAddress Modbus 从站地址
 * @param functionCode 读功能码
 * @param registerAddress 寄存器起始地址
 * @param dataType 数据类型
 * @param byteOrder 多字节字节序
 * @param scale 缩放系数（可空）
 * @param offset 偏移量（可空）
 * @param pollingIntervalMs 轮询周期（毫秒）
 * @param version 发布版本号
 * @param status 发布状态
 * @param createdAt 创建时刻
 * @param updatedAt 更新时刻
 */
public record ModbusPointMapping(UUID id, UUID tenantId, UUID projectId, UUID deviceId, UUID subDeviceId,
                                 String propertyKey, int slaveAddress, FunctionCode functionCode,
                                 int registerAddress, DataType dataType, ByteOrder byteOrder,
                                 BigDecimal scale, BigDecimal offset, int pollingIntervalMs,
                                 int version, Status status, Instant createdAt, Instant updatedAt) {

    /** 读功能码；点位只读，不支持写功能码。 */
    public enum FunctionCode {
        /** 读线圈（位）。 */ READ_COILS,
        /** 读离散输入（位）。 */ READ_DISCRETE_INPUTS,
        /** 读保持寄存器（16 位）。 */ READ_HOLDING_REGISTERS,
        /** 读输入寄存器（16 位）。 */ READ_INPUT_REGISTERS
    }

    /** 点位数据类型。 */
    public enum DataType {
        /** 位。 */ BIT(1),
        /** 16 位有符号整数。 */ INT16(1),
        /** 16 位无符号整数。 */ UINT16(1),
        /** 32 位有符号整数。 */ INT32(2),
        /** 32 位无符号整数。 */ UINT32(2),
        /** 32 位浮点。 */ FLOAT32(2);

        /** 占用的寄存器（或位单元）数，用于重叠校验。 */
        private final int width;

        DataType(int width) { this.width = width; }

        /** @return 占用的寄存器（或位单元）数 */
        public int width() { return width; }
    }

    /** 多字节字节序。 */
    public enum ByteOrder { /** 大端。 */ BIG_ENDIAN, /** 小端。 */ LITTLE_ENDIAN }

    /** 发布状态。 */
    public enum Status { /** 可编辑。 */ DRAFT, /** 已冻结。 */ PUBLISHED }
}
