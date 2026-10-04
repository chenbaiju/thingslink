package com.things.link.device.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Modbus 平台驱动轮询的运行时状态（S10-4c）。
 *
 * <p>已发布点位的物化副本 + 到期时间/短租约/在途请求。发布时把 {@code MODBUS_RTU_CLOUD_GATEWAY} 的
 * 已发布点位物化到本表；调度器按 {@code next_poll_at} 领取、发送读请求并置 {@code IN_FLIGHT}，响应或
 * 超时后重置为 {@code IDLE} 并推进 {@code next_poll_at}。</p>
 *
 * @param id 轮询行 ID
 * @param tenantId 租户 ID
 * @param projectId 项目 ID
 * @param deviceId 网关设备 ID
 * @param subDeviceId 子设备 ID
 * @param projectKey MQTT 项目键
 * @param gatewayKey MQTT 网关设备键
 * @param propertyKey 子设备属性键
 * @param slaveAddress 从站地址
 * @param functionCode 功能码
 * @param registerAddress 寄存器地址
 * @param dataType 数据类型
 * @param byteOrder 字节序
 * @param scale 缩放系数
 * @param offset 偏移量
 * @param pollingIntervalMs 轮询周期
 * @param nextPollAt 下一次轮询时刻
 * @param leaseUntil 当前租约到期时刻
 * @param attempt 在途请求尝试序号
 * @param status 状态
 * @param requestId 在途请求关联标识
 * @param createdAt 创建时刻
 * @param updatedAt 更新时刻
 */
public record ModbusPoll(UUID id, UUID tenantId, UUID projectId, UUID deviceId, UUID subDeviceId,
                         String projectKey, String gatewayKey, String propertyKey, int slaveAddress,
                         ModbusPointMapping.FunctionCode functionCode, int registerAddress,
                         ModbusPointMapping.DataType dataType, ModbusPointMapping.ByteOrder byteOrder,
                         BigDecimal scale, BigDecimal offset, int pollingIntervalMs, Instant nextPollAt,
                         Instant leaseUntil, int attempt, Status status, UUID requestId, Instant createdAt,
                         Instant updatedAt) {

    /** 轮询状态。 */
    public enum Status { /** 待轮询。 */ IDLE, /** 已发请求等待响应。 */ IN_FLIGHT }
}
