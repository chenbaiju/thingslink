package com.things.link.rule.infrastructure.persistence;

import com.things.link.rule.domain.RuleScene;
import com.things.link.rule.domain.RuleSceneExecution;
import com.things.link.rule.domain.RuleSceneRepository;
import com.things.link.rule.domain.RuleSceneVersion;
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

/** JDBC 场景仓储；只访问 rule_scene / rule_scene_version / rule_scene_execution 自有表，版本与执行事实只追加。 */
@Repository
public class JdbcRuleSceneRepository implements RuleSceneRepository {

    /** 显式 JDBC 访问器。 */
    private final JdbcTemplate jdbcTemplate;
    /** 版本条件/动作 jsonb 与领域 JsonNode 之间的映射器。 */
    private final ObjectMapper objectMapper;

    /** @param jdbcTemplate JDBC 访问器 @param objectMapper JSON 映射器 */
    public JdbcRuleSceneRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<RuleScene> lock(UUID projectId, UUID sceneId) {
        return jdbcTemplate.query(sceneSelect() + " WHERE project_id = ? AND id = ? AND deleted_at IS NULL FOR UPDATE",
                this::mapScene, projectId, sceneId).stream().findFirst();
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public List<RuleScene> search(UUID projectId, String name, String status, Instant beforeTime, UUID beforeId, int limit) {
        return jdbcTemplate.query(sceneSelect() + """
                 WHERE project_id = ? AND deleted_at IS NULL
                   AND position(lower(?) in lower(name)) > 0 AND (? = '' OR status = ?)
                   AND (?::timestamptz IS NULL OR (created_at, id) < (?::timestamptz, ?::uuid))
                 ORDER BY created_at DESC, id DESC LIMIT ?
                """, this::mapScene, projectId, name, status, status,
                beforeTime == null ? null : time(beforeTime), beforeTime == null ? null : time(beforeTime), beforeId, limit);
    }
    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public List<RuleSceneVersion> history(UUID projectId, UUID sceneId, Long beforeVersion, int limit) {
        return jdbcTemplate.query(versionSelect() + """
                 WHERE project_id = ? AND scene_id = ? AND (?::bigint IS NULL OR version_number < ?)
                 ORDER BY version_number DESC LIMIT ?
                """, this::mapVersion, projectId, sceneId, beforeVersion, beforeVersion, limit);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean pause(UUID projectId, UUID sceneId, long expectedVersion) {
        return jdbcTemplate.update("""
                UPDATE rule_scene SET status = 'PAUSED', version = version + 1, updated_at = now()
                 WHERE project_id = ? AND id = ? AND version = ? AND status = 'ACTIVE' AND deleted_at IS NULL
                """, projectId, sceneId, expectedVersion) == 1;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean create(RuleScene scene, RuleSceneVersion initialVersion) {
        int definitions = jdbcTemplate.update("""
                INSERT INTO rule_scene (
                    id, tenant_id, project_id, name, description, status, active_version_id,
                    version, created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, scene.id(), scene.tenantId(), scene.projectId(), scene.name(), scene.description(),
                scene.status().name(), scene.activeVersionId(), scene.version(), scene.createdBy(),
                time(scene.createdAt()), time(scene.updatedAt()));
        return definitions == 1 && insertVersion(initialVersion) == 1;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<RuleScene> find(UUID projectId, UUID sceneId) {
        return jdbcTemplate.query(sceneSelect() + """
                 WHERE project_id = ? AND id = ? AND deleted_at IS NULL
                """, this::mapScene, projectId, sceneId).stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<RuleSceneVersion> findVersion(UUID projectId, UUID sceneId, UUID versionId) {
        return jdbcTemplate.query(versionSelect() + """
                 WHERE project_id = ? AND scene_id = ? AND id = ?
                """, this::mapVersion, projectId, sceneId, versionId).stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public List<RuleSceneVersion> versions(UUID projectId, UUID sceneId) {
        return jdbcTemplate.query(versionSelect() + """
                 WHERE project_id = ? AND scene_id = ? ORDER BY version_number
                """, this::mapVersion, projectId, sceneId);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public long nextVersionNumber(UUID projectId, UUID sceneId) {
        Long value = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(version_number), 0) + 1
                  FROM rule_scene_version WHERE project_id = ? AND scene_id = ?
                """, Long.class, projectId, sceneId);
        return value == null ? 1L : value;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean revise(RuleScene replacement, long expectedVersion, RuleSceneVersion newVersion) {
        int definitions = jdbcTemplate.update("""
                UPDATE rule_scene
                   SET name = ?, description = ?, version = version + 1, updated_at = ?
                 WHERE project_id = ? AND id = ? AND version = ? AND deleted_at IS NULL
                """, replacement.name(), replacement.description(), time(replacement.updatedAt()),
                replacement.projectId(), replacement.id(), expectedVersion);
        return definitions == 1 && insertVersion(newVersion) == 1;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean activate(UUID projectId, UUID sceneId, UUID versionId, long expectedVersion) {
        return jdbcTemplate.update("""
                UPDATE rule_scene AS scene
                   SET status = 'ACTIVE', active_version_id = ?, version = version + 1, updated_at = now()
                 WHERE scene.project_id = ? AND scene.id = ? AND scene.version = ? AND scene.deleted_at IS NULL
                   AND (scene.status <> 'ACTIVE' OR scene.active_version_id IS DISTINCT FROM ?)
                   AND EXISTS (
                       SELECT 1 FROM rule_scene_version AS candidate
                        WHERE candidate.project_id = scene.project_id
                          AND candidate.scene_id = scene.id AND candidate.id = ?)
                """, versionId, projectId, sceneId, expectedVersion, versionId, versionId) == 1;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean softDelete(UUID projectId, UUID sceneId, long expectedVersion) {
        return jdbcTemplate.update("""
                UPDATE rule_scene
                   SET deleted_at = now(), version = version + 1, updated_at = now()
                 WHERE project_id = ? AND id = ? AND version = ? AND deleted_at IS NULL
                """, projectId, sceneId, expectedVersion) == 1;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<RuleSceneExecution> findExecutionByKey(
            UUID projectId, UUID sceneId, String idempotencyKey) {
        return jdbcTemplate.query(executionSelect() + """
                 WHERE project_id = ? AND scene_id = ? AND idempotency_key = ?
                """, this::mapExecution, projectId, sceneId, idempotencyKey).stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public boolean insertExecution(RuleSceneExecution execution) {
        return jdbcTemplate.update("""
                INSERT INTO rule_scene_execution (
                    id, tenant_id, project_id, scene_id, scene_version_id, idempotency_key,
                    request_digest, device_id, status, trigger, operator_account_id, trace_id,
                    occurred_at, created_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (project_id, scene_id, idempotency_key) DO NOTHING
                """, execution.id(), execution.tenantId(), execution.projectId(), execution.sceneId(),
                execution.sceneVersionId(), execution.idempotencyKey(), execution.requestDigest(),
                execution.deviceId(), execution.status().name(), execution.trigger().name(),
                execution.operatorAccountId(), execution.traceId(), time(execution.occurredAt()),
                time(execution.createdAt()), time(execution.completedAt())) == 1;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<RuleSceneExecution> findExecution(UUID projectId, UUID executionId) {
        return jdbcTemplate.query(executionSelect() + """
                 WHERE project_id = ? AND id = ?
                """, this::mapExecution, projectId, executionId).stream().findFirst();
    }

    /** 追加一行条件与动作版本；该类型故意不存在 updateVersion 方法。 */
    private int insertVersion(RuleSceneVersion version) {
        return jdbcTemplate.update("""
                INSERT INTO rule_scene_version (
                    id, tenant_id, project_id, scene_id, version_number, conditions, actions,
                    created_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
                """, version.id(), version.tenantId(), version.projectId(), version.sceneId(),
                version.versionNumber(), version.conditions().toString(), version.actions().toString(),
                version.createdBy(), time(version.createdAt()));
    }

    /** @return 场景定义显式查询列，迁移新增列不会静默改变映射 */
    private static String sceneSelect() {
        return """
                SELECT id, tenant_id, project_id, name, description, status, active_version_id,
                       version, created_by, created_at, updated_at, deleted_at
                  FROM rule_scene
                """;
    }

    /** @return 不可变版本显式查询列 */
    private static String versionSelect() {
        return """
                SELECT id, tenant_id, project_id, scene_id, version_number, conditions, actions,
                       created_by, created_at
                  FROM rule_scene_version
                """;
    }

    /** @return 执行事实显式查询列 */
    private static String executionSelect() {
        return """
                SELECT id, tenant_id, project_id, scene_id, scene_version_id, idempotency_key,
                       request_digest, device_id, status, trigger, operator_account_id, trace_id,
                       occurred_at, created_at, completed_at
                  FROM rule_scene_execution
                """;
    }

    /** @param resultSet 数据库行 @param row 行号 @return 场景定义 */
    private RuleScene mapScene(ResultSet resultSet, int row) throws SQLException {
        return new RuleScene(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("tenant_id", UUID.class),
                resultSet.getObject("project_id", UUID.class),
                resultSet.getString("name"),
                resultSet.getString("description"),
                RuleScene.Status.valueOf(resultSet.getString("status")),
                resultSet.getObject("active_version_id", UUID.class),
                resultSet.getLong("version"),
                resultSet.getObject("created_by", UUID.class),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at"),
                instant(resultSet, "deleted_at"));
    }

    /** @param resultSet 数据库行 @param row 行号 @return 条件与动作版本 */
    private RuleSceneVersion mapVersion(ResultSet resultSet, int row) throws SQLException {
        return new RuleSceneVersion(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("tenant_id", UUID.class),
                resultSet.getObject("project_id", UUID.class),
                resultSet.getObject("scene_id", UUID.class),
                resultSet.getLong("version_number"),
                readJson(resultSet.getString("conditions")),
                readJson(resultSet.getString("actions")),
                resultSet.getObject("created_by", UUID.class),
                instant(resultSet, "created_at"));
    }

    /** @param resultSet 数据库行 @param row 行号 @return 执行事实 */
    private RuleSceneExecution mapExecution(ResultSet resultSet, int row) throws SQLException {
        return new RuleSceneExecution(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("tenant_id", UUID.class),
                resultSet.getObject("project_id", UUID.class),
                resultSet.getObject("scene_id", UUID.class),
                resultSet.getObject("scene_version_id", UUID.class),
                resultSet.getString("idempotency_key"),
                resultSet.getString("request_digest"),
                resultSet.getObject("device_id", UUID.class),
                RuleSceneExecution.Status.valueOf(resultSet.getString("status")),
                RuleSceneExecution.Trigger.valueOf(resultSet.getString("trigger")),
                resultSet.getObject("operator_account_id", UUID.class),
                resultSet.getString("trace_id"),
                instant(resultSet, "occurred_at"),
                instant(resultSet, "created_at"),
                instant(resultSet, "completed_at"));
    }

    /** @param json 原始 JSON @return 领域 JsonNode 数组 */
    private JsonNode readJson(String json) {
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
