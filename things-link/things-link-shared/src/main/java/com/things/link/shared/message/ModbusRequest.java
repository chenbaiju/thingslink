package com.things.link.shared.message;

import java.util.UUID;

/**
 * 平台驱动轮询的 Modbus 读请求，发往 {@code tc.device.modbus.request}，由 ingestion 编码为
 * {@code down/modbus/request}。
 *
 * <p>结构化 JSON 而非原始 RTU：网关在 RS485 侧做 RTU 编解码，平台用 {@code requestId} 关联响应、
 * 超时与有限重试。归属字段由 device 模块从可信事实解析，ingestion 不自行推导。</p>
 *
 * @param requestId 本次读请求的 UUIDv7 关联标识
 * @param tenantId 网关所属租户
 * @param projectId 网关所属项目
 * @param gatewayId 目标网关设备 ID，同时是 Kafka 分区键
 * @param projectKey MQTT 项目键
 * @param gatewayKey MQTT 网关设备键
 * @param slaveAddress 从站地址
 * @param functionCode 读功能码
 * @param registerAddress 寄存器起始地址
 * @param quantity 读取数量（寄存器数或位单元数）
 */
public record ModbusRequest(UUID requestId, UUID tenantId, UUID projectId, UUID gatewayId,
                            String projectKey, String gatewayKey, int slaveAddress, String functionCode,
                            int registerAddress, int quantity) {

    /** Outbox 事件类型，由发布器映射到 {@code tc.device.modbus.request}。 */
    public static final String EVENT_TYPE = "DEVICE_MODBUS_REQUEST";

    /**
     * 冻结读请求的必填字段与不可变性。
     */
    public ModbusRequest {
        if (requestId == null || requestId.version() != 7) {
            throw new IllegalArgumentException("requestId 必须是 UUIDv7");
        }
        if (tenantId == null || projectId == null || gatewayId == null) {
            throw new IllegalArgumentException("Modbus 读请求归属不能为空");
        }
        if (projectKey == null || projectKey.isBlank() || gatewayKey == null || gatewayKey.isBlank()
                || functionCode == null || functionCode.isBlank()) {
            throw new IllegalArgumentException("Modbus 读请求路由与功能码不完整");
        }
        if (quantity < 1) {
            throw new IllegalArgumentException("Modbus 读数量必须为正");
        }
    }
}
