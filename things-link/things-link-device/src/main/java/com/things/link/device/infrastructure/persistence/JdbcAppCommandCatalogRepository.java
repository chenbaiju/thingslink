package com.things.link.device.infrastructure.persistence;

import com.things.link.device.domain.AppCommandCatalogRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

/** ADR0110：数据库内先判字节，再传有界文本，避免恶意历史Schema先占满应用堆。 */
@Repository
public class JdbcAppCommandCatalogRepository implements AppCommandCatalogRepository {
    /** 复用RLS事务连接。 */
    private final JdbcTemplate jdbc;
    /** @param jdbc RLS连接访问器 */
    public JdbcAppCommandCatalogRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** {@inheritDoc} */
    @Override
    public List<Entry> findBounded(UUID projectId, UUID deviceTypeId) {
        return jdbc.query("""
                SELECT command_key, name, description, timeout_seconds,
                       CASE WHEN input_bytes <= 65536 THEN input_schema::text END AS input_schema,
                       CASE WHEN output_bytes <= 65536 THEN output_schema::text END AS output_schema,
                       (input_bytes > 65536 OR output_bytes > 65536) AS oversized
                  FROM (SELECT command_key, name, description, timeout_seconds, input_schema, output_schema,
                               COALESCE(octet_length(convert_to(input_schema::text, 'UTF8')), 0) AS input_bytes,
                               COALESCE(octet_length(convert_to(output_schema::text, 'UTF8')), 0) AS output_bytes,
                               sort_order, id
                          FROM dev_command_definition
                         WHERE project_id = ? AND device_type_id = ? AND deleted_at IS NULL
                         ORDER BY sort_order, id LIMIT 101) candidate
                 ORDER BY sort_order, id
                """, (row, index) -> new Entry(row.getString("command_key"), row.getString("name"),
                row.getString("description"), row.getString("input_schema"), row.getString("output_schema"),
                row.getInt("timeout_seconds"), row.getBoolean("oversized")), projectId, deviceTypeId);
    }
}
