package com.things.link.project.infrastructure.persistence;

import com.things.link.project.domain.plan.EffectiveEntitlement;
import com.things.link.project.domain.plan.EffectiveEntitlementRepository;
import com.things.link.project.domain.plan.PlanEntitlement;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 以 PostgreSQL 读取 S14-1b 产品修订版的功能权益投影。
 *
 * <p>权益是平台级只读事实：本查询没有租户参数、不走 RLS，只按修订版身份返回公开能力状态。
 * 修订版声明原样保留，ADR0163规定当前仅作目录解释，不代表当前部署能力或运行授权。
 */
@Repository
public class JdbcEffectiveEntitlementRepository implements EffectiveEntitlementRepository {

    /** 权益行联合投影；左连接权益表，因此能力编码可能为空行。 */
    private static final String SELECT_SQL_TEMPLATE = """
            SELECT r.id AS plan_revision_id, p.code AS plan_code, r.revision_code,
                   r.quota_policy_id, q.version AS quota_policy_version,
                   e.capability_code, e.state
              FROM sys_plan_revision r
              JOIN sys_plan p ON p.id = r.plan_id
              JOIN sys_quota_policy q ON q.id = r.quota_policy_id
              LEFT JOIN sys_plan_entitlement e ON e.plan_revision_id = r.id
             WHERE %s
             ORDER BY e.capability_code
            """;

    /** JDBC 数据库访问模板。 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate JDBC 数据库访问模板
     */
    public JdbcEffectiveEntitlementRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<EffectiveEntitlement> findByPlanRevisionId(UUID planRevisionId) {
        return load(SELECT_SQL_TEMPLATE.formatted("r.id = ?"), planRevisionId);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<EffectiveEntitlement> findByRevisionAndPlan(String revisionCode, String planCode) {
        return load(SELECT_SQL_TEMPLATE.formatted("r.revision_code = ? AND p.code = ?"), revisionCode, planCode);
    }

    /**
     * 执行同一投影查询并把多行权益组装成一个修订版快照。
     *
     * @param sql 已选定过滤条件的 SQL
     * @param arguments 过滤参数
     * @return 修订版权益投影；修订版不存在时返回空
     */
    private Optional<EffectiveEntitlement> load(String sql, Object... arguments) {
        List<EntitlementRow> rows = jdbcTemplate.query(sql, (resultSet, rowNumber) -> new EntitlementRow(
                resultSet.getObject("plan_revision_id", UUID.class),
                resultSet.getString("plan_code"),
                resultSet.getString("revision_code"),
                resultSet.getObject("quota_policy_id", UUID.class),
                resultSet.getLong("quota_policy_version"),
                resultSet.getString("capability_code"),
                resultSet.getString("state")), arguments);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        EntitlementRow first = rows.getFirst();
        List<PlanEntitlement> entitlements = new ArrayList<>();
        for (EntitlementRow row : rows) {
            if (row.capabilityCode() != null) {
                entitlements.add(new PlanEntitlement(row.capabilityCode(), "ENABLED".equals(row.state())));
            }
        }
        return Optional.of(new EffectiveEntitlement(first.planRevisionId(), first.planCode(),
                first.revisionCode(), first.quotaPolicyId(), first.quotaPolicyVersion(), entitlements));
    }

    /**
     * 权益联合投影行。
     *
     * @param planRevisionId 产品修订版 ID
     * @param planCode 档位编码
     * @param revisionCode 产品修订版标识
     * @param quotaPolicyId 配额模板 ID
     * @param quotaPolicyVersion 配额模板版本
     * @param capabilityCode 能力编码；修订版没有任何权益行为空
     * @param state {@code ENABLED} 或 {@code DISABLED}
     */
    private record EntitlementRow(UUID planRevisionId, String planCode, String revisionCode,
                                  UUID quotaPolicyId, long quotaPolicyVersion,
                                  String capabilityCode, String state) {
    }
}
