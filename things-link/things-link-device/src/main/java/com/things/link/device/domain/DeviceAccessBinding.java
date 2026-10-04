package com.things.link.device.domain;

import com.things.link.shared.message.TransportProtocol;

import java.time.Instant;
import java.util.UUID;

/**
 * 一台设备的生效接入配置。
 *
 * <p>「一台设备同一时刻只有一份生效接入配置」由主键保证；{@code configVersion} 只在配置真正变化时递增，
 * 会话携带建立时的代次，配置变更后旧会话随之过期。没有配置行的设备是存量 MQTT 直连路径，读取端口用
 * {@link #legacyMqtt} 合成默认配置，只读时不回填数据行；配置或凭据变化后持久保存MQTT代次，切回不得删行。</p>
 *
 * @param deviceId 设备主键
 * @param tenantId 归属租户
 * @param projectId 归属项目
 * @param protocol 接入平面使用的传输协议
 * @param configVersion 配置代次，从 1 开始；存量 MQTT 默认配置为 0
 * @param heartbeatSeconds 历史TCP心跳提示（秒）；实际周期以连接AUTH_RESPONSE及持久会话为准
 * @param enabled 是否允许该协议接入
 * @param lastActivityAt 最近一次认证通过的接入活动时刻；从未活动时为空（在线判据另见会话事实）
 * @param createdAt 记录创建时刻
 * @param updatedAt 记录最后更新时刻
 */
public record DeviceAccessBinding(UUID deviceId, UUID tenantId, UUID projectId, TransportProtocol protocol,
                                  long configVersion, Integer heartbeatSeconds, boolean enabled,
                                  Instant lastActivityAt, Instant createdAt, Instant updatedAt) {

    /** 存量 MQTT 直连路径的配置代次：仅没有配置行时使用；显式MQTT绑定正常递增。 */
    public static final long LEGACY_MQTT_VERSION = 0L;

    /** 冻结接入配置的必填字段与代次下界。 */
    public DeviceAccessBinding {
        if (deviceId == null || tenantId == null || projectId == null || protocol == null) {
            throw new IllegalArgumentException("接入配置归属与协议不能为空");
        }
        if (configVersion < 0) {
            throw new IllegalArgumentException("配置代次不能为负数");
        }
        if (protocol != TransportProtocol.MQTT && configVersion == LEGACY_MQTT_VERSION) {
            throw new IllegalArgumentException("原生协议配置代次必须为正数");
        }
    }

    /**
     * 合成存量 MQTT 直连设备的默认配置。
     *
     * @param tenantId 设备归属租户
     * @param projectId 设备归属项目
     * @param deviceId 设备主键
     * @return 代次为 0 的 MQTT 配置
     */
    public static DeviceAccessBinding legacyMqtt(UUID tenantId, UUID projectId, UUID deviceId) {
        return new DeviceAccessBinding(deviceId, tenantId, projectId, TransportProtocol.MQTT,
                LEGACY_MQTT_VERSION, null, true, null, null, null);
    }

    /**
     * 判断给定协议是否可以按本配置接入。
     *
     * @param candidate 本次连接的传输协议
     * @return 配置启用且协议一致
     */
    public boolean allows(TransportProtocol candidate) {
        return enabled && protocol == candidate;
    }
}
