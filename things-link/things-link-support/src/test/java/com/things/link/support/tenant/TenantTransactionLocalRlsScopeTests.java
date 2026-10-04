package com.things.link.support.tenant;

import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.RlsScope;
import com.things.link.shared.tenant.RlsScopeContext;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** S12-2a1e / D-098：tenant-only 集中组件的真实 PostgreSQL 隔离、互斥与清理反例。 */
@DisplayName("D-098：事务局部 tenant-only RLS 的建立与互斥边界")
class TenantTransactionLocalRlsScopeTests extends AbstractIntegrationTest {

    /** 生产应用数据源，既准备独立探针，也观察事务绑定连接。 */
    @Autowired
    private JdbcTemplate fixtureJdbc;

    /** 被测生产 tenant-only 单例。 */
    @Autowired
    private TenantTransactionLocalRlsScope tenantTransactionLocalRlsScope;

    /** 与 tenant-only 组件共享事务标记的生产双轴单例。 */
    @Autowired
    private TransactionLocalRlsScope transactionLocalRlsScope;

    /** 建立真实 Spring 事务并绑定同一物理连接。 */
    @Autowired
    private TransactionTemplate transactionTemplate;

    /** 两个不同租户的探针；项目 ID 只用于种数和判断双轴权限未被扩大。 */
    private final List<RlsScope> scopes = List.of(
            new RlsScope(Uuid7.generate(), Uuid7.generate()),
            new RlsScope(Uuid7.generate(), Uuid7.generate()));

    /** 通过既有借出前双轴上下文写入两个租户事实，避免 owner 绕过 RLS。 */
    @BeforeEach
    void seedTenantFacts() {
        for (RlsScope scope : scopes) {
            TenantContext.set(new TenantScope(scope.tenantId(), scope.projectId(), Uuid7.generate()));
            try {
                fixtureJdbc.update("INSERT INTO rls_probe (id, tenant_id, note) VALUES (?, ?, ?)",
                        Uuid7.generate(), scope.tenantId(), scope.projectId().toString());
                fixtureJdbc.update("""
                        INSERT INTO project_rls_probe (id, tenant_id, project_id, note) VALUES (?, ?, ?, ?)
                        """, Uuid7.generate(), scope.tenantId(), scope.projectId(), scope.projectId().toString());
            } finally {
                TenantContext.clear();
            }
        }
    }

    /** 清理本类独立探针；失败用例也不能给后续共享容器测试留下事实。 */
    @AfterEach
    void deleteTenantFacts() {
        for (RlsScope scope : scopes) {
            TenantContext.set(new TenantScope(scope.tenantId(), scope.projectId(), Uuid7.generate()));
            try {
                fixtureJdbc.update("DELETE FROM project_rls_probe WHERE project_id = ?", scope.projectId());
                fixtureJdbc.update("DELETE FROM rls_probe WHERE tenant_id = ?", scope.tenantId());
            } finally {
                TenantContext.clear();
            }
        }
    }

    /** 空基线应在真实事务同一连接建立租户轴、保持项目轴为空，并允许同租户重入。 */
    @Test
    void establishesTenantOnlyScopeOnTheTransactionBoundConnection() {
        RlsScope scope = scopes.getFirst();
        TenantContext.clear();

        transactionTemplate.executeWithoutResult(status -> {
            Integer before = fixtureJdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
            tenantTransactionLocalRlsScope.establish(scope.tenantId());
            Integer after = fixtureJdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);

            assertThat(after).isEqualTo(before);
            assertOnlyTenantVisible(fixtureJdbc, scope);
            tenantTransactionLocalRlsScope.establish(scope.tenantId());
            assertOnlyTenantVisible(fixtureJdbc, scope);
            assertThat(RlsScopeContext.current()).isEmpty();
        });

        assertScopeAbsent(fixtureJdbc);
        assertThat(RlsScopeContext.current()).isEmpty();
    }

    /** 完整合法 session 双轴基线只在首次调用可被原子替换成可信 tenant-only 范围。 */
    @Test
    void replacesACompleteSessionBaselineAndClearsItsProjectAxis() {
        RlsScope baseline = scopes.getLast();
        RlsScope target = scopes.getFirst();
        TenantContext.set(new TenantScope(baseline.tenantId(), baseline.projectId(), Uuid7.generate()));
        try {
            transactionTemplate.executeWithoutResult(status -> {
                assertThat(fixtureJdbc.queryForObject("SELECT app_current_tenant()", String.class))
                        .isEqualTo(baseline.tenantId().toString());
                assertThat(fixtureJdbc.queryForObject("SELECT app_current_project()", String.class))
                        .isEqualTo(baseline.projectId().toString());
                TenantContext.clear();

                tenantTransactionLocalRlsScope.establish(target.tenantId());

                assertOnlyTenantVisible(fixtureJdbc, target);
            });
        } finally {
            TenantContext.clear();
        }
        assertScopeAbsent(fixtureJdbc);
    }

    /** 未选项目的合法tenant会话基线可由首次可信调用跨租户覆盖，结果仍保持项目轴为空。 */
    @Test
    void replacesATenantOnlySessionBaselineFromAnotherTenant() {
        RlsScope baseline = scopes.getLast();
        RlsScope target = scopes.getFirst();
        TenantContext.set(new TenantScope(baseline.tenantId(), null, Uuid7.generate()));
        try {
            transactionTemplate.executeWithoutResult(status -> {
                assertThat(fixtureJdbc.queryForObject("SELECT app_current_tenant()", String.class))
                        .isEqualTo(baseline.tenantId().toString());
                assertThat(fixtureJdbc.queryForObject("SELECT app_current_project()", String.class)).isNull();
                TenantContext.clear();

                tenantTransactionLocalRlsScope.establish(target.tenantId());

                assertOnlyTenantVisible(fixtureJdbc, target);
            });
        } finally {
            TenantContext.clear();
        }
        assertScopeAbsent(fixtureJdbc);
    }

    /** 两种组件共享事务标记，任一模式先建立后都不能被另一模式覆盖。 */
    @Test
    void rejectsBothDirectionsOfTenantOnlyAndProjectScopeInterleaving() {
        RlsScope scope = scopes.getFirst();

        transactionTemplate.executeWithoutResult(status -> {
            transactionLocalRlsScope.establish(scope.tenantId(), scope.projectId());
            assertThatThrownBy(() -> tenantTransactionLocalRlsScope.establish(scope.tenantId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("禁止切换隔离域");
            assertFullScopeVisible(fixtureJdbc, scope);
        });

        transactionTemplate.executeWithoutResult(status -> {
            tenantTransactionLocalRlsScope.establish(scope.tenantId());
            assertThatThrownBy(() -> transactionLocalRlsScope.establish(scope.tenantId(), scope.projectId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("禁止切换隔离域");
            assertOnlyTenantVisible(fixtureJdbc, scope);
        });
    }

    /** 共享标记按事务连接而非组件实例区分：同模式同范围可重入，异范围仍拒绝。 */
    @Test
    void coordinatesDifferentComponentInstancesOnTheSameTransactionConnection() {
        RlsScope first = scopes.getFirst();
        RlsScope second = scopes.getLast();
        TenantTransactionLocalRlsScope anotherTenantScope =
                new TenantTransactionLocalRlsScope(fixtureJdbc);
        TransactionLocalRlsScope anotherFullScope = new TransactionLocalRlsScope(fixtureJdbc);

        transactionTemplate.executeWithoutResult(status -> {
            tenantTransactionLocalRlsScope.establish(first.tenantId());
            anotherTenantScope.establish(first.tenantId());
            assertThatThrownBy(() -> anotherTenantScope.establish(second.tenantId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("禁止切换隔离域");
            assertOnlyTenantVisible(fixtureJdbc, first);
        });

        transactionTemplate.executeWithoutResult(status -> {
            transactionLocalRlsScope.establish(first.tenantId(), first.projectId());
            anotherFullScope.establish(first.tenantId(), first.projectId());
            assertThatThrownBy(() -> anotherFullScope.establish(second.tenantId(), second.projectId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("禁止切换隔离域");
            assertFullScopeVisible(fixtureJdbc, first);
        });
    }

    /** 建立后即使同租户，项目轴重新出现或当前租户被外部改写也必须失败关闭。 */
    @Test
    void rejectsDatabaseScopeMutationAfterEstablishment() {
        RlsScope first = scopes.getFirst();
        RlsScope second = scopes.getLast();

        transactionTemplate.executeWithoutResult(status -> {
            tenantTransactionLocalRlsScope.establish(first.tenantId());
            setLocal("app.project_id", first.projectId().toString());
            assertThatThrownBy(() -> tenantTransactionLocalRlsScope.establish(first.tenantId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("禁止切换隔离域");
        });

        transactionTemplate.executeWithoutResult(status -> {
            tenantTransactionLocalRlsScope.establish(first.tenantId());
            setLocal("app.tenant_id", second.tenantId().toString());
            assertThatThrownBy(() -> tenantTransactionLocalRlsScope.establish(first.tenantId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("禁止切换隔离域");
        });
    }

    /** project-only 与非法值都是损坏基线，不能由首次调用猜测或修补。 */
    @Test
    void rejectsProjectOnlyOrInvalidInitialScope() {
        RlsScope scope = scopes.getFirst();

        transactionTemplate.executeWithoutResult(status -> {
            setLocal("app.project_id", scope.projectId().toString());
            assertThatThrownBy(() -> tenantTransactionLocalRlsScope.establish(scope.tenantId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("残缺或非法");
        });
        transactionTemplate.executeWithoutResult(status -> {
            setLocal("app.tenant_id", "invalid");
            setLocal("app.project_id", scope.projectId().toString());
            assertThatThrownBy(() -> tenantTransactionLocalRlsScope.establish(scope.tenantId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("残缺或非法");
        });
    }

    /** 无事务、空身份、关闭同步或绑定另一数据源都必须在授权 SQL 生效前失败。 */
    @Test
    void rejectsMissingTrustOrAUsableBoundTransaction() throws SQLException {
        RlsScope scope = scopes.getFirst();
        assertThatThrownBy(() -> tenantTransactionLocalRlsScope.establish(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不得为空");
        assertThatThrownBy(() -> tenantTransactionLocalRlsScope.establish(scope.tenantId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("真实Spring事务");

        DriverManagerDataSource secondDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), APP_ROLE, APP_ROLE_PASSWORD);
        TenantTransactionLocalRlsScope secondScope =
                new TenantTransactionLocalRlsScope(new JdbcTemplate(secondDataSource));
        transactionTemplate.executeWithoutResult(status ->
                assertThatThrownBy(() -> secondScope.establish(scope.tenantId()))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("未绑定当前Spring事务"));

        try (Connection connection = openApplicationConnection()) {
            JdbcTemplate jdbc = onSameConnection(connection);
            SingleConnectionDataSource dataSource = new SingleConnectionDataSource(connection, true);
            DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
            manager.setTransactionSynchronization(AbstractPlatformTransactionManager.SYNCHRONIZATION_NEVER);
            TransactionTemplate transactions = new TransactionTemplate(manager);
            TenantTransactionLocalRlsScope localScope =
                    new TenantTransactionLocalRlsScope(new JdbcTemplate(dataSource));
            transactions.executeWithoutResult(status -> {
                assertThat(jdbc.execute((ConnectionCallback<Boolean>) active -> !active.getAutoCommit())).isTrue();
                assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
                assertThatThrownBy(() -> localScope.establish(scope.tenantId()))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("启用事务同步");
                assertScopeAbsent(jdbc);
            });
        }
    }

    /** 提交、显式回滚和业务异常回滚后，局部两轴与共享事务标记都必须清理。 */
    @Test
    void clearsTenantOnlyScopeAfterEveryCompletionPath() throws SQLException {
        RlsScope first = scopes.getFirst();
        RlsScope second = scopes.getLast();
        try (Connection connection = openApplicationConnection()) {
            JdbcTemplate jdbc = onSameConnection(connection);
            SingleConnectionDataSource dataSource = new SingleConnectionDataSource(connection, true);
            TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            TenantTransactionLocalRlsScope scope =
                    new TenantTransactionLocalRlsScope(new JdbcTemplate(dataSource));
            Integer backendPid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);

            transactions.executeWithoutResult(status -> {
                scope.establish(first.tenantId());
                assertOnlyTenantVisible(jdbc, first);
            });
            assertScopeAbsent(jdbc);

            transactions.executeWithoutResult(status -> {
                scope.establish(second.tenantId());
                assertOnlyTenantVisible(jdbc, second);
                status.setRollbackOnly();
            });
            assertScopeAbsent(jdbc);

            IllegalStateException businessFailure = new IllegalStateException("tenant-only业务失败反例");
            assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                scope.establish(first.tenantId());
                assertOnlyTenantVisible(jdbc, first);
                throw businessFailure;
            })).isSameAs(businessFailure);
            assertScopeAbsent(jdbc);
            assertThat(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)).isEqualTo(backendPid);
        }
    }

    /** 在当前事务连接构造局部 GUC 前置，只用于残缺/非法反例。 */
    private void setLocal(String name, String value) {
        fixtureJdbc.queryForObject("SELECT set_config(?, ?, true)", String.class, name, value);
    }

    /** tenant-only 只能看到同租户事实，项目表必须因空项目轴保持不可见。 */
    private void assertOnlyTenantVisible(JdbcTemplate jdbc, RlsScope scope) {
        assertThat(jdbc.queryForObject("SELECT app_current_tenant()", String.class))
                .isEqualTo(scope.tenantId().toString());
        assertThat(jdbc.queryForObject("SELECT app_current_project()", String.class)).isNull();
        assertThat(jdbc.queryForList("SELECT note FROM rls_probe", String.class))
                .containsExactly(scope.projectId().toString());
        assertThat(jdbc.queryForList("SELECT note FROM project_rls_probe", String.class)).isEmpty();
    }

    /** 完整双轴模式仍只能看到指定租户与项目，交错拒绝不能破坏原授权。 */
    private void assertFullScopeVisible(JdbcTemplate jdbc, RlsScope scope) {
        assertThat(jdbc.queryForObject("SELECT app_current_tenant()", String.class))
                .isEqualTo(scope.tenantId().toString());
        assertThat(jdbc.queryForObject("SELECT app_current_project()", String.class))
                .isEqualTo(scope.projectId().toString());
        assertThat(jdbc.queryForList("SELECT note FROM rls_probe", String.class))
                .containsExactly(scope.projectId().toString());
        assertThat(jdbc.queryForList("SELECT note FROM project_rls_probe", String.class))
                .containsExactly(scope.projectId().toString());
    }

    /** 两轴都空且真实 RLS 表均不可见。 */
    private void assertScopeAbsent(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForObject("SELECT app_current_tenant()", String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT app_current_project()", String.class)).isNull();
        assertThat(jdbc.queryForList("SELECT note FROM rls_probe", String.class)).isEmpty();
        assertThat(jdbc.queryForList("SELECT note FROM project_rls_probe", String.class)).isEmpty();
    }

    /** 用生产应用角色包装器借出单一物理连接，直接观察事务完成后的 GUC。 */
    private Connection openApplicationConnection() throws SQLException {
        TenantContext.clear();
        return new TenantAwareDataSource(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), APP_ROLE, APP_ROLE_PASSWORD)).getConnection();
    }

    /** 禁止 JdbcTemplate 关闭外层连接，确保所有断言落在同一 PostgreSQL 会话。 */
    private JdbcTemplate onSameConnection(Connection connection) {
        return new JdbcTemplate(new SingleConnectionDataSource(connection, true));
    }
}
