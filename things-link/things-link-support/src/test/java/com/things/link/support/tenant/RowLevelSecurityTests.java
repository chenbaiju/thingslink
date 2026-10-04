package com.things.link.support.tenant;

import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 行级安全的机制验证与强制检查。
 *
 * <p>架构文档第 7 节：应用层的 {@link TenantContext} 是第一道防线，RLS 是第二道。
 * 第二道防线的价值恰恰在于第一道失效时它还在 —— 所以必须独立验证它真的生效，
 * 而不是「策略建了就当它管用」。
 */
@DisplayName("行级安全（BACKEND_ARCHITECTURE.md 第 7 节）")
class RowLevelSecurityTests extends AbstractIntegrationTest {

    /**
     * 豁免 RLS 的表，每一张都要写明理由。
     *
     * <p>这个清单存在的意义是<b>强迫豁免成为一个显式决定</b>。没有它的话，
     * 下面那条强制检查只能整体开或整体关，而现实中确实有个别平台级表不按租户隔离。
     */
    private static final Set<String> RLS_EXEMPT_TABLES = Set.of(
            // Flyway 自己的历史表，无租户概念
            "flyway_schema_history",
            // ADR0102起新HTTP记录只在认证与可信范围后写入，但本技术表仍需无请求范围的
            // 清理器访问，并在升级窗口兼容查询旧NULL范围墓碑，因此暂不直接套业务RLS。
            // 新键按actor摘要且查询显式绑定tenant/project；不再保存HTTP响应。
            "sys_idempotency_record",
            // sys_account 与 sys_tenant_member：认证发生在租户身份确立之前。登录时只有
            // 邮箱和口令，必须先查这两张表才能知道租户是谁 —— 而 RLS 是 fail-closed 的，
            // 加了策略就永远查不到，形成先有鸡还是先有蛋。
            // 它们的访问控制只有应用层一道，见架构文档第 9 节与两份迁移的头部注释。
            "sys_account",
            "sys_tenant_member",
            // sys_refresh_token：同一个次序问题。刷新请求只携带一个令牌，此时租户上下文
            // 尚未建立 —— 必须先查本表才知道租户是谁。它的隔离由令牌自身的 256 位
            // 随机性与查询中显式的 account_id 保证，见 V20260801_1120。
            "sys_refresh_token",
            // app_refresh_token：与控制台刷新令牌同一个「先有鸡还是先有蛋」次序问题，
            // 只是作用于第二类身份 app_user。刷新请求只带一个令牌，此时租户/项目上下文
            // 都还没建立 —— 必须先查本表才能恢复上下文去复核 app_user + app_user_role。
            // 表本身在 enduser 模块，support 的迁移测试看不到它，此处登记仅作「豁免清单」
            // 权威记录；真正的「无策略」钉住测试在 bootstrap 的 EndUserIsolationIntegrationTests。
            "app_refresh_token",
            // sys_project 与 sys_project_member：同一类先有鸡还是先有蛋。
            // 它们正是用来**确定当前用户能进哪些项目**的表 —— 给它们加项目级策略，
            // 就要先知道当前项目才能查项目。访问控制只有应用层一道：
            // 列表查询必须 join sys_project_member 限定到当前账号（ADR 0012）。
            "sys_project",
            "sys_project_member",
            // CONSOLE-TODO-001：接受前尚无项目成员上下文，邀请目录按管理范围/已验证邮箱/有效码显式确权。
            "sys_project_invitation",
            // sys_audit_log：审计查询同时服务项目内排障、租户账务追溯与平台运维视角。
            // 直接套项目 RLS 会让跨项目/跨租户追溯变得含混；它的访问控制应由
            // 专门的审计查询接口显式判定。写入侧由不可变触发器保护，见迁移注释。
            "sys_audit_log");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    /**
     * <b>本类最重要的一条。</b>扫描所有带 {@code project_id} 列的表，断言每一张都
     * 启用了 RLS 且有策略。开发手册 6.2 要求「新建表时同步建策略，别攒到最后」——
     * 这条测试就是那个「同步」的强制手段。没有它的话，每加一张表都靠记性，
     * 而漏掉的那张不会有任何症状：功能正常、测试全绿，只是少了一道防线。
     *
     * <p><b>隔离轴已从租户改为项目（ADR 0012）。</b>
     *
     * <p>原规则检查的是「带 tenant_id 的表是否有策略」。校准后业务表仍然保留
     * tenant_id 列（计费聚合与归属追溯要用），但策略不建在它上面 —— 继续按老规则
     * 检查的话，每一张业务表都要写进豁免清单，那份清单很快就会长到没人看，
     * 于是失去意义。
     *
     * <p>因此本规则改为检查 {@code project_id}。租户轴的
     * {@code enable_tenant_rls()} 仍然保留，供真正按租户隔离的表（账单、
     * 配额用量）使用。S7-2 起这类表由所属业务模块的集成测试额外钉住策略轴，
     * 因为 support 模块自己的迁移测试不会加载后续业务模块迁移。
     */
    @Test
    @DisplayName("每张带 project_id 的表都必须启用 RLS 并有策略")
    void everyProjectScopedTableHasRowLevelSecurity() {
        List<String> unprotected = jdbcTemplate.queryForList("""
                SELECT c.relname
                  FROM pg_class c
                  JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = 'public'
                   AND c.relkind = 'r'
                   AND EXISTS (SELECT 1 FROM information_schema.columns col
                                WHERE col.table_schema = 'public'
                                  AND col.table_name = c.relname
                                  AND col.column_name = 'project_id')
                   AND (c.relrowsecurity = false
                        OR NOT EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = c.oid))
                 ORDER BY c.relname
                """, String.class);

        assertThat(unprotected)
                .as("这些表带 project_id 却没有 RLS 策略。在建表迁移里加一行 "
                        + "SELECT enable_project_rls('<表名>')；确实不该隔离的话，"
                        + "把表名加进 RLS_EXEMPT_TABLES 并写明理由")
                .allSatisfy(table -> assertThat(RLS_EXEMPT_TABLES).contains(table));
    }

    /**
     * 验证运行时账号确实受 RLS 约束。
     *
     * <p>这条守的是整套机制的前提。PostgreSQL 的表 owner 默认绕过 RLS，
     * superuser 永远绕过 —— 如果应用不小心用了建表的账号连接，
     * 下面所有隔离测试都会「通过」，因为压根没有策略在起作用。
     */
    @Test
    @DisplayName("应用连接用的是非 owner 且非超级用户的账号")
    void applicationConnectsAsNonOwnerRole() {
        String currentUser = jdbcTemplate.queryForObject("SELECT current_user", String.class);
        Boolean isSuperuser = jdbcTemplate.queryForObject(
                "SELECT rolsuper FROM pg_roles WHERE rolname = current_user", Boolean.class);
        Boolean bypassesRls = jdbcTemplate.queryForObject(
                "SELECT rolbypassrls FROM pg_roles WHERE rolname = current_user", Boolean.class);

        assertThat(currentUser)
                .as("应用必须用 %s 连接；用表 owner 连接会让所有 RLS 策略静默失效", APP_ROLE)
                .isEqualTo(APP_ROLE);
        assertThat(isSuperuser).isFalse();
        assertThat(bypassesRls).isFalse();
    }

    @Test
    @DisplayName("租户只能看到自己的数据")
    void isolatesRowsAcrossTenants() {
        UUID tenantA = Uuid7.generate();
        UUID tenantB = Uuid7.generate();

        withTenant(tenantA, () -> insertProbe(tenantA, "属于租户 A"));
        withTenant(tenantB, () -> insertProbe(tenantB, "属于租户 B"));

        List<String> visibleToA = withTenant(tenantA, this::selectProbeNotes);
        List<String> visibleToB = withTenant(tenantB, this::selectProbeNotes);

        assertThat(visibleToA).containsExactly("属于租户 A");
        assertThat(visibleToB).containsExactly("属于租户 B");
    }

    /**
     * 未设置租户时一行也看不到。
     *
     * <p>反过来的设计（未设置时不过滤）看似方便，实际是把安全控制做成了 fail-open：
     * 应用层任何一处忘了设置租户，RLS 就自动放行全部数据 —— 那恰恰是它本该拦住的
     * 情况。宁可查不到数据报错，也不能静默泄露。
     */
    @Test
    @DisplayName("未设置租户时查不到任何数据（fail-closed）")
    void hidesEverythingWhenTenantUnset() {
        UUID tenantA = Uuid7.generate();
        withTenant(tenantA, () -> insertProbe(tenantA, "属于租户 A"));

        // 不设置 TenantContext，直接查
        assertThat(selectProbeNotes())
                .as("没有租户上下文时必须查不到数据，而不是查到全部")
                .isEmpty();
    }

    /**
     * 写入其他租户的数据会被拒绝。
     *
     * <p>这条守的是策略里的 {@code WITH CHECK}。只写 {@code USING} 的话，
     * 租户 A 可以插入一行 {@code tenant_id} 是租户 B 的数据 —— 插进去之后
     * 自己还看不见它。这是最容易漏掉的一半。
     */
    @Test
    @DisplayName("不能写入其他租户的数据（WITH CHECK）")
    void rejectsWritingForAnotherTenant() {
        UUID tenantA = Uuid7.generate();
        UUID tenantB = Uuid7.generate();

        // 断言写在 root cause 上：Spring 会把 PSQLException 包成 DataAccessException，
        // 外层消息只有 SQL 语句本身，PostgreSQL 的真实原因在 cause 里
        assertThatThrownBy(() -> withTenant(tenantA, () -> insertProbe(tenantB, "伪造成租户 B 的数据")))
                .as("策略的 WITH CHECK 必须拦住越权写入")
                .rootCause()
                .hasMessageContaining("row-level security");
    }

    private void insertProbe(UUID tenantId, String note) {
        jdbcTemplate.update("INSERT INTO rls_probe (id, tenant_id, note) VALUES (?, ?, ?)",
                Uuid7.generate(), tenantId, note);
    }

    private List<String> selectProbeNotes() {
        return jdbcTemplate.queryForList("SELECT note FROM rls_probe ORDER BY note", String.class);
    }

    /**
     * 在指定租户上下文中执行一段逻辑。
     *
     * <p>{@link TenantAwareDataSource} 在借出连接时读取 {@link TenantContext}，
     * 所以必须先设置上下文、再执行 SQL。
     */
    private <T> T withTenant(UUID tenantId, Supplier<T> action) {
        TenantContext.set(new TenantScope(tenantId, null, Uuid7.generate()));
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    private void withTenant(UUID tenantId, Runnable action) {
        withTenant(tenantId, () -> {
            action.run();
            return null;
        });
    }

    // ------------------------------------------------------------ 项目轴（ADR 0012）

    /**
     * <b>校准后业务表走的就是这条轴。</b>
     *
     * <p>关键在于两个项目属于<b>不同的租户</b>：这正是校准要解决的场景 ——
     * 项目所有者邀请其他租户的账号来协作。按租户轴隔离的话，协作者要么什么都看不到，
     * 要么能看到对方租户的全部项目；按项目轴才恰好是「只看到被邀请的那一个」。
     */
    @Test
    @DisplayName("项目只能看到自己的数据，且不受所属租户影响")
    void isolatesRowsAcrossProjects() {
        UUID tenantA = Uuid7.generate();
        UUID tenantB = Uuid7.generate();
        UUID projectA = Uuid7.generate();
        UUID projectB = Uuid7.generate();

        withProject(tenantA, projectA, () -> insertProjectProbe(tenantA, projectA, "项目 A 的数据"));
        withProject(tenantB, projectB, () -> insertProjectProbe(tenantB, projectB, "项目 B 的数据"));

        assertThat(withProject(tenantA, projectA, this::selectProjectProbeNotes))
                .containsExactly("项目 A 的数据");
        assertThat(withProject(tenantB, projectB, this::selectProjectProbeNotes))
                .containsExactly("项目 B 的数据");
    }

    /**
     * 只有租户上下文、没有项目上下文时，项目级表必须一行也读不到。
     *
     * <p>这条防的是「把租户当项目用」这类误配：如果策略被写成按 tenant_id，
     * 下面这次查询会读到数据，而不是空。
     */
    @Test
    @DisplayName("只有租户、没有项目时查不到项目级数据（fail-closed）")
    void hidesProjectRowsWhenProjectUnset() {
        UUID tenantA = Uuid7.generate();
        UUID projectA = Uuid7.generate();
        withProject(tenantA, projectA, () -> insertProjectProbe(tenantA, projectA, "项目 A 的数据"));

        // 只设租户，不设项目
        assertThat(withTenant(tenantA, this::selectProjectProbeNotes))
                .as("项目级策略必须只认 project_id；能靠 tenant_id 读到就说明轴配错了")
                .isEmpty();
    }

    /**
     * 越权写入同样要被 {@code WITH CHECK} 拦住 —— 项目轴上这一半和租户轴一样容易漏。
     */
    @Test
    @DisplayName("不能写入其他项目的数据（WITH CHECK）")
    void rejectsWritingForAnotherProject() {
        UUID tenantA = Uuid7.generate();
        UUID projectA = Uuid7.generate();
        UUID projectB = Uuid7.generate();

        assertThatThrownBy(() -> withProject(tenantA, projectA,
                () -> insertProjectProbe(tenantA, projectB, "伪造成项目 B 的数据")))
                .rootCause()
                .hasMessageContaining("row-level security");
    }

    private void insertProjectProbe(UUID tenantId, UUID projectId, String note) {
        jdbcTemplate.update(
                "INSERT INTO project_rls_probe (id, tenant_id, project_id, note) VALUES (?, ?, ?, ?)",
                Uuid7.generate(), tenantId, projectId, note);
    }

    private List<String> selectProjectProbeNotes() {
        return jdbcTemplate.queryForList(
                "SELECT note FROM project_rls_probe ORDER BY note", String.class);
    }

    /**
     * 在指定的租户 + 项目上下文中执行一段逻辑。
     *
     * @param tenantId  租户
     * @param projectId 项目
     * @param action    待执行逻辑
     * @param <T>       返回类型
     * @return 逻辑的返回值
     */
    private <T> T withProject(UUID tenantId, UUID projectId, Supplier<T> action) {
        TenantContext.set(new TenantScope(tenantId, projectId, Uuid7.generate()));
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    private void withProject(UUID tenantId, UUID projectId, Runnable action) {
        withProject(tenantId, projectId, () -> {
            action.run();
            return null;
        });
    }

}
