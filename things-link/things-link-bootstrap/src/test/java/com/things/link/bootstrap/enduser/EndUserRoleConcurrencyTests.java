package com.things.link.bootstrap.enduser;

import com.things.link.enduser.application.EndUserRoleService;
import com.things.link.enduser.domain.EndUserRole;
import com.things.link.enduser.infrastructure.persistence.JdbcAppUserDeviceRepository;
import com.things.link.enduser.infrastructure.persistence.JdbcAppUserRepository;
import com.things.link.enduser.infrastructure.persistence.JdbcAppUserRoleRepository;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.project.infrastructure.persistence.JdbcProjectRepository;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S12-2a3a1：四个角色写入口使用真实项目许可与用户互斥，为后续grant串行化提供前置证据。
 * 身份路由可替身，用户锁、角色/设备写入和提交回滚必须由普通APP连接执行。
 */
@Testcontainers
class EndUserRoleConcurrencyTests {

    /** 独占数据库隔离锁观察与故障约束，避免共享集成库的清理干扰并发前提。 */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("enduser_role_concurrency")
            .withUsername("thingslink").withPassword("thingslink");

    /** Owner仅迁移、准备夹具与独立观察，不参与被测业务。 */
    private static JdbcTemplate owner;
    /** 业务SQL与锁使用无BYPASSRLS的普通运行身份。 */
    private JdbcTemplate app;
    /** 所有代理和显式持锁者使用同一数据源，但各线程取得不同物理事务。 */
    private DataSourceTransactionManager transactions;
    /** 恢复原事务上下文的集中RLS组件。 */
    private TransactionLocalRlsScope scope;
    /** 仅替换Console成员身份与可信路由，不替换项目生命周期或持久状态。 */
    private ProjectService projects;
    /** 用户行锁直接复用ADR0097生产实现。 */
    private JdbcAppUserRepository users;
    /** 项目共享许可持有至原事务结束，证明不同用户允许共享同一项目许可。 */
    private ProjectLifecycleAccessService lifecycle;
    /** 被测服务经Spring事务代理开启并提交真实事务。 */
    private EndUserRoleService service;

    /** 加载全部生产领域迁移，不能以H2或遗漏跨领域前置代替真实资格。 */
    @BeforeAll
    static void migrate() {
        owner = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration/support", "classpath:db/migration/project",
                        "classpath:db/migration/device", "classpath:db/migration/telemetry",
                        "classpath:db/migration/alarm", "classpath:db/migration/task",
                        "classpath:db/migration/rule", "classpath:db/migration/iam",
                        "classpath:db/migration/enduser", "classpath:db/migration/export",
                        "classpath:db/migration/dashboard", "classpath:db/migration/ota")
                .placeholders(Map.of("app_role_password", "thingslink")).load().migrate();
    }

    /** 每例独立业务对象，历史夹具留在独占容器内，不对共享库做全表清理。 */
    @BeforeEach
    void setup() {
        DriverManagerDataSource source = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "thingslink_app", "thingslink");
        app = new JdbcTemplate(source);
        transactions = new DataSourceTransactionManager(source);
        scope = new TransactionLocalRlsScope(app);
        projects = mock(ProjectService.class);
        users = new JdbcAppUserRepository(app);
        lifecycle = proxy(
                new ProjectLifecycleAccessService(new JdbcProjectRepository(app)));
        service = proxy(new EndUserRoleService(projects, users, new JdbcAppUserRoleRepository(app),
                new JdbcAppUserDeviceRepository(app), scope, lifecycle));
        assertThat(app.queryForObject("SELECT current_user", String.class)).isEqualTo("thingslink_app");
        assertThat(app.queryForObject("SELECT rolbypassrls FROM pg_roles WHERE rolname=current_user",
                Boolean.class)).isFalse();
    }

    /** 用户锁必须早于角色写；旧版直接写角色的实现会在持有者释放前完成，从而失败。 */
    @ParameterizedTest
    @EnumSource(RoleOperation.class)
    void everyRoleMutationWaitsForSameUserLock(RoleOperation operation) throws Exception {
        Fixture target = fixture(operation);
        exerciseWithUserLock(target, target, operation, true);
        assertRoleResult(target, operation);
    }

    /** 用户是互斥粒度，不得把相同项目的不同用户串成项目排他写锁。 */
    @ParameterizedTest
    @EnumSource(RoleOperation.class)
    void anotherUserInSameProjectDoesNotWait(RoleOperation operation) throws Exception {
        Fixture target = fixture(operation);
        Fixture holder = additionalUser(target);
        exerciseWithUserLock(holder, target, operation, false);
        assertRoleResult(target, operation);
    }

    /** Console成员路由成功不代表有写许可；归档必须在用户/角色副作用前按50017拒绝。 */
    @ParameterizedTest
    @EnumSource(RoleOperation.class)
    void archivedProjectRejectsEveryRoleMutation(RoleOperation operation) {
        Fixture target = fixture(operation);
        owner.update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?", target.projectId());
        List<String> before = roleSnapshot(target);
        Throwable failure = catchThrowable(() -> invoke(operation, target));
        assertThat(failure).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) failure).errorCode().code()).isEqualTo(50017);
        assertThat(roleSnapshot(target)).isEqualTo(before);
    }

    /** 真实设备关系写SQL失败时，已执行的角色停用必须回滚，不能留下半完成授权状态。 */
    @Test
    void deviceClosureFailureRollsBackRoleAndBinding() {
        Fixture target = fixture(RoleOperation.SUSPEND);
        UUID bindingId = UUID.randomUUID();
        owner.update("""
                INSERT INTO app_user_device(id,tenant_id,project_id,app_user_id,device_id,relation_role)
                VALUES (?,?,?,?,?,'PRIMARY')
                """, bindingId, target.tenantId(), target.projectId(), target.userId(), UUID.randomUUID());
        List<String> before = roleSnapshot(target);
        String bindingBefore = owner.queryForObject(
                "SELECT row_to_json(d)::text FROM app_user_device d WHERE id=?", String.class, bindingId);
        owner.execute("""
                ALTER TABLE app_user_device ADD CONSTRAINT app_user_device_test_close_failure_ck
                CHECK (status <> 'CLOSED') NOT VALID
                """);
        try {
            Throwable failure = catchThrowable(() -> service.suspend(target.projectId(), target.userId()));
            assertThat(failure).isInstanceOf(DataAccessException.class);
            assertThat(sqlState(failure)).isEqualTo("23514");
            assertThat(roleSnapshot(target)).isEqualTo(before);
            assertThat(owner.queryForObject("SELECT row_to_json(d)::text FROM app_user_device d WHERE id=?",
                    String.class, bindingId)).isEqualTo(bindingBefore);
        } finally {
            owner.execute("ALTER TABLE app_user_device DROP CONSTRAINT app_user_device_test_close_failure_ck");
        }
        service.suspend(target.projectId(), target.userId());
        assertRoleResult(target, RoleOperation.SUSPEND);
        assertThat(owner.queryForObject("SELECT status FROM app_user_device WHERE id=?", String.class, bindingId))
                .isEqualTo("CLOSED");
        service.restore(target.projectId(), target.userId());
        assertThat(owner.queryForObject("SELECT status FROM app_user_device WHERE id=?", String.class, bindingId))
                .as("恢复项目角色不能复活已关闭设备关系").isEqualTo("CLOSED");
    }

    /** 两个APP事务使用真实用户锁；PG阻塞关系是证据，Future未完成或固定等待不算。 */
    private void exerciseWithUserLock(Fixture holderFixture, Fixture target, RoleOperation operation,
                                      boolean shouldBlock) throws Exception {
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        AtomicReference<DatabaseTransaction> holderIdentity = new AtomicReference<>();
        AtomicReference<DatabaseTransaction> waiterIdentity = new AtomicReference<>();
        when(projects.requireRoleInProject(target.projectId())).thenAnswer(invocation -> {
            if (waiterIdentity.compareAndSet(null, identity())) entered.countDown();
            return ProjectRole.ADMIN;
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                scope.establish(holderFixture.tenantId(), holderFixture.projectId());
                lifecycle.requireActiveForWrite(holderFixture.tenantId(), holderFixture.projectId());
                assertThat(users.lockByIdAndTenant(holderFixture.tenantId(), holderFixture.userId())).isPresent();
                holderIdentity.set(identity());
                acquired.countDown();
                await(release);
            }));
            await(acquired);
            Future<?> waiter = executor.submit(() -> invoke(operation, target));
            await(entered);
            assertThat(waiterIdentity.get().pid()).isNotEqualTo(holderIdentity.get().pid());
            assertThat(waiterIdentity.get().transactionId()).isNotEqualTo(holderIdentity.get().transactionId());
            if (shouldBlock) {
                assertBlocked(waiter, waiterIdentity.get(), holderIdentity.get());
            } else {
                waiter.get(5, TimeUnit.SECONDS);
            }
            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
            waiter.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** 观察连接检查指定持有者与未授权锁；操作提前成功说明确实缺用户锁。 */
    private void assertBlocked(Future<?> operation, DatabaseTransaction waiter, DatabaseTransaction holder)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (operation.isDone()) {
                operation.get(1, TimeUnit.SECONDS);
                throw new AssertionError("角色写在用户锁释放前完成，缺少同用户互斥");
            }
            if (Boolean.TRUE.equals(owner.queryForObject("""
                    SELECT ?=ANY(pg_blocking_pids(?))
                       AND EXISTS(SELECT 1 FROM pg_locks WHERE pid=? AND NOT granted)
                    """, Boolean.class, holder.pid(), waiter.pid(), waiter.pid()))) return;
            Thread.sleep(5);
        }
        throw new AssertionError("未观察到指定APP用户事务阻塞角色写入");
    }

    /** 取得业务连接PID与事务号，同时断言未误用迁移owner。 */
    private DatabaseTransaction identity() {
        return app.queryForObject("SELECT current_user,pg_backend_pid(),txid_current()", (row, ignored) -> {
            assertThat(row.getString(1)).isEqualTo("thingslink_app");
            return new DatabaseTransaction(row.getInt(2), row.getLong(3));
        });
    }

    /** 所有操作调用同一生产服务代理，避免测试手写角色更新冒充用户锁接线。 */
    private void invoke(RoleOperation operation, Fixture fixture) {
        switch (operation) {
            case ASSIGN -> service.assign(fixture.projectId(), fixture.userId(), EndUserRole.OBSERVER);
            case UPDATE -> service.updateRole(fixture.projectId(), fixture.userId(), EndUserRole.OBSERVER);
            case SUSPEND -> service.suspend(fixture.projectId(), fixture.userId());
            case RESTORE -> service.restore(fixture.projectId(), fixture.userId());
        }
    }

    /** 对已提交事实断言实际变更，不能只凭调用完成判定通过。 */
    private void assertRoleResult(Fixture target, RoleOperation operation) {
        assertThat(owner.queryForObject("SELECT status FROM app_user_role WHERE project_id=? AND app_user_id=?",
                String.class, target.projectId(), target.userId()))
                .isEqualTo(operation == RoleOperation.SUSPEND ? "DISABLED" : "ACTIVE");
        if (operation == RoleOperation.ASSIGN || operation == RoleOperation.UPDATE) {
            assertThat(owner.queryForObject("SELECT role FROM app_user_role WHERE project_id=? AND app_user_id=?",
                    String.class, target.projectId(), target.userId())).isEqualTo("OBSERVER");
        }
    }

    /** 仅生成本例租户、项目和用户；ASSIGN特意不预置角色，RESTORE预置停用角色。 */
    private Fixture fixture(RoleOperation operation) {
        Fixture fixture = new Fixture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        owner.update("INSERT INTO sys_tenant(id,name) VALUES (?,'角色互斥租户')", fixture.tenantId());
        owner.update("INSERT INTO sys_project(id,tenant_id,name,project_key) VALUES (?,?,'角色互斥项目',?)",
                fixture.projectId(), fixture.tenantId(), fixture.projectId().toString());
        insertUser(fixture);
        if (operation != RoleOperation.ASSIGN) {
            owner.update("""
                    INSERT INTO app_user_role(id,tenant_id,project_id,app_user_id,role,status)
                    VALUES (?,?,?,?,'APP_ADMIN',?)
                    """, UUID.randomUUID(), fixture.tenantId(), fixture.projectId(), fixture.userId(),
                    operation == RoleOperation.RESTORE ? "DISABLED" : "ACTIVE");
        }
        when(projects.requireRoleInProject(fixture.projectId())).thenReturn(ProjectRole.ADMIN);
        when(projects.requireRoutingContext(fixture.projectId())).thenReturn(
                new ProjectService.ProjectRoutingContext(fixture.tenantId(), fixture.projectId().toString()));
        return fixture;
    }

    /** 同项目另一个稳定用户只作锁持有者，排除项目独占锁造成全项目串行。 */
    private Fixture additionalUser(Fixture target) {
        Fixture fixture = new Fixture(target.tenantId(), target.projectId(), UUID.randomUUID());
        insertUser(fixture);
        return fixture;
    }

    /** 夹具不走登录，不保存明文凭据；生产锁仍读取完整真实用户行。 */
    private void insertUser(Fixture fixture) {
        owner.update("""
                INSERT INTO app_user(id,tenant_id,username,password_hash,status)
                VALUES (?,?,?,'test-only-unusable-hash','ACTIVE')
                """, fixture.userId(), fixture.tenantId(), fixture.userId().toString());
    }

    /** 完整行快照同时捕捉错误状态、角色与时间戳的意外部分提交。 */
    private List<String> roleSnapshot(Fixture fixture) {
        return owner.queryForList("""
                SELECT row_to_json(r)::text FROM app_user_role r
                WHERE project_id=? AND app_user_id=? ORDER BY id
                """, String.class, fixture.projectId(), fixture.userId());
    }

    /** 实际事务代理保留MANDATORY与调用方原事务，不用手工提交替代服务边界。 */
    @SuppressWarnings("unchecked")
    private <T> T proxy(T target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    /** 有界屏障失败保留线程中断状态，不让后台事务无限持锁。 */
    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).as("并发屏障须及时到达或释放").isTrue();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("角色并发测试屏障中断", failure);
        }
    }

    /** SQLSTATE必须来自真实数据库根因，不接受测试任意异常冒充回滚反例。 */
    private static String sqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) return sql.getSQLState();
        }
        return null;
    }

    /** 四个既有角色写入口保持独立的成功前置与终态。 */
    private enum RoleOperation {
        /** 首次分配没有角色行，只有用户父行可作稳定互斥。 */
        ASSIGN,
        /** 修改已有项目角色。 */
        UPDATE,
        /** 停用角色并关闭设备授权。 */
        SUSPEND,
        /** 恢复角色但不恢复设备授权。 */
        RESTORE
    }

    /** 独立租户/项目/用户身份，供生产路由和owner夹具共享。 */
    private record Fixture(UUID tenantId, UUID projectId, UUID userId) { }

    /** 实际APP物理连接与事务身份，防止单连接串行产生伪并发证据。 */
    private record DatabaseTransaction(int pid, long transactionId) { }
}
