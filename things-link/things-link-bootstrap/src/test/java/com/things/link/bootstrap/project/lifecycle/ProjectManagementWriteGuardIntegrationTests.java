package com.things.link.bootstrap.project.lifecycle;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.project.application.ProjectManagementWriteGuard;
import com.things.link.project.application.ProjectService;
import com.things.link.project.infrastructure.persistence.JdbcProjectRepository;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.scheduling.NotificationWorkCoordinator;
import com.things.link.support.tenant.DatabaseWorkload;
import com.things.link.support.tenant.DatabaseWorkloadContext;
import com.things.link.testing.AbstractIntegrationTest;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

/**
 * ADR0064决策2/5、S12-P0-5d1：普通授权显式过滤状态，管理许可在调用者原事务排他锁后重新授权。
 * 本类验证管理许可基础；用真实仓储业务写模拟调用者，七入口接线证据由5d2至5d4独立承担。
 */
@Import(ProjectManagementWriteGuardIntegrationTests.IsolatedDatabaseConfiguration.class)
@OwnedTestContainers({"MANAGEMENT_POSTGRES"})
class ProjectManagementWriteGuardIntegrationTests extends AbstractIntegrationTest {

    /** D-109：独立物理数据库防止缓存上下文的全局worker干扰，随机项目不能替代数据库隔离。 */
    private static final String DATABASE_NAME = "project_management_" + UUID.randomUUID().toString().replace("-", "");
    /** 同镜像、同owner的独占集群，避免迁移时角色级变化污染其他测试。 */
    private static final PostgreSQLContainer<?> MANAGEMENT_POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName(DATABASE_NAME).withUsername(POSTGRES.getUsername()).withPassword(POSTGRES.getPassword());
    /** 运行连接、Flyway和owner观察统一落在此地址。 */
    private static final String DATABASE_URL = startDatabase();
    /** 父runner直连共享POSTGRES；本类不用HTTP限流夹具，必须明确抑制跨库修改。 */
    @MockitoBean(enforceOverride = true, name = "relaxRestQuota")
    private ApplicationRunner unusedRestQuotaRelaxation;
    /** 协调器不受publisher开关约束，本类专测管理事务而不运行后台领取。 */
    @MockitoBean(enforceOverride = true)
    private NotificationWorkCoordinator unusedNotificationWorkCoordinator;
    /** 被测生产代理；MANDATORY必须加入调用者事务，不能自行短提交。 */
    @Autowired private ProjectManagementWriteGuard guard;
    /** 真实OWNER删除沿5d4保全合同执行，普通授权仍须因项目状态拒绝。 */
    @Autowired private ProjectService projectService;
    /** 真实SQL执行；spy仅在锁SQL前记录APP PID，所有查询与更新都callRealMethod。 */
    @MockitoSpyBean private JdbcProjectRepository repository;
    /** APP连接用于观测原事务PID以及角色，不能用owner替代业务主体。 */
    @Autowired private JdbcTemplate jdbc;
    /** 基础guard没有独立业务入口，由调用者测试事务验证加入与提交边界。 */
    @Autowired private PlatformTransactionManager transactionManager;
    /** 每例独占身份，另一租户只属于协作者，绝不把它用作项目归属。 */
    private final Fixture fixture = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
            Uuid7.generate(), Uuid7.generate(), Uuid7.generate());

    /** 写种子前先验证CONTROL、DATA、owner三个实际数据库与权限落点。 */
    @BeforeEach
    void prepare() throws SQLException {
        assertThat(DATABASE_URL).isNotEqualTo(POSTGRES.getJdbcUrl());
        assertThat(mockingDetails(unusedRestQuotaRelaxation).isMock()).isTrue();
        assertThat(mockingDetails(unusedNotificationWorkCoordinator).isMock()).isTrue();
        for (DatabaseWorkload workload : DatabaseWorkload.values()) {
            try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(workload)) {
                Map<String, Object> identity = jdbc.queryForMap("SELECT current_database(), current_user");
                assertThat(identity.get("current_database")).isEqualTo(DATABASE_NAME);
                assertThat(identity.get("current_user")).isEqualTo(APP_ROLE);
                assertThat(jdbc.queryForObject("SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname=current_user", Boolean.class)).isTrue();
            }
        }
        try (Connection owner = fixtureOwnerConnection()) {
            try (PreparedStatement query = owner.prepareStatement("SELECT current_database(), current_user"); ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo(DATABASE_NAME).isNotEqualTo(POSTGRES.getDatabaseName());
                assertThat(rows.getString(2)).isEqualTo(MANAGEMENT_POSTGRES.getUsername());
            }
            owner.setAutoCommit(false);
            execute(owner, "INSERT INTO sys_tenant (id,name) VALUES (?, '项目归属租户'), (?, '协作者自身租户')", fixture.tenantId(), fixture.collaboratorTenantId());
            for (UUID account : List.of(fixture.ownerId(), fixture.collaboratorId(), fixture.outsiderId())) {
                execute(owner, "INSERT INTO sys_account (id,email,password_hash,display_name) VALUES (?, ?, '{noop}unused', '管理许可测试账号')", account, account + "@example.com");
            }
            execute(owner, "INSERT INTO sys_tenant_member (id,tenant_id,account_id) VALUES (?, ?, ?), (?, ?, ?)",
                    Uuid7.generate(), fixture.tenantId(), fixture.ownerId(), Uuid7.generate(), fixture.collaboratorTenantId(), fixture.collaboratorId());
            execute(owner, "INSERT INTO sys_project (id,tenant_id,name,region,project_key) VALUES (?, ?, '管理许可原名', 'sh-1', ?)",
                    fixture.projectId(), fixture.tenantId(), "management_" + fixture.projectId().toString().replace("-", ""));
            execute(owner, "INSERT INTO sys_project_member (id,project_id,account_id,role) VALUES (?, ?, ?, 'OWNER')", Uuid7.generate(), fixture.projectId(), fixture.ownerId());
            owner.commit();
        }
    }

    /** 三普通查询各自报告结果；DELETING且未软删是旧SQL的确定反例，不用第一断言掩盖其余两条。 */
    @ParameterizedTest
    @EnumSource(ReadState.class)
    void ordinaryQueriesExposeOnlyReadableProjects(ReadState state) throws Exception {
        if (state == ReadState.SOFT_DELETED) {
            String retainedOwner;
            try (Connection owner = fixtureOwnerConnection()) {
                retainedOwner = rows(owner, "SELECT row_to_json(m)::text FROM sys_project_member m WHERE project_id=?", fixture.projectId()).getFirst();
            }
            asOwner(() -> { projectService.delete(fixture.projectId()); return null; });
            try (Connection owner = fixtureOwnerConnection()) {
                // 5d4真实删除现在保全原完整OWNER行；不再由测试补插，防止掩盖生产仍物理删成员。
                assertThat(rows(owner, "SELECT row_to_json(m)::text FROM sys_project_member m WHERE project_id=?", fixture.projectId())).containsExactly(retainedOwner);
            }
        } else {
            setState(state.name());
        }
        boolean readable = state == ReadState.ACTIVE || state == ReadState.ARCHIVED;
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(repository.findMembershipsByAccount(fixture.ownerId())).as("list: %s", state).hasSize(readable ? 1 : 0);
        softly.assertThat(repository.findMembership(fixture.projectId(), fixture.ownerId()).isPresent()).as("membership: %s", state).isEqualTo(readable);
        softly.assertThat(repository.findRole(fixture.projectId(), fixture.ownerId()).isPresent()).as("role: %s", state).isEqualTo(readable);
        softly.assertThat(repository.findMembershipsByAccount(fixture.outsiderId())).as("他人列表").isEmpty();
        softly.assertThat(repository.findMembership(fixture.projectId(), fixture.outsiderId())).as("他人成员查询").isEmpty();
        softly.assertThat(repository.findRole(fixture.projectId(), fixture.outsiderId())).as("他人角色查询").isEmpty();
        softly.assertAll();
        // 仓储排他锁自身也需白名单，不能仅依赖guard预检恰好先拒绝DELETING。
        assertThat(inTransaction(() -> repository.lockForManagement(fixture.projectId())).isPresent()).isEqualTo(readable);
        if (readable) {
            assertThat(repository.findMembership(fixture.projectId(), fixture.ownerId()).orElseThrow().project().status().name()).isEqualTo(state.name());
        } else {
            assertBusinessCode(catchThrowable(() -> inTransaction(() -> guard.requireOwner(fixture.projectId(), fixture.ownerId()))), 50001);
        }
    }

    /** 共享角色枚举决定最小权限，归档只对已授权者返回50017，非成员不泄露项目存在。 */
    @ParameterizedTest
    @EnumSource(ProjectRole.class)
    void guardUsesActualRoleAndPreservesAuthorizationBeforeArchive(ProjectRole role) throws Exception {
        UUID actor = role == ProjectRole.OWNER ? fixture.ownerId() : fixture.collaboratorId();
        if (role != ProjectRole.OWNER) addCollaborator(role);
        assertThat(inTransaction(() -> guard.requireMember(fixture.projectId(), actor))).isEqualTo(role);
        if (role.canManageMembers()) assertThat(inTransaction(() -> guard.requireMemberManager(fixture.projectId(), actor))).isEqualTo(role);
        else assertBusinessCode(catchThrowable(() -> inTransaction(() -> guard.requireMemberManager(fixture.projectId(), actor))), 50002);
        if (role == ProjectRole.OWNER) assertThat(inTransaction(() -> guard.requireOwner(fixture.projectId(), actor))).isEqualTo(role);
        else assertBusinessCode(catchThrowable(() -> inTransaction(() -> guard.requireOwner(fixture.projectId(), actor))), 50003);
        setState("ARCHIVED");
        List<String> before = facts();
        assertBusinessCode(catchThrowable(() -> inTransaction(() -> guard.requireMember(fixture.projectId(), actor))), 50017);
        assertBusinessCode(catchThrowable(() -> inTransaction(() -> guard.requireOwner(fixture.projectId(), actor))), role == ProjectRole.OWNER ? 50017 : 50003);
        assertBusinessCode(catchThrowable(() -> inTransaction(() -> guard.requireMemberManager(fixture.projectId(), actor))), role.canManageMembers() ? 50017 : 50002);
        assertBusinessCode(catchThrowable(() -> inTransaction(() -> guard.requireMember(fixture.projectId(), fixture.outsiderId()))), 50001);
        assertThat(facts()).isEqualTo(before);
    }

    /** ADR0012跨租户协作者用自己的tenant，不可被误当作项目归属不匹配而拒绝。 */
    @Test
    void collaboratorFromAnotherTenantRetainsProjectRole() throws Exception {
        addCollaborator(ProjectRole.ADMIN);
        TenantContext.set(new TenantScope(fixture.collaboratorTenantId(), fixture.projectId(), fixture.collaboratorId()));
        try {
            assertThat(inTransaction(() -> guard.requireMemberManager(fixture.projectId(), fixture.collaboratorId()))).isEqualTo(ProjectRole.ADMIN);
            assertThat(projectService.listMine()).singleElement().satisfies(membership -> {
                assertThat(membership.project().tenantId()).isEqualTo(fixture.tenantId());
                assertThat(membership.role()).isEqualTo(ProjectRole.ADMIN);
            });
        } finally { TenantContext.clear(); }
    }

    /** 无事务和只读外层都必须拒绝，不能悄悄新建可写事务取得瞬时许可。 */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void guardRequiresExistingWritableTransaction(boolean readOnly) throws Exception {
        List<String> before = facts();
        Throwable failure;
        if (readOnly) {
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            transaction.setReadOnly(true);
            failure = catchThrowable(() -> transaction.execute(status -> guard.requireMember(fixture.projectId(), fixture.ownerId())));
            assertThat(failure).isExactlyInstanceOf(IllegalStateException.class);
        } else {
            failure = catchThrowable(() -> guard.requireMember(fixture.projectId(), fixture.ownerId()));
            assertThat(failure).isInstanceOf(IllegalTransactionStateException.class);
        }
        assertThat(facts()).isEqualTo(before);
    }

    /**
     * RR/SERIALIZABLE的真实旧成员快照：另事务只移除成员而不更新项目行，FOR UPDATE项目不能刷新成员快照。
     * 通过服务器SET TRANSACTION制造实际隔离与Spring DEFAULT元数据不同，防止仅查TSM漏掉连接侧隔离设置。
     */
    @ParameterizedTest
    @ValueSource(strings = {"REPEATABLE READ", "SERIALIZABLE"})
    void snapshotIsolationRejectsStaleMemberAuthorizationWithoutSpringIsolationMetadata(String isolation) throws Exception {
        addCollaborator(ProjectRole.ADMIN);
        AtomicReference<List<String>> afterRemoval = new AtomicReference<>();
        Throwable failure = catchThrowable(() -> inTransaction(() -> {
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()).isNull();
            // 隔离字符串来自固定测试枚举；先于任何读取执行，使PG真实隔离确定而不依赖连接池复用顺序。
            jdbc.execute("SET TRANSACTION ISOLATION LEVEL " + isolation);
            assertThat(jdbc.queryForObject("SHOW transaction_isolation", String.class)).isEqualTo(isolation.toLowerCase(java.util.Locale.ROOT));
            assertThat(repository.findRole(fixture.projectId(), fixture.collaboratorId())).contains(ProjectRole.ADMIN);
            try (Connection concurrentOwner = fixtureOwnerConnection()) {
                concurrentOwner.setAutoCommit(false);
                lockProject(concurrentOwner);
                execute(concurrentOwner, "DELETE FROM sys_project_member WHERE project_id=? AND account_id=?", fixture.projectId(), fixture.collaboratorId());
                concurrentOwner.commit();
                afterRemoval.set(facts());
            } catch (SQLException sql) {
                throw new AssertionError("独立成员移除夹具失败，不能计为隔离拒绝", sql);
            }
            // 独立事务已真实移除，但本事务快照仍返回ADMIN；这是旧实现能够错误授权的具体前提。
            assertThat(repository.findRole(fixture.projectId(), fixture.collaboratorId())).contains(ProjectRole.ADMIN);
            return guard.requireMemberManager(fixture.projectId(), fixture.collaboratorId());
        }));
        assertThat(failure).isNotNull();
        Throwable root = failure;
        while (root.getCause() != null) root = root.getCause();
        // @Repository可能包为InvalidDataAccessApiUsageException；只接受真实不支持隔离的IllegalStateException根因。
        assertThat(root).isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("项目管理排他锁要求READ COMMITTED或READ UNCOMMITTED事务");
        assertThat(facts()).isEqualTo(afterRemoval.get());
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(jdbc.queryForObject("SHOW transaction_isolation", String.class)).isEqualTo("read committed");
        assertBusinessCode(catchThrowable(() -> inTransaction(() -> guard.requireMemberManager(fixture.projectId(), fixture.collaboratorId()))), 50001);
        addCollaborator(ProjectRole.ADMIN);
        assertThat(inTransaction(() -> guard.requireMemberManager(fixture.projectId(), fixture.collaboratorId()))).isEqualTo(ProjectRole.ADMIN);
    }

    /** PostgreSQL把RU按RC执行，实际服务器隔离是允许值；结束后不污染池连接的下一个事务。 */
    @Test
    void actualReadUncommittedWithoutSpringIsolationMetadataRemainsSupported() throws Exception {
        List<String> before = facts();
        assertThat(inTransaction(() -> {
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()).isNull();
            jdbc.execute("SET TRANSACTION ISOLATION LEVEL READ UNCOMMITTED");
            assertThat(jdbc.queryForObject("SHOW transaction_isolation", String.class)).isEqualTo("read uncommitted");
            return guard.requireOwner(fixture.projectId(), fixture.ownerId());
        })).isEqualTo(ProjectRole.OWNER);
        assertThat(facts()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SHOW transaction_isolation", String.class)).isEqualTo("read committed");
        assertThat(inTransaction(() -> guard.requireOwner(fixture.projectId(), fixture.ownerId()))).isEqualTo(ProjectRole.OWNER);
    }

    /** guard返回后仅观察并暂停，排除后续UPDATE自行加锁掩盖许可提前释放；OWNER删除必须等原事务提交。 */
    @Test
    void managementLockRemainsAfterGuardReturnsUntilCallerCommitAndBlocksOwnerDelete() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch permitted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> permitPid = new CompletableFuture<>();
        CompletableFuture<Integer> deletePid = new CompletableFuture<>();
        try {
            Future<?> permittedOperation = executor.submit(() -> asOwner(() -> inTransaction(() -> {
                guard.requireOwner(fixture.projectId(), fixture.ownerId());
                permitPid.complete(appPid());
                assertThat(repository.findMembership(fixture.projectId(), fixture.ownerId()).orElseThrow().project().name()).isEqualTo("管理许可原名");
                permitted.countDown(); await(release); return null;
            })));
            assertThat(permitted.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> deletion = executor.submit(() -> asOwner(() -> inTransaction(() -> {
                deletePid.complete(appPid()); projectService.delete(fixture.projectId()); return null;
            })));
            assertBlockedBy(deletePid.get(5, TimeUnit.SECONDS), permitPid.get(5, TimeUnit.SECONDS), deletion);
            release.countDown(); permittedOperation.get(5, TimeUnit.SECONDS); deletion.get(5, TimeUnit.SECONDS);
            try (Connection owner = fixtureOwnerConnection()) {
                assertThat(rows(owner, "SELECT name FROM sys_project WHERE id=? AND status='DELETING' AND deleted_at IS NOT NULL", fixture.projectId())).containsExactly("管理许可原名");
                assertThat(repository.findRole(fixture.projectId(), fixture.ownerId())).isEmpty();
            }
        } finally { release.countDown(); shutdown(executor); }
    }

    /** 两个管理许可本身互斥，排除误用FOR SHARE仍可阻挡DELETE而产生的假阳性；双方均不写项目行。 */
    @Test
    void managementPermitsExcludeEachOtherUntilOriginalTransactionCommits() throws Exception {
        List<String> before = facts();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch permitted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> firstPid = new CompletableFuture<>();
        CompletableFuture<Integer> secondPid = new CompletableFuture<>();
        try {
            Future<ProjectRole> first = executor.submit(() -> inTransaction(() -> {
                ProjectRole role = guard.requireMember(fixture.projectId(), fixture.ownerId());
                firstPid.complete(appPid());
                permitted.countDown(); await(release); return role;
            }));
            assertThat(permitted.await(5, TimeUnit.SECONDS)).isTrue();
            Future<ProjectRole> second = executor.submit(() -> inTransaction(() -> {
                secondPid.complete(appPid());
                return guard.requireOwner(fixture.projectId(), fixture.ownerId());
            }));
            assertBlockedBy(secondPid.get(5, TimeUnit.SECONDS), firstPid.get(5, TimeUnit.SECONDS), second);
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(ProjectRole.OWNER);
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(ProjectRole.OWNER);
            assertThat(facts()).isEqualTo(before);
        } finally { release.countDown(); shutdown(executor); }
    }

    /** 真角色预检完成后等待项目锁；持锁者提交角色删除/降级/归档，不能使用锁前角色直接放行。 */
    @ParameterizedTest
    @EnumSource(ConcurrentChange.class)
    void lockWaitRechecksActualMembershipAndProjectState(ConcurrentChange change) throws Exception {
        addCollaborator(ProjectRole.ADMIN);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CompletableFuture<Integer> waiterPid = captureLockPid();
        try (Connection holder = fixtureOwnerConnection()) {
            holder.setAutoCommit(false);
            int holderPid = lockProject(holder);
            Future<Throwable> waiter = executor.submit(() -> catchThrowable(() -> inTransaction(() -> {
                guard.requireMemberManager(fixture.projectId(), fixture.collaboratorId());
                repository.updateName(fixture.projectId(), "不得使用旧授权写入"); return null;
            })));
            assertBlockedBy(waiterPid.get(5, TimeUnit.SECONDS), holderPid, waiter);
            // 此处专测基础许可锁后复验；明确在真实项目排他锁内修改持久角色，不能mock授权查询。
            switch (change) {
                case REMOVED -> execute(holder, "DELETE FROM sys_project_member WHERE project_id=? AND account_id=?", fixture.projectId(), fixture.collaboratorId());
                case DEMOTED -> execute(holder, "UPDATE sys_project_member SET role='VIEWER' WHERE project_id=? AND account_id=?", fixture.projectId(), fixture.collaboratorId());
                case ARCHIVED -> execute(holder, "UPDATE sys_project SET status='ARCHIVED' WHERE id=?", fixture.projectId());
            }
            holder.commit();
            assertBusinessCode(waiter.get(5, TimeUnit.SECONDS), change.code);
            assertThat(rows(holder, "SELECT name FROM sys_project WHERE id=?", fixture.projectId())).containsExactly("管理许可原名");
        } finally { shutdown(executor); }
    }

    /** 原JDBC五秒query budget真实触发57014；保持业务失败分类，释放锁后原请求可重试成功。 */
    @Test
    void actualQueryTimeoutRollsBackAndRecoversAfterLockRelease() throws Exception {
        List<String> before = facts();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CompletableFuture<Integer> waiterPid = captureLockPid();
        try (Connection holder = fixtureOwnerConnection()) {
            holder.setAutoCommit(false);
            int holderPid = lockProject(holder);
            long started = System.nanoTime();
            Future<Throwable> waiter = executor.submit(() -> catchThrowable(() -> inTransaction(() -> {
                guard.requireOwner(fixture.projectId(), fixture.ownerId());
                repository.updateName(fixture.projectId(), "超时不应写入"); return null;
            })));
            assertBlockedBy(waiterPid.get(5, TimeUnit.SECONDS), holderPid, waiter);
            Throwable failure = waiter.get(8, TimeUnit.SECONDS);
            assertThat(hasSqlTimeout(failure)).as("原SQLException SQLSTATE57014").isTrue();
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isGreaterThanOrEqualTo(4000);
            assertThat(facts()).isEqualTo(before);
            holder.rollback();
        } finally { shutdown(executor); }
        assertThat(inTransaction(() -> guard.requireOwner(fixture.projectId(), fixture.ownerId()))).isEqualTo(ProjectRole.OWNER);
        assertThat(facts()).isEqualTo(before);
    }

    /** 已持许可后真实改名和成员新增，再内部故障必须整体回滚；恢复后同两条真实写一起提交。 */
    @Test
    void internalFailureRollsBackActualProjectAndMemberWritesThenRecovers() throws Exception {
        List<String> before = facts();
        Throwable failure = catchThrowable(() -> inTransaction(() -> {
            writeProjectAndMember(); throw new IllegalStateException("许可后业务内部故障");
        }));
        assertThat(failure).isExactlyInstanceOf(IllegalStateException.class).hasMessage("许可后业务内部故障");
        assertThat(facts()).isEqualTo(before);
        inTransaction(() -> { writeProjectAndMember(); return null; });
        assertThat(repository.findRole(fixture.projectId(), fixture.collaboratorId())).contains(ProjectRole.VIEWER);
        assertThat(repository.findMembership(fixture.projectId(), fixture.ownerId()).orElseThrow().project().name()).isEqualTo("许可后原子改名");
    }

    /** 代表调用者的原事务业务；本例只验证基础许可原子性，不冒称七入口端到端证据。 */
    private void writeProjectAndMember() {
        assertThat(guard.requireOwner(fixture.projectId(), fixture.ownerId())).isEqualTo(ProjectRole.OWNER);
        assertThat(repository.updateName(fixture.projectId(), "许可后原子改名")).isEqualTo(1);
        repository.addMember(Uuid7.generate(), fixture.projectId(), fixture.collaboratorId(), ProjectRole.VIEWER);
        assertThat(repository.findRole(fixture.projectId(), fixture.collaboratorId())).contains(ProjectRole.VIEWER);
    }

    /** SQL前才记录PID，能走到这里已完成原真实角色预检，随后原FOR UPDATE自行等待。 */
    private CompletableFuture<Integer> captureLockPid() {
        CompletableFuture<Integer> pid = new CompletableFuture<>();
        JdbcProjectRepository target = AopTestUtils.getUltimateTargetObject(repository);
        doAnswer(invocation -> { pid.complete(appPid()); return invocation.callRealMethod(); })
                .when(target).lockForManagement(fixture.projectId());
        return pid;
    }

    /** 验证真实业务连接角色和非只读事务，owner只用于独立持锁与观察。 */
    private int appPid() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        return jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
    }

    /** 明确项目锁而非成员行锁，才能检验预检后的重新授权语义。 */
    private int lockProject(Connection connection) throws SQLException {
        try (PreparedStatement lock = connection.prepareStatement("SELECT pg_backend_pid() FROM sys_project WHERE id=? FOR UPDATE")) {
            lock.setObject(1, fixture.projectId());
            try (ResultSet rows = lock.executeQuery()) { assertThat(rows.next()).isTrue(); return rows.getInt(1); }
        }
    }

    /** pg_blocking_pids和未授予锁双证据，避免Future未完成被误认为项目锁排序。 */
    private void assertBlockedBy(int waiter, int holder, Future<?> operation) throws Exception {
        try (Connection observer = fixtureOwnerConnection(); PreparedStatement query = observer.prepareStatement(
                "SELECT pg_backend_pid(), ? = ANY(pg_blocking_pids(?)), EXISTS(SELECT 1 FROM pg_locks WHERE pid=? AND NOT granted)")) {
            assertThat(waiter).isNotEqualTo(holder);
            query.setInt(1, holder); query.setInt(2, waiter); query.setInt(3, waiter); query.setQueryTimeout(2);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < deadline) {
                try (ResultSet rows = query.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isNotEqualTo(waiter).isNotEqualTo(holder);
                    if (rows.getBoolean(2) && rows.getBoolean(3)) return;
                }
                if (operation.isDone()) throw new AssertionError("操作已完成却没有真实项目锁等待");
                Thread.sleep(5);
            }
        }
        throw new AssertionError("未观察到指定项目排他锁阻塞");
    }

    /** 每次调用独立事务；拒绝异常不能被测试吞在事务内而意外提交。 */
    private <T> T inTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    /** OWNER身份只服务真实删除入口；guard明确接收account，不从ThreadLocal猜项目归属。 */
    private <T> T asOwner(Supplier<T> work) {
        TenantContext.set(new TenantScope(fixture.tenantId(), fixture.projectId(), fixture.ownerId()));
        try { return work.get(); } finally { TenantContext.clear(); }
    }

    /** 确认错误来自业务码，而非SQLException或测试框架异常。 */
    private void assertBusinessCode(Throwable failure, int expected) {
        assertThat(failure).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) failure).errorCode().code()).isEqualTo(expected);
    }

    /** 仅接受PG query_canceled；连接故障或死锁不算预算验收通过。 */
    private boolean hasSqlTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "57014".equals(sql.getSQLState())) return true;
        }
        return false;
    }

    /** 控制提交屏障有限等待，中断不吞掉。 */
    private void await(CountDownLatch latch) {
        try { assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
    }

    /** 失败也收束所有工作线程，避免下一例被晚到写入污染。 */
    private void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow(); assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    /** 只创建明确角色的有效跨租户成员，不改tenant归属。 */
    private void addCollaborator(ProjectRole role) throws SQLException {
        try (Connection owner = fixtureOwnerConnection()) {
            execute(owner, "INSERT INTO sys_project_member (id,project_id,account_id,role) VALUES (?, ?, ?, ?)",
                    Uuid7.generate(), fixture.projectId(), fixture.collaboratorId(), role.name());
        }
    }

    /** 状态反例保留真实项目和OWNER行；DELETING刻意不设置deleted_at以独立检验白名单。 */
    private void setState(String state) throws SQLException {
        try (Connection owner = fixtureOwnerConnection()) { execute(owner, "UPDATE sys_project SET status=? WHERE id=?", state, fixture.projectId()); }
    }

    /** 完整项目与成员行作原子性比较，包含时间戳、角色、状态，不能仅比较数量。 */
    private List<String> facts() throws SQLException {
        List<String> result = new ArrayList<>();
        try (Connection owner = fixtureOwnerConnection()) {
            result.addAll(rows(owner, "SELECT row_to_json(p)::text FROM sys_project p WHERE id=?", fixture.projectId()));
            result.addAll(rows(owner, "SELECT row_to_json(m)::text FROM sys_project_member m WHERE project_id=? ORDER BY id", fixture.projectId()));
        }
        return List.copyOf(result);
    }

    /** 本片无不可变审计写入；只清理本例身份，不全局删除或关闭约束。 */
    @AfterEach
    void cleanup() throws SQLException {
        TenantContext.clear();
        try (Connection owner = fixtureOwnerConnection()) {
            owner.setAutoCommit(false);
            execute(owner, "DELETE FROM sys_project_member WHERE project_id=?", fixture.projectId());
            execute(owner, "DELETE FROM sys_project WHERE id=?", fixture.projectId());
            execute(owner, "DELETE FROM sys_tenant_member WHERE tenant_id IN (?, ?)", fixture.tenantId(), fixture.collaboratorTenantId());
            execute(owner, "DELETE FROM sys_account WHERE id IN (?, ?, ?)", fixture.ownerId(), fixture.collaboratorId(), fixture.outsiderId());
            execute(owner, "DELETE FROM sys_tenant WHERE id IN (?, ?)", fixture.tenantId(), fixture.collaboratorTenantId());
            owner.commit();
        }
    }

    /** owner只承担种子、锁竞争和观察，运行/Flyway/基类辅助夹具统一专库。 */
    @Override
    protected Connection fixtureOwnerConnection() throws SQLException {
        return DriverManager.getConnection(DATABASE_URL, MANAGEMENT_POSTGRES.getUsername(), MANAGEMENT_POSTGRES.getPassword());
    }

    /** 参数化更新只影响本例身份；不拼接用户数据。 */
    private void execute(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            statement.executeUpdate();
        }
    }

    /** 精确查询作为独立owner观察，不改变应用事务与权限。 */
    private List<String> rows(Connection connection, String sql, Object... values) throws SQLException {
        List<String> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            try (ResultSet rows = statement.executeQuery()) { while (rows.next()) result.add(rows.getString(1)); }
        }
        return List.copyOf(result);
    }

    /** 启动独占PG先于Flyway，容器生命周期由本类OwnedTestContainers管理。 */
    private static String startDatabase() {
        MANAGEMENT_POSTGRES.start(); return MANAGEMENT_POSTGRES.getJdbcUrl();
    }

    /** Registrar覆盖继承的共享URL，避免父DynamicPropertySource最后注册导致落点错误。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class IsolatedDatabaseConfiguration {
        /** 运行/Flyway同源，抑制与本片无关的自动publisher、retry与listener。 */
        @Bean
        DynamicPropertyRegistrar isolatedDatabaseProperties() {
            return registry -> {
                registry.add("spring.datasource.url", () -> DATABASE_URL);
                registry.add("spring.flyway.url", () -> DATABASE_URL);
                registry.add("things-link.outbox.publisher.enabled", () -> "false");
                registry.add("things-link.notification.retry.enabled", () -> "false");
                registry.add("spring.kafka.listener.auto-startup", () -> "false");
            };
        }
    }

    /** 显式区分未软删DELETING与真实已删种子，防止状态过滤被deleted_at代偿。 */
    private enum ReadState {
        /** 正常读写。 */ ACTIVE,
        /** 普通只读可见。 */ ARCHIVED,
        /** 仅状态进入删除中。 */ DELETING,
        /** 真实OWNER软删后由生产保留原成员事实。 */ SOFT_DELETED
    }

    /** 等待锁期间发生的三种独立授权变化。 */
    private enum ConcurrentChange {
        /** 失去成员身份。 */ REMOVED(50001),
        /** 成员仍在但失去管理权限。 */ DEMOTED(50002),
        /** 仍有权限但项目只读。 */ ARCHIVED(50017);
        /** 对应既定业务错误码。 */ private final int code;
        /** @param code 权限优先于归档分类的业务结果 */ ConcurrentChange(int code) { this.code = code; }
    }

    /** @param tenantId 项目归属 @param projectId 项目 @param ownerId 真实OWNER @param collaboratorTenantId 协作者自身租户 @param collaboratorId 协作者 @param outsiderId 非成员账号 */
    private record Fixture(UUID tenantId, UUID projectId, UUID ownerId, UUID collaboratorTenantId, UUID collaboratorId, UUID outsiderId) { }
}
