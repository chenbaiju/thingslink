package com.things.link.device.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** ADR0194：原Client ID不改写，只将服务器认证快照映射为隔离的Broker会话与主题。 */
public record DeviceMqttSessionIdentity(DeviceMqttIdentity authentication, String wireClientId) {
    /** 不允许缺失身份或非法MQTT UTF-8标识退回未隔离会话。 */
    public DeviceMqttSessionIdentity {
        if (authentication == null || !validClientId(wireClientId)) {
            throw new IllegalArgumentException("MQTT原会话标识无效");
        }
    }

    /** MQTT UTF-8字符串非空、无NUL/非法代理项且不超过双字节长度，不trim。 */
    public static boolean validClientId(String value) {
        return value != null && !value.isEmpty() && value.length() <= 65535 && value.indexOf('\0') < 0
                && StandardCharsets.UTF_8.newEncoder().canEncode(value)
                && value.getBytes(StandardCharsets.UTF_8).length <= 65535;
    }

    /** 固定编码只使用服务器身份、配置版本及原Client ID，不采纳连接自报属性。 */
    public String effectiveClientId() {
        var identity = authentication.identity();
        String source = String.join("\n", identity.tenantId().toString(), identity.projectId().toString(),
                identity.deviceId().toString(), Long.toString(authentication.configVersion()), wireClientId);
        try {
            return "tc-device-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("运行环境缺少SHA-256", impossible);
        }
    }

    /** 五项原身份加服务器挂载点与原Client ID；不可被调用方修改。 */
    public Map<String, String> attributes() {
        var attributes = new LinkedHashMap<>(authentication.attributes());
        attributes.put("tc_auth_mountpoint", mountpoint(authentication.identity().deviceId(), authentication.configVersion()));
        attributes.put("tc_auth_wire_client_id", wireClientId);
        return Map.copyOf(attributes);
    }

    /** 会话与下行路由共用同一规范前缀，版本0仅来自真实存量配置。 */
    public static String mountpoint(UUID deviceId, long configVersion) {
        if (deviceId == null || configVersion < 0) throw new IllegalArgumentException("MQTT命名空间身份无效");
        return "tc/private/device/" + deviceId + "/" + configVersion + "/";
    }
}
