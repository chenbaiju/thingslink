package com.things.link.telemetry.infrastructure.persistence;

import com.things.link.telemetry.application.PropertyPointExportSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.UUID;

/** 只读取telemetry域原始hypertable视图的流式导出适配器。 */
@Repository
public class JdbcPropertyPointExportSource implements PropertyPointExportSource {

    /** 时序结果较大，适度增大服务端游标抓取批次以减少往返。 */
    private static final int FETCH_SIZE = 2_048;
    /** 本域JDBC访问器。 */
    private final JdbcTemplate jdbcTemplate;
    /** jsonb值解析器，确保归档中的valueJson仍是JSON而非二次编码字符串。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建属性点导出事实源。
     * @param jdbcTemplate JDBC访问器
     * @param objectMapper JSON解析器
     */
    public JdbcPropertyPointExportSource(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public long streamPropertyPoints(UUID tenantId, UUID projectId, PropertyPointSink sink) {
        long[] count = {0L};
        jdbcTemplate.query(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    SELECT p.device_id, p.property_key, p.ts, p.message_id, p.data_type,
                           p.thing_model_version_id, p.model_version, p.value_double,
                           p.value_text, p.value_bool, p.value_json, p.quality
                      FROM ts_property_point p
                     WHERE p.project_id = ?
                     ORDER BY p.device_id, p.property_key, p.ts, p.message_id
                    """);
            statement.setObject(1, projectId);
            statement.setFetchSize(FETCH_SIZE);
            return statement;
        }, rs -> {
            sink.accept(new PropertyPointExportRow(
                    rs.getObject("device_id", UUID.class), rs.getString("property_key"),
                    instant(rs.getTimestamp("ts")), rs.getObject("message_id", UUID.class),
                    rs.getString("data_type"), rs.getObject("thing_model_version_id", UUID.class),
                    rs.getString("model_version"), rs.getObject("value_double", Double.class),
                    rs.getString("value_text"), rs.getObject("value_bool", Boolean.class),
                    json(rs.getString("value_json")), rs.getShort("quality")));
            count[0]++;
        });
        return count[0];
    }

    /** nullable jsonb文本到JSON树。 */
    private JsonNode json(String value) {
        if (value == null) return null;
        try {
            return objectMapper.readTree(value);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("属性点value_json持久事实损坏", exception);
        }
    }

    /** nullable JDBC时间到UTC。 */
    private static java.time.Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
