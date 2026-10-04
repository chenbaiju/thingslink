package com.things.link.device.infrastructure.persistence;

import com.things.link.device.domain.DeviceDataStream;
import com.things.link.device.domain.DeviceDataStreamRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 使用显式 SQL 持久化自定义数据流，所有查询同时限定项目与设备类型。
 *
 * <p>V1 控制面已下线（PS-039 TECHNICAL_DEFERRED）：本类**不是 Spring Bean**，写方法在 V1 内不可被误注入，
 * 仅作为普通代码保留供 S15 显式接线（D-057）。S15 恢复时由 S15-1 显式决定是否复用本仓储的写语义。</p>
 */
public class JdbcDeviceDataStreamRepository implements DeviceDataStreamRepository {
    /** JDBC 执行入口。 */ private final JdbcTemplate jdbcTemplate;
    /** @param jdbcTemplate 已接入 RLS 上下文的数据访问模板 */
    public JdbcDeviceDataStreamRepository(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    /** {@inheritDoc} */
    @Override public void create(DeviceDataStream stream) {
        jdbcTemplate.update("""
                INSERT INTO dev_data_stream
                    (id, tenant_id, project_id, device_type_id, stream_key, name, format,
                     mqtt_topic_advanced, publish_topic, subscribe_topic)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, stream.id(), stream.tenantId(), stream.projectId(), stream.deviceTypeId(),
                stream.streamKey(), stream.name(), stream.format().name(), stream.mqttTopicAdvanced(),
                stream.publishTopic(), stream.subscribeTopic());
    }

    /** {@inheritDoc} */
    @Override public List<DeviceDataStream> findByDeviceType(UUID projectId, UUID deviceTypeId) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, project_id, device_type_id, stream_key, name, format,
                       mqtt_topic_advanced, publish_topic, subscribe_topic, created_at
                  FROM dev_data_stream
                 WHERE project_id = ? AND device_type_id = ? AND deleted_at IS NULL
                 ORDER BY created_at DESC, id DESC
                """, this::map, projectId, deviceTypeId);
    }

    /** {@inheritDoc} */
    @Override public Optional<DeviceDataStream> findById(UUID projectId, UUID deviceTypeId, UUID id) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, project_id, device_type_id, stream_key, name, format,
                       mqtt_topic_advanced, publish_topic, subscribe_topic, created_at
                  FROM dev_data_stream
                 WHERE project_id = ? AND device_type_id = ? AND id = ? AND deleted_at IS NULL
                """, this::map, projectId, deviceTypeId, id).stream().findFirst();
    }

    /** {@inheritDoc} */
    @Override public boolean update(DeviceDataStream stream) {
        return jdbcTemplate.update("""
                UPDATE dev_data_stream
                   SET stream_key = ?, name = ?, format = ?, mqtt_topic_advanced = ?,
                       publish_topic = ?, subscribe_topic = ?, updated_at = now()
                 WHERE project_id = ? AND device_type_id = ? AND id = ? AND deleted_at IS NULL
                """, stream.streamKey(), stream.name(), stream.format().name(), stream.mqttTopicAdvanced(),
                stream.publishTopic(), stream.subscribeTopic(), stream.projectId(),
                stream.deviceTypeId(), stream.id()) == 1;
    }

    /** {@inheritDoc} */
    @Override public boolean softDelete(UUID projectId, UUID deviceTypeId, UUID id) {
        return jdbcTemplate.update("""
                UPDATE dev_data_stream SET deleted_at = now(), updated_at = now()
                 WHERE project_id = ? AND device_type_id = ? AND id = ? AND deleted_at IS NULL
                """, projectId, deviceTypeId, id) == 1;
    }

    /** @param rs 查询结果 @param rowNum 行号 @return 领域对象 */
    private DeviceDataStream map(ResultSet rs, int rowNum) throws SQLException {
        return new DeviceDataStream(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("project_id", UUID.class), rs.getObject("device_type_id", UUID.class),
                rs.getString("stream_key"), rs.getString("name"),
                DeviceDataStream.Format.valueOf(rs.getString("format")), rs.getBoolean("mqtt_topic_advanced"),
                rs.getString("publish_topic"), rs.getString("subscribe_topic"),
                rs.getTimestamp("created_at").toInstant());
    }
}
