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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D-098 最低层反例：区分线程上下文、借出连接时的会话范围与事务局部范围。
 *
 * <p>架构第 7 节与 ADR 0012 要求项目 RLS fail-closed。用真实非 owner 连接检验
 * 手工 set_config 的必要前置，不把重复设置直接推断成现有消费者已发生数据丢失。
 */
@DisplayName("D-098：事务局部 RLS 的生效和失效边界")
class TransactionLocalRlsScopeTests extends AbstractIntegrationTest {

    /** 仅用生产数据源准备和清理本用例的独立探针，不改变共享容器其他测试的数据。 */
    @Autowired
    private JdbcTemplate fixtureJdbc;

    /** S12-2a1a集中组件；测试必须经过生产Bean而不是复制SET LOCAL实现。 */
    @Autowired
    private TransactionLocalRlsScope transactionLocalScope;

    /** 与双轴组件共享事务授权标记的 tenant-only 生产 Bean。 */
    @Autowired
    private TenantTransactionLocalRlsScope tenantTransactionLocalRlsScope;

    /** 建立组件要求的真实Spring事务，并使JdbcTemplate绑定同一连接。 */
    @Autowired
    private TransactionTemplate transactionTemplate;

    /** 两个不同租户的项目，防止只有一个范围时把完全未隔离误判为隔离成功。 */
    private final List<RlsScope> scopes = List.of(
            new RlsScope(Uuid7.generate(), Uuid7.generate()),
            new RlsScope(Uuid7.generate(), Uuid7.generate()));

    /** 按既有借出前上下文合同准备已提交数据，使回滚测试仍有可见性对照。 */
    @BeforeEach
    void seedIndependentScopes() {
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

    /** 清除线程范围和本用例两组探针，失败反例也不能污染后续 RLS 测试。 */
    @AfterEach
    void deleteIndependentScopes() {
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

    /** 借出后修改 ThreadLocal 不会重设已绑定连接；显式事务 SQL 才能使该连接读到项目。 */
    @Test
    void lateThreadScopeDoesNotRepairAnAlreadyBorrowedConnection() throws SQLException {
        try (Connection connection = openApplicationConnection()) {
            JdbcTemplate jdbc = onSameConnection(connection);
            connection.setAutoCommit(false);
            try {
                RlsScopeContext.set(scopes.getFirst());
                assertScopeAbsent(jdbc);

                applyTransactionScope(jdbc, scopes.getFirst());
                assertOnlyScopeVisible(jdbc, scopes.getFirst());
            } finally {
                connection.rollback();
                TenantContext.clear();
            }
        }
    }

    /**
     * 同一物理会话依次服务两个项目，提交和回滚都必须恢复空范围。
     * 在 close 前检查，避免把 TenantAwareDataSource 的归还清理误当成事务自动清理。
     */
    @Test
    void commitAndRollbackClearLocalScopeBeforeConnectionReturn() throws SQLException {
        try (Connection connection = openApplicationConnection()) {
            JdbcTemplate jdbc = onSameConnection(connection);
            Integer backendPid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
            connection.setAutoCommit(false);
            try {
                applyTransactionScope(jdbc, scopes.getFirst());
                assertOnlyScopeVisible(jdbc, scopes.getFirst());
                connection.commit();
                assertScopeAbsent(jdbc);

                applyTransactionScope(jdbc, scopes.getLast());
                assertOnlyScopeVisible(jdbc, scopes.getLast());
                connection.rollback();
                assertScopeAbsent(jdbc);
                assertThat(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)).isEqualTo(backendPid);
            } finally {
                connection.rollback();
            }
        }
    }

    /** 无显式事务时 local 设置随自身语句提交消失，即使下一条 SQL 仍用同一物理连接也无权限。 */
    @Test
    void localScopeInAutoCommitDoesNotAuthorizeTheFollowingStatement() throws SQLException {
        try (Connection connection = openApplicationConnection()) {
            assertThat(connection.getAutoCommit()).isTrue();
            JdbcTemplate jdbc = onSameConnection(connection);
            applyTransactionScope(jdbc, scopes.getFirst());
            assertScopeAbsent(jdbc);
        }
    }

    /** 空范围在同一事务连接建立完整两轴，同范围重复调用保持幂等且不写ThreadLocal。 */
    @Test
    void establishesCompleteScopeOnTheTransactionBoundConnection() {
        RlsScope scope = scopes.getFirst();
        TenantContext.clear();

        transactionTemplate.executeWithoutResult(status -> {
            Integer before = fixtureJdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
            transactionLocalScope.establish(scope.tenantId(), scope.projectId());
            Integer after = fixtureJdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);

            assertThat(after).isEqualTo(before);
            assertOnlyScopeVisible(fixtureJdbc, scope);
            transactionLocalScope.establish(scope.tenantId(), scope.projectId());
            assertOnlyScopeVisible(fixtureJdbc, scope);
            assertThat(RlsScopeContext.current()).isEmpty();
        });

        assertThat(RlsScopeContext.current()).isEmpty();
    }

    /**
     * capability已核验得到的显式二元组必须能在没有Servlet线程上下文的工作线程建立范围。
     *
     * <p>S12-2a1h以独立线程模拟后续WebSocket工作线程；生产组件只消费显式权威结果，
     * 不会把原始请求字段或调用线程中的ThreadLocal当成授权事实。</p>
     */
    @Test
    void establishesExplicitCapabilityScopeWithoutThreadContext() throws Exception {
        RlsScope scope = scopes.getFirst();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            List<String> visible = executor.submit(() -> {
                TenantContext.clear();
                try {
                    return transactionTemplate.execute(status -> {
                        transactionLocalScope.establish(scope.tenantId(), scope.projectId());
                        assertThat(RlsScopeContext.current()).isEmpty();
                        return fixtureJdbc.queryForList("SELECT note FROM project_rls_probe", String.class);
                    });
                } finally {
                    TenantContext.clear();
                }
            }).get(10, TimeUnit.SECONDS);

            assertThat(visible).containsExactly(scope.projectId().toString());
            assertThat(RlsScopeContext.current()).isEmpty();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** DATA路由也必须在事务开始前选池，并由同一集中组件建立相同的完整隔离范围。 */
    @Test
    void establishesCompleteScopeOnTheDataPoolTransaction() {
        RlsScope scope = scopes.getLast();
        TenantContext.clear();
        Integer controlPid = transactionTemplate.execute(status ->
                fixtureJdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));

        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            transactionTemplate.executeWithoutResult(status -> {
                Integer before = fixtureJdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                transactionLocalScope.establish(scope.tenantId(), scope.projectId());

                assertThat(fixtureJdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)).isEqualTo(before);
                assertThat(before).isNotEqualTo(controlPid);
                assertOnlyScopeVisible(fixtureJdbc, scope);
            });
        }

        assertThat(DatabaseWorkloadContext.current()).isEqualTo(DatabaseWorkload.CONTROL);
        assertThat(RlsScopeContext.current()).isEmpty();
    }

    /** 首次可信调用可覆盖完整session基线，但本事务记录范围后不能再次切换隔离域。 */
    @Test
    void overridesCompleteSessionBaselineOnlyOnTheFirstCall() {
        RlsScope original = scopes.getFirst();
        RlsScope other = scopes.getLast();

        TenantContext.set(new TenantScope(other.tenantId(), other.projectId(), Uuid7.generate()));
        try {
            transactionTemplate.executeWithoutResult(status -> {
                assertOnlyScopeVisible(fixtureJdbc, other);
                TenantContext.clear();
                transactionLocalScope.establish(original.tenantId(), original.projectId());
                assertOnlyScopeVisible(fixtureJdbc, original);

                assertThatThrownBy(() -> transactionLocalScope.establish(
                                original.tenantId(), other.projectId()))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("禁止切换隔离域");
                assertThatThrownBy(() -> transactionLocalScope.establish(
                                other.tenantId(), original.projectId()))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("禁止切换隔离域");
                assertOnlyScopeVisible(fixtureJdbc, original);
            });
        } finally {
            TenantContext.clear();
        }
    }

    /** 控制台未选项目时只有合法tenant会话基线，权威项目二元组可在首次调用补全且可跨租户覆盖。 */
    @Test
    void completesATenantOnlySessionBaselineFromAnotherTenant() {
        RlsScope baseline = scopes.getLast();
        RlsScope target = scopes.getFirst();
        TenantContext.set(new TenantScope(baseline.tenantId(), null, Uuid7.generate()));
        try {
            transactionTemplate.executeWithoutResult(status -> {
                assertThat(fixtureJdbc.queryForObject("SELECT app_current_tenant()", String.class))
                        .isEqualTo(baseline.tenantId().toString());
                assertThat(fixtureJdbc.queryForObject("SELECT app_current_project()", String.class)).isNull();
                TenantContext.clear();

                transactionLocalScope.establish(target.tenantId(), target.projectId());

                assertOnlyScopeVisible(fixtureJdbc, target);
            });
        } finally {
            TenantContext.clear();
        }
        assertScopeAbsent(fixtureJdbc);
    }

    /** 共享事务标记跨组件类型和实例生效，禁止双轴与tenant-only在同一连接互相覆盖。 */
    @Test
    void sharesTheEstablishedModeAcrossScopeComponentsAndInstances() {
        RlsScope scope = scopes.getFirst();
        TransactionLocalRlsScope anotherFullScope = new TransactionLocalRlsScope(fixtureJdbc);

        transactionTemplate.executeWithoutResult(status -> {
            transactionLocalScope.establish(scope.tenantId(), scope.projectId());
            anotherFullScope.establish(scope.tenantId(), scope.projectId());
            assertThatThrownBy(() -> tenantTransactionLocalRlsScope.establish(scope.tenantId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("禁止切换隔离域");
            assertOnlyScopeVisible(fixtureJdbc, scope);
        });

        transactionTemplate.executeWithoutResult(status -> {
            tenantTransactionLocalRlsScope.establish(scope.tenantId());
            assertThatThrownBy(() -> transactionLocalScope.establish(scope.tenantId(), scope.projectId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("禁止切换隔离域");
            assertThat(fixtureJdbc.queryForObject("SELECT app_current_tenant()", String.class))
                    .isEqualTo(scope.tenantId().toString());
            assertThat(fixtureJdbc.queryForObject("SELECT app_current_project()", String.class)).isNull();
        });
    }

    /** 仅存在project或任一轴非法属于损坏前置，不能由组件猜出身份后继续查询。 */
    @Test
    void rejectsProjectOnlyOrInvalidTransactionScope() {
        RlsScope scope = scopes.getFirst();

        transactionTemplate.executeWithoutResult(status -> {
            fixtureJdbc.queryForObject("SELECT set_config('app.project_id', ?, true)", String.class,
                    scope.projectId().toString());
            assertThatThrownBy(() -> transactionLocalScope.establish(
                    scope.tenantId(), scope.projectId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("残缺或非法");
        });

        transactionTemplate.executeWithoutResult(status -> {
            fixtureJdbc.queryForObject("SELECT set_config('app.tenant_id', 'invalid', true)", String.class);
            fixtureJdbc.queryForObject("SELECT set_config('app.project_id', ?, true)", String.class,
                    scope.projectId().toString());
            assertThatThrownBy(() -> transactionLocalScope.establish(
                            scope.tenantId(), scope.projectId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("非法");
        });
    }

    /** 活动事务只绑定主数据源时，另一数据源的连接不能借全局事务标志冒充事务绑定连接。 */
    @Test
    void rejectsASecondDataSourceThatIsNotBoundToTheActiveTransaction() {
        RlsScope scope = scopes.getFirst();
        DriverManagerDataSource secondDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), APP_ROLE, APP_ROLE_PASSWORD);
        TransactionLocalRlsScope secondScope = new TransactionLocalRlsScope(new JdbcTemplate(secondDataSource));

        transactionTemplate.executeWithoutResult(status ->
                assertThatThrownBy(() -> secondScope.establish(scope.tenantId(), scope.projectId()))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("未绑定当前Spring事务"));
    }

    /** 真实事务关闭同步时必须在组件借连接前失败，且不能产生任何tenant/project范围副作用。 */
    @Test
    void rejectsAnActualTransactionWhoseSynchronizationIsDisabled() throws SQLException {
        RlsScope scope = scopes.getFirst();
        try (Connection connection = openApplicationConnection()) {
            JdbcTemplate jdbc = onSameConnection(connection);
            SingleConnectionDataSource dataSource = new SingleConnectionDataSource(connection, true);
            DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
            transactionManager.setTransactionSynchronization(AbstractPlatformTransactionManager.SYNCHRONIZATION_NEVER);
            TransactionTemplate transactions = new TransactionTemplate(transactionManager);
            TransactionLocalRlsScope localScope = new TransactionLocalRlsScope(new JdbcTemplate(dataSource));

            transactions.executeWithoutResult(status -> {
                assertThat(jdbc.execute((ConnectionCallback<Boolean>)
                        activeConnection -> !activeConnection.getAutoCommit())).isTrue();
                assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
                assertScopeAbsent(jdbc);
                assertThatThrownBy(() -> localScope.establish(scope.tenantId(), scope.projectId()))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("启用事务同步");
                assertScopeAbsent(jdbc);
            });
            assertScopeAbsent(jdbc);
        }
    }

    /** REQUIRES_NEW内层独占B范围并回滚后，必须恢复外层原连接与A范围且两层标记都能清理复用。 */
    @Test
    void restoresOuterScopeAfterRequiresNewRollback() {
        RlsScope outer = scopes.getFirst();
        RlsScope inner = scopes.getLast();
        TransactionTemplate requiresNew = new TransactionTemplate(transactionTemplate.getTransactionManager());
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Integer[] backendPids = new Integer[2];

        transactionTemplate.executeWithoutResult(outerStatus -> {
            transactionLocalScope.establish(outer.tenantId(), outer.projectId());
            backendPids[0] = fixtureJdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
            assertOnlyScopeVisible(fixtureJdbc, outer);

            requiresNew.executeWithoutResult(innerStatus -> {
                transactionLocalScope.establish(inner.tenantId(), inner.projectId());
                backendPids[1] = fixtureJdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                assertOnlyScopeVisible(fixtureJdbc, inner);
                innerStatus.setRollbackOnly();
            });

            assertThat(backendPids[1]).isNotEqualTo(backendPids[0]);
            assertThat(fixtureJdbc.queryForObject("SELECT pg_backend_pid()", Integer.class))
                    .isEqualTo(backendPids[0]);
            transactionLocalScope.establish(outer.tenantId(), outer.projectId());
            assertOnlyScopeVisible(fixtureJdbc, outer);
        });

        transactionTemplate.executeWithoutResult(status -> {
            transactionLocalScope.establish(inner.tenantId(), inner.projectId());
            assertOnlyScopeVisible(fixtureJdbc, inner);
        });
        assertScopeAbsent(fixtureJdbc);
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
    }

    /** 生产组件的local范围在同一物理连接提交、回滚和异常回滚后都必须消失。 */
    @Test
    void clearsProductionScopeAfterEveryTransactionCompletionPath() throws SQLException {
        RlsScope first = scopes.getFirst();
        RlsScope second = scopes.getLast();
        try (Connection connection = openApplicationConnection()) {
            JdbcTemplate jdbc = onSameConnection(connection);
            SingleConnectionDataSource dataSource = new SingleConnectionDataSource(connection, true);
            TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            TransactionLocalRlsScope scope = new TransactionLocalRlsScope(new JdbcTemplate(dataSource));
            Integer backendPid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);

            transactions.executeWithoutResult(status -> {
                scope.establish(first.tenantId(), first.projectId());
                assertOnlyScopeVisible(jdbc, first);
            });
            assertScopeAbsent(jdbc);

            transactions.executeWithoutResult(status -> {
                scope.establish(second.tenantId(), second.projectId());
                assertOnlyScopeVisible(jdbc, second);
                status.setRollbackOnly();
            });
            assertScopeAbsent(jdbc);

            IllegalStateException businessFailure = new IllegalStateException("业务失败反例");
            assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                scope.establish(first.tenantId(), first.projectId());
                assertOnlyScopeVisible(jdbc, first);
                throw businessFailure;
            })).isSameAs(businessFailure);
            assertScopeAbsent(jdbc);
            assertThat(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)).isEqualTo(backendPid);
        }
    }

    /** RLS读取SQL失败时必须保留底层SQLException，不能改写成范围冲突而丢失基础设施根因。 */
    @Test
    void preservesTheSqlInfrastructureRootCause() throws SQLException {
        RlsScope scope = scopes.getFirst();
        SQLException infrastructureFailure = new SQLException("RLS基础设施读取失败");
        try (Connection connection = openApplicationConnection()) {
            Connection failingConnection = failScopeRead(connection, infrastructureFailure);
            SingleConnectionDataSource dataSource = new SingleConnectionDataSource(failingConnection, true);
            TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            TransactionLocalRlsScope failingScope = new TransactionLocalRlsScope(new JdbcTemplate(dataSource));

            assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                    failingScope.establish(scope.tenantId(), scope.projectId())))
                    .hasRootCause(infrastructureFailure);
        }
    }

    /** 无Spring事务及缺失可信二元组都必须在任何范围SQL生效前失败关闭。 */
    @Test
    void rejectsMissingTransactionOrTrustedIdentity() {
        RlsScope scope = scopes.getFirst();
        TenantContext.clear();

        assertThatThrownBy(() -> transactionLocalScope.establish(
                        scope.tenantId(), scope.projectId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("真实Spring事务");
        assertThatThrownBy(() -> transactionLocalScope.establish(
                        null, scope.projectId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("同时提供租户与项目");
        assertScopeAbsent(fixtureJdbc);
    }

    /** 用应用非 owner 账号并经过生产包装器借出一次；后续一直持有，排除换连接的干扰。 */
    private Connection openApplicationConnection() throws SQLException {
        TenantContext.clear();
        return new TenantAwareDataSource(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), APP_ROLE, APP_ROLE_PASSWORD)).getConnection();
    }

    /** 禁止 JdbcTemplate 的每次归还关闭外层连接，以便直接观察 commit/rollback 后的原会话。 */
    private JdbcTemplate onSameConnection(Connection connection) {
        return new JdbcTemplate(new SingleConnectionDataSource(connection, true));
    }

    /** 使用数据库原生两轴局部设置构造集中组件建立前的机制反例。 */
    private void applyTransactionScope(JdbcTemplate jdbc, RlsScope scope) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class,
                scope.tenantId().toString());
        jdbc.queryForObject("SELECT set_config('app.project_id', ?, true)", String.class,
                scope.projectId().toString());
    }

    /** 两轴都必须为空且真实 RLS 表不可读，不能只验证某个 set_config 返回值。 */
    private void assertScopeAbsent(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForObject("SELECT app_current_tenant()", String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT app_current_project()", String.class)).isNull();
        assertThat(jdbc.queryForList("SELECT note FROM rls_probe", String.class)).isEmpty();
        assertThat(jdbc.queryForList("SELECT note FROM project_rls_probe", String.class)).isEmpty();
    }

    /** 独立租户表和项目表分别只看见指定范围，保持 ADR 0012 的两种隔离用途。 */
    private void assertOnlyScopeVisible(JdbcTemplate jdbc, RlsScope scope) {
        assertThat(jdbc.queryForList("SELECT note FROM rls_probe", String.class))
                .containsExactly(scope.projectId().toString());
        assertThat(jdbc.queryForList("SELECT note FROM project_rls_probe", String.class))
                .containsExactly(scope.projectId().toString());
    }

    /**
     * 只让读取当前范围的基础设施SQL失败，事务开始、回滚与连接关闭仍委托真实PostgreSQL连接。
     *
     * @param connection 真实应用角色连接
     * @param failure 要保留到最外层异常链的根因
     * @return 仅注入一次固定读取故障的连接代理
     */
    private Connection failScopeRead(Connection connection, SQLException failure) {
        return (Connection) Proxy.newProxyInstance(
                TransactionLocalRlsScopeTests.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("prepareStatement".equals(method.getName())
                            && args != null
                            && args.length > 0
                            && args[0] instanceof String sql
                            && sql.contains("current_setting('app.tenant_id'")) {
                        throw failure;
                    }
                    try {
                        return method.invoke(connection, args);
                    } catch (InvocationTargetException invocationFailure) {
                        throw invocationFailure.getCause();
                    }
                });
    }
}
