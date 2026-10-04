package com.things.link.bootstrap.enduser;

import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 终端用户身份的两轴 RLS 与复合外键验收（S11-1a）。
 *
 * <h2>为什么放在 bootstrap 而不是 support</h2>
 * support 的 {@code RowLevelSecurityTests} 只加载 support 自己的迁移，看不到 enduser 的
 * 四张表。这条测试钉住的是「app_user 走租户轴、app_user_role / app_user_device /
 * app_device_bind_token 走项目轴」
 * 这个轴分配 —— 轴配错时没有编译错误、没有功能症状，只有隔离测试会红。
 *
 * <h2>复合外键的独立价值</h2>
 * 项目轴 RLS 的 {@code WITH CHECK} 只查 {@code project_id}，<b>不查 {@code tenant_id}</b>。
 * 也就是说，单靠 RLS 是能写出一行「tenant_id 与项目归属不一致」的脏数据的 —— 它插得进去，
 * 只是以后计费/审计会把它算进错误的租户。两条复合外键在数据库层直接拒绝这种写入，而不是靠
 * RLS「隐藏」它（ADR 0035）。
 */
@DisplayName("终端用户两轴 RLS 与复合外键（S11-1a）")
class EndUserIsolationIntegrationTests extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 本用例插入的租户，按依赖顺序在 @AfterEach 回收。 */
    private final Set<UUID> tenantIds = new LinkedHashSet<>();
    /** 项目 → 归属租户，回收项目级事实时用于建立正确的项目上下文。 */
    private final Map<UUID, UUID> projectTenants = new LinkedHashMap<>();

    @AfterEach
    void cleanup() {
        try {
            for (Map.Entry<UUID, UUID> entry : projectTenants.entrySet()) {
                UUID projectId = entry.getKey();
                TenantContext.set(new TenantScope(entry.getValue(), projectId, Uuid7.generate()));
                jdbcTemplate.update("DELETE FROM app_device_bind_token WHERE project_id = ?", projectId);
                jdbcTemplate.update("DELETE FROM app_user_device WHERE project_id = ?", projectId);
                jdbcTemplate.update("DELETE FROM app_user_role WHERE project_id = ?", projectId);
            }
            for (UUID tenantId : tenantIds) {
                TenantContext.set(new TenantScope(tenantId, null, Uuid7.generate()));
                jdbcTemplate.update("DELETE FROM app_user WHERE tenant_id = ?", tenantId);
            }
            TenantContext.clear();
            for (UUID projectId : projectTenants.keySet()) {
                jdbcTemplate.update("DELETE FROM sys_project WHERE id = ?", projectId);
            }
            for (UUID tenantId : tenantIds) {
                jdbcTemplate.update("DELETE FROM sys_tenant WHERE id = ?", tenantId);
            }
        } finally {
            TenantContext.clear();
        }
    }

    /** app_user 是租户级身份，走租户轴；三张项目事实表走项目轴。 */
    @Test
    @DisplayName("轴分配正确：app_user 走租户轴，role/device 走项目轴")
    void migrationEnablesCorrectRlsPolicyAxis() {
        assertThat(rlsPolicy("app_user")).containsExactly("tenant_isolation");
        assertThat(rlsPolicy("app_user_role")).containsExactly("project_isolation");
        assertThat(rlsPolicy("app_user_device")).containsExactly("project_isolation");
        assertThat(rlsPolicy("app_device_bind_token")).containsExactly("project_isolation");
        assertThat(rlsEnabled("app_user")).isTrue();
        assertThat(rlsEnabled("app_user_role")).isTrue();
        assertThat(rlsEnabled("app_user_device")).isTrue();
        assertThat(rlsEnabled("app_device_bind_token")).isTrue();
    }

    /** app_refresh_token 是上下文建立类 RLS 例外（与控制台 sys_refresh_token 同构）。 */
    @Test
    @DisplayName("app_refresh_token 豁免 RLS，钉住无策略")
    void appRefreshTokenIsRlsExempt() {
        assertThat(tableExists("app_refresh_token"))
                .as("钉住豁免的测试必须先确认表真实存在，否则空策略断言会空转")
                .isTrue();
        assertThat(rlsPolicy("app_refresh_token")).isEmpty();
        assertThat(rlsEnabled("app_refresh_token")).isFalse();
    }

    /** 租户轴：终端用户登录身份只按租户可见，未设租户时 fail-closed。 */
    @Test
    @DisplayName("app_user 只按租户可见，未设租户时一行也看不到")
    void appUserIsTenantIsolated() {
        UUID tenantA = tenant("甲租户");
        UUID tenantB = tenant("乙租户");
        user(tenantA, "alice");
        user(tenantB, "bob");

        assertThat(usernamesVisibleTo(tenantA, null)).containsExactly("alice");
        assertThat(usernamesVisibleTo(tenantB, null)).containsExactly("bob");
        assertThat(usernamesWithoutContext())
                .as("未设租户时租户轴表必须一行也读不到（fail-closed）")
                .isEmpty();
    }

    /** 项目轴：同一租户下的两个项目，其角色与设备授权互不可见。 */
    @Test
    @DisplayName("app_user_role / app_user_device 只按项目可见")
    void projectFactsAreProjectIsolated() {
        UUID tenantA = tenant("甲租户");
        UUID projectOne = project(tenantA);
        UUID projectTwo = project(tenantA);
        UUID alice = user(tenantA, "alice");

        insertRole(tenantA, projectOne, alice, "APP_ADMIN");
        insertRole(tenantA, projectTwo, alice, "OBSERVER");
        insertDevice(tenantA, projectOne, alice, "PRIMARY");
        insertDevice(tenantA, projectTwo, alice, "READ_ONLY");

        assertThat(rolesVisibleTo(tenantA, projectOne)).containsExactly("APP_ADMIN");
        assertThat(rolesVisibleTo(tenantA, projectTwo)).containsExactly("OBSERVER");
        assertThat(relationRolesVisibleTo(tenantA, projectOne)).containsExactly("PRIMARY");
        assertThat(relationRolesVisibleTo(tenantA, projectTwo)).containsExactly("READ_ONLY");
    }

    /** 复合外键：租户 A 的用户塞进租户 B 的项目被 (tenant_id, project_id) 外键拒绝。 */
    @Test
    @DisplayName("复合外键拒绝跨租户项目绑定")
    void compositeForeignKeyRejectsCrossTenantProjectBinding() {
        UUID tenantA = tenant("甲租户");
        UUID tenantB = tenant("乙租户");
        UUID projectOfB = project(tenantB);
        UUID aliceOfA = user(tenantA, "alice");

        // 项目上下文切到 projectOfB，RLS 的 project_id 检查放行；但 tenant_id 用甲租户，
        // 与项目归属（乙租户）不一致 —— 只有复合外键能拦住。
        assertThatThrownBy(() -> withScope(tenantA, projectOfB, () ->
                jdbcTemplate.update("""
                        INSERT INTO app_user_role (id, tenant_id, project_id, app_user_id, role)
                        VALUES (?, ?, ?, ?, 'APP_ADMIN')
                        """, Uuid7.generate(), tenantA, projectOfB, aliceOfA)))
                .rootCause()
                .hasMessageContaining("app_user_role_tenant_project_fk");
    }

    /** 复合外键：把其他租户的用户赋给本项目被 (tenant_id, app_user_id) 外键拒绝。 */
    @Test
    @DisplayName("复合外键拒绝跨租户用户绑定")
    void compositeForeignKeyRejectsCrossTenantUserBinding() {
        UUID tenantA = tenant("甲租户");
        UUID tenantB = tenant("乙租户");
        UUID projectOfA = project(tenantA);
        UUID bobOfB = user(tenantB, "bob");

        // tenant_id 与项目一致（甲），但 app_user_id 是乙租户的用户 —— 只有复合外键能拦住。
        assertThatThrownBy(() -> withScope(tenantA, projectOfA, () ->
                jdbcTemplate.update("""
                        INSERT INTO app_user_role (id, tenant_id, project_id, app_user_id, role)
                        VALUES (?, ?, ?, ?, 'APP_ADMIN')
                        """, Uuid7.generate(), tenantA, projectOfA, bobOfB)))
                .rootCause()
                .hasMessageContaining("app_user_role_tenant_user_fk");
    }

    // ---------------------------------------------------------------- 夹具

    private UUID tenant(String name) {
        UUID tenantId = Uuid7.generate();
        jdbcTemplate.update("INSERT INTO sys_tenant (id, name) VALUES (?, ?)", tenantId, name);
        tenantIds.add(tenantId);
        return tenantId;
    }

    private UUID project(UUID tenantId) {
        UUID projectId = Uuid7.generate();
        String key = "s11" + projectId.toString().replace("-", "").substring(0, 16);
        jdbcTemplate.update("""
                INSERT INTO sys_project (id, tenant_id, name, region, project_key)
                VALUES (?, ?, ?, 'sh-1', ?)
                """, projectId, tenantId, "项目-" + projectId, key);
        projectTenants.put(projectId, tenantId);
        return projectId;
    }

    /** 在租户上下文下插入一名终端用户（app_user 受租户轴 RLS 约束）。 */
    private UUID user(UUID tenantId, String username) {
        UUID userId = Uuid7.generate();
        TenantContext.set(new TenantScope(tenantId, null, Uuid7.generate()));
        try {
            jdbcTemplate.update("""
                    INSERT INTO app_user (id, tenant_id, username, password_hash, status)
                    VALUES (?, ?, ?, '{bcrypt}unused', 'ACTIVE')
                    """, userId, tenantId, username);
        } finally {
            TenantContext.clear();
        }
        return userId;
    }

    private void insertRole(UUID tenantId, UUID projectId, UUID userId, String role) {
        withScope(tenantId, projectId, () -> jdbcTemplate.update("""
                INSERT INTO app_user_role (id, tenant_id, project_id, app_user_id, role)
                VALUES (?, ?, ?, ?, ?)
                """, Uuid7.generate(), tenantId, projectId, userId, role));
    }

    private void insertDevice(UUID tenantId, UUID projectId, UUID userId, String relationRole) {
        withScope(tenantId, projectId, () -> jdbcTemplate.update("""
                INSERT INTO app_user_device (id, tenant_id, project_id, app_user_id, device_id, relation_role)
                VALUES (?, ?, ?, ?, ?, ?)
                """, Uuid7.generate(), tenantId, projectId, userId, Uuid7.generate(), relationRole));
    }

    private void withScope(UUID tenantId, UUID projectId, Runnable action) {
        TenantContext.set(new TenantScope(tenantId, projectId, Uuid7.generate()));
        try {
            action.run();
        } finally {
            TenantContext.clear();
        }
    }

    // ---------------------------------------------------------------- 查询投影

    private Set<String> rlsPolicy(String table) {
        return Set.copyOf(jdbcTemplate.queryForList("""
                SELECT polname
                  FROM pg_policy
                  JOIN pg_class ON pg_class.oid = pg_policy.polrelid
                 WHERE pg_class.relname = ?
                """, String.class, table));
    }

    private boolean rlsEnabled(String table) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT relrowsecurity FROM pg_class WHERE relname = ?", Boolean.class, table));
    }

    private boolean tableExists(String table) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM pg_class c
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'public' AND c.relname = ?
                )
                """, Boolean.class, table));
    }

    private Set<String> usernamesVisibleTo(UUID tenantId, UUID projectId) {
        TenantContext.set(new TenantScope(tenantId, projectId, Uuid7.generate()));
        try {
            return Set.copyOf(jdbcTemplate.queryForList(
                    "SELECT username FROM app_user ORDER BY username", String.class));
        } finally {
            TenantContext.clear();
        }
    }

    private Set<String> usernamesWithoutContext() {
        return Set.copyOf(jdbcTemplate.queryForList(
                "SELECT username FROM app_user ORDER BY username", String.class));
    }

    private Set<String> rolesVisibleTo(UUID tenantId, UUID projectId) {
        TenantContext.set(new TenantScope(tenantId, projectId, Uuid7.generate()));
        try {
            return Set.copyOf(jdbcTemplate.queryForList(
                    "SELECT role FROM app_user_role ORDER BY role", String.class));
        } finally {
            TenantContext.clear();
        }
    }

    private Set<String> relationRolesVisibleTo(UUID tenantId, UUID projectId) {
        TenantContext.set(new TenantScope(tenantId, projectId, Uuid7.generate()));
        try {
            return Set.copyOf(jdbcTemplate.queryForList(
                    "SELECT relation_role FROM app_user_device ORDER BY relation_role", String.class));
        } finally {
            TenantContext.clear();
        }
    }
}
