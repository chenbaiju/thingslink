package com.things.link.device.infrastructure.persistence;

import com.things.link.device.application.DeviceExportSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.UUID;

/** 只读取device域表的流式设备导出适配器。 */
@Repository
public class JdbcDeviceExportSource implements DeviceExportSource {

    /** PostgreSQL服务端游标的单次抓取行数。 */
    private static final int FETCH_SIZE = 512;
    /** 本域JDBC访问器。 */
    private final JdbcTemplate jdbcTemplate;

    /** @param jdbcTemplate JDBC访问器 */
    public JdbcDeviceExportSource(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public long streamDevices(UUID tenantId, UUID projectId, DeviceSink sink) {
        long[] count = {0L};
        jdbcTemplate.query(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    SELECT id, device_type_id, gateway_id, device_key, name, description,
                           status, location, last_online_at, created_at
                      FROM dev_device
                     WHERE tenant_id = ? AND project_id = ? AND deleted_at IS NULL
                     ORDER BY id
                    """);
            statement.setObject(1, tenantId);
            statement.setObject(2, projectId);
            statement.setFetchSize(FETCH_SIZE);
            return statement;
        }, rs -> {
            sink.accept(new DeviceExportRow(
                    rs.getObject("id", UUID.class), rs.getObject("device_type_id", UUID.class),
                    rs.getObject("gateway_id", UUID.class), rs.getString("device_key"), rs.getString("name"),
                    rs.getString("description"), rs.getString("status"), rs.getString("location"),
                    instant(rs.getTimestamp("last_online_at")), instant(rs.getTimestamp("created_at"))));
            count[0]++;
        });
        return count[0];
    }

    /** nullable JDBC时间到UTC。 */
    private static java.time.Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
