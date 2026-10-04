package com.things.link.bootstrap.project.subscription;

import com.things.link.project.application.TenantProvisioning;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D-174 收口验收（真实 PostgreSQL）：部署工具的租户供给 SQL 与注册路径**同形**，夹具分支保持旧形态。
 *
 * <p>被测的不是 Java 服务，而是 `deploy/scripts/add-console-account.sh` 真正执行的**同一份 SQL 文本**
 * （`deploy/sql/*.sql`，脚本用 `psql -v` 传参，本用例只把 `:'变量'` 换成字面量后原样执行），
 * 因此脚本与测试之间不存在第二份实现：
 * <ul>
 *   <li>{@code provision-console-tenant.sql}（默认路径）：产出的租户行与订阅行必须与注册路径
 *       （{@link TenantProvisioning#createTenant(String)}）**逐字段一致**——这正是 D-174 的核心不变量；</li>
 *   <li>{@code legacy-console-tenant.sql}（仅测试夹具）：保持 S14 之前形态（无订阅、S7 运行时基线），
 *       E2E/契约夹具显式走它，因此既有浏览器旅程与契约套件的额度行为不变。</li>
 * </ul>
 */
@DisplayName("D-174 部署工具租户供给 SQL")
class DeployTenantProvisioningSqlTests extends AbstractIntegrationTest {

    /** 夹具事实写入与核验入口。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;
    /** 注册路径入口：作为「同形」比对的基准。 */
    @Autowired
    private TenantProvisioning tenantProvisioning;
    /** 为 {@code MANDATORY} 的租户创建提供外层事务。 */
    @Autowired
    private TransactionTemplate transactionTemplate;

    /** 本用例创建的租户，清理时按依赖顺序回收。 */
    private final Set<UUID> tenantIds = new LinkedHashSet<>();

    /** 按外键依赖顺序回收夹具。 */
    @AfterEach
    void cleanUp() {
        try {
            for (UUID tenantId : tenantIds) {
                jdbcTemplate.update("DELETE FROM sys_tenant_subscription WHERE tenant_id = ?", tenantId);
                jdbcTemplate.update("DELETE FROM sys_tenant_member WHERE tenant_id = ?", tenantId);
            }
            for (UUID tenantId : tenantIds) {
                jdbcTemplate.update("DELETE FROM sys_tenant WHERE id = ?", tenantId);
            }
        } finally {
            tenantIds.clear();
        }
    }

    /** 默认供给路径必须与注册路径产出同形的租户与订阅（D-174 的核心不变量）。 */
    @Test
    void provisioningSqlProducesSameTenantAndSubscriptionShapeAsRegistration() throws Exception {
        String sql = Files.readString(repositoryRoot().resolve("deploy/sql/provision-console-tenant.sql"));

        UUID viaSql = executeProvisioning(sql, "FREE", "D-174 SQL 供给租户");
        UUID viaRegistration = transactionTemplate.execute(status ->
                tenantProvisioning.createTenant("D-174 注册租户"));
        assertThat(viaRegistration).isNotNull();

        assertThat(tenantShape(viaSql))
                .as("租户行：策略绑定、绑定版本、展示列与状态必须与注册路径一致")
                .isEqualTo(tenantShape(viaRegistration));
        assertThat(subscriptionShape(viaSql))
                .as("订阅行：档位修订、状态、周期、续费方式、价格、币种与是否有终点必须与注册路径一致")
                .isEqualTo(subscriptionShape(viaRegistration));
        assertThat(subscriptionShape(viaSql)).containsEntry("endsAtIsNull", true);
    }

    /** 同一租户重放供给 SQL 不产生第二行（与注册路径同一幂等口径）。 */
    @Test
    void provisioningSqlIsIdempotentOnReplay() throws Exception {
        String sql = Files.readString(repositoryRoot().resolve("deploy/sql/provision-console-tenant.sql"));

        UUID tenantId = executeProvisioning(sql, "FREE", "D-174 重放租户");
        jdbcTemplate.execute("SELECT 1");

        // 同一租户 ID 再执行一次：两条 INSERT 都以 WHERE NOT EXISTS 收窄，行数必须不变。
        executeProvisioningRow(sql, "FREE", tenantId, "D-174 重放租户");

        assertThat(tenantCount(tenantId)).isEqualTo(1);
        assertThat(subscriptionCount(tenantId)).isEqualTo(1);
    }

    /** 夹具分支保持 S14 之前形态：无订阅、绑定 S7 运行时基线（非套餐模板）。 */
    @Test
    void legacyFixtureSqlKeepsUnprovisionedShape() throws Exception {
        String sql = Files.readString(repositoryRoot().resolve("deploy/sql/legacy-console-tenant.sql"));

        UUID tenantId = executeProvisioning(sql, "FREE", "D-174 夹具租户");

        assertThat(subscriptionCount(tenantId))
                .as("夹具分支不得写订阅：E2E/契约夹具的额度行为必须与 S14 之前完全一致")
                .isZero();
        assertThat(planTemplateBound(tenantId))
                .as("夹具绑定的是 S7 运行时基线（plan_template=false），因此没有免费档强制面")
                .isFalse();
        assertThat(planCode(tenantId)).isEqualTo("FREE");
    }

    /** 未知档位不产生任何行：脚本的档位校验之外，SQL 自身也 fail-closed。 */
    @Test
    void provisioningSqlInsertsNothingForUnknownPlanCode() throws Exception {
        String sql = Files.readString(repositoryRoot().resolve("deploy/sql/provision-console-tenant.sql"));

        UUID tenantId = executeProvisioningRow(sql, "NO_SUCH_PLAN", null, "D-174 未知档位");

        assertThat(tenantCount(tenantId)).as("未知档位不得落租户行").isZero();
        assertThat(subscriptionCount(tenantId)).isZero();
    }

    @Test
    void paidProvisioningCreatesExplicitSimulatedSourceAndReplayDoesNotDuplicate() throws Exception {
        String sql = Files.readString(repositoryRoot().resolve("deploy/sql/provision-console-tenant.sql"));
        UUID tenant = executeProvisioning(sql, "STANDARD", "R2 simulated");
        var fact = jdbcTemplate.queryForMap("""
                SELECT o.provider,o.status,o.provider_event_id,o.amount_cents,s.price_cents,
                       o.tenant_id=s.tenant_id AS same_tenant,o.plan_revision_id=s.plan_revision_id AS same_plan
                FROM sys_tenant_subscription s JOIN sys_tenant_order o ON o.id=s.source_order_id WHERE s.tenant_id=?
                """,tenant);
        assertThat(fact).containsEntry("provider","SIMULATED").containsEntry("status","PAID")
                .containsEntry("same_tenant",true).containsEntry("same_plan",true)
                .containsEntry("provider_event_id","provision-simulated:"+tenant);
        assertThat(fact.get("amount_cents")).isEqualTo(fact.get("price_cents"));
        executeProvisioningRow(sql,"STANDARD",tenant,"R2 replay");
        assertThat(subscriptionCount(tenant)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM sys_tenant_order WHERE tenant_id=?",Integer.class,tenant)).isEqualTo(1);
    }

    @Test
    void sourceConstraintsRejectMissingDanglingAndCrossTenant() throws Exception {
        String sql = Files.readString(repositoryRoot().resolve("deploy/sql/provision-console-tenant.sql"));
        UUID first=executeProvisioning(sql,"STANDARD","R2 A"),second=executeProvisioning(sql,"STANDARD","R2 B");
        UUID source=jdbcTemplate.queryForObject("SELECT source_order_id FROM sys_tenant_subscription WHERE tenant_id=?",UUID.class,first);
        assertThatThrownBy(()->jdbcTemplate.update("UPDATE sys_tenant_subscription SET source_order_id=NULL WHERE tenant_id=?",first)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbcTemplate.update("UPDATE sys_tenant_subscription SET source_order_id=? WHERE tenant_id=?",UUID.randomUUID(),first)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbcTemplate.update("UPDATE sys_tenant_subscription SET source_order_id=? WHERE tenant_id=?",source,second)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbcTemplate.update("DELETE FROM sys_tenant_order WHERE id=?",source)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbcTemplate.queryForObject("SELECT source_order_id FROM sys_tenant_subscription WHERE tenant_id=?",UUID.class,first)).isEqualTo(source);
    }

    // ------------------------------------------------------------------
    // 夹具与断言辅助
    // ------------------------------------------------------------------

    /**
     * 执行供给 SQL 并断言租户行已落库。
     *
     * @param sql 供给 SQL 文本
     * @param planCode 档位编码（替换 {@code :'plan_code'}）
     * @param tenantName 租户名
     * @return 新租户 ID
     */
    private UUID executeProvisioning(String sql, String planCode, String tenantName) {
        UUID tenantId = executeProvisioningRow(sql, planCode, null, tenantName);
        assertThat(tenantCount(tenantId)).as("供给 SQL 必须落租户行").isEqualTo(1);
        return tenantId;
    }

    /**
     * 执行供给 SQL（把 psql 变量替换为字面量，文本其余部分原样）。
     *
     * @param sql 供给 SQL 文本
     * @param planCode 档位编码
     * @param fixedTenantId 指定租户 ID；{@code null} 表示自动生成
     * @param tenantName 租户名
     * @return 本次使用的租户 ID
     */
    private UUID executeProvisioningRow(String sql, String planCode, UUID fixedTenantId,
                                        String tenantName) {
        UUID tenantId = fixedTenantId == null ? UUID.randomUUID() : fixedTenantId;
        tenantIds.add(tenantId);
        String substituted = sql
                .replace(":'tenant_id'", "'" + tenantId + "'")
                .replace(":'tenant_name'", "'" + tenantName + "'")
                .replace(":'plan_code'", "'" + planCode + "'");
        assertThat(substituted).as("psql 变量必须已被替换，否则执行的就不是同一份 SQL").doesNotContainPattern(":'[a-z_][a-z0-9_]*'");
        jdbcTemplate.execute(substituted);
        return tenantId;
    }

    /**
     * 读取租户行上与商用供给有关的列。
     *
     * @param tenantId 租户 ID
     * @return 列名到值的映射（策略绑定与展示列）
     */
    private Map<String, Object> tenantShape(UUID tenantId) {
        Map<String, Object> shape = new LinkedHashMap<>(jdbcTemplate.queryForMap("""
                SELECT status, plan_code, quota_policy_id, quota_policy_assignment_version
                  FROM sys_tenant WHERE id = ?
                """, tenantId));
        return shape;
    }

    /**
     * 读取当前 ACTIVE 订阅的关键字段。
     *
     * @param tenantId 租户 ID
     * @return 列名到值的映射；无订阅时为空映射
     */
    private Map<String, Object> subscriptionShape(UUID tenantId) {
        var rows = jdbcTemplate.queryForList("""
                SELECT status, plan_revision_id, billing_period, renewal_mode, price_cents, currency,
                       (ends_at IS NULL) AS ends_at_is_null, source_order_id
                  FROM sys_tenant_subscription
                 WHERE tenant_id = ? AND status = 'ACTIVE'
                """, tenantId);
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> row = new LinkedHashMap<>(rows.getFirst());
        // 不同实现可能给 Boolean 或 String，统一成布尔后再比对，避免驱动差异造成假失败。
        Map<String, Object> shape = new LinkedHashMap<>();
        row.forEach((key, value) -> shape.put(
                "ends_at_is_null".equals(key) ? "endsAtIsNull" : key,
                "ends_at_is_null".equals(key) ? Boolean.parseBoolean(String.valueOf(value)) : value));
        return shape;
    }

    /** @param tenantId 租户 ID @return 租户行数 */
    private int tenantCount(UUID tenantId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sys_tenant WHERE id = ?", Integer.class, tenantId);
    }

    /** @param tenantId 租户 ID @return 该租户 ACTIVE 订阅行数 */
    private int subscriptionCount(UUID tenantId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sys_tenant_subscription WHERE tenant_id = ?", Integer.class, tenantId);
    }

    /** @param tenantId 租户 ID @return 绑定策略是否为 S14 冻结套餐模板 */
    private boolean planTemplateBound(UUID tenantId) {
        Boolean template = jdbcTemplate.queryForObject("""
                SELECT q.plan_template FROM sys_tenant t JOIN sys_quota_policy q ON q.id = t.quota_policy_id
                 WHERE t.id = ?
                """, Boolean.class, tenantId);
        return Boolean.TRUE.equals(template);
    }

    /** @param tenantId 租户 ID @return 展示兼容列 {@code plan_code} */
    private String planCode(UUID tenantId) {
        return jdbcTemplate.queryForObject(
                "SELECT plan_code FROM sys_tenant WHERE id = ?", String.class, tenantId);
    }

    /**
     * 同时兼容从仓库根目录、后端 reactor、bootstrap 模块与 IDE 启动测试。
     *
     * @return 同时包含 docs 与 things-link 的仓库根目录
     */
    private static Path repositoryRoot() {
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve("docs"))
                    && Files.isRegularFile(candidate.resolve("things-link/pom.xml"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("无法定位 ThingsLink 仓库根目录");
    }
}
