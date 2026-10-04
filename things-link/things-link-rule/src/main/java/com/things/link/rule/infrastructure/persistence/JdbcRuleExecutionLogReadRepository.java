package com.things.link.rule.infrastructure.persistence;

import com.things.link.rule.domain.RuleExecutionAttempt;
import com.things.link.rule.domain.RuleExecutionLogQuery;
import com.things.link.rule.domain.RuleExecutionLogReadRepository;
import com.things.link.rule.domain.RuleExecutionSummary;
import com.things.link.rule.domain.RuleOption;
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
import java.util.UUID;

/**
 * 上行规则执行日志只读仓储。
 *
 * <p>列表在 SQL 层按 {@code (messageId, ruleId, ruleVersionId)} 聚合为执行级摘要，最新 attempt 状态用
 * {@code ARRAY_AGG(... ORDER BY attempt DESC)[1]} 取出，累计耗时与时间边界用聚合函数；详情再按 attempt 升序
 * 展开时间线。查询都在项目 RLS 范围内执行。</p>
 */
@Repository
public class JdbcRuleExecutionLogReadRepository implements RuleExecutionLogReadRepository {

    /** 显式 JDBC 访问器。 */
    private final JdbcTemplate jdbc;

    /** @param jdbc JDBC 访问器 */
    public JdbcRuleExecutionLogReadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    public CursorPage<RuleExecutionSummary> findSummaries(RuleExecutionLogQuery query) {
        Position position = decodeCursor(query.cursor());
        StringBuilder sql = new StringBuilder("""
                SELECT summary.message_id, summary.rule_id, rule.name AS rule_name,
                       summary.rule_version_id, summary.attempt_count, summary.sum_duration_millis,
                       summary.latest_status, summary.latest_result_code,
                       summary.first_attempt_at, summary.last_attempt_at
                  FROM (SELECT project_id, message_id, rule_id, rule_version_id,
                               COUNT(*)::integer AS attempt_count,
                               SUM(duration_millis)::bigint AS sum_duration_millis,
                               MIN(created_at) AS first_attempt_at,
                               MAX(created_at) AS last_attempt_at,
                               (ARRAY_AGG(status ORDER BY attempt DESC))[1] AS latest_status,
                               (ARRAY_AGG(result_code ORDER BY attempt DESC))[1] AS latest_result_code
                          FROM rule_execution_log
                         WHERE project_id = ?
                """);
        List<Object> parameters = new ArrayList<>(List.of(query.projectId()));
        if (query.ruleId() != null) {
            sql.append(" AND rule_id = ?");
            parameters.add(query.ruleId());
        }
        sql.append("""
                 GROUP BY project_id, message_id, rule_id, rule_version_id) summary
                  JOIN rule_message rule ON rule.project_id = summary.project_id AND rule.id = summary.rule_id
                 WHERE 1 = 1
                """);
        if (query.status() != null) {
            sql.append(" AND summary.latest_status = ?");
            parameters.add(query.status());
        }
        if (query.from() != null) {
            sql.append(" AND summary.last_attempt_at >= ?");
            parameters.add(Timestamp.from(query.from()));
        }
        if (query.to() != null) {
            sql.append(" AND summary.last_attempt_at < ?");
            parameters.add(Timestamp.from(query.to()));
        }
        if (position != null) {
            sql.append("""
                 AND (summary.last_attempt_at, summary.message_id, summary.rule_id, summary.rule_version_id)
                       < (?, ?, ?, ?)
                """);
            parameters.add(Timestamp.from(position.lastAttemptAt()));
            parameters.add(position.messageId());
            parameters.add(position.ruleId());
            parameters.add(position.ruleVersionId());
        }
        sql.append("""
                 ORDER BY summary.last_attempt_at DESC, summary.message_id DESC,
                          summary.rule_id DESC, summary.rule_version_id DESC
                 LIMIT ?
                """);
        parameters.add(query.limit() + 1);

        List<RuleExecutionSummary> rows = jdbc.query(sql.toString(), this::mapSummary, parameters.toArray());
        if (rows.size() <= query.limit()) {
            return CursorPage.last(rows);
        }
        List<RuleExecutionSummary> items = List.copyOf(rows.subList(0, query.limit()));
        return CursorPage.of(items, encodeCursor(items.getLast()));
    }

    /** {@inheritDoc} */
    @Override
    public List<RuleExecutionAttempt> findAttempts(
            UUID projectId, UUID messageId, UUID ruleId, UUID ruleVersionId) {
        return jdbc.query("""
                SELECT attempt, status, result_code, duration_millis, input_bytes, output_bytes, created_at
                  FROM rule_execution_log
                 WHERE project_id = ? AND message_id = ? AND rule_id = ? AND rule_version_id = ?
                 ORDER BY attempt
                """, this::mapAttempt, projectId, messageId, ruleId, ruleVersionId);
    }

    /** {@inheritDoc} */
    @Override
    public List<RuleOption> findRuleOptions(UUID projectId) {
        return jdbc.query("""
                SELECT DISTINCT rule.id, rule.name
                  FROM rule_execution_log execution
                  JOIN rule_message rule ON rule.project_id = execution.project_id AND rule.id = execution.rule_id
                 WHERE execution.project_id = ?
                 ORDER BY rule.name
                """, this::mapOption, projectId);
    }

    /** @return 执行级摘要的不可解释游标 */
    private static String encodeCursor(RuleExecutionSummary summary) {
        String payload = summary.lastAttemptAt() + "|" + summary.messageId() + "|"
                + summary.ruleId() + "|" + summary.ruleVersionId();
        return Cursor.encode(payload);
    }

    /** 严格解码游标，损坏或伪造游标统一按参数错误处理。 */
    private static Position decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String[] parts = Cursor.decode(cursor).split("\\|");
            if (parts.length != 4) {
                throw new IllegalArgumentException("游标字段不完整");
            }
            return new Position(Instant.parse(parts[0]), UUID.fromString(parts[1]),
                    UUID.fromString(parts[2]), UUID.fromString(parts[3]));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "执行日志游标不合法");
        }
    }

    /** @return 执行级摘要行 */
    private RuleExecutionSummary mapSummary(ResultSet resultSet, int row) throws SQLException {
        return new RuleExecutionSummary(
                resultSet.getObject("message_id", UUID.class),
                resultSet.getObject("rule_id", UUID.class),
                resultSet.getString("rule_name"),
                resultSet.getObject("rule_version_id", UUID.class),
                resultSet.getInt("attempt_count"),
                resultSet.getString("latest_status"),
                resultSet.getString("latest_result_code"),
                resultSet.getLong("sum_duration_millis"),
                resultSet.getTimestamp("first_attempt_at").toInstant(),
                resultSet.getTimestamp("last_attempt_at").toInstant());
    }

    /** @return 单次 attempt 时间线节点 */
    private RuleExecutionAttempt mapAttempt(ResultSet resultSet, int row) throws SQLException {
        return new RuleExecutionAttempt(
                resultSet.getInt("attempt"),
                resultSet.getString("status"),
                resultSet.getString("result_code"),
                resultSet.getLong("duration_millis"),
                resultSet.getInt("input_bytes"),
                resultSet.getInt("output_bytes"),
                resultSet.getTimestamp("created_at").toInstant());
    }

    /** @return 筛选下拉框规则选项 */
    private RuleOption mapOption(ResultSet resultSet, int row) throws SQLException {
        return new RuleOption(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("name"));
    }

    /** 游标的内部键集位置：最后尝试时刻 + 聚合分组键。 */
    private record Position(Instant lastAttemptAt, UUID messageId, UUID ruleId, UUID ruleVersionId) {
    }
}
