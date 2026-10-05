package com.things.link.project.infrastructure.persistence;

import com.things.link.project.domain.PlanQuotaAddition;
import com.things.link.project.domain.PlanSummaryRepository;
import com.things.link.project.domain.TenantPlanSummary;
import com.things.link.project.domain.TenantResourcePackageRepository;
import com.things.link.project.domain.plan.EffectivePlanQuota;
import com.things.link.project.domain.plan.PlanDimension;
import com.things.link.project.domain.plan.PlanEntitlement;
import com.things.link.project.domain.plan.PlanIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 以 PostgreSQL 读取当前项目归属租户的套餐摘要（S14-2c）。
 *
 * <p><b>为什么租户过滤是「调用者是归属租户的成员」。</b>项目与租户的关系只在数据库权威：
 * 查询按调用方已授权且已选中的项目 ID 收窄，并要求 {@code sys_tenant_member} 中存在
 * 「账户属于该项目归属租户」的 ACTIVE 成员关系。跨租户协作者虽然通过了项目成员校验，却不在
 * 归属租户的成员表里，因此一行也读不到，只能看到既有共享池投影（架构文档 §6）。
 *
 * <p><b>为什么不用 {@code app_current_tenant()}。</b>它读的是连接级会话变量：当连接已被上层
 * 事务/上下文复用时，变量值可能与本次请求的 {@code TenantContext} 不一致（集成测试的共享
 * 线程里可稳定复现）。租户归属是「谁属于哪个租户」的持久事实，直接查成员表既确定又自解释；
 * 传入的项目 ID 与账户 ID 都来自服务端已验证的 {@code TenantScope}，不接受客户端参数。
 *
 * <p>订阅表 {@code sys_tenant_subscription} 与目录表 {@code sys_plan*} 都不套 tenant RLS
 * （注册时尚无租户上下文），因此这里的授权完全由成员关系连接承担。
 *
 * <p>四个查询各自独立取值：订阅头、冻结维度、功能权益，以及（S14-4c）运行时有效维度与
 * 扩容/调整溯源。维度与权益始终来自 {@code subscribedPlan} 锁定的修订版，不按运行时指针二次拼接；
 * 运行时有效维度则来自运行时指针绑定的冻结模板**加上**此刻有效的资源包与人工调整，用的是与
 * 强制面同一份 {@link EffectivePlanQuota#compose} 规则，因此不存在「显示 3 台、实际放行 5 台」。
 *
 * <p>运行时模板按项目归属租户 ID 直接连接读取，不依赖会话级 {@code app_current_tenant()}
 * （连接池复用时该变量可能陈旧，见 D-168）。
 */
@Repository
public class JdbcPlanSummaryRepository implements PlanSummaryRepository {

    /** 已受 TenantAwareDataSource 管理的 JDBC 访问器。 */
    private final JdbcTemplate jdbcTemplate;
    /** 资源包与人工调整事实端口（S14-4c）：运行时有效额度与溯源的唯一来源。 */
    private final TenantResourcePackageRepository resourcePackageRepository;

    /**
     * @param jdbcTemplate 当前请求带 RLS 会话变量的 JDBC 访问器
     * @param resourcePackageRepository 资源包与人工调整事实端口
     */
    public JdbcPlanSummaryRepository(JdbcTemplate jdbcTemplate,
                                     TenantResourcePackageRepository resourcePackageRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.resourcePackageRepository = resourcePackageRepository;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<TenantPlanSummary> findForCurrentProject(UUID projectId, UUID callerAccountId) {
        List<SummaryRow> rows = jdbcTemplate.query("""
                SELECT proj.tenant_id AS tenant_id,
                       s.status, s.starts_at, s.ends_at, s.billing_period, s.renewal_mode,
                       r.id AS plan_revision_id, p.code AS plan_code, r.name AS plan_name,
                       r.revision_code, r.revision_no,
                       r.reference_price_cents, r.reference_price_currency,
                       effective.plan_code AS effective_plan_code,
                       effective.plan_name AS effective_plan_name,
                       effective.revision_code AS effective_revision_code,
                       effective.revision_no AS effective_revision_no
                  FROM sys_project proj
                  JOIN sys_tenant_member caller
                    ON caller.tenant_id = proj.tenant_id
                   AND caller.account_id = ?
                   AND caller.status = 'ACTIVE'
                  JOIN sys_tenant_subscription s
                    ON s.tenant_id = proj.tenant_id
                   -- S14-6b：活状态而不是只有 ACTIVE。宽限与受限免费期（P4）里订阅仍然有效、
                   -- 读写与设备连接照常，租户必须继续看到自己的档位与有效额度；只认 ACTIVE 会让
                   -- 控制台在宽限期整段显示「本读取面不提供套餐事实」（S14-6b 整合验收发现的缺口）。
                   -- 每租户同时刻至多一条活订阅（同一行状态推进、续费换行取代旧行），
                   -- 下面的 ORDER BY + LIMIT 1 只是把「取哪一行」写死，防止事实损坏时静默取错。
                   AND s.status IN ('ACTIVE', 'GRACE', 'RESTRICTED_FREE')
                  JOIN sys_plan_revision r ON r.id = s.plan_revision_id
                  JOIN sys_plan p ON p.id = r.plan_id
                  LEFT JOIN LATERAL (
                        SELECT p2.code AS plan_code, r2.name AS plan_name,
                               r2.revision_code AS revision_code, r2.revision_no AS revision_no
                          FROM sys_tenant t
                          JOIN sys_quota_policy q ON q.id = t.quota_policy_id
                          JOIN sys_plan_revision r2 ON r2.quota_policy_id = q.id
                          JOIN sys_plan p2 ON p2.id = r2.plan_id
                         WHERE t.id = proj.tenant_id
                           AND q.plan_template
                         ORDER BY r2.revision_no DESC, p2.display_order
                         LIMIT 1
                  ) effective ON true
                 WHERE proj.id = ?
                   AND proj.deleted_at IS NULL
                   AND proj.status = 'ACTIVE'
                 ORDER BY s.starts_at DESC, s.id DESC
                 LIMIT 1
                """, (resultSet, rowNumber) -> new SummaryRow(
                resultSet.getObject("tenant_id", UUID.class),
                resultSet.getString("status"),
                resultSet.getTimestamp("starts_at").toInstant(),
                resultSet.getTimestamp("ends_at") == null
                        ? null : resultSet.getTimestamp("ends_at").toInstant(),
                resultSet.getString("billing_period"),
                resultSet.getString("renewal_mode"),
                resultSet.getObject("plan_revision_id", UUID.class),
                new PlanIdentity(resultSet.getString("plan_code"), resultSet.getString("plan_name"),
                        resultSet.getString("revision_code"), resultSet.getInt("revision_no")),
                resultSet.getObject("effective_plan_code") == null ? null : new PlanIdentity(
                        resultSet.getString("effective_plan_code"),
                        resultSet.getString("effective_plan_name"),
                        resultSet.getString("effective_revision_code"),
                        resultSet.getInt("effective_revision_no")),
                resultSet.getObject("reference_price_cents", Long.class),
                resultSet.getString("reference_price_currency")), callerAccountId, projectId);
        if (rows.isEmpty()) {
            // 调用者不是项目归属租户成员，或该租户没有处于活状态（ACTIVE/GRACE/RESTRICTED_FREE）
            // 的订阅：两种都必须返回空，
            // 而不是伪造一份零额度摘要。
            return Optional.empty();
        }
        SummaryRow row = rows.getFirst();
        List<PlanDimension> dimensions = jdbcTemplate.query("""
                SELECT dimension_code, value_amount, unit, window_kind
                  FROM sys_plan_revision_dimension
                 WHERE plan_revision_id = ?
                 ORDER BY dimension_code
                """, (resultSet, rowNumber) -> new PlanDimension(
                        resultSet.getString("dimension_code"), resultSet.getLong("value_amount"),
                        resultSet.getString("unit"), resultSet.getString("window_kind")),
                row.planRevisionId());
        List<PlanEntitlement> capabilities = jdbcTemplate.query("""
                SELECT capability_code, state
                  FROM sys_plan_entitlement
                 WHERE plan_revision_id = ?
                 ORDER BY capability_code
                """, (resultSet, rowNumber) -> new PlanEntitlement(
                        resultSet.getString("capability_code"),
                        "ENABLED".equals(resultSet.getString("state"))),
                row.planRevisionId());
        // S14-6b：与强制面同源——窗口判定统一取数据库时钟，避免 JVM 与 PostgreSQL 的毫秒级
        // 偏移让「刚生效的扩容」在只读面暂时消失。
        Instant now = DatabaseTime.now(jdbcTemplate);
        List<PlanQuotaAddition> additions = resourcePackageRepository.findLiveByTenant(row.tenantId())
                .stream()
                .map(quotaRow -> PlanQuotaAddition.from(quotaRow, now))
                .toList();
        List<PlanDimension> effectiveDimensions = findRuntimeQuota(row.tenantId(), now)
                .map(EffectivePlanQuota::effectiveDimensions)
                .orElse(List.of());
        return Optional.of(new TenantPlanSummary(row.subscribedPlan(), row.effectivePlan(),
                row.subscriptionStatus(), row.serviceStartsAt(), row.serviceEndsAt(),
                row.billingPeriod(), row.renewalMode(), row.referencePriceCents(),
                row.referencePriceCurrency(), dimensions, capabilities, effectiveDimensions,
                additions));
    }

    /**
     * 读取该租户运行时指针绑定的冻结模板，并合成此刻有效的扩容与人工调整（S14-4c）。
     *
     * <p>只接受 {@code plan_template = true} 的行：运行时指针指向 S7 遗留的非模板策略时，
     * 「有效额度」不是可售套餐语义，返回空让调用方显式表达「没有运行时额度投影」，
     * 而不是拿旧策略的列冒充套餐维度。按租户 ID 直接连接，不读会话级变量。
     *
     * @param tenantId 项目归属租户 ID
     * @param now 合成时刻（UTC）
     * @return 已含有效扩容与调整的运行时有效额度；绑定不是套餐模板时为空
     */
    private Optional<EffectivePlanQuota> findRuntimeQuota(UUID tenantId, Instant now) {
        return jdbcTemplate.query("SELECT " + PlanQuotaTemplateRows.COLUMNS
                        + " FROM sys_tenant t"
                        + " JOIN sys_quota_policy q ON q.id = t.quota_policy_id"
                        + " WHERE t.id = ? AND t.deleted_at IS NULL AND q.plan_template",
                PlanQuotaTemplateRows.MAPPER, tenantId)
                .stream().findFirst()
                .map(PlanQuotaTemplateRows.PlanQuotaTemplateRow::toBaseQuota)
                .map(base -> EffectivePlanQuota.compose(base,
                        resourcePackageRepository.findActiveAdditions(tenantId, now)));
    }

    /**
     * 订阅头与两侧档位身份的联合投影行。
     *
     * @param tenantId 项目归属租户 ID；只在仓库内部用于读取扩容事实，不进入任何响应
     * @param subscriptionStatus 订阅状态
     * @param serviceStartsAt 服务期起始时刻
     * @param serviceEndsAt 服务期结束时刻；{@code null} 表示长期有效
     * @param billingPeriod 计费周期快照
     * @param renewalMode 续费方式
     * @param planRevisionId 订阅锁定的修订版 ID，用于读取冻结维度与权益
     * @param subscribedPlan 订阅锁定的档位身份
     * @param effectivePlan 运行时指针绑定的档位身份；{@code null} 表示绑定不是套餐模板
     * @param referencePriceCents 参考价快照；不是成交价
     * @param referencePriceCurrency 参考价币种；与参考价同生共死
     */
    private record SummaryRow(
            UUID tenantId,
            String subscriptionStatus,
            Instant serviceStartsAt,
            Instant serviceEndsAt,
            String billingPeriod,
            String renewalMode,
            UUID planRevisionId,
            PlanIdentity subscribedPlan,
            PlanIdentity effectivePlan,
            Long referencePriceCents,
            String referencePriceCurrency) {
    }
}
