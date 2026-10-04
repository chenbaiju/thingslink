package com.things.link.rule.infrastructure.persistence;

import com.things.link.rule.domain.DeviceActionDeliverySummary;
import com.things.link.rule.domain.NotificationDeliverySummary;
import com.things.link.rule.domain.RuleOption;
import com.things.link.rule.domain.RuleSceneExecutionQuery;
import com.things.link.rule.domain.RuleSceneExecutionReadRepository;
import com.things.link.rule.domain.RuleSceneExecutionSummary;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.page.Cursor;
import com.things.link.shared.page.CursorPage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 手动场景执行事实只读仓储。
 *
 * <p>场景执行一行即一次逻辑执行，列表直接映射执行事实并读侧关联当前场景名称；详情再追加通知与设备动作两类
 * 投递事实。查询都在项目 RLS 范围内执行。</p>
 */
@Repository
public class JdbcRuleSceneExecutionReadRepository implements RuleSceneExecutionReadRepository {

    /** 显式 JDBC 访问器。 */
    private final JdbcTemplate jdbc;

    /** @param jdbc JDBC 访问器 */
    public JdbcRuleSceneExecutionReadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    public CursorPage<RuleSceneExecutionSummary> find(RuleSceneExecutionQuery query) {
        Position position = decodeCursor(query.cursor());
        StringBuilder sql = new StringBuilder(summarySelect());
        List<Object> parameters = new ArrayList<>(List.of(query.projectId()));
        if (query.sceneId() != null) {
            sql.append(" AND execution.scene_id = ?");
            parameters.add(query.sceneId());
        }
        if (query.status() != null) {
            sql.append(" AND execution.status = ?");
            parameters.add(query.status());
        }
        if (query.from() != null) {
            sql.append(" AND execution.created_at >= ?");
            parameters.add(Timestamp.from(query.from()));
        }
        if (query.to() != null) {
            sql.append(" AND execution.created_at < ?");
            parameters.add(Timestamp.from(query.to()));
        }
        if (position != null) {
            sql.append(" AND (execution.created_at, execution.id) < (?, ?)");
            parameters.add(Timestamp.from(position.createdAt()));
            parameters.add(position.id());
        }
        sql.append(" ORDER BY execution.created_at DESC, execution.id DESC LIMIT ?");
        parameters.add(query.limit() + 1);

        List<RuleSceneExecutionSummary> rows = jdbc.query(sql.toString(), this::mapSummary, parameters.toArray());
        if (rows.size() <= query.limit()) {
            return CursorPage.last(rows);
        }
        List<RuleSceneExecutionSummary> items = List.copyOf(rows.subList(0, query.limit()));
        return CursorPage.of(items, encodeCursor(items.getLast()));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<RuleSceneExecutionSummary> findSummary(UUID projectId, UUID executionId) {
        return jdbc.query(summarySelect() + " AND execution.id = ?",
                this::mapSummary, projectId, executionId).stream().findFirst();
    }

    /** {@inheritDoc} */
    @Override
    public List<NotificationDeliverySummary> findNotifications(UUID projectId, UUID executionId) {
        return jdbc.query("""
                SELECT channel, recipient, status, attempt_count, max_attempts,
                       last_error_code, delivered_at, created_at
                  FROM rule_notification_delivery
                 WHERE project_id = ? AND scene_execution_id = ?
                 ORDER BY created_at, id
                """, this::mapNotification, projectId, executionId);
    }

    /** {@inheritDoc} */
    @Override
    public List<DeviceActionDeliverySummary> findDeviceActions(UUID projectId, UUID executionId) {
        return jdbc.query("""
                SELECT device_id, operation_type, command_id, status, failure_code, created_at, completed_at
                  FROM rule_device_action_delivery
                 WHERE project_id = ? AND scene_execution_id = ?
                 ORDER BY created_at, id
                """, this::mapDeviceAction, projectId, executionId);
    }

    /** {@inheritDoc} */
    @Override
    public List<RuleOption> findSceneOptions(UUID projectId) {
        return jdbc.query("""
                SELECT DISTINCT scene.id, scene.name
                  FROM rule_scene_execution execution
                  JOIN rule_scene scene ON scene.project_id = execution.project_id AND scene.id = execution.scene_id
                 WHERE execution.project_id = ?
                 ORDER BY scene.name
                """, this::mapOption, projectId);
    }

    /** @return 场景执行事实的显式查询列，迁移新增列不会静默改变映射 */
    private static String summarySelect() {
        return """
                SELECT execution.id, execution.scene_id, scene.name AS scene_name, execution.scene_version_id,
                       execution.device_id, execution.status, execution.operator_account_id, execution.trace_id,
                       execution.occurred_at, execution.created_at, execution.completed_at
                  FROM rule_scene_execution execution
                  JOIN rule_scene scene ON scene.project_id = execution.project_id AND scene.id = execution.scene_id
                 WHERE execution.project_id = ?
                """;
    }

    /** @return 场景执行事实的不可解释游标 */
    private static String encodeCursor(RuleSceneExecutionSummary summary) {
        return Cursor.encode(summary.createdAt() + "|" + summary.id());
    }

    /** 严格解码游标，损坏或伪造游标统一按参数错误处理。 */
    private static Position decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String[] parts = Cursor.decode(cursor).split("\\|");
            if (parts.length != 2) {
                throw new IllegalArgumentException("游标字段不完整");
            }
            return new Position(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "场景执行游标不合法");
        }
    }

    /** @return 执行事实行 */
    private RuleSceneExecutionSummary mapSummary(ResultSet resultSet, int row) throws SQLException {
        return new RuleSceneExecutionSummary(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("scene_id", UUID.class),
                resultSet.getString("scene_name"),
                resultSet.getObject("scene_version_id", UUID.class),
                resultSet.getObject("device_id", UUID.class),
                resultSet.getString("status"),
                resultSet.getObject("operator_account_id", UUID.class),
                resultSet.getString("trace_id"),
                resultSet.getTimestamp("occurred_at").toInstant(),
                resultSet.getTimestamp("created_at").toInstant(),
                instant(resultSet, "completed_at"));
    }

    /** @return 通知投递事实行 */
    private NotificationDeliverySummary mapNotification(ResultSet resultSet, int row) throws SQLException {
        return new NotificationDeliverySummary(
                resultSet.getString("channel"),
                resultSet.getString("recipient"),
                resultSet.getString("status"),
                resultSet.getInt("attempt_count"),
                resultSet.getInt("max_attempts"),
                resultSet.getString("last_error_code"),
                instant(resultSet, "delivered_at"),
                resultSet.getTimestamp("created_at").toInstant());
    }

    /** @return 设备动作投递事实行 */
    private DeviceActionDeliverySummary mapDeviceAction(ResultSet resultSet, int row) throws SQLException {
        return new DeviceActionDeliverySummary(
                resultSet.getObject("device_id", UUID.class),
                resultSet.getString("operation_type"),
                resultSet.getObject("command_id", UUID.class),
                resultSet.getString("status"),
                resultSet.getString("failure_code"),
                resultSet.getTimestamp("created_at").toInstant(),
                instant(resultSet, "completed_at"));
    }

    /** @return 筛选下拉框场景选项 */
    private RuleOption mapOption(ResultSet resultSet, int row) throws SQLException {
        return new RuleOption(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("name"));
    }

    /** @return nullable timestamptz 对应的 UTC Instant */
    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    /** 游标的内部键集位置：执行落库时刻 + 执行事实 ID。 */
    private record Position(Instant createdAt, UUID id) {
    }
}
