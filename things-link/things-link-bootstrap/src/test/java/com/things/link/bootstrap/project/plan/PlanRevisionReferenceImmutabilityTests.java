package com.things.link.bootstrap.project.plan;

import com.things.link.project.application.TenantProvisioning;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S14-6h 验收：报价修订版的不可变性以**订阅/订单引用**为判据，而不只是「已有维度或权益行」。
 *
 * <p>背景（S14-6g 复合审计发现）：{@code V20260912_0230} 把「已发布即不可变」实现为「该修订版是否已有子行」，
 * 而真正代表「已售出」的事实是 {@code sys_tenant_subscription.plan_revision_id} 与
 * {@code sys_tenant_order.plan_revision_id/source_plan_revision_id}。于是被订阅引用但子表为空的修订版
 * 仍可改价改名、甚至删除；子表也没有 INSERT 守卫，已售出的档位可以被事后追加额度行，
 * 而目录与套餐摘要读面是**实时**读这些行的。
 *
 * <p>本类钉住三态：① 未被引用的修订版仍能完整播种（否则播种路径会被自己的守卫挡住）；
 * ② 一旦被订阅或订单引用，报价列、删除与子表 INSERT 全部被拒绝；③ 引用解除后（且无子行）又能删——
 * 证明这是一条**引用判据**，不是把修订版一律锁死。
 */
@DisplayName("S14-6h 修订版引用不可变性")
class PlanRevisionReferenceImmutabilityTests extends AbstractIntegrationTest {

    /** 夹具事实读取与探针写入入口。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;
    /** 真实租户创建入口（注册同事务写 FREE 订阅与策略绑定）。 */
    @Autowired
    private TenantProvisioning tenantProvisioning;
    /** 为 {@code MANDATORY} 的租户创建提供外层事务，并用于「回滚掉探针子行」的验证。 */
    @Autowired
    private TransactionTemplate transactionTemplate;

    /** 本用例创建的租户。 */
    private final Set<UUID> tenantIds = new LinkedHashSet<>();
    /** 本用例创建的探针修订版（只被引用过，子行只在回滚事务里插入，故总能删干净）。 */
    private final Set<UUID> revisionIds = new LinkedHashSet<>();

    /** 清理夹具：先解除引用（订阅/订单），再删探针修订版与租户。 */
    @AfterEach
    void cleanUp() {
        try {
            for (UUID tenantId : tenantIds) {
                jdbcTemplate.update("DELETE FROM sys_tenant_subscription WHERE tenant_id = ?", tenantId);
                jdbcTemplate.update("DELETE FROM sys_tenant_order WHERE tenant_id = ?", tenantId);
                jdbcTemplate.update("DELETE FROM sys_tenant_member WHERE tenant_id = ?", tenantId);
                jdbcTemplate.update("DELETE FROM sys_tenant WHERE id = ?", tenantId);
            }
            for (UUID revisionId : revisionIds) {
                jdbcTemplate.update("DELETE FROM sys_plan_revision WHERE id = ?", revisionId);
            }
        } finally {
            TenantContext.clear();
            tenantIds.clear();
            revisionIds.clear();
        }
    }

    /** 未被引用的修订版仍可播种，被引用后报价列、删除与子行追加全部冻结，解除引用后又能删。 */
    @Test
    void unreferencedRevisionSeedsFreelyAndIsFrozenOnlyWhileReferenced() {
        UUID tenantId = transactionTemplate.execute(status -> tenantProvisioning.createTenant("S14-6h 引用租户"));
        assertThat(tenantId).isNotNull();
        tenantIds.add(tenantId);
        UUID revisionId = probeRevision("S14-6h 探针档位");

        // ① 未被引用：播种路径必须仍能插入维度行。用回滚事务验证，避免留下「永不可删」的子行
        //    （子表的 UPDATE/DELETE 守卫是无条件拒绝的，插进去就再也拿不出来）。
        Integer seeded = transactionTemplate.execute(status -> {
            status.setRollbackOnly();
            return jdbcTemplate.update("""
                    INSERT INTO sys_plan_revision_dimension
                        (plan_revision_id, dimension_code, value_amount, unit, window_kind)
                    VALUES (?, 'DEVICES_MAX', 5, 'COUNT', 'NONE')
                    """, revisionId);
        });
        assertThat(seeded).as("未被引用的修订版必须仍可播种冻结维度").isEqualTo(1);

        // ② 被订阅引用：报价列、删除、追加子行全部被拒绝。
        jdbcTemplate.update("""
                INSERT INTO sys_tenant_subscription (id, tenant_id, plan_revision_id, status, starts_at, ends_at,
                                                     billing_period, renewal_mode, price_cents, currency)
                VALUES (?, ?, ?, 'SUPERSEDED', now(), NULL, 'NONE', 'NONE', 0, 'CNY')
                """, Uuid7.generate(), tenantId, revisionId);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE sys_plan_revision SET name = '被引用后改名' WHERE id = ?", revisionId))
                .as("被订阅引用的修订版不得原地改名").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM sys_plan_revision WHERE id = ?", revisionId))
                .as("被订阅引用的修订版不得删除").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO sys_plan_revision_dimension
                    (plan_revision_id, dimension_code, value_amount, unit, window_kind)
                VALUES (?, 'PROJECTS_MAX', 3, 'COUNT', 'NONE')
                """, revisionId))
                .as("被订阅引用的修订版不得事后追加额度行").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO sys_plan_entitlement (plan_revision_id, capability_code, state)
                VALUES (?, 'OTA', 'ENABLED')
                """, revisionId))
                .as("被订阅引用的修订版不得事后追加权益行").isInstanceOf(DataIntegrityViolationException.class);

        // ③ 换成订单引用：同一判据同样冻结（覆盖 sys_tenant_order 的两列引用）。
        jdbcTemplate.update("DELETE FROM sys_tenant_subscription WHERE tenant_id = ?", tenantId);
        assertThat(jdbcTemplate.update(
                "UPDATE sys_plan_revision SET name = '解除引用可改' WHERE id = ?", revisionId))
                .as("引用解除后（且无子行）应恢复可改，证明这是引用判据而不是一律锁死")
                .isEqualTo(1);
        jdbcTemplate.update("""
                INSERT INTO sys_tenant_order (id, tenant_id, plan_revision_id, provider, status,
                                              amount_cents, currency, order_kind)
                VALUES (?, ?, ?, 'SIMULATED', 'CREATED', 0, 'CNY', 'PURCHASE')
                """, Uuid7.generate(), tenantId, revisionId);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE sys_plan_revision SET name = '被订单引用后改名' WHERE id = ?", revisionId))
                .as("被订单引用的修订版不得原地改名").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO sys_plan_entitlement (plan_revision_id, capability_code, state)
                VALUES (?, 'OTA', 'ENABLED')
                """, revisionId))
                .as("被订单引用的修订版不得事后追加权益行").isInstanceOf(DataIntegrityViolationException.class);

        // ④ 解除订单引用后可以删除：引用判据可逆，夹具不会在共享库里留下删不掉的行。
        jdbcTemplate.update("DELETE FROM sys_tenant_order WHERE tenant_id = ?", tenantId);
        assertThat(jdbcTemplate.update("DELETE FROM sys_plan_revision WHERE id = ?", revisionId)).isEqualTo(1);
        revisionIds.remove(revisionId);
    }

    /** 已交付的四档修订版仍被守卫拦住（既有语义未被本片放宽）。 */
    @Test
    void seededProductRevisionStaysImmutable() {
        UUID freeRevisionId = jdbcTemplate.queryForObject("""
                SELECT r.id
                  FROM sys_plan_revision r
                  JOIN sys_plan p ON p.id = r.plan_id
                 WHERE p.code = 'FREE' AND r.revision_code = 'product-revision-1'
                """, UUID.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE sys_plan_revision SET price_cents = 1 WHERE id = ?", freeRevisionId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO sys_plan_entitlement (plan_revision_id, capability_code, state)
                VALUES (?, 'OTA', 'ENABLED')
                """, freeRevisionId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * 建一条未被任何订阅/订单引用的探针修订版。
     *
     * <p>两个刻意的取值：① {@code valid_from} 取**未来**一年，探针修订版因此不会成为「当前生效」的目录项，
     * 不污染同一次运行里其它目录用例（与 {@code PlanQuotaTemplateIntegrationTests} 同一处置方式）；
     * ② 配额策略借用该套餐既有的冻结模板行——本用例验证的是不可变判据，不是策略绑定。
     */
    private UUID probeRevision(String name) {
        UUID planId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_plan WHERE code = 'STANDARD'", UUID.class);
        UUID quotaPolicyId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_quota_policy WHERE code = 'PLAN_R1_STANDARD'", UUID.class);
        int revisionNo = jdbcTemplate.queryForObject(
                "SELECT coalesce(max(revision_no), 0) + 100 FROM sys_plan_revision WHERE plan_id = ?",
                Integer.class, planId);
        UUID revisionId = Uuid7.generate();
        jdbcTemplate.update("""
                INSERT INTO sys_plan_revision (id, plan_id, revision_code, revision_no, name, sale_status,
                                               billing_period, currency, price_cents, valid_from, valid_until,
                                               quota_policy_id)
                VALUES (?, ?, ?, ?, ?, 'NOT_FOR_SALE', 'YEAR', 'CNY', NULL,
                        now() + INTERVAL '365 days', NULL, ?)
                """, revisionId, planId, "probe-immutability-" + UUID.randomUUID(), revisionNo, name,
                quotaPolicyId);
        revisionIds.add(revisionId);
        return revisionId;
    }
}
