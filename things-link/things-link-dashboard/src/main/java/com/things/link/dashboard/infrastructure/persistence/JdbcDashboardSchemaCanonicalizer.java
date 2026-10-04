package com.things.link.dashboard.infrastructure.persistence;

import com.things.link.dashboard.application.publication.DashboardSchemaCanonicalizer;
import com.things.link.dashboard.application.publication.PostgreSqlDashboardSchemaCanonicalForm;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Objects;

/** 使用真实PostgreSQL {@code jsonb::text}和pgcrypto生成看板Schema权威摘要。 */
@Component
public class JdbcDashboardSchemaCanonicalizer implements DashboardSchemaCanonicalizer {

    /** 数据库执行入口。 */
    private final JdbcTemplate jdbcTemplate;

    /** @param jdbcTemplate 与业务事务共享连接上下文的JDBC模板 */
    public JdbcDashboardSchemaCanonicalizer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    /** {@inheritDoc} */
    @Override
    public PostgreSqlDashboardSchemaCanonicalForm canonicalize(JsonNode normalizedSchema) {
        Objects.requireNonNull(normalizedSchema, "normalizedSchema");
        List<PostgreSqlDashboardSchemaCanonicalForm> rows = jdbcTemplate.query("""
                WITH canonical AS (
                    SELECT (?::jsonb)::text AS canonical_text
                )
                SELECT canonical_text,
                       encode(digest(convert_to(canonical_text, 'UTF8'), 'sha256'), 'hex') AS schema_digest
                FROM canonical
                """, (result, row) -> new PostgreSqlDashboardSchemaCanonicalForm(
                        result.getString("canonical_text"),
                        PostgreSqlDashboardSchemaCanonicalForm.DIGEST_ALGORITHM,
                        result.getString("schema_digest")), normalizedSchema.toString());
        if (rows.size() != 1) {
            throw new DataIntegrityViolationException("PostgreSQL未返回唯一看板Schema规范表示");
        }
        return rows.getFirst();
    }
}
