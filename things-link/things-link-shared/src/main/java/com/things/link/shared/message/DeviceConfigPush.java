package com.things.link.shared.message;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 平台下发到网关的配置信封，发往 {@code tc.device.config}，由 ingestion 编码为 {@code down/config}。
 *
 * <p>这是设备侧契约：网关按 {@code version} 整体替换旧配置（全量点位集），点位用 {@code subDeviceKey}
 * 而非 UUID——网关只认 MQTT 设备键。configType 当前固定为 {@code MODBUS_POINT_MAPPING}。</p>
 *
 * @param tenantId 网关所属租户
 * @param projectId 网关所属项目
 * @param gatewayId 目标网关设备 ID，同时是 Kafka 分区键
 * @param projectKey MQTT 项目键
 * @param gatewayKey MQTT 网关设备键
 * @param configType 配置类型
 * @param version 已发布配置版本号
 * @param points 全量点位（按从站/功能码/地址排序）
 */
public record DeviceConfigPush(UUID tenantId, UUID projectId, UUID gatewayId,
                               String projectKey, String gatewayKey, String configType, int version,
                               List<Point> points) {

    /** Modbus 点位映射配置类型。 */
    public static final String CONFIG_TYPE = "MODBUS_POINT_MAPPING";

    /** Outbox 事件类型，由发布器映射到 {@code tc.device.config}。 */
    public static final String EVENT_TYPE = "DEVICE_CONFIG_PUSH";

    /** 单个 Modbus 点位，绑定到子设备属性。 */
    public record Point(String subDeviceKey, String propertyKey, int slaveAddress, String functionCode,
                        int registerAddress, String dataType, String byteOrder, BigDecimal scale, BigDecimal offset,
                        int pollingIntervalMs) {
    }

    /**
     * 冻结下发信封的必填字段与不可变性。
     */
    public DeviceConfigPush {
        if (tenantId == null || projectId == null || gatewayId == null) {
            throw new IllegalArgumentException("配置下发归属不能为空");
        }
        if (projectKey == null || projectKey.isBlank() || gatewayKey == null || gatewayKey.isBlank()
                || configType == null || configType.isBlank()) {
            throw new IllegalArgumentException("配置下发路由与类型不完整");
        }
        if (version < 1) {
            throw new IllegalArgumentException("配置版本必须为正整数");
        }
        points = points == null ? List.of() : List.copyOf(points);
    }
}
