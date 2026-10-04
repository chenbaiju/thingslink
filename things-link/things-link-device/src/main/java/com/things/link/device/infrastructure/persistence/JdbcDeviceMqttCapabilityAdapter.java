package com.things.link.device.infrastructure.persistence;

import com.things.link.device.application.DeviceMqttCapabilityPort;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 本域设备锁及配置复验，跨模块调用方只能取得能力结果。 */
@Repository
public class JdbcDeviceMqttCapabilityAdapter implements DeviceMqttCapabilityPort {
    /** 当前已确权事务及精确RLS连接。 */
    private final JdbcTemplate jdbc;

    /** 注入本域数据库入口。 */
    public JdbcDeviceMqttCapabilityAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 先锁设备再读配置，拒绝缺失/删除/错归属，不能当作无行默认MQTT。 */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockCurrent(UUID tenantId, UUID projectId, UUID deviceId) {
        var devices = jdbc.queryForList("""
                SELECT id FROM dev_device WHERE tenant_id=? AND project_id=? AND id=?
                  AND deleted_at IS NULL FOR SHARE
                """, UUID.class, tenantId, projectId, deviceId);
        return devices.size() == 1 && DeviceMqttCapability.allowed(jdbc, tenantId, projectId, deviceId);
    }
}
