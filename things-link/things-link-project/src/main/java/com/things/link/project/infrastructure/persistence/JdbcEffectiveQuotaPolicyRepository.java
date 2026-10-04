package com.things.link.project.infrastructure.persistence;

import com.things.link.project.application.EffectiveQuotaPolicy;
import com.things.link.project.domain.EffectiveQuotaPolicyRepository;
import com.things.link.project.domain.TenantResourcePackageRepository;
import com.things.link.project.domain.plan.EffectivePlanQuota;
import com.things.link.project.domain.plan.ResourcePackageAddition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 以 JDBC 读取租户当前配额绑定及可复用策略模板。
 *
 * <p>本查询刻意只投影运行时保护需要的字段，不加载日用量或账单聚合；短窗口热路径应由上层缓存吸收。
 */
@Repository
public class JdbcEffectiveQuotaPolicyRepository implements EffectiveQuotaPolicyRepository {

    /** 统一行映射，SQL 的列别名必须与 record 分量语义一一对应。 */
    private static final RowMapper<EffectiveQuotaPolicy> ROW_MAPPER = (resultSet, rowNumber) ->
            new EffectiveQuotaPolicy(
                    resultSet.getObject("tenant_id", UUID.class),
                    resultSet.getObject("policy_id", UUID.class),
                    resultSet.getLong("assignment_version"),
                    resultSet.getLong("policy_version"),
                    resultSet.getObject("uplink_device_refill_per_second", Long.class),
                    resultSet.getObject("uplink_device_burst_capacity", Long.class),
                    resultSet.getObject("uplink_tenant_per_second_limit", Long.class),
                    resultSet.getObject("uplink_tenant_per_minute_limit", Long.class),
                    resultSet.getObject("rest_api_read_rate_per_second", Long.class),
                    resultSet.getObject("rest_api_write_rate_per_second", Long.class),
                    resultSet.getObject("rest_api_read_rate_per_minute", Long.class),
                    resultSet.getObject("rest_api_write_rate_per_minute", Long.class),
                    resultSet.getObject("websocket_connection_limit", Long.class),
                    resultSet.getObject("task_project_dispatch_per_second", Long.class),
                    resultSet.getObject("task_tenant_dispatch_per_second", Long.class),
                    resultSet.getObject("rule_tenant_concurrency_limit", Long.class),
                    resultSet.getObject("rule_tenant_queue_capacity", Long.class),
                    resultSet.getObject("rule_project_queue_capacity", Long.class),
                    resultSet.getInt("daily_soft_limit_basis_points"),
                    resultSet.getInt("daily_degrade_basis_points"));

    /** JDBC 数据库访问模板。 */
    private final JdbcTemplate jdbcTemplate;
    /** 有效资源包加数读取端口（S14-4a）：合成「基础档 + 有效包」的唯一事实来源。 */
    private final TenantResourcePackageRepository resourcePackageRepository;

    /**
     * 创建策略读取仓储。
     *
     * @param jdbcTemplate JDBC 数据库访问模板
     * @param resourcePackageRepository 有效资源包加数读取端口
     */
    public JdbcEffectiveQuotaPolicyRepository(JdbcTemplate jdbcTemplate,
                                              TenantResourcePackageRepository resourcePackageRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.resourcePackageRepository = resourcePackageRepository;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<EffectiveQuotaPolicy> findByTenantId(UUID tenantId) {
        return enrich(jdbcTemplate.query(SELECT_BY_TENANT_SQL, ROW_MAPPER, tenantId).stream().findFirst());
    }

    /** {@inheritDoc} */
    @Override
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Optional<EffectivePlanQuota> findForCommercialLifecycle(UUID tenantId) {
        java.util.Objects.requireNonNull(tenantId,"商业生命周期租户不得为空");
        return jdbcTemplate.query("SELECT " + PlanQuotaTemplateRows.COLUMNS + """
                 FROM sys_tenant t JOIN sys_quota_policy q ON q.id=t.quota_policy_id
                 WHERE t.id=? AND t.status='ACTIVE' AND q.plan_template
                """,PlanQuotaTemplateRows.MAPPER,tenantId).stream().findFirst()
                .map(PlanQuotaTemplateRows.PlanQuotaTemplateRow::toBaseQuota)
                .map(base -> EffectivePlanQuota.compose(base,resourcePackageRepository.findActiveAdditions(
                        tenantId,DatabaseTime.now(jdbcTemplate))));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<EffectiveQuotaPolicy> findByProjectId(UUID projectId) {
        return enrich(jdbcTemplate.query(SELECT_BY_PROJECT_SQL, ROW_MAPPER).stream().findFirst());
    }

    /** {@inheritDoc} */
    @Override
    public Optional<EffectiveQuotaPolicy> findByDeviceProject(UUID tenantId, UUID projectId) {
        return enrich(jdbcTemplate.query(SELECT_BY_DEVICE_PROJECT_SQL, ROW_MAPPER, tenantId, projectId)
                .stream().findFirst());
    }

    /** {@inheritDoc} */
    @Override
    public Optional<EffectiveQuotaPolicy> findByPlanRevision(UUID planRevisionId) {
        return enrich(jdbcTemplate.query(SELECT_BY_PLAN_REVISION_SQL, ROW_MAPPER, planRevisionId)
                .stream().findFirst());
    }

    /**
     * 把策略行承载的 S14-1b 冻结配额补进快照，并合成 S14-4a 的有效资源包加数。
     *
     * <p>只有 {@code plan_template = true} 的行才有完整冻结值；S7 既有运行时模板没有该投影，
     * 保持 {@code planQuota = null}（表示「不是可售套餐模板」，不是「不限」）。
     *
     * <p>包是租户维度的事实，因此只有带 {@code tenantId} 的读取（租户/项目/设备路径）才合成；
     * 按产品修订版读取（{@code tenantId = null}）返回纯基础档模板。
     *
     * @param raw 已映射的 S7 运行时策略
     * @return 附带冻结配额（含有效资源包）的策略；模板行缺失时原样返回
     */
    private Optional<EffectiveQuotaPolicy> enrich(Optional<EffectiveQuotaPolicy> raw) {
        return raw.map(policy -> findPlanQuota(policy.policyId())
                .map(quota -> policy.withPlanQuota(composePackages(policy, quota)))
                .orElse(policy));
    }

    /**
     * 把该租户此刻有效的资源包加数合成到基础冻结配额上。
     *
     * @param policy 已解析策略（提供 tenantId）
     * @param base 基础档冻结配额投影
     * @return 合成后的有效配额；无租户上下文或无有效包时返回基础档
     */
    private EffectivePlanQuota composePackages(EffectiveQuotaPolicy policy, EffectivePlanQuota base) {
        if (policy.tenantId() == null) {
            return base;
        }
        // S14-6b：窗口判定必须用数据库时钟，与强制面（SQL 加数函数里的 now()）同源；
        // 用 JVM 时间会在窗口边界上出现「刚生效的包在投影里还没开始」的分叉。
        List<ResourcePackageAddition> additions =
                resourcePackageRepository.findActiveAdditions(policy.tenantId(),
                        DatabaseTime.now(jdbcTemplate));
        return additions.isEmpty() ? base : EffectivePlanQuota.compose(base, additions);
    }

    /**
     * 按模板 ID 读取 S14-1b 冻结配额。
     *
     * <p>列清单与行映射复用 {@link PlanQuotaTemplateRows}：与租户套餐摘要读取共用同一份
     * 「一行模板 ↔ 17 个冻结列」的还原逻辑，不各写一半。
     *
     * @param policyId 配额模板 ID
     * @return 模板行存在且 {@code plan_template} 时的冻结配额
     */
    private Optional<EffectivePlanQuota> findPlanQuota(UUID policyId) {
        return jdbcTemplate.query("SELECT " + PlanQuotaTemplateRows.COLUMNS
                        + " FROM sys_quota_policy q WHERE q.id = ? AND q.plan_template",
                PlanQuotaTemplateRows.MAPPER, policyId)
                .stream().findFirst()
                .map(PlanQuotaTemplateRows.PlanQuotaTemplateRow::toBaseQuota);
    }

    /**
     * 租户读取 SQL。策略目录是可复用平台数据，租户绑定才决定实际生效策略。
     */
    private static final String SELECT_BY_TENANT_SQL = """
            SELECT t.id AS tenant_id, t.quota_policy_id AS policy_id,
                   t.quota_policy_assignment_version AS assignment_version,
                   q.version AS policy_version,
                   q.uplink_device_refill_per_second, q.uplink_device_burst_capacity,
                   q.uplink_tenant_per_second_limit, q.uplink_tenant_per_minute_limit,
                   q.rest_api_read_rate_per_second, q.rest_api_write_rate_per_second,
                   q.rest_api_read_rate_per_minute, q.rest_api_write_rate_per_minute,
                   q.websocket_connection_limit,
                   q.task_project_dispatch_per_second, q.task_tenant_dispatch_per_second,
                   q.rule_tenant_concurrency_limit, q.rule_tenant_queue_capacity, q.rule_project_queue_capacity,
                   q.daily_soft_limit_basis_points, q.daily_degrade_basis_points
             FROM sys_tenant t
              JOIN sys_quota_policy q ON q.id = t.quota_policy_id
             WHERE t.id = ?
               AND t.id = app_current_tenant()
               AND t.status = 'ACTIVE'
            """;

    /**
     * 项目读取 SQL。跨租户协作者只能从项目所属租户得到策略，绝不采用其账号自身的租户关系。
     */
    private static final String SELECT_BY_PROJECT_SQL = """
            SELECT tenant_id, policy_id, assignment_version, policy_version,
                   uplink_device_refill_per_second, uplink_device_burst_capacity,
                   uplink_tenant_per_second_limit, uplink_tenant_per_minute_limit,
                   rest_api_read_rate_per_second, rest_api_write_rate_per_second,
                   rest_api_read_rate_per_minute, rest_api_write_rate_per_minute,
                   websocket_connection_limit,
                   task_project_dispatch_per_second, task_tenant_dispatch_per_second,
                   rule_tenant_concurrency_limit, rule_tenant_queue_capacity, rule_project_queue_capacity,
                   daily_soft_limit_basis_points, daily_degrade_basis_points
              FROM project_effective_quota_policy()
            """;

    /**
     * 设备协议回调没有控制台 JWT 的 app_current_project；只允许受限 SECURITY DEFINER 函数同时验证
     * 服务端确权得到的 tenant/project 二元组，绝不能退回普通跨表 JOIN。
     */
    private static final String SELECT_BY_DEVICE_PROJECT_SQL = """
            SELECT tenant_id, policy_id, assignment_version, policy_version,
                   uplink_device_refill_per_second, uplink_device_burst_capacity,
                   uplink_tenant_per_second_limit, uplink_tenant_per_minute_limit,
                   rest_api_read_rate_per_second, rest_api_write_rate_per_second,
                   rest_api_read_rate_per_minute, rest_api_write_rate_per_minute,
                   websocket_connection_limit,
                   task_project_dispatch_per_second, task_tenant_dispatch_per_second,
                   rule_tenant_concurrency_limit, rule_tenant_queue_capacity, rule_project_queue_capacity,
                   daily_soft_limit_basis_points, daily_degrade_basis_points
              FROM device_project_effective_quota_policy(?, ?)
            """;

    /**
     * 产品修订版读取 SQL：S14-1b 的模板侧入口。没有租户绑定，因此 tenant/assignment 用固定值占位，
     * 冻结额度由 {@link PlanQuotaTemplateRows} 在同一模板 ID 上补齐。
     */
    private static final String SELECT_BY_PLAN_REVISION_SQL = """
            SELECT NULL::uuid AS tenant_id, r.quota_policy_id AS policy_id, 0::bigint AS assignment_version,
                   q.version AS policy_version,
                   q.uplink_device_refill_per_second, q.uplink_device_burst_capacity,
                   q.uplink_tenant_per_second_limit, q.uplink_tenant_per_minute_limit,
                   q.rest_api_read_rate_per_second, q.rest_api_write_rate_per_second,
                   q.rest_api_read_rate_per_minute, q.rest_api_write_rate_per_minute,
                   q.websocket_connection_limit,
                   q.task_project_dispatch_per_second, q.task_tenant_dispatch_per_second,
                   q.rule_tenant_concurrency_limit, q.rule_tenant_queue_capacity, q.rule_project_queue_capacity,
                   q.daily_soft_limit_basis_points, q.daily_degrade_basis_points
              FROM sys_plan_revision r
              JOIN sys_quota_policy q ON q.id = r.quota_policy_id
             WHERE r.id = ?
            """;

}
