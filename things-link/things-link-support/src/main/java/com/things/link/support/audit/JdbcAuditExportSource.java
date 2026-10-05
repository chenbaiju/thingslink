package com.things.link.support.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.UUID;

/** 以显式tenant/project条件读取RLS豁免审计表的流式适配器。 */
@Repository
public class JdbcAuditExportSource implements AuditExportSource {

    /** 审计表可能较大，使用服务端游标限制堆占用。 */
    private static final int FETCH_SIZE = 1_024;
    /** JDBC访问器。 */
    private final JdbcTemplate jdbcTemplate;
    /** jsonb详情解析器。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建审计导出事实源。
     * @param jdbcTemplate JDBC访问器
     * @param objectMapper JSON解析器
     */
    public JdbcAuditExportSource(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public long streamAuditLogs(UUID tenantId, UUID projectId, AuditSink sink) {
        long[] count = {0L};
        jdbcTemplate.query(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    SELECT id, actor_account_id, target_type, target_id, action, trace_id, details, created_at
                      FROM sys_audit_log
                     WHERE tenant_id = ? AND project_id = ?
                     ORDER BY id
                    """);
            statement.setObject(1, tenantId);
            statement.setObject(2, projectId);
            statement.setFetchSize(FETCH_SIZE);
            return statement;
        }, rs -> {
            sink.accept(new AuditExportRow(
                    rs.getObject("id", UUID.class), rs.getObject("actor_account_id", UUID.class),
                    rs.getString("target_type"), rs.getObject("target_id", UUID.class),
                    rs.getString("action"), rs.getString("trace_id"), json(rs.getString("details")),
                    instant(rs.getTimestamp("created_at"))));
            count[0]++;
        });
        return count[0];
    }

    /** 审计details列非空；保留防御式空值处理以免异常旧库静默产生字符串。 */
    private JsonNode json(String value) {
        if (value == null) return null;
        try {
            return objectMapper.readTree(value);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("审计details持久事实损坏", exception);
        }
    }

    /** nullable JDBC时间到UTC。 */
    private static java.time.Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
