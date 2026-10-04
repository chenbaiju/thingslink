package com.things.link.bootstrap.project.subscription;

import com.things.link.project.application.TenantProvisioning;
import com.things.link.project.application.TenantSubscriptionProvisioning;
import com.things.link.project.domain.plan.ProductRevision1;
import com.things.link.shared.id.Uuid7;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S14-2a 租户订阅事实与注册默认 FREE 的真实 PostgreSQL 验收（架构文档 §3.2）。
 *
 * <p>本类钉住四件事：新租户在注册同一事务里恰好得到一条 ACTIVE 零价 FREE 订阅、该订阅指向
 * {@code product-revision-1} 的 FREE 修订版与 {@code PLAN_R1_FREE} 模板、运行时绑定版本恰好递增一次、
 * 重放补建既不重复建行也不重复递增。另外两条数据库不变式由 DB 自己守：每租户至多一条 ACTIVE 基础订阅，
 * 无终点的订阅只能是零价无计费周期（服务期语义，不是「不限额度」）。
 *
 * <p>存量租户不做迁移回填：直接落库、没有订阅的租户行必须保持可读且不被本迁移改写，免费订阅与指针
 * 推进留给显式操作走同一个应用服务。最后一条用例就钉住这个边界。
 */
@DisplayName("S14-2a 租户订阅事实与注册默认 FREE")
class TenantFreeSubscriptionIntegrationTests extends AbstractIntegrationTest {

    /** 订阅事实、租户指针与目录引用的验证入口。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;
    /** 租户创建的模块唯一入口；FREE 订阅在同一事务内补齐。 */
    @Autowired
    private TenantProvisioning tenantProvisioning;
    /** 显式补建/重放免费订阅的应用服务。 */
    @Autowired
    private TenantSubscriptionProvisioning tenantSubscriptionProvisioning;
    /** 为 {@code MANDATORY} 的租户创建入口提供注册业务事务。 */
    @Autowired
    private TransactionTemplate transactionTemplate;

    /** 本用例创建的租户，清理时先删订阅再删租户。 */
    private final Set<UUID> tenantIds = new LinkedHashSet<>();

    /** 回收共享 Testcontainers 数据库中的夹具，避免污染其他测试类。 */
    @AfterEach
    void cleanUp() {
        for (UUID tenantId : tenantIds) {
            jdbcTemplate.update("DELETE FROM sys_tenant_subscription WHERE tenant_id = ?", tenantId);
            jdbcTemplate.update("DELETE FROM sys_tenant WHERE id = ?", tenantId);
        }
        tenantIds.clear();
    }

    /** 新租户恰好一条 ACTIVE 零价 FREE 订阅，且指向 product-revision-1 的 FREE 档与 PLAN_R1_FREE。 */
    @Test
    void newTenantGetsExactlyOneActiveFreeSubscriptionBoundToProductRevision1AndPlanR1Free() {
        UUID tenantId = inTransaction(() -> tenantProvisioning.createTenant("S14-2a 免费租户"));
        tenantIds.add(tenantId);

        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*)
                  FROM sys_tenant_subscription s
                  JOIN sys_plan_revision r ON r.id = s.plan_revision_id
                  JOIN sys_plan p ON p.id = r.plan_id
                 WHERE s.tenant_id = ?
                   AND p.code = 'FREE'
                   AND r.revision_code = 'product-revision-1'
                """, Integer.class, tenantId))
                .as("新租户的订阅必须指向 product-revision-1 的 FREE 修订版，不能是别的档位或别的修订版")
                .isEqualTo(1);

        Map<String, Object> subscription = jdbcTemplate.queryForMap("""
                SELECT status, plan_revision_id, price_cents, currency, billing_period,
                       ends_at, source_order_id, renewal_mode, revision
                  FROM sys_tenant_subscription
                 WHERE tenant_id = ?
                """, tenantId);
        assertThat(subscription.get("status")).isEqualTo("ACTIVE");
        assertThat(subscription.get("plan_revision_id")).isEqualTo(freeRevisionId());
        assertThat(((Number) subscription.get("price_cents")).longValue())
                .as("FREE 是价格为零的真实订阅，不是没有订阅（架构文档 §4.1）")
                .isZero();
        assertThat(subscription.get("currency")).isEqualTo("CNY");
        assertThat(subscription.get("billing_period")).isEqualTo("NONE");
        assertThat(subscription.get("renewal_mode")).isEqualTo("NONE");
        assertThat(subscription.get("ends_at"))
                .as("零价长期 FREE 的服务期没有终点；这是服务期语义，不是「不限额度」")
                .isNull();
        assertThat(subscription.get("source_order_id"))
                .as("支付与订单尚未落地（S14-5），FREE 订阅不得凭空引用订单")
                .isNull();
        assertThat(((Number) subscription.get("revision")).longValue()).isEqualTo(1L);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT quota_policy_id FROM sys_tenant WHERE id = ?", UUID.class, tenantId))
                .as("运行时有效策略指针必须绑到 PLAN_R1_FREE，而不是 S7 遗留的 FREE 模板")
                .isEqualTo(planR1FreePolicyId());
        assertThat(assignmentVersion(tenantId))
                .as("租户行默认版本为 1，注册恰好推进一次到 2")
                .isEqualTo(2L);
    }

    /**
     * 存量/直接落库的租户不被迁移回填；显式补建恰好把绑定版本推进一次。
     *
     * <p>迁移里的 SQL 回填无法广播缓存失效事件，只会留下「订阅说 FREE、指针还指向旧模板」的静默漂移，
     * 因此本片选择不回填，并把补建留给走同一 CAS 协议的应用服务。
     */
    @Test
    void existingTenantWithoutSubscriptionIsNotBackfilledAndExplicitProvisioningAdvancesVersionByOne() {
        UUID tenantId = Uuid7.generate();
        jdbcTemplate.update("INSERT INTO sys_tenant (id, name) VALUES (?, ?)", tenantId, "S14-2a 存量租户");
        tenantIds.add(tenantId);

        assertThat(subscriptionCount(tenantId))
                .as("迁移不替存量租户伪造订阅；它保持「没有订阅」而不是被静默改写")
                .isZero();
        assertThat(assignmentVersion(tenantId)).isEqualTo(1L);

        inTransaction(() -> {
            tenantSubscriptionProvisioning.provisionFreeSubscription(tenantId);
            return null;
        });

        assertThat(subscriptionCount(tenantId)).isEqualTo(1);
        assertThat(assignmentVersion(tenantId))
                .as("显式补建恰好推进一次绑定版本：从默认 1 到 2，不多也不少")
                .isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT quota_policy_id FROM sys_tenant WHERE id = ?", UUID.class, tenantId))
                .isEqualTo(planR1FreePolicyId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT name FROM sys_tenant WHERE id = ?", String.class, tenantId))
                .as("补建不得改写租户自身的事实")
                .isEqualTo("S14-2a 存量租户");
    }

    /** 重放补建既不建第二条 ACTIVE 订阅，也不把绑定版本从 2 抬到 3。 */
    @Test
    void replayedProvisioningCreatesNoDuplicateAndDoesNotDoubleBumpAssignmentVersion() {
        UUID tenantId = inTransaction(() -> tenantProvisioning.createTenant("S14-2a 重放租户"));
        tenantIds.add(tenantId);
        assertThat(assignmentVersion(tenantId)).isEqualTo(2L);

        inTransaction(() -> {
            tenantSubscriptionProvisioning.provisionFreeSubscription(tenantId);
            return null;
        });
        inTransaction(() -> {
            tenantSubscriptionProvisioning.provisionFreeSubscription(tenantId);
            return null;
        });

        assertThat(subscriptionCount(tenantId)).isEqualTo(1);
        assertThat(assignmentVersion(tenantId))
                .as("重放不是新建：绑定版本必须停在 2，重复推进会让缓存失效事件顺序失去意义")
                .isEqualTo(2L);
    }

    /** 同一租户的第二条 ACTIVE 基础订阅被数据库拒绝（历史行仍可累积）。 */
    @Test
    void databaseRejectsSecondActiveBaseSubscriptionForSameTenant() {
        UUID tenantId = inTransaction(() -> tenantProvisioning.createTenant("S14-2a 单活租户"));
        tenantIds.add(tenantId);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO sys_tenant_subscription (
                    id, tenant_id, plan_revision_id, status, starts_at, ends_at,
                    billing_period, renewal_mode, price_cents, currency, source_order_id, revision)
                VALUES (?, ?, ?, 'ACTIVE', now(), NULL, 'NONE', 'NONE', 0, 'CNY', NULL, 1)
                """, Uuid7.generate(), tenantId, freeRevisionId()))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 非 ACTIVE 的历史行不受该约束：终态订阅必须能无限累积以保留全部历史。
        jdbcTemplate.update("""
                INSERT INTO sys_tenant_subscription (
                    id, tenant_id, plan_revision_id, status, starts_at, ends_at,
                    billing_period, renewal_mode, price_cents, currency, source_order_id, revision)
                VALUES (?, ?, ?, 'EXPIRED', now() - interval '1 day', now(), 'MONTH', 'MANUAL', 0, 'CNY', NULL, 1)
                """, Uuid7.generate(), tenantId, freeRevisionId());
        assertThat(subscriptionCount(tenantId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM sys_tenant_subscription
                 WHERE tenant_id = ? AND status = 'ACTIVE'
                """, Integer.class, tenantId)).isEqualTo(1);
    }

    /** 无终点的订阅只能是零价且无计费周期；付费档不得借 NULL 表达「不限时长」。 */
    @Test
    void perpetualSubscriptionMustBeZeroPriceWithoutBillingPeriod() {
        UUID tenantId = Uuid7.generate();
        jdbcTemplate.update("INSERT INTO sys_tenant (id, name) VALUES (?, ?)", tenantId, "S14-2a 探测租户");
        tenantIds.add(tenantId);

        assertThatThrownBy(() -> insertSubscription(tenantId, "ACTIVE", "MONTH", 298000L))
                .as("付费档没有服务期终点必须被拒，否则 NULL 会被读成「永久有效」")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertSubscription(tenantId, "ACTIVE", "YEAR", 0L))
                .as("有计费周期的订阅必须给出服务期终点")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(subscriptionCount(tenantId)).isZero();

        insertSubscription(tenantId, "ACTIVE", "NONE", 0L);
        assertThat(subscriptionCount(tenantId)).isEqualTo(1);
    }

    /**
     * 在注册业务事务里执行一段逻辑；{@code TenantProvisioning} 用 {@code MANDATORY} 强制外层事务。
     *
     * @param action 待执行逻辑
     * @param <T> 返回类型
     * @return 逻辑返回值
     */
    private <T> T inTransaction(Supplier<T> action) {
        return transactionTemplate.execute(status -> action.get());
    }

    /** @param tenantId 租户 ID @return 该租户的订阅行数（含历史） */
    private int subscriptionCount(UUID tenantId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sys_tenant_subscription WHERE tenant_id = ?", Integer.class, tenantId);
    }

    /** @param tenantId 租户 ID @return 运行时配额策略绑定版本 */
    private long assignmentVersion(UUID tenantId) {
        return jdbcTemplate.queryForObject(
                "SELECT quota_policy_assignment_version FROM sys_tenant WHERE id = ?", Long.class, tenantId);
    }

    /** @return {@code product-revision-1} 的 FREE 修订版 ID */
    private UUID freeRevisionId() {
        return jdbcTemplate.queryForObject("""
                SELECT r.id
                  FROM sys_plan_revision r
                  JOIN sys_plan p ON p.id = r.plan_id
                 WHERE p.code = ? AND r.revision_code = ?
                """, UUID.class, ProductRevision1.FREE_PLAN_CODE, ProductRevision1.CODE);
    }

    /** @return {@code PLAN_R1_FREE} 配额模板 ID */
    private UUID planR1FreePolicyId() {
        return jdbcTemplate.queryForObject("SELECT id FROM sys_quota_policy WHERE code = ?", UUID.class,
                ProductRevision1.quotaPolicyCode(ProductRevision1.FREE_PLAN_CODE));
    }

    /**
     * 插入一条无终点的探针订阅，验证数据库对「无终点」形状的约束。
     *
     * @param tenantId 租户 ID
     * @param status 订阅状态
     * @param billingPeriod 计费周期
     * @param priceCents 成交价（人民币分）
     */
    private void insertSubscription(UUID tenantId, String status, String billingPeriod, long priceCents) {
        jdbcTemplate.update("""
                INSERT INTO sys_tenant_subscription (
                    id, tenant_id, plan_revision_id, status, starts_at, ends_at,
                    billing_period, renewal_mode, price_cents, currency, source_order_id, revision)
                VALUES (?, ?, ?, ?, now(), NULL, ?, 'NONE', ?, 'CNY', NULL, 1)
                """, Uuid7.generate(), tenantId, freeRevisionId(), status, billingPeriod, priceCents);
    }
}
