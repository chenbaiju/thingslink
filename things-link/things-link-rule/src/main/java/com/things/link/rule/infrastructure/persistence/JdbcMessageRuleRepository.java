package com.things.link.rule.infrastructure.persistence;

import com.things.link.rule.domain.MessageRule;
import com.things.link.rule.domain.MessageRuleRepository;
import com.things.link.rule.domain.MessageRuleVersion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** JDBC 规则仓储；只访问 rule_ 自有表，版本表只有 INSERT 和 SELECT。 */
@Repository
public class JdbcMessageRuleRepository implements MessageRuleRepository {

    /** 显式 JDBC 访问器。 */
    private final JdbcTemplate jdbcTemplate;
    /** 版本动作 jsonb 与领域 JsonNode 之间的映射器。 */
    private final ObjectMapper objectMapper;

    /** @param jdbcTemplate JDBC 访问器 @param objectMapper JSON 映射器 */
    public JdbcMessageRuleRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<MessageRule> lock(UUID projectId, UUID ruleId) {
        return jdbcTemplate.query(ruleSelect() + " WHERE project_id = ? AND id = ? AND deleted_at IS NULL FOR UPDATE",
                this::mapRule, projectId, ruleId).stream().findFirst();
    }
    /** {@inheritDoc} */
    @Override
    public List<MessageRule> search(UUID projectId, String name, String status, Instant beforeTime, UUID beforeId, int limit) {
        return jdbcTemplate.query(ruleSelect() + """
                 WHERE project_id = ? AND deleted_at IS NULL
                   AND position(lower(?) in lower(name)) > 0 AND (? = '' OR status = ?)
                   AND (?::timestamptz IS NULL OR (created_at, id) < (?::timestamptz, ?::uuid))
                 ORDER BY created_at DESC, id DESC LIMIT ?
                """, this::mapRule, projectId, name, status, status,
                beforeTime == null ? null : time(beforeTime), beforeTime == null ? null : time(beforeTime), beforeId, limit);
    }
    /** {@inheritDoc} */
    @Override
    public List<MessageRuleVersion> history(UUID projectId, UUID ruleId, Long beforeVersion, int limit) {
        return jdbcTemplate.query(versionSelect() + """
                 WHERE project_id = ? AND rule_id = ? AND (?::bigint IS NULL OR version_number < ?)
                 ORDER BY version_number DESC LIMIT ?
                """, this::mapVersion, projectId, ruleId, beforeVersion, beforeVersion, limit);
    }

    /** {@inheritDoc} */
    @Override
    public boolean create(MessageRule rule, MessageRuleVersion initialVersion) {
        int definitions = jdbcTemplate.update("""
                INSERT INTO rule_message (
                    id, tenant_id, project_id, name, description, status, active_version_id,
                    version, created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, rule.id(), rule.tenantId(), rule.projectId(), rule.name(), rule.description(),
                rule.status().name(), rule.activeVersionId(), rule.version(), rule.createdBy(),
                time(rule.createdAt()), time(rule.updatedAt()));
        return definitions == 1 && insertVersion(initialVersion) == 1;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<MessageRule> find(UUID projectId, UUID ruleId) {
        return jdbcTemplate.query(ruleSelect() + """
                 WHERE project_id = ? AND id = ? AND deleted_at IS NULL
                """, this::mapRule, projectId, ruleId).stream().findFirst();
    }

    /** {@inheritDoc} */
    @Override
    public Optional<MessageRuleVersion> findVersion(UUID projectId, UUID ruleId, UUID versionId) {
        return jdbcTemplate.query(versionSelect() + """
                 WHERE project_id = ? AND rule_id = ? AND id = ?
                """, this::mapVersion, projectId, ruleId, versionId).stream().findFirst();
    }

    /** {@inheritDoc} */
    @Override
    public List<MessageRuleVersion> versions(UUID projectId, UUID ruleId) {
        return jdbcTemplate.query(versionSelect() + """
                 WHERE project_id = ? AND rule_id = ? ORDER BY version_number
                """, this::mapVersion, projectId, ruleId);
    }

    /** {@inheritDoc} */
    @Override
    public long nextVersionNumber(UUID projectId, UUID ruleId) {
        Long value = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(version_number), 0) + 1
                  FROM rule_version WHERE project_id = ? AND rule_id = ?
                """, Long.class, projectId, ruleId);
        return value == null ? 1L : value;
    }

    /** {@inheritDoc} */
    @Override
    public boolean revise(MessageRule replacement, long expectedVersion, MessageRuleVersion newVersion) {
        int definitions = jdbcTemplate.update("""
                UPDATE rule_message
                   SET name = ?, description = ?, version = version + 1, updated_at = ?
                 WHERE project_id = ? AND id = ? AND version = ? AND deleted_at IS NULL
                """, replacement.name(), replacement.description(), time(replacement.updatedAt()),
                replacement.projectId(), replacement.id(), expectedVersion);
        return definitions == 1 && insertVersion(newVersion) == 1;
    }

    /** {@inheritDoc} */
    @Override
    public boolean activate(UUID projectId, UUID ruleId, UUID versionId, long expectedVersion) {
        return jdbcTemplate.update("""
                UPDATE rule_message AS rule
                   SET status = 'ACTIVE', active_version_id = ?, version = version + 1, updated_at = now()
                 WHERE rule.project_id = ? AND rule.id = ? AND rule.version = ? AND rule.deleted_at IS NULL
                   AND (rule.status <> 'ACTIVE' OR rule.active_version_id IS DISTINCT FROM ?)
                   AND EXISTS (
                       SELECT 1 FROM rule_version AS candidate
                        WHERE candidate.project_id = rule.project_id
                          AND candidate.rule_id = rule.id AND candidate.id = ?)
                """, versionId, projectId, ruleId, expectedVersion, versionId, versionId) == 1;
    }

    /** {@inheritDoc} */
    @Override
    public boolean pause(UUID projectId, UUID ruleId, long expectedVersion) {
        return jdbcTemplate.update("""
                UPDATE rule_message
                   SET status = 'PAUSED', version = version + 1, updated_at = now()
                 WHERE project_id = ? AND id = ? AND version = ?
                   AND status = 'ACTIVE' AND deleted_at IS NULL
                """, projectId, ruleId, expectedVersion) == 1;
    }

    /** {@inheritDoc} */
    @Override
    public boolean softDelete(UUID projectId, UUID ruleId, long expectedVersion) {
        return jdbcTemplate.update("""
                UPDATE rule_message
                   SET deleted_at = now(), version = version + 1, updated_at = now()
                 WHERE project_id = ? AND id = ? AND version = ? AND deleted_at IS NULL
                """, projectId, ruleId, expectedVersion) == 1;
    }

    /** 追加一行源码版本；该类型故意不存在 updateVersion 方法。 */
    private int insertVersion(MessageRuleVersion version) {
        return jdbcTemplate.update("""
                INSERT INTO rule_version (
                    id, tenant_id, project_id, rule_id, version_number, source,
                    source_sha256, actions, created_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                """, version.id(), version.tenantId(), version.projectId(), version.ruleId(),
                version.versionNumber(), version.source(), version.sourceSha256(),
                version.actions().toString(), version.createdBy(), time(version.createdAt()));
    }

    /** @return 规则定义显式查询列，迁移新增列不会静默改变映射 */
    private static String ruleSelect() {
        return """
                SELECT id, tenant_id, project_id, name, description, status, active_version_id,
                       version, created_by, created_at, updated_at, deleted_at
                  FROM rule_message
                """;
    }

    /** @return 不可变版本显式查询列 */
    private static String versionSelect() {
        return """
                SELECT id, tenant_id, project_id, rule_id, version_number, source,
                       source_sha256, actions, created_by, created_at
                  FROM rule_version
                """;
    }

    /** @param resultSet 数据库行 @param row 行号 @return 规则定义 */
    private MessageRule mapRule(ResultSet resultSet, int row) throws SQLException {
        return new MessageRule(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("tenant_id", UUID.class),
                resultSet.getObject("project_id", UUID.class),
                resultSet.getString("name"),
                resultSet.getString("description"),
                MessageRule.Status.valueOf(resultSet.getString("status")),
                resultSet.getObject("active_version_id", UUID.class),
                resultSet.getLong("version"),
                resultSet.getObject("created_by", UUID.class),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at"),
                instant(resultSet, "deleted_at"));
    }

    /** @param resultSet 数据库行 @param row 行号 @return 脚本版本 */
    private MessageRuleVersion mapVersion(ResultSet resultSet, int row) throws SQLException {
        return new MessageRuleVersion(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("tenant_id", UUID.class),
                resultSet.getObject("project_id", UUID.class),
                resultSet.getObject("rule_id", UUID.class),
                resultSet.getLong("version_number"),
                resultSet.getString("source"),
                resultSet.getString("source_sha256"),
                readActions(resultSet.getString("actions")),
                resultSet.getObject("created_by", UUID.class),
                instant(resultSet, "created_at"));
    }

    /** @param json actions 列原始 JSON @return 领域 JsonNode 动作数组 */
    private JsonNode readActions(String json) {
        if (json == null) {
            return objectMapper.createArrayNode();
        }
        return objectMapper.readTree(json);
    }

    /** @return Instant 对应的 PostgreSQL timestamptz 参数 */
    private static Timestamp time(Instant value) {
        return Timestamp.from(value);
    }

    /** @return nullable timestamptz 对应的 UTC Instant */
    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
