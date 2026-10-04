package com.things.link.bootstrap.enduser;

import com.things.link.enduser.application.EndUserRoleService;
import com.things.link.enduser.domain.AppUserDashboardGrant;
import com.things.link.enduser.domain.AppUserDashboardGrantRepository;
import com.things.link.enduser.infrastructure.persistence.JdbcAppUserDashboardGrantRepository;
import com.things.link.dashboard.application.DashboardGrantTargetService;
import com.things.link.dashboard.infrastructure.persistence.JdbcDashboardGrantTargetRepository;
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
import org.junit.jupiter.params.provider.ValueSource;
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
import java.util.function.Supplier;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.things.link.enduser.domain.AppUserDashboardGrant.Status.ACTIVE;
import static com.things.link.enduser.domain.AppUserDashboardGrant.Status.REVOKED;
import static com.things.link.enduser.domain.AppUserDashboardGrantRepository.Outcome.CREATED;
import static com.things.link.enduser.domain.AppUserDashboardGrantRepository.Outcome.UPDATED;
import static com.things.link.enduser.domain.AppUserDashboardGrantRepository.Outcome.UNCHANGED;
import static com.things.link.enduser.domain.AppUserDashboardGrantRepository.Outcome.NOT_FOUND;
import static com.things.link.enduser.domain.AppUserDashboardGrantRepository.Outcome.CONFLICT;
import static com.things.link.enduser.domain.AppUserDashboardGrantRepository.Outcome.INVALID;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S12-2a3a2：真实普通角色验证grant持久CAS、范围、外键和用户角色竞争。
 * 身份路由可替身，用户锁、角色/设备写入和提交回滚必须由普通APP连接执行。
 */
@Testcontainers
class AppUserDashboardGrantPersistenceTests {

    /** 独占数据库隔离锁观察与故障约束，避免共享集成库的清理干扰并发前提。 */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("app_user_dashboard_grant")
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

    /** 原事务受控CAS及成功事实恢复。 */
    private JdbcAppUserDashboardGrantRepository grants;
    /** 看板公开端口仅锁稳定目录，不授予用户权限。 */
    private DashboardGrantTargetService dashboards;

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
                .placeholders(Map.of("app_role_password", "thingslink")).target("20260906.0250").load().migrate();
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
        grants = new JdbcAppUserDashboardGrantRepository(app);
        dashboards = proxy(new DashboardGrantTargetService(new JdbcDashboardGrantTargetRepository(app)));
        assertThat(app.queryForObject("SELECT current_user", String.class)).isEqualTo("thingslink_app");
        assertThat(app.queryForObject("SELECT rolbypassrls FROM pg_roles WHERE rolname=current_user",
                Boolean.class)).isFalse();
    }

    /** 数据库自身产生微秒时间；同状态不更新行，撤销重授不更换身份或创建字段。 */
    @Test
    void createsReplaysRevokesAndRegrantsOriginalRow() {
        Fixture f = fixture();
        var first = write(f, 0, ACTIVE);
        assertThat(first.outcome()).isEqualTo(CREATED);
        var original = first.grant();
        assertThat(original.revision()).isEqualTo(1);
        assertThat(original.createdAt().getNano() % 1000).isZero();
        var same = write(f, 1, ACTIVE);
        assertThat(same.outcome()).isEqualTo(UNCHANGED);
        assertThat(same.grant()).isEqualTo(original);
        assertThat(write(f, 0, ACTIVE).outcome()).isEqualTo(CONFLICT);
        var revoked = write(f, 1, REVOKED);
        assertThat(revoked.outcome()).isEqualTo(UPDATED);
        assertThat(revoked.grant().revision()).isEqualTo(2);
        assertThat(revoked.grant().revokedAt()).isEqualTo(revoked.grant().updatedAt());
        assertThat(revoked.grant().revokedBy()).isEqualTo(f.actorId());
        var restored = write(f, 2, ACTIVE).grant();
        assertThat(restored.id()).isEqualTo(original.id());
        assertThat(restored.createdAt()).isEqualTo(original.createdAt());
        assertThat(restored.createdBy()).isEqualTo(original.createdBy());
        assertThat(restored.revision()).isEqualTo(3);
        assertThat(restored.revokedAt()).isNull();
        assertThat(restored.revokedBy()).isNull();
        assertThat(rows(f)).isEqualTo(1);
    }

    /** 首次撤销没有历史身份，不能造一条REVOKED行；非法输入不能写入。 */
    @Test
    void missingRevocationAndInvalidInputsDoNotWrite() {
        Fixture f = fixture();
        assertThat(write(f, 0, REVOKED).outcome()).isEqualTo(NOT_FOUND);
        assertThat(write(f, -1, ACTIVE).outcome()).isEqualTo(INVALID);
        assertThat(write(f, 0, null).outcome()).isEqualTo(INVALID);
        assertThat(rows(f)).isZero();
    }

    /** Long最大值仍允许同状态零写，但状态变化必须明确CONFLICT且不能溢出。 */
    @Test
    void maximumRevisionAllowsNoopAndRejectsMutation() {
        Fixture f = fixture();
        write(f, 0, ACTIVE);
        owner.update("UPDATE app_user_dashboard SET revision=? WHERE project_id=?", Long.MAX_VALUE, f.projectId());
        var same = write(f, Long.MAX_VALUE, ACTIVE);
        assertThat(same.outcome()).isEqualTo(UNCHANGED);
        assertThat(write(f, Long.MAX_VALUE, REVOKED).outcome()).isEqualTo(CONFLICT);
        assertThat(read(f)).isEqualTo(same.grant());
    }

    /** 普通角色的直接表写全部拒绝；SELECT唯一可用，函数未泄露给PUBLIC。 */
    @ParameterizedTest
    @ValueSource(strings = {"INSERT", "UPDATE", "DELETE", "TRUNCATE"})
    void ordinaryDirectDmlIsDenied(String privilege) {
        Fixture f = fixture();
        write(f, 0, ACTIVE);
        String sql = switch (privilege) {
            case "INSERT" -> "INSERT INTO app_user_dashboard SELECT * FROM app_user_dashboard LIMIT 1";
            case "UPDATE" -> "UPDATE app_user_dashboard SET status='REVOKED'";
            case "DELETE" -> "DELETE FROM app_user_dashboard";
            default -> "TRUNCATE app_user_dashboard";
        };
        Throwable failure = catchThrowable(() -> scoped(f, () -> { app.execute(sql); return null; }));
        assertThat(sqlState(failure)).isEqualTo("42501");
        assertThat(rows(f)).isEqualTo(1);
        assertThat(app.queryForObject("SELECT has_table_privilege(current_user,'app_user_dashboard',?)",
                Boolean.class, privilege)).isFalse();
        assertThat(owner.queryForObject("""
                SELECT count(*) FROM pg_proc p,LATERAL aclexplode(p.proacl) a
                WHERE p.oid='public.app_user_dashboard_cas(uuid,uuid,uuid,uuid,uuid,bigint,character varying,uuid)'::regprocedure
                AND a.grantee=0 AND a.privilege_type='EXECUTE'
                """, Long.class)).isZero();
    }

    /** 缺失或错误的任一轴不能借SECURITY DEFINER扩大写范围。 */
    @ParameterizedTest
    @ValueSource(strings = {"missing", "tenant-only", "project-only", "wrong-tenant", "wrong-project", "invalid-tenant", "invalid-project"})
    void controlledWriteRejectsIncompleteOrWrongScope(String mode) {
        Fixture f = fixture();
        Throwable failure = catchThrowable(() -> new TransactionTemplate(transactions).execute(status -> {
            String tenant = mode.equals("missing") || mode.equals("project-only") ? ""
                    : (mode.equals("wrong-tenant") ? UUID.randomUUID().toString() : f.tenantId().toString());
            String project = mode.equals("missing") || mode.equals("tenant-only") ? ""
                    : (mode.equals("wrong-project") ? UUID.randomUUID().toString() : f.projectId().toString());
            if (mode.equals("invalid-tenant")) tenant = "not-a-uuid";
            if (mode.equals("invalid-project")) project = "not-a-uuid";
            app.queryForObject("SELECT set_config('app.tenant_id',?,true),set_config('app.project_id',?,true)",
                    (row, ignored) -> row.getString(1), tenant, project);
            return cas(f, 0, ACTIVE);
        }));
        assertThat(sqlState(failure)).isEqualTo("42501");
        assertThat(rows(f)).isZero();
    }

    /** 标准项目RLS拒绝空/异项目；仓储额外匹配完整四元组，不借同项目ID跨租户读取。 */
    @Test
    void ordinaryReadsRespectProjectRlsAndCompleteIdentity() {
        Fixture f = fixture();
        Fixture other = fixture();
        write(f, 0, ACTIVE);
        assertThat(app.queryForObject("SELECT count(*) FROM app_user_dashboard", Long.class)).isZero();
        assertThat(scoped(other, () -> app.queryForObject("SELECT count(*) FROM app_user_dashboard", Long.class))).isZero();
        assertThat(scoped(f, () -> grants.find(other.tenantId(), f.projectId(), f.userId(), f.dashboardId()))).isEmpty();
        assertThat(read(f).appUserId()).isEqualTo(f.userId());
    }

    /** 复合外键约束实际归属，owner也不能把其他项目或租户的用户/看板混入。 */
    @ParameterizedTest
    @ValueSource(strings = {"project", "user", "dashboard"})
    void compositeForeignKeysRejectMixedIdentity(String part) {
        Fixture f = fixture();
        Fixture other = fixture();
        Throwable failure = catchThrowable(() -> owner.update("""
                INSERT INTO app_user_dashboard(id,tenant_id,project_id,app_user_id,dashboard_id,
                    permission,status,revision,created_at,updated_at,created_by,updated_by)
                VALUES (?,?,?,?,?,'READ','ACTIVE',1,now(),now(),?,?)
                """, UUID.randomUUID(), f.tenantId(), part.equals("project") ? other.projectId() : f.projectId(),
                part.equals("user") ? other.userId() : f.userId(),
                part.equals("dashboard") ? other.dashboardId() : f.dashboardId(), f.actorId(), f.actorId()));
        assertThat(sqlState(failure)).isEqualTo("23503");
        assertThat(rows(f)).isZero();
    }

    /** CAS同时校验有效用户与角色，不允许旧有效快照或无角色关系直接生成授权。 */
    @ParameterizedTest
    @ValueSource(strings = {"locked-user", "disabled-role", "missing-role", "missing-user"})
    void unusableUserOrRoleCannotWrite(String reason) {
        Fixture f = fixture();
        switch (reason) {
            case "locked-user" -> owner.update("UPDATE app_user SET status='LOCKED' WHERE id=?", f.userId());
            case "disabled-role" -> owner.update("UPDATE app_user_role SET status='DISABLED' WHERE app_user_id=?", f.userId());
            default -> owner.update("DELETE FROM app_user_role WHERE app_user_id=?", f.userId());
        }
        if (reason.equals("missing-user")) owner.update("DELETE FROM app_user WHERE id=?", f.userId());
        assertThat(scoped(f, () -> cas(f, 0, ACTIVE)).outcome()).isEqualTo(NOT_FOUND);
        assertThat(rows(f)).isZero();
    }

    /** 无原事务或更强快照隔离不得使锁提前释放/沿用旧快照。 */
    @Test
    void rejectsMissingReadonlyAndRepeatableReadTransactions() {
        Fixture f = fixture();
        assertThatThrownBy(() -> cas(f, 0, ACTIVE)).isInstanceOf(IllegalStateException.class);
        TransactionTemplate readonly = new TransactionTemplate(transactions);
        readonly.setReadOnly(true);
        assertThatThrownBy(() -> readonly.execute(status -> cas(f, 0, ACTIVE))).isInstanceOf(IllegalStateException.class);
        TransactionTemplate repeatable = new TransactionTemplate(transactions);
        repeatable.setIsolationLevel(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
        assertThatThrownBy(() -> repeatable.execute(status -> cas(f, 0, ACTIVE))).isInstanceOf(IllegalStateException.class);
    }

    /** 缺行与既有行两种竞争都只能一胜；两个事务有独立PID和提交事实。 */
    @ParameterizedTest
    @ValueSource(longs = {0, 1})
    void concurrentCompareAndSetHasExactlyOneWinner(long revision) throws Exception {
        Fixture f = fixture();
        if (revision == 1) write(f, 0, ACTIVE);
        CountDownLatch start = new CountDownLatch(1);
        java.util.Set<Integer> pids = java.util.concurrent.ConcurrentHashMap.newKeySet();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<AppUserDashboardGrantRepository.WriteResult>> results = java.util.stream.IntStream.range(0, 2)
                    .mapToObj(i -> executor.submit(() -> { await(start); return scoped(f, () -> {
                        pids.add(identity().pid());
                        return cas(f, revision, revision == 0 ? ACTIVE : REVOKED);
                    }); })).toList();
            start.countDown();
            var first = results.get(0).get(10, TimeUnit.SECONDS);
            var second = results.get(1).get(10, TimeUnit.SECONDS);
            assertThat(List.of(first.outcome(), second.outcome())).containsExactlyInAnyOrder(
                    revision == 0 ? CREATED : UPDATED, CONFLICT);
            assertThat(pids).hasSize(2);
            assertThat(rows(f)).isEqualTo(1);
            assertThat(read(f).revision()).isEqualTo(revision == 0 ? 1 : 2);
        } finally { start.countDown(); executor.shutdownNow(); }
    }

    /** grant先提交则保留ACTIVE事实后停角色；停角色先提交则grant锁后拒绝，不能越过停用。 */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void grantAndRealSuspendSerializeOnStableUser(boolean grantFirst) throws Exception {
        Fixture f = fixture();
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        AtomicReference<DatabaseTransaction> holderId = new AtomicReference<>();
        AtomicReference<DatabaseTransaction> waiterId = new AtomicReference<>();
        AtomicReference<AppUserDashboardGrantRepository.WriteResult> written = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = executor.submit(() -> scoped(f, () -> {
                lifecycle.requireActiveForWrite(f.tenantId(), f.projectId());
                if (grantFirst) written.set(cas(f, 0, ACTIVE));
                else service.suspend(f.projectId(), f.userId());
                holderId.set(identity()); acquired.countDown(); await(release); return null;
            }));
            await(acquired);
            Future<?> waiter = executor.submit(() -> scoped(f, () -> {
                waiterId.set(identity()); entered.countDown();
                lifecycle.requireActiveForWrite(f.tenantId(), f.projectId());
                if (grantFirst) service.suspend(f.projectId(), f.userId());
                else written.set(cas(f, 0, ACTIVE));
                return null;
            }));
            await(entered);
            assertThat(waiterId.get().pid()).isNotEqualTo(holderId.get().pid());
            assertThat(waiterId.get().transactionId()).isNotEqualTo(holderId.get().transactionId());
            assertBlocked(waiter, waiterId.get(), holderId.get());
            release.countDown(); holder.get(10, TimeUnit.SECONDS); waiter.get(10, TimeUnit.SECONDS);
            assertThat(written.get().outcome()).isEqualTo(grantFirst ? CREATED : NOT_FOUND);
            assertThat(rows(f)).isEqualTo(grantFirst ? 1 : 0);
            assertThat(owner.queryForObject("SELECT status FROM app_user_role WHERE app_user_id=?", String.class, f.userId()))
                    .isEqualTo("DISABLED");
            if (grantFirst) assertThat(read(f).status()).isEqualTo(ACTIVE);
        } finally { release.countDown(); executor.shutdownNow(); executor.awaitTermination(10, TimeUnit.SECONDS); }
    }

    /** 未发布目录也可预授予；软删与错租户/项目明确不可用且不返回管理正文。 */
    @Test
    void stableTargetDoesNotRequirePublicationAndRejectsSoftDelete() {
        Fixture f = fixture();
        scoped(f, () -> {
            assertThat(dashboards.lockForGrant(f.tenantId(), f.projectId(), f.dashboardId())).isPresent();
            assertThat(dashboards.lockForGrant(UUID.randomUUID(), f.projectId(), f.dashboardId())).isEmpty();
            assertThat(dashboards.lockForGrant(f.tenantId(), UUID.randomUUID(), f.dashboardId())).isEmpty();
            return null;
        });
        owner.update("UPDATE dash_dashboard SET deleted_at=now(),publication_revision=1 WHERE id=?", f.dashboardId());
        assertThat(scoped(f, () -> dashboards.lockForGrant(f.tenantId(), f.projectId(), f.dashboardId()))).isEmpty();
        assertThatThrownBy(() -> dashboards.lockForGrant(f.tenantId(), f.projectId(), f.dashboardId()))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    /** 两个授权读锁互相兼容，但必须阻塞软删非键写直到授权原事务结束。 */
    @Test
    void dashboardSharedLocksBlockSoftDeleteWithoutSerializingReaders() throws Exception {
        Fixture f = fixture();
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        AtomicReference<DatabaseTransaction> holderId = new AtomicReference<>();
        AtomicReference<DatabaseTransaction> writerId = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = executor.submit(() -> scoped(f, () -> {
                assertThat(dashboards.lockForGrant(f.tenantId(), f.projectId(), f.dashboardId())).isPresent();
                holderId.set(identity()); acquired.countDown(); await(release); return null;
            }));
            await(acquired);
            // 在持有者尚未释放时取得第二份SHARE，证明锁未扩大为目录排他锁。
            assertThat(scoped(f, () -> dashboards.lockForGrant(f.tenantId(), f.projectId(), f.dashboardId()))).isPresent();
            Future<?> writer = executor.submit(() -> new TransactionTemplate(
                    new DataSourceTransactionManager(owner.getDataSource())).execute(status -> {
                writerId.set(owner.queryForObject("SELECT pg_backend_pid(),txid_current()",
                        (row, index) -> new DatabaseTransaction(row.getInt(1), row.getLong(2))));
                entered.countDown();
                return owner.update("UPDATE dash_dashboard SET deleted_at=now(),publication_revision=1 WHERE id=?", f.dashboardId());
            }));
            await(entered);
            assertBlocked(writer, writerId.get(), holderId.get());
            release.countDown(); holder.get(10, TimeUnit.SECONDS); writer.get(10, TimeUnit.SECONDS);
            assertThat(scoped(f, () -> dashboards.lockForGrant(f.tenantId(), f.projectId(), f.dashboardId()))).isEmpty();
        } finally { release.countDown(); executor.shutdownNow(); executor.awaitTermination(10, TimeUnit.SECONDS); }
    }

    /** 业务写编排的项目/用户/看板锁序，为CAS原事务提供真实前置。 */
    private AppUserDashboardGrantRepository.WriteResult write(Fixture f, long expected, AppUserDashboardGrant.Status status) {
        return scoped(f, () -> {
            lifecycle.requireActiveForWrite(f.tenantId(), f.projectId());
            users.lockByIdAndTenant(f.tenantId(), f.userId()).orElseThrow();
            dashboards.lockForGrant(f.tenantId(), f.projectId(), f.dashboardId()).orElseThrow();
            return cas(f, expected, status);
        });
    }

    /** 直接调用持久端口用于验证函数自行用户互斥和角色新快照；不是公开管理授权服务。 */
    private AppUserDashboardGrantRepository.WriteResult cas(Fixture f, long expected, AppUserDashboardGrant.Status status) {
        return grants.compareAndSet(UUID.randomUUID(), f.tenantId(), f.projectId(), f.userId(), f.dashboardId(),
                expected, status, f.actorId());
    }

    /** 每次读取均用显式完整范围，不让测试owner读取冒充普通用户成功。 */
    private AppUserDashboardGrant read(Fixture f) {
        return scoped(f, () -> grants.find(f.tenantId(), f.projectId(), f.userId(), f.dashboardId()).orElseThrow());
    }

    /** 独立Spring事务建立集中范围；所有被测服务加入此物理事务。 */
    private <T> T scoped(Fixture f, Supplier<T> action) {
        return new TransactionTemplate(transactions).execute(status -> {
            scope.establish(f.tenantId(), f.projectId());
            return action.get();
        });
    }

    /** 仅检查本例持久行数，不删其他用例的数据。 */
    private long rows(Fixture f) {
        return owner.queryForObject("SELECT count(*) FROM app_user_dashboard WHERE project_id=?", Long.class, f.projectId());
    }

    /** 独占容器中的真实同域父对象；未发布看板是合同允许预授予的必要反例。 */
    private Fixture fixture() {
        Fixture f = new Fixture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        owner.update("INSERT INTO sys_tenant(id,name) VALUES (?,'grant租户')", f.tenantId());
        owner.update("INSERT INTO sys_project(id,tenant_id,name,project_key) VALUES (?,?,'grant项目',?)",
                f.projectId(), f.tenantId(), f.projectId().toString());
        owner.update("INSERT INTO app_user(id,tenant_id,username,password_hash) VALUES (?,?,?,'unusable-test-hash')",
                f.userId(), f.tenantId(), f.userId().toString());
        owner.update("INSERT INTO app_user_role(id,tenant_id,project_id,app_user_id,role) VALUES (?,?,?,?,'OBSERVER')",
                UUID.randomUUID(), f.tenantId(), f.projectId(), f.userId());
        owner.update("INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?,?,'unusable-test-hash','授权操作者')", f.actorId(), f.actorId()+"@example.com");
        owner.update("INSERT INTO dash_dashboard(id,tenant_id,project_id,management_name,created_by,updated_by) VALUES (?,?,?,'预授予',?,?)",
                f.dashboardId(), f.tenantId(), f.projectId(), f.actorId(), f.actorId());
        when(projects.requireRoleInProject(f.projectId())).thenReturn(ProjectRole.ADMIN);
        when(projects.requireRoutingContext(f.projectId())).thenReturn(new ProjectService.ProjectRoutingContext(f.tenantId(), f.projectId().toString()));
        return f;
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

    /** @param tenantId 租户 @param projectId 项目 @param userId 用户 @param dashboardId 看板 @param actorId Console操作者 */
    private record Fixture(UUID tenantId, UUID projectId, UUID userId, UUID dashboardId, UUID actorId) { }

    /** @param pid 物理连接 @param transactionId 独立事务号 */
    private record DatabaseTransaction(int pid, long transactionId) { }
}
