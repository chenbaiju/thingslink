package com.things.link.device.application;

import com.things.link.shared.message.AuthenticatedDeviceIdentity;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Broker原认证快照的固定属性；配置代次独立于共享消息v2的四字段身份。 */
public record DeviceMqttIdentity(AuthenticatedDeviceIdentity identity, long configVersion) {
    /** 必须有真实凭据身份与非负配置代次，不能以空身份猜补当前设备。 */
    public DeviceMqttIdentity {
        if (identity == null || configVersion < 0) throw new IllegalArgumentException("MQTT认证快照无效");
    }

    /** 只读取认证器生成的五个固定属性，缺失、半套或非法值均拒绝。 */
    public static Optional<DeviceMqttIdentity> parse(Map<String, ?> attributes) {
        if (attributes == null) return Optional.empty();
        try {
            return Optional.of(new DeviceMqttIdentity(new AuthenticatedDeviceIdentity(
                    uuid(attributes, "tc_auth_tenant_id"), uuid(attributes, "tc_auth_project_id"),
                    uuid(attributes, "tc_auth_device_id"), version(attributes, "tc_auth_credential_version")),
                    version(attributes, "tc_auth_config_version")));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    /** 原连接标识独立于共享v2身份，缺失或非规范UUID不得猜补。 */
    public static Optional<UUID> connectionId(Map<String, ?> attributes) {
        if (attributes == null) return Optional.empty();
        try { return Optional.of(uuid(attributes, "tc_auth_connection_id")); }
        catch (IllegalArgumentException failure) { return Optional.empty(); }
    }

    /** 认证输出固定字段，不序列化设备密钥、节点或其他客户端自报属性。 */
    public Map<String, String> attributes() {
        return Map.of("tc_auth_tenant_id", identity.tenantId().toString(),
                "tc_auth_project_id", identity.projectId().toString(),
                "tc_auth_device_id", identity.deviceId().toString(),
                "tc_auth_credential_version", Long.toString(identity.credentialVersion()),
                "tc_auth_config_version", Long.toString(configVersion));
    }

    /** UUID只接受规范表示，拒绝Java宽松解析的缩写形式。 */
    private static UUID uuid(Map<String, ?> attributes, String key) {
        String text = text(attributes, key);
        UUID value = UUID.fromString(text);
        if (!value.toString().equals(text)) throw new IllegalArgumentException("UUID格式无效");
        return value;
    }

    /** bigint代次使用规范十进制，禁止浮点、前导零及溢出。 */
    private static long version(Map<String, ?> attributes, String key) {
        String text = text(attributes, key);
        if (!text.matches("0|[1-9][0-9]{0,18}")) throw new IllegalArgumentException("版本格式无效");
        return Long.parseLong(text);
    }

    /** Broker属性为字符串；不自动转换数字或空值。 */
    private static String text(Map<String, ?> attributes, String key) {
        Object value = attributes.get(key);
        if (!(value instanceof String text)) throw new IllegalArgumentException("缺少认证属性");
        return text;
    }
}
