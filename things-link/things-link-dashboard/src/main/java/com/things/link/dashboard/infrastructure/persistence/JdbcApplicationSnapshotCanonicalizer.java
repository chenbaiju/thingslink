package com.things.link.dashboard.infrastructure.persistence;

import com.things.link.dashboard.application.publication.ApplicationSnapshotCanonicalizer;
import com.things.link.dashboard.application.publication.PostgreSqlApplicationSnapshotCanonicalForm;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Objects;

/** 使用真实PostgreSQL {@code jsonb::text}和pgcrypto生成应用快照权威摘要。 */
@Component
public class JdbcApplicationSnapshotCanonicalizer implements ApplicationSnapshotCanonicalizer {

    /** 与业务事务共享连接上下文的数据库执行入口。 */
    private final JdbcTemplate jdbcTemplate;

    /** @param jdbcTemplate 当前事务使用的JDBC模板 */
    public JdbcApplicationSnapshotCanonicalizer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    /** {@inheritDoc} */
    @Override
    public PostgreSqlApplicationSnapshotCanonicalForm canonicalize(JsonNode snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        List<PostgreSqlApplicationSnapshotCanonicalForm> rows = jdbcTemplate.query("""
                WITH canonical AS (
                    SELECT (?::jsonb)::text AS canonical_text
                )
                SELECT canonical_text,
                       encode(digest(convert_to(canonical_text, 'UTF8'), 'sha256'), 'hex') AS snapshot_digest
                  FROM canonical
                """, (result, row) -> new PostgreSqlApplicationSnapshotCanonicalForm(
                result.getString("canonical_text"),
                PostgreSqlApplicationSnapshotCanonicalForm.DIGEST_ALGORITHM,
                result.getString("snapshot_digest")), snapshot.toString());
        if (rows.size() != 1) {
            throw new DataIntegrityViolationException("PostgreSQL未返回唯一应用快照规范表示");
        }
        return rows.getFirst();
    }
}
