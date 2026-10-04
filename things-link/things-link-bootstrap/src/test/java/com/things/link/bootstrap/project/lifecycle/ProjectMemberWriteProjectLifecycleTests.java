package com.things.link.bootstrap.project.lifecycle;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.project.application.ProjectMemberService;
import com.things.link.project.application.ProjectService;
import com.things.link.project.infrastructure.persistence.JdbcProjectRepository;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.scheduling.NotificationWorkCoordinator;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import com.things.link.support.tenant.DatabaseWorkload;
import com.things.link.support.tenant.DatabaseWorkloadContext;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.ArgumentMatchers.argThat;

/**
 * ADR0064决策2/5、S12-P0-5d2：三个成员写入口在自身生产事务中取得排他许可并原子记录审计。
 * 本类不包外层业务测试事务；保全及转让/退出沿各自切片的现行实现，审计tenant仍取原请求scope。
 */
@Import(ProjectMemberWriteProjectLifecycleTests.IsolatedDatabaseConfiguration.class)
@OwnedTestContainers({"MEMBER_POSTGRES"})
class ProjectMemberWriteProjectLifecycleTests extends AbstractIntegrationTest {

    /** D-109：独立物理数据库防止缓存上下文的全局worker干扰，随机项目不能替代数据库隔离。 */
    private static final String DATABASE_NAME = "project_member_write_" + UUID.randomUUID().toString().replace("-", "");
    /** 同镜像、同owner的独占集群，避免迁移时角色级变化污染其他测试。 */
    private static final PostgreSQLContainer<?> MEMBER_POSTGRES = new PostgreSQLContainer<>(
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
    /** 三个被测入口由原Spring代理创建业务事务，测试不得用TT替它补事务。 */
    @Autowired private ProjectMemberService members;
    /** 真实OWNER删除仅用于当前已有可执行路径，成员计数限制不放宽。 */
    @Autowired private ProjectService projects;
    /** 原仓储所有SQL真实执行；spy只在项目许可SQL前捕捉APP连接PID。 */
    @MockitoSpyBean private JdbcProjectRepository repository;
    /** 审计已经真实写入后设置屏障/故障，防止假造未执行审计的回滚证据。 */
    @MockitoSpyBean private AuditLogService audits;
    /** 观察原事务内尚未提交的审计与成员事实。 */
    @Autowired private JdbcTemplate jdbc;
    /** 比较完整jsonb结构，不依赖Map序列化键顺序。 */
    @Autowired private ObjectMapper mapper;
    /** 每例独占身份：项目归属、跨tenant管理员、目标账号与非成员账号彼此独立。 */
    private final Fixture fixture = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
            Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate());

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
                assertThat(rows.getString(2)).isEqualTo(MEMBER_POSTGRES.getUsername());
            }
            owner.setAutoCommit(false);
            execute(owner, "INSERT INTO sys_tenant (id,name) VALUES (?, '项目归属租户'), (?, '协作者自身租户')", fixture.tenantId(), fixture.collaboratorTenantId());
            execute(owner, "UPDATE sys_tenant SET quota_policy_id=(SELECT id FROM sys_quota_policy WHERE code='PLAN_R1_STANDARD') WHERE id=?", fixture.tenantId());
            for (UUID account : List.of(fixture.ownerId(), fixture.collaboratorId(), fixture.outsiderId(), fixture.targetId())) {
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

    /** 旧路径确定RED：原服务在ARCHIVED仍会写成员和审计，本片三个入口都必须50017且完整事实不变。 */
    @ParameterizedTest
    @EnumSource(Operation.class)
    void archivedEntryRejectsWithoutMemberOrAuditChanges(Operation operation) throws Exception {
        prepareOperation(operation);
        setProjectState("ARCHIVED");
        List<String> before = facts();
        assertBusinessCode(catchThrowable(() -> invoke(operation, fixture.collaboratorId(), fixture.collaboratorTenantId())), 50017);
        assertThat(facts()).isEqualTo(before);
    }

    /** 正常跨tenant管理员仍可操作他人归属项目；审计保留原scope租户、操作者、目标和原详情合同。 */
    @ParameterizedTest
    @EnumSource(Operation.class)
    void activeCrossTenantAdministratorWritesMemberAndOriginalAudit(Operation operation) throws Exception {
        prepareOperation(operation);
        invoke(operation, fixture.collaboratorId(), fixture.collaboratorTenantId());
        assertCommittedOutcome(operation, fixture.collaboratorId(), fixture.collaboratorTenantId());
    }

    /** 无权、非成员、DELETING依次核验原业务码；每次失败均无成员或审计增量。 */
    @ParameterizedTest
    @EnumSource(Operation.class)
    void unauthorizedAndDeletingEntriesMakeNoChanges(Operation operation) throws Exception {
        prepareOperation(operation);
        try (Connection owner = fixtureOwnerConnection()) {
            execute(owner, "UPDATE sys_project_member SET role='VIEWER' WHERE project_id=? AND account_id=?", fixture.projectId(), fixture.collaboratorId());
        }
        List<String> before = facts();
        assertBusinessCode(catchThrowable(() -> invoke(operation, fixture.collaboratorId(), fixture.collaboratorTenantId())), 50002);
        assertBusinessCode(catchThrowable(() -> invoke(operation, fixture.outsiderId(), fixture.tenantId())), 50001);
        assertThat(facts()).isEqualTo(before);
        setProjectState("DELETING");
        before = facts();
        assertBusinessCode(catchThrowable(() -> invoke(operation, fixture.ownerId(), fixture.tenantId())), 50001);
        assertThat(facts()).isEqualTo(before);
    }

    /** 成员和审计SQL均执行后仍处于原service事务；独立非键status更新必须等待许可直至提交。 */
    @ParameterizedTest
    @EnumSource(Operation.class)
    void originalServiceTransactionKeepsLockAfterActualAuditUntilCommit(Operation operation) throws Exception {
        prepareOperation(operation);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch audited = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> writerPid = new CompletableFuture<>();
        CompletableFuture<Integer> archivePid = new CompletableFuture<>();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            writerPid.complete(appPid());
            assertUncommittedOutcome(operation);
            audited.countDown(); await(release); return null;
        }).when(audits).record(argThat(entry -> isTargetAudit(entry, operation)));
        try {
            Future<?> writer = executor.submit(() -> invoke(operation, fixture.collaboratorId(), fixture.collaboratorTenantId()));
            assertThat(audited.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> archiver = executor.submit(() -> {
                try (Connection owner = fixtureOwnerConnection()) {
                    archivePid.complete(connectionPid(owner));
                    // 非键status更新不会被INSERT外键的KEY SHARE挡住；等待证明来自管理排他许可。
                    execute(owner, "UPDATE sys_project SET status='ARCHIVED' WHERE id=?", fixture.projectId());
                } catch (SQLException failure) { throw new IllegalStateException(failure); }
            });
            assertBlockedBy(archivePid.get(5, TimeUnit.SECONDS), writerPid.get(5, TimeUnit.SECONDS), archiver);
            release.countDown(); writer.get(5, TimeUnit.SECONDS); archiver.get(5, TimeUnit.SECONDS);
            assertCommittedOutcome(operation, fixture.collaboratorId(), fixture.collaboratorTenantId());
            try (Connection owner = fixtureOwnerConnection()) {
                assertThat(rows(owner, "SELECT status FROM sys_project WHERE id=?", fixture.projectId())).containsExactly("ARCHIVED");
            }
        } finally { release.countDown(); shutdown(executor); }
    }

    /** 锁前真实管理员预检通过；项目锁等待后分别真实移除、降级、归档，后续成员写和审计都不得发生。 */
    @ParameterizedTest
    @EnumSource(Operation.class)
    void waitingServiceRechecksAuthorityAndArchiveBeforeWriting(Operation operation) throws Exception {
        prepareOperation(operation);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CompletableFuture<Integer> waiterPid = captureLockPid();
        try (Connection holder = fixtureOwnerConnection()) {
            holder.setAutoCommit(false);
            int holderPid = lockProject(holder);
            Future<Throwable> waiter = executor.submit(() -> catchThrowable(() -> invoke(operation, fixture.collaboratorId(), fixture.collaboratorTenantId())));
            assertBlockedBy(waiterPid.get(5, TimeUnit.SECONDS), holderPid, waiter);
            int expected = switch (operation) {
                case INVITE -> {
                    execute(holder, "DELETE FROM sys_project_member WHERE project_id=? AND account_id=?", fixture.projectId(), fixture.collaboratorId());
                    yield 50001;
                }
                case UPDATE_ROLE -> {
                    execute(holder, "UPDATE sys_project_member SET role='VIEWER' WHERE project_id=? AND account_id=?", fixture.projectId(), fixture.collaboratorId());
                    yield 50002;
                }
                case REMOVE -> {
                    execute(holder, "UPDATE sys_project SET status='ARCHIVED' WHERE id=?", fixture.projectId());
                    yield 50017;
                }
            };
            holder.commit();
            assertBusinessCode(waiter.get(5, TimeUnit.SECONDS), expected);
            assertOriginalTargetAndNoAudit(operation);
        } finally { shutdown(executor); }
    }

    /** 管理员保留权限但目标在等待项目锁期间真实消失，锁后目标复验必须50001且不得生成成功审计。 */
    @ParameterizedTest
    @EnumSource(value = Operation.class, names = {"UPDATE_ROLE", "REMOVE"})
    void waitingServiceRechecksTargetRemovedUnderProjectLock(Operation operation) throws Exception {
        prepareOperation(operation);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CompletableFuture<Integer> waiterPid = captureLockPid();
        try (Connection holder = fixtureOwnerConnection()) {
            holder.setAutoCommit(false);
            int holderPid = lockProject(holder);
            Future<Throwable> waiter = executor.submit(() -> catchThrowable(() -> invoke(operation, fixture.collaboratorId(), fixture.collaboratorTenantId())));
            assertBlockedBy(waiterPid.get(5, TimeUnit.SECONDS), holderPid, waiter);
            execute(holder, "DELETE FROM sys_project_member WHERE project_id=? AND account_id=?", fixture.projectId(), fixture.targetId());
            holder.commit();
            assertBusinessCode(waiter.get(5, TimeUnit.SECONDS), 50001);
            assertThat(rows(holder, "SELECT role FROM sys_project_member WHERE project_id=? AND account_id=?", fixture.projectId(), fixture.collaboratorId()))
                    .containsExactly("ADMIN");
            assertThat(rows(holder, "SELECT id::text FROM sys_project_member WHERE project_id=? AND account_id=?", fixture.projectId(), fixture.targetId())).isEmpty();
            assertThat(rows(holder, "SELECT id::text FROM sys_audit_log WHERE project_id=?", fixture.projectId())).isEmpty();
        } finally { shutdown(executor); }
    }

    /** 三入口原JDBC五秒预算各自真实产生57014，不能转50017/50001；解锁后原服务可正常重试。 */
    @ParameterizedTest
    @EnumSource(Operation.class)
    void realQueryTimeoutRollsBackAndOriginalServiceRecovers(Operation operation) throws Exception {
        prepareOperation(operation);
        List<String> before = facts();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CompletableFuture<Integer> waiterPid = captureLockPid();
        try (Connection holder = fixtureOwnerConnection()) {
            holder.setAutoCommit(false);
            int holderPid = lockProject(holder);
            long started = System.nanoTime();
            Future<Throwable> waiter = executor.submit(() -> catchThrowable(() -> invoke(operation, fixture.collaboratorId(), fixture.collaboratorTenantId())));
            assertBlockedBy(waiterPid.get(5, TimeUnit.SECONDS), holderPid, waiter);
            assertThat(hasSqlTimeout(waiter.get(8, TimeUnit.SECONDS))).isTrue();
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isGreaterThanOrEqualTo(4000);
            assertThat(facts()).isEqualTo(before);
            holder.rollback();
        } finally { shutdown(executor); }
        invoke(operation, fixture.collaboratorId(), fixture.collaboratorTenantId());
        assertCommittedOutcome(operation, fixture.collaboratorId(), fixture.collaboratorTenantId());
    }

    /** 每入口成员变动和审计都真实落SQL后再故障；回滚必须同时撤销二者，第二次重试真实成功。 */
    @ParameterizedTest
    @EnumSource(Operation.class)
    void internalFailureAfterRealAuditRollsBackMemberAndAuditThenRecovers(Operation operation) throws Exception {
        prepareOperation(operation);
        List<String> before = facts();
        AtomicBoolean failOnce = new AtomicBoolean(true);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            appPid(); assertUncommittedOutcome(operation);
            if (failOnce.compareAndSet(true, false)) throw new AfterAuditFailure();
            return null;
        }).when(audits).record(argThat(entry -> isTargetAudit(entry, operation)));
        Throwable failure = catchThrowable(() -> invoke(operation, fixture.collaboratorId(), fixture.collaboratorTenantId()));
        assertThat(failure).isExactlyInstanceOf(AfterAuditFailure.class);
        assertThat(failOnce).isFalse();
        assertThat(facts()).isEqualTo(before);
        invoke(operation, fixture.collaboratorId(), fixture.collaboratorTenantId());
        assertCommittedOutcome(operation, fixture.collaboratorId(), fixture.collaboratorTenantId());
    }

    /** 删除先真实提交：项目只剩OWNER，使用现有合法删除入口，不伪造带其他成员也能删除。 */
    @Test
    void ownerDeletionCommittedBeforeInvitePreventsNewMembership() throws Exception {
        asActor(fixture.ownerId(), fixture.tenantId(), () -> { projects.delete(fixture.projectId()); return null; });
        List<String> before = facts();
        assertBusinessCode(catchThrowable(() -> invoke(Operation.INVITE, fixture.ownerId(), fixture.tenantId())), 50001);
        assertThat(facts()).isEqualTo(before);
        try (Connection owner = fixtureOwnerConnection()) {
            assertThat(rows(owner, "SELECT status FROM sys_project WHERE id=? AND deleted_at IS NOT NULL", fixture.projectId())).containsExactly("DELETING");
        }
    }

    /** 邀请成功提交后成员数为二，OWNER删除必须50015；本例只验证串行提交，删除锁后重数的并发证据由5d4承担。 */
    @Test
    void committedInvitePreservesExistingOwnerDeletionMemberCountRestriction() throws Exception {
        invoke(Operation.INVITE, fixture.ownerId(), fixture.tenantId());
        assertCommittedOutcome(Operation.INVITE, fixture.ownerId(), fixture.tenantId());
        List<String> before = facts();
        assertBusinessCode(catchThrowable(() -> asActor(fixture.ownerId(), fixture.tenantId(), () -> {
            projects.delete(fixture.projectId()); return null;
        })), 50015);
        assertThat(facts()).isEqualTo(before);
    }

    /** 所有被测写均无外层测试事务，由原ProjectMemberService代理负责开始、提交或回滚。 */
    private void invoke(Operation operation, UUID actorId, UUID actorTenantId) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        asActor(actorId, actorTenantId, () -> {
            switch (operation) {
                case INVITE -> {
                    var invited = members.invite(fixture.projectId(), targetEmail(), ProjectRole.OPERATOR);
                    assertThat(invited.accountId()).isEqualTo(fixture.targetId());
                    assertThat(invited.role()).isEqualTo(ProjectRole.OPERATOR);
                }
                case UPDATE_ROLE -> members.updateRole(fixture.projectId(), fixture.targetId(), ProjectRole.OPERATOR);
                case REMOVE -> members.remove(fixture.projectId(), fixture.targetId());
            }
            return null;
        });
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    /** 按操作建立最少目标事实，种子不产生冒充业务成功的审计。 */
    private void prepareOperation(Operation operation) throws SQLException {
        try (Connection owner = fixtureOwnerConnection()) {
            addMember(owner, fixture.collaboratorId(), ProjectRole.ADMIN);
            if (operation != Operation.INVITE) addMember(owner, fixture.targetId(), ProjectRole.VIEWER);
        }
    }

    /** 真实账号目录按此邮件查找，沿原invite trim/lookup合同。 */
    private String targetEmail() { return fixture.targetId() + "@example.com"; }

    /** 注册账号已由prepare插入，这里只创建项目角色，不修改租户归属。 */
    private void addMember(Connection owner, UUID accountId, ProjectRole role) throws SQLException {
        execute(owner, "INSERT INTO sys_project_member (id,project_id,account_id,role) VALUES (?, ?, ?, ?)",
                Uuid7.generate(), fixture.projectId(), accountId, role.name());
    }

    /** spy仅匹配本例目标与当前动作，其他真实组件的审计不被故障或屏障影响。 */
    private boolean isTargetAudit(AuditLogEntry entry, Operation operation) {
        return entry != null && fixture.projectId().equals(entry.projectId())
                && fixture.targetId().equals(entry.targetId()) && operation.action.equals(entry.action());
    }

    /** 审计callRealMethod之后在原事务自身连接读取，独立owner无法读取未提交数据，不能拿它做假证据。 */
    private void assertUncommittedOutcome(Operation operation) {
        List<String> role = jdbc.queryForList("SELECT role FROM sys_project_member WHERE project_id=? AND account_id=?", String.class,
                fixture.projectId(), fixture.targetId());
        assertThat(role).containsExactlyElementsOf(operation == Operation.REMOVE ? List.of() : List.of("OPERATOR"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_audit_log WHERE project_id=? AND target_id=? AND action=?", Integer.class,
                fixture.projectId(), fixture.targetId(), operation.action)).isEqualTo(1);
    }

    /** 成功事实由独立owner读取，核验稳定账号目标、原scope租户以及完整详情，不以日志代替持久审计。 */
    private void assertCommittedOutcome(Operation operation, UUID actorId, UUID actorTenantId) throws SQLException {
        try (Connection owner = fixtureOwnerConnection()) {
            assertThat(rows(owner, "SELECT role FROM sys_project_member WHERE project_id=? AND account_id=?", fixture.projectId(), fixture.targetId()))
                    .containsExactlyElementsOf(operation == Operation.REMOVE ? List.of() : List.of("OPERATOR"));
            Map<String, String> details = switch (operation) {
                case INVITE -> Map.of("email", targetEmail(), "role", "OPERATOR");
                case UPDATE_ROLE -> Map.of("oldRole", "VIEWER", "newRole", "OPERATOR");
                case REMOVE -> Map.of("oldRole", "VIEWER");
            };
            try (PreparedStatement query = owner.prepareStatement("""
                    SELECT tenant_id, actor_account_id, target_type, target_id, action, details = ?::jsonb
                    FROM sys_audit_log WHERE project_id=? ORDER BY id
                    """)) {
                query.setString(1, mapper.writeValueAsString(details)); query.setObject(2, fixture.projectId());
                try (ResultSet audit = query.executeQuery()) {
                    assertThat(audit.next()).isTrue();
                    assertThat(audit.getObject(1, UUID.class)).isEqualTo(actorTenantId);
                    assertThat(audit.getObject(2, UUID.class)).isEqualTo(actorId);
                    assertThat(audit.getString(3)).isEqualTo("project_member");
                    assertThat(audit.getObject(4, UUID.class)).isEqualTo(fixture.targetId());
                    assertThat(audit.getString(5)).isEqualTo(operation.action);
                    assertThat(audit.getBoolean(6)).isTrue();
                    assertThat(audit.next()).isFalse();
                }
            }
        }
    }

    /** 等待后拒绝不允许对目标角色或审计做部分变动；权限夹具本身的变更另由持锁者负责。 */
    private void assertOriginalTargetAndNoAudit(Operation operation) throws SQLException {
        try (Connection owner = fixtureOwnerConnection()) {
            assertThat(rows(owner, "SELECT role FROM sys_project_member WHERE project_id=? AND account_id=?", fixture.projectId(), fixture.targetId()))
                    .containsExactlyElementsOf(operation == Operation.INVITE ? List.of() : List.of("VIEWER"));
            assertThat(rows(owner, "SELECT id::text FROM sys_audit_log WHERE project_id=?", fixture.projectId())).isEmpty();
        }
    }

    /** 原认证范围中的tenantId保留不变；项目guard负责按真实成员授权，而非误用caller tenant匹配归属。 */
    private <T> T asActor(UUID accountId, UUID tenantId, Supplier<T> work) {
        TenantContext.set(new TenantScope(tenantId, fixture.projectId(), accountId));
        try { return work.get(); } finally { TenantContext.clear(); }
    }

    /** 状态夹具仅改变本项目；归档无现有生产API，不伪造一个服务入口。 */
    private void setProjectState(String state) throws SQLException {
        try (Connection owner = fixtureOwnerConnection()) { execute(owner, "UPDATE sys_project SET status=? WHERE id=?", state, fixture.projectId()); }
    }

    /** 观察独立归档连接PID，真正持许可的APP PID由appPid另核验。 */
    private int connectionPid(Connection connection) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT pg_backend_pid()"); ResultSet rows = query.executeQuery()) {
            assertThat(rows.next()).isTrue(); return rows.getInt(1);
        }
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

    /** 完整成员、项目和审计事实用于回滚/拒绝比较，包含ID、状态、时间戳及全部详情。 */
    private List<String> facts() throws SQLException {
        List<String> result = new ArrayList<>();
        try (Connection owner = fixtureOwnerConnection()) {
            result.addAll(rows(owner, "SELECT row_to_json(p)::text FROM sys_project p WHERE id=?", fixture.projectId()));
            result.addAll(rows(owner, "SELECT row_to_json(m)::text FROM sys_project_member m WHERE project_id=? ORDER BY id", fixture.projectId()));
            result.addAll(rows(owner, "SELECT row_to_json(a)::text FROM sys_audit_log a WHERE project_id=? ORDER BY id", fixture.projectId()));
        }
        return List.copyOf(result);
    }

    /** 不可变审计及其身份上下文保留至专用容器回收；没有审计的失败种子才精确清理，绝不删审计或禁守卫。 */
    @AfterEach
    void cleanup() throws SQLException {
        TenantContext.clear();
        try (Connection owner = fixtureOwnerConnection()) {
            if (!rows(owner, "SELECT id::text FROM sys_audit_log WHERE project_id=?", fixture.projectId()).isEmpty()) return;
            owner.setAutoCommit(false);
            execute(owner, "DELETE FROM sys_project_member WHERE project_id=?", fixture.projectId());
            execute(owner, "DELETE FROM sys_project WHERE id=?", fixture.projectId());
            execute(owner, "DELETE FROM sys_tenant_member WHERE tenant_id IN (?, ?)", fixture.tenantId(), fixture.collaboratorTenantId());
            execute(owner, "DELETE FROM sys_account WHERE id IN (?, ?, ?, ?)", fixture.ownerId(), fixture.collaboratorId(), fixture.outsiderId(), fixture.targetId());
            execute(owner, "DELETE FROM sys_tenant WHERE id IN (?, ?)", fixture.tenantId(), fixture.collaboratorTenantId());
            owner.commit();
        }
    }

    /** owner只承担种子、锁竞争和观察，运行/Flyway/基类辅助夹具统一专库。 */
    @Override
    protected Connection fixtureOwnerConnection() throws SQLException {
        return DriverManager.getConnection(DATABASE_URL, MEMBER_POSTGRES.getUsername(), MEMBER_POSTGRES.getPassword());
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
        MEMBER_POSTGRES.start(); return MEMBER_POSTGRES.getJdbcUrl();
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

    /** 三入口原动作编码与原角色变更详情保持不变，不在生命周期片改审计合同。 */
    private enum Operation {
        /** 新建OPERATOR。 */ INVITE("project.member.invited"),
        /** VIEWER提升OPERATOR。 */ UPDATE_ROLE("project.member.role_updated"),
        /** 移除VIEWER目标。 */ REMOVE("project.member.removed");
        /** 审计稳定动作编码。 */ private final String action;
        /** @param action 原成员服务审计动作 */ Operation(String action) { this.action = action; }
    }

    /** 真实审计写后故障与SQL故障区分，防止测试把前置失败错认业务回滚成功。 */
    private static class AfterAuditFailure extends RuntimeException {
        /** 明确故障发生于审计已执行之后。 */ AfterAuditFailure() { super("成员与审计写后内部故障"); }
    }

    /** @param tenantId 项目归属 @param projectId 项目 @param ownerId OWNER @param collaboratorTenantId 管理员自身租户 @param collaboratorId 管理员 @param outsiderId 非成员 @param targetId 被邀请或操作的稳定账号 */
    private record Fixture(UUID tenantId, UUID projectId, UUID ownerId, UUID collaboratorTenantId,
                           UUID collaboratorId, UUID outsiderId, UUID targetId) { }
}
