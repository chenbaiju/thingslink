package com.things.link.shared.message;

import java.time.Instant;
import java.util.UUID;

/**
 * 网关拓扑/子设备上下线消息的标准信封，发往 {@code tc.device.topo}。
 *
 * <p>由 ingestion 从 {@code tc.device.uplink.raw} 解析出来：租户、项目与网关身份取自已认证的
 * Topic/连接，禁止从 payload 接受（ADR 0031 信任边界）；只有子设备标识与注册附加字段来自
 * payload。{@code receivedAt} 采用平台侧接收时刻，供在线态 CAS 防乱序覆盖。</p>
 *
 * @param messageId 设备生成的 UUIDv7 消息标识，重试时保持不变
 * @param tenantId 网关所属租户
 * @param projectId 网关所属项目
 * @param gatewayId 已认证的发布网关设备 ID，同时是 Kafka 分区键
 * @param type 拓扑消息类型
 * @param subDeviceKey 子设备 deviceKey（payload 自报，需在项目内唯一）
 * @param name 子设备显示名，仅 {@link Type#SUB_DEVICE_REGISTER} 使用
 * @param deviceTypeKey 子设备类型 typeKey，仅 {@link Type#SUB_DEVICE_REGISTER} 使用
 * @param receivedAt 平台接收消息的 UTC 时刻，防乱序的权威时间
 * @param traceId 接入层生成的链路追踪标识
 */
public record DeviceTopologyMessage(UUID messageId, UUID tenantId, UUID projectId, UUID gatewayId, Type type,
                                    String subDeviceKey, String name, String deviceTypeKey,
                                    Instant receivedAt, String traceId) {

    /** 拓扑消息类型，与 §5.3 上行 Topic 一一对应。 */
    public enum Type {
        /** {@code up/topo/add}：网关上报子设备绑定。 */
        TOPO_ADD,
        /** {@code up/topo/delete}：网关上报子设备解绑。 */
        TOPO_DELETE,
        /** {@code up/sub/login}：子设备上线。 */
        SUB_DEVICE_LOGIN,
        /** {@code up/sub/logout}：子设备下线。 */
        SUB_DEVICE_LOGOUT,
        /** {@code up/sub/register}：网关注册新子设备。 */
        SUB_DEVICE_REGISTER
    }

    /** 子设备标识沿用物模型安全字符集，与 MQTT Topic 的 deviceKey 段一致。 */
    private static final String KEY_PATTERN = "[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}";

    /**
     * 冻结拓扑信封的必填字段与不可变性。
     */
    public DeviceTopologyMessage {
        requireUuidV7(messageId);
        if (tenantId == null || projectId == null || gatewayId == null || type == null) {
            throw new IllegalArgumentException("拓扑消息归属与类型不能为空");
        }
        if (subDeviceKey == null || !subDeviceKey.matches(KEY_PATTERN)) {
            throw new IllegalArgumentException("子设备标识不符合安全字符集");
        }
        if (receivedAt == null || traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("接收时间与 traceId 不能为空");
        }
        boolean register = type == Type.SUB_DEVICE_REGISTER;
        if (register && (name == null || name.isBlank() || deviceTypeKey == null
                || !deviceTypeKey.matches(KEY_PATTERN))) {
            throw new IllegalArgumentException("注册消息必须携带子设备名称与类型标识");
        }
        if (!register && (name != null || deviceTypeKey != null)) {
            throw new IllegalArgumentException("非注册消息不得携带子设备名称与类型标识");
        }
    }

    /**
     * 校验设备消息使用 UUIDv7；版本位错误意味着设备 SDK 没有遵守不可回退的消息契约。
     *
     * @param value 待校验标识
     */
    private static void requireUuidV7(UUID value) {
        if (value == null || value.version() != 7) {
            throw new IllegalArgumentException("messageId 必须是 UUIDv7");
        }
    }
}
