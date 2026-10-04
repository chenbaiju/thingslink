package com.things.link.device.infrastructure.persistence;

import com.things.link.project.application.SelfHostedDeviceUsageReader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** 设备领域只向平台应用端口提供已绑定租户的有效存量总数。 */
@Repository
public class JdbcSelfHostedDeviceUsageReader implements SelfHostedDeviceUsageReader {
    private final JdbcTemplate jdbc;

    public JdbcSelfHostedDeviceUsageReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long activeDevices(UUID billingTenantId) {
        Long used = jdbc.queryForObject("SELECT public.shc_local_device_usage(?)", Long.class,
                billingTenantId);
        if (used == null) throw new IllegalStateException("设备权威用量为空");
        return used;
    }
}
