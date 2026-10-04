package com.things.link.device.infrastructure.persistence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** 已锁定设备的当前 MQTT 能力复验；必须在设备锁之后以独立语句读取配置。 */
final class DeviceMqttCapability {
    private DeviceMqttCapability() { }

    /** 无显式绑定沿用历史 MQTT，显式绑定必须属于当前租户且启用 MQTT。 */
    static boolean allowed(JdbcTemplate jdbc, UUID tenantId, UUID projectId, UUID deviceId) {
        return allAllowed(jdbc, tenantId, projectId, List.of(deviceId));
    }

    /** 调用者已完整锁定非空目标集合，批量读取避免逐设备查询。 */
    static boolean allAllowed(JdbcTemplate jdbc, UUID tenantId, UUID projectId, List<UUID> deviceIds) {
        var arguments = new ArrayList<Object>(deviceIds.size() + 2);
        arguments.add(projectId);
        arguments.addAll(deviceIds);
        arguments.add(tenantId);
        String placeholders = String.join(",", Collections.nCopies(deviceIds.size(), "?"));
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT NOT EXISTS(SELECT 1 FROM dev_access_binding
                  WHERE project_id=? AND device_id IN (
                """ + placeholders + ") AND (tenant_id<>? OR protocol<>'MQTT' OR NOT enabled))",
                Boolean.class, arguments.toArray()));
    }
}
