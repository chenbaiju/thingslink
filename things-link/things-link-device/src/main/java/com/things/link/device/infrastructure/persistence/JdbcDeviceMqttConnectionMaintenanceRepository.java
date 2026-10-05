package com.things.link.device.infrastructure.persistence;

import com.things.link.device.domain.DeviceMqttConnectionMaintenanceRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 固定SECURITY DEFINER入口自身原子且有界，不以应用RLS绕过跨项目扫描。 */
@Repository
public class JdbcDeviceMqttConnectionMaintenanceRepository implements DeviceMqttConnectionMaintenanceRepository {
    /** 受限数据源，不读取票据内容。 */
    private final JdbcTemplate jdbc;
    /** 装配实际数据源。 */
    public JdbcDeviceMqttConnectionMaintenanceRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override public int cleanExpired() {
        return jdbc.queryForObject("SELECT public.dev_mqtt_connection_maintenance()", Integer.class);
    }
}
