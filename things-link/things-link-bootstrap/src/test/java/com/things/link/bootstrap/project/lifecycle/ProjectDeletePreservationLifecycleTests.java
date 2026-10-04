package com.things.link.bootstrap.project.lifecycle;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.project.application.ProjectMemberService;
import com.things.link.project.application.ProjectService;
import com.things.link.project.application.ProjectQuotaService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.ArgumentMatchers.argThat;

/**
 * ADR0064决策2/5、S12-P0-5d4：项目改名/删除在原生产事务锁后复核OWNER和成员数，删除保全所有原成员字段。
 * 专库隔离；关键invite/delete竞态使用新旧代码都必经的findRole观察PID，不以缺失新方法造成超时冒充业务RED。
 */
@Import(ProjectDeletePreservationLifecycleTests.IsolatedDatabaseConfiguration.class)
@OwnedTestContainers({"PRESERVATION_POSTGRES"})
class ProjectDeletePreservationLifecycleTests extends AbstractIntegrationTest {

    /** D-109：独立物理数据库防止缓存上下文的全局worker干扰，随机项目不能替代数据库隔离。 */
    private static final String DATABASE_NAME = "project_delete_preservation_" + UUID.randomUUID().toString().replace("-", "");
    /** 同镜像、同owner的独占集群，避免迁移时角色级变化污染其他测试。 */
    private static final PostgreSQLContainer<?> PRESERVATION_POSTGRES = new PostgreSQLContainer<>(
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
    /** 两个被测项目入口均由原生产代理自行创建事务。 */
    @Autowired private ProjectService projects;
    /** 邀请、退出和转让竞争方也必须使用原生产代理，不复制业务实现。 */
    @Autowired private ProjectMemberService members;
    /** 配额读门禁须拒绝真实删除后保留的OWNER，不能把留存成员当可用授权。 */
    @Autowired private ProjectQuotaService quotas;
    /** 原SQL真实执行，spy只观察原角色查询、计数或项目实际写入的边界。 */
    @MockitoSpyBean private JdbcProjectRepository repository;
    /** 真实成员审计写入后才暂停竞争方，不伪造已完成业务状态。 */
    @MockitoSpyBean private AuditLogService audits;
    /** 原APP事务PID和未提交业务事实观察。 */
    @Autowired private JdbcTemplate jdbc;
    /** 传入名称带空白，沿原改名trim合同，常量不改变项目其他字段。 */
    private static final String RENAMED = "保全项目新名";
    /** 每例唯一身份；OWNER自身租户与项目归属不同，三名禁用成员覆盖各非OWNER角色。 */
    private final Fixture fixture = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
            Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate());

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
                assertThat(rows.getString(2)).isEqualTo(PRESERVATION_POSTGRES.getUsername());
            }
            owner.setAutoCommit(false);
            execute(owner, "INSERT INTO sys_tenant (id,name) VALUES (?, '项目归属租户'), (?, 'OWNER自身租户')", fixture.tenantId(), fixture.ownerTenantId());
            execute(owner, "UPDATE sys_tenant SET quota_policy_id=(SELECT id FROM sys_quota_policy WHERE code='PLAN_R1_STANDARD') WHERE id=?", fixture.tenantId());
            for (UUID account : accounts()) {
                execute(owner, "INSERT INTO sys_account (id,email,password_hash,display_name) VALUES (?, ?, '{noop}unused', '项目保全测试账号')", account, account + "@example.com");
                execute(owner, "INSERT INTO sys_tenant_member (id,tenant_id,account_id) VALUES (?, ?, ?)", Uuid7.generate(),
                        account.equals(fixture.ownerId()) ? fixture.ownerTenantId() : fixture.tenantId(), account);
            }
            execute(owner, "INSERT INTO sys_project (id,tenant_id,name,region,project_key) VALUES (?, ?, '保全项目原名', 'sh-1', ?)",
                    fixture.projectId(), fixture.tenantId(), "preservation_" + fixture.projectId().toString().replace("-", ""));
            execute(owner, "INSERT INTO sys_project_member (id,project_id,account_id,role) VALUES (?, ?, ?, 'OWNER')", Uuid7.generate(), fixture.projectId(), fixture.ownerId());
            Map<UUID, ProjectRole> disabled = Map.of(fixture.disabledAdminId(), ProjectRole.ADMIN,
                    fixture.disabledOperatorId(), ProjectRole.OPERATOR, fixture.disabledViewerId(), ProjectRole.VIEWER);
            for (Map.Entry<UUID, ProjectRole> entry : disabled.entrySet()) {
                // 非默认且不同的历史时间能抓到重建成员或顺手刷新updated_at造成的“看似保留”。
                execute(owner, """
                        INSERT INTO sys_project_member(id,project_id,account_id,role,status,created_at,updated_at)
                        VALUES (?, ?, ?, ?, 'DISABLED', '2026-08-01 01:02:03.123456+00', '2026-08-02 04:05:06.654321+00')
                        """, Uuid7.generate(), fixture.projectId(), entry.getKey(), entry.getValue().name());
            }
            owner.commit();
        }
    }

    /** 合法跨tenant OWNER改名/删除不改变项目归属；新删除保留OWNER和全部DISABLED角色每个原字段的UTF8字节。 */
    @ParameterizedTest
    @EnumSource(Operation.class)
    void activeCrossTenantOwnerPreservesAllMemberBytesAndReadBoundary(Operation operation) throws Exception {
        List<String> before = memberBytes();
        invoke(operation);
        assertThat(memberBytes()).isEqualTo(before);
        assertProjectResult(operation);
        if (operation == Operation.DELETE) {
            assertOrdinaryAccessDenied();
            List<String> deleted = facts();
            assertBusinessCode(catchThrowable(() -> invoke(Operation.DELETE)), 50001);
            assertThat(facts()).isEqualTo(deleted);
        }
    }

    /** ARCHIVED是只读而非不存在，两个项目业务写入口均50017且完整项目/成员/审计事实不变。 */
    @ParameterizedTest
    @EnumSource(Operation.class)
    void archivedProjectWritesRejectWithoutChangingFacts(Operation operation) throws Exception {
        setProjectState("ARCHIVED");
        List<String> before = facts();
        assertBusinessCode(catchThrowable(() -> invoke(operation)), 50017);
        assertThat(facts()).isEqualTo(before);
    }

    /** 非OWNER与非成员不泄露额外权限；DELETING即使仍有有效OWNER也统一50001。 */
    @ParameterizedTest
    @EnumSource(Operation.class)
    void unauthorizedOrDeletingProjectsRejectWithoutChanges(Operation operation) throws Exception {
        addActiveTarget(ProjectRole.ADMIN);
        List<String> before = facts();
        assertBusinessCode(catchThrowable(() -> callAs(fixture.targetId(), fixture.tenantId(), () -> perform(operation))), 50003);
        assertBusinessCode(catchThrowable(() -> callAs(fixture.outsiderId(), fixture.tenantId(), () -> perform(operation))), 50001);
        assertThat(facts()).isEqualTo(before);
        setProjectState("DELETING"); before = facts();
        assertBusinessCode(catchThrowable(() -> invoke(operation)), 50001);
        assertThat(facts()).isEqualTo(before);
    }

    /** 只有ACTIVE OWNER可以管理项目；禁用OWNER不能靠仍保留的角色字符串继续改名或删除。 */
    @ParameterizedTest
    @EnumSource(Operation.class)
    void disabledOwnerIsNotRevivedForManagement(Operation operation) throws Exception {
        try (Connection owner = fixtureOwnerConnection()) {
            execute(owner, "UPDATE sys_project_member SET status='DISABLED' WHERE project_id=? AND account_id=?", fixture.projectId(), fixture.ownerId());
        }
        List<String> before = facts();
        assertBusinessCode(catchThrowable(() -> invoke(operation)), 50001);
        assertThat(facts()).isEqualTo(before);
    }

    /** 历史已物理移除成员没有可证明OWNER；任何当前读写都不能从tenant成员补造恢复身份。 */
    @Test
    void historicalDeletingProjectWithoutMembersDoesNotManufactureOwner() throws Exception {
        try (Connection owner = fixtureOwnerConnection()) {
            execute(owner, "DELETE FROM sys_project_member WHERE project_id=?", fixture.projectId());
            execute(owner, "UPDATE sys_project SET status='DELETING',deleted_at=now() WHERE id=?", fixture.projectId());
        }
        List<String> before = facts();
        for (Operation operation : Operation.values()) assertBusinessCode(catchThrowable(() -> invoke(operation)), 50001);
        assertOrdinaryAccessDenied();
        assertThat(memberBytes()).isEmpty();
        assertThat(facts()).isEqualTo(before);
    }

    /** 许可返回而UPDATE尚未执行时就暂停；独立FOR UPDATE已被阻挡，排除项目自身写入加锁的假阳性。 */
    @ParameterizedTest
    @EnumSource(Operation.class)
    void managementPermitAlreadyHeldBeforeActualProjectMutation(Operation operation) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch permitted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> servicePid = new CompletableFuture<>();
        CompletableFuture<Integer> contenderPid = new CompletableFuture<>();
        observeProjectWrite(operation, false, () -> {
            servicePid.complete(appPid()); permitted.countDown(); await(release);
        });
        try {
            Future<?> service = executor.submit(() -> invoke(operation));
            assertThat(permitted.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> contender = executor.submit(() -> {
                try (Connection owner = fixtureOwnerConnection()) {
                    contenderPid.complete(connectionPid(owner)); lockProject(owner);
                } catch (SQLException failure) { throw new IllegalStateException(failure); }
            });
            assertBlockedBy(contenderPid.get(5, TimeUnit.SECONDS), servicePid.get(5, TimeUnit.SECONDS), contender);
            release.countDown(); service.get(5, TimeUnit.SECONDS); contender.get(5, TimeUnit.SECONDS);
            assertProjectResult(operation);
        } finally { release.countDown(); shutdown(executor); }
    }

    /** 原JDBC五秒预算各入口真实触发57014，不允许转业务拒绝或继续写；解锁后原服务恢复。 */
    @ParameterizedTest
    @EnumSource(Operation.class)
    void realQueryTimeoutRollsBackAndRecovers(Operation operation) throws Exception {
        List<String> before = facts();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CompletableFuture<Integer> servicePid = captureOwnerRolePid();
        try (Connection holder = fixtureOwnerConnection()) {
            holder.setAutoCommit(false);
            int holderPid = lockProject(holder);
            long started = System.nanoTime();
            Future<Throwable> service = executor.submit(() -> catchThrowable(() -> invoke(operation)));
            assertBlockedBy(servicePid.get(5, TimeUnit.SECONDS), holderPid, service);
            assertThat(hasSqlTimeout(service.get(8, TimeUnit.SECONDS))).isTrue();
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isGreaterThanOrEqualTo(4000);
            assertThat(facts()).isEqualTo(before);
            holder.rollback();
        } finally { shutdown(executor); }
        invoke(operation); assertProjectResult(operation);
    }

    /** 改名/softDelete真实SQL执行后内部故障，删除状态与代次+1共同回滚，恢复后只递增一次。 */
    @ParameterizedTest
    @EnumSource(Operation.class)
    void failureAfterActualProjectWriteRollsBackAndRecovers(Operation operation) throws Exception {
        List<String> before = facts();
        AtomicBoolean failOnce = new AtomicBoolean(true);
        observeProjectWrite(operation, true, () -> {
            appPid(); assertUncommittedProjectResult(operation);
            if (failOnce.compareAndSet(true, false)) throw new AfterProjectWriteFailure();
        });
        Throwable failure = catchThrowable(() -> invoke(operation));
        assertThat(rootCause(failure)).isExactlyInstanceOf(AfterProjectWriteFailure.class);
        assertThat(failOnce).isFalse();
        assertThat(facts()).isEqualTo(before);
        invoke(operation); assertProjectResult(operation);
    }

    /** 生命周期在等待项目锁期间变为ARCHIVED，两个入口必须按锁后状态拒绝，项目名及全部成员保持原值。 */
    @ParameterizedTest
    @EnumSource(Operation.class)
    void archiveCommittedDuringManagementWaitRejectsBeforeMutation(Operation operation) throws Exception {
        List<String> beforeMembers = memberBytes();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CompletableFuture<Integer> servicePid = captureOwnerRolePid();
        try (Connection holder = fixtureOwnerConnection()) {
            holder.setAutoCommit(false);
            int holderPid = lockProject(holder);
            Future<Throwable> service = executor.submit(() -> catchThrowable(() -> invoke(operation)));
            assertBlockedBy(servicePid.get(5, TimeUnit.SECONDS), holderPid, service);
            execute(holder, "UPDATE sys_project SET status='ARCHIVED' WHERE id=?", fixture.projectId());
            holder.commit();
            assertBusinessCode(service.get(5, TimeUnit.SECONDS), 50017);
            assertThat(memberBytes()).isEqualTo(beforeMembers);
            assertThat(rows(holder, "SELECT name FROM sys_project WHERE id=? AND deleted_at IS NULL", fixture.projectId())).containsExactly("保全项目原名");
        } finally { shutdown(executor); }
    }

    /**
     * 旧竞态确定RED：invite已写审计但未提交，删除的旧实现先数到仅OWNER后等softDelete锁并错误成功。
     * PID旁观原findRole而非仅新lockForManagement，新旧路径都真实执行；新实现等待后必须数到新成员而50015。
     */
    @Test
    void inviteCommittedWhileDeleteWaitsMustBeCountedBeforeDeletion() throws Exception {
        List<String> retained = memberBytes();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch invited = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> invitePid = pauseAfterMemberAudit(MemberAction.INVITE, invited, release);
        try {
            Future<?> invite = executor.submit(() -> invokeMember(MemberAction.INVITE));
            assertThat(invited.await(5, TimeUnit.SECONDS)).isTrue();
            try (Connection owner = fixtureOwnerConnection()) {
                assertThat(rows(owner, "SELECT count(*)::text FROM sys_project_member WHERE project_id=? AND status='ACTIVE'", fixture.projectId())).containsExactly("1");
            }
            CompletableFuture<Integer> deletePid = captureOwnerRolePid();
            Future<Throwable> deletion = executor.submit(() -> catchThrowable(() -> invoke(Operation.DELETE)));
            assertBlockedBy(deletePid.get(5, TimeUnit.SECONDS), invitePid.get(5, TimeUnit.SECONDS), deletion);
            release.countDown(); invite.get(5, TimeUnit.SECONDS);
            assertBusinessCode(deletion.get(5, TimeUnit.SECONDS), 50015);
            assertThat(memberBytesExcept(fixture.targetId())).isEqualTo(retained);
            assertActiveOriginalProject();
            assertTargetRole("OPERATOR"); assertOnlyMemberAudit(MemberAction.INVITE);
        } finally { release.countDown(); shutdown(executor); }
    }

    /** 真删除的softDelete已执行而未提交，invite随后等项目锁；提交后50001且全部原成员字节保留。 */
    @Test
    void deleteBeforeInvitePreservesMembersAndRejectsNewMembershipAfterCommit() throws Exception {
        List<String> retained = memberBytes();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch deleted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> deletePid = new CompletableFuture<>();
        observeProjectWrite(Operation.DELETE, true, () -> {
            deletePid.complete(appPid()); assertUncommittedProjectResult(Operation.DELETE);
            deleted.countDown(); await(release);
        });
        try {
            Future<?> deletion = executor.submit(() -> invoke(Operation.DELETE));
            assertThat(deleted.await(5, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Integer> invitePid = captureOwnerRolePid();
            Future<Throwable> invite = executor.submit(() -> catchThrowable(() -> invokeMember(MemberAction.INVITE)));
            assertBlockedBy(invitePid.get(5, TimeUnit.SECONDS), deletePid.get(5, TimeUnit.SECONDS), invite);
            release.countDown(); deletion.get(5, TimeUnit.SECONDS);
            assertBusinessCode(invite.get(5, TimeUnit.SECONDS), 50001);
            assertProjectResult(Operation.DELETE);
            assertThat(memberBytes()).isEqualTo(retained);
            assertNoAudit(); assertOrdinaryAccessDenied();
        } finally { release.countDown(); shutdown(executor); }
    }

    /** transfer原事务先改双角色并写审计，旧OWNER的改名/删除等锁后必须50003，不得使用旧授权。 */
    @ParameterizedTest
    @EnumSource(Operation.class)
    void realTransferBeforeProjectWriteRevokesOldOwnerAfterWait(Operation operation) throws Exception {
        addActiveTarget(ProjectRole.VIEWER);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch transferred = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> transferPid = pauseAfterMemberAudit(MemberAction.TRANSFER, transferred, release);
        try {
            Future<?> transfer = executor.submit(() -> invokeMember(MemberAction.TRANSFER));
            assertThat(transferred.await(5, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Integer> projectPid = captureOwnerRolePid();
            Future<Throwable> projectWrite = executor.submit(() -> catchThrowable(() -> invoke(operation)));
            assertBlockedBy(projectPid.get(5, TimeUnit.SECONDS), transferPid.get(5, TimeUnit.SECONDS), projectWrite);
            release.countDown(); transfer.get(5, TimeUnit.SECONDS);
            assertBusinessCode(projectWrite.get(5, TimeUnit.SECONDS), 50003);
            assertActiveOriginalProject(); assertTargetRole("OWNER"); assertOnlyMemberAudit(MemberAction.TRANSFER);
            try (Connection owner = fixtureOwnerConnection()) {
                assertThat(rows(owner, "SELECT role FROM sys_project_member WHERE project_id=? AND account_id=?", fixture.projectId(), fixture.ownerId())).containsExactly("ADMIN");
            }
        } finally { release.countDown(); shutdown(executor); }
    }

    /** 改名先执行真实SQL且尚未提交，transfer/leave的原事务等同项目许可；改名提交后二者按最新事实成功。 */
    @ParameterizedTest
    @EnumSource(value = MemberAction.class, names = {"TRANSFER", "LEAVE"})
    void renameBeforeMemberServiceKeepsOriginalTransactionOrdering(MemberAction memberAction) throws Exception {
        addActiveTarget(ProjectRole.VIEWER);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch renamed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> renamePid = new CompletableFuture<>();
        observeProjectWrite(Operation.RENAME, true, () -> {
            renamePid.complete(appPid()); assertUncommittedProjectResult(Operation.RENAME);
            renamed.countDown(); await(release);
        });
        try {
            Future<?> rename = executor.submit(() -> invoke(Operation.RENAME));
            assertThat(renamed.await(5, TimeUnit.SECONDS)).isTrue();
            UUID actor = memberAction == MemberAction.LEAVE ? fixture.targetId() : fixture.ownerId();
            CompletableFuture<Integer> memberPid = captureRolePid(actor);
            Future<?> member = executor.submit(() -> invokeMember(memberAction));
            assertBlockedBy(memberPid.get(5, TimeUnit.SECONDS), renamePid.get(5, TimeUnit.SECONDS), member);
            release.countDown(); rename.get(5, TimeUnit.SECONDS); member.get(5, TimeUnit.SECONDS);
            assertProjectResult(Operation.RENAME); assertOnlyMemberAudit(memberAction);
            assertTargetRole(memberAction == MemberAction.TRANSFER ? "OWNER" : null);
        } finally { release.countDown(); shutdown(executor); }
    }

    /** leave真实删成员且写审计但未提交，删除必须等待；退出提交后仅OWNER活跃，删除成功并保全剩余所有成员。 */
    @Test
    void leaveBeforeDeleteAllowsLockedRecountToObserveOnlyOwner() throws Exception {
        addActiveTarget(ProjectRole.VIEWER);
        List<String> retained = memberBytesExcept(fixture.targetId());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch left = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> leavePid = pauseAfterMemberAudit(MemberAction.LEAVE, left, release);
        try {
            Future<?> leave = executor.submit(() -> invokeMember(MemberAction.LEAVE));
            assertThat(left.await(5, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Integer> deletePid = captureOwnerRolePid();
            Future<?> deletion = executor.submit(() -> invoke(Operation.DELETE));
            assertBlockedBy(deletePid.get(5, TimeUnit.SECONDS), leavePid.get(5, TimeUnit.SECONDS), deletion);
            release.countDown(); leave.get(5, TimeUnit.SECONDS); deletion.get(5, TimeUnit.SECONDS);
            assertProjectResult(Operation.DELETE); assertThat(memberBytes()).isEqualTo(retained);
            assertTargetRole(null); assertOnlyMemberAudit(MemberAction.LEAVE); assertOrdinaryAccessDenied();
        } finally { release.countDown(); shutdown(executor); }
    }

    /** 删除持管理许可并真实数到2，transfer/leave均须等待；删除50015后竞争方才成功，不把有活跃成员项目伪造成可删。 */
    @ParameterizedTest
    @EnumSource(value = MemberAction.class, names = {"TRANSFER", "LEAVE"})
    void deleteCountingTwoMembersRejectsBeforeWaitingMemberServiceCanProceed(MemberAction memberAction) throws Exception {
        addActiveTarget(ProjectRole.VIEWER);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch counted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> deletePid = new CompletableFuture<>();
        JdbcProjectRepository target = AopTestUtils.getUltimateTargetObject(repository);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            assertThat(result).isEqualTo(2); deletePid.complete(appPid()); counted.countDown(); await(release);
            return result;
        }).when(target).countActiveMembers(fixture.projectId());
        try {
            Future<Throwable> deletion = executor.submit(() -> catchThrowable(() -> invoke(Operation.DELETE)));
            assertThat(counted.await(5, TimeUnit.SECONDS)).isTrue();
            UUID actor = memberAction == MemberAction.LEAVE ? fixture.targetId() : fixture.ownerId();
            CompletableFuture<Integer> memberPid = captureRolePid(actor);
            Future<?> member = executor.submit(() -> invokeMember(memberAction));
            assertBlockedBy(memberPid.get(5, TimeUnit.SECONDS), deletePid.get(5, TimeUnit.SECONDS), member);
            release.countDown(); assertBusinessCode(deletion.get(5, TimeUnit.SECONDS), 50015);
            member.get(5, TimeUnit.SECONDS);
            assertActiveOriginalProject(); assertTargetRole(memberAction == MemberAction.TRANSFER ? "OWNER" : null);
            assertOnlyMemberAudit(memberAction);
        } finally { release.countDown(); shutdown(executor); }
    }

    /** 项目入口统一由真实跨tenant OWNER调用，不在外侧补造业务事务。 */
    private void invoke(Operation operation) {
        callAs(fixture.ownerId(), fixture.ownerTenantId(), () -> perform(operation));
    }

    /** 保留原改名trim及返回值合同，删除只有生产方法负责状态写入与成员保全。 */
    private void perform(Operation operation) {
        if (operation == Operation.RENAME) {
            var result = projects.updateName(fixture.projectId(), "  " + RENAMED + "  ");
            assertThat(result.project().name()).isEqualTo(RENAMED);
            assertThat(result.project().tenantId()).isEqualTo(fixture.tenantId());
        } else projects.delete(fixture.projectId());
    }

    /** 三个竞争方也都经过真实service事务；审计tenant沿调用者自身范围。 */
    private void invokeMember(MemberAction action) {
        UUID actor = action == MemberAction.LEAVE ? fixture.targetId() : fixture.ownerId();
        UUID tenant = action == MemberAction.LEAVE ? fixture.tenantId() : fixture.ownerTenantId();
        callAs(actor, tenant, () -> {
            switch (action) {
                case INVITE -> members.invite(fixture.projectId(), fixture.targetId() + "@example.com", ProjectRole.OPERATOR);
                case TRANSFER -> members.transferOwnership(fixture.projectId(), fixture.targetId());
                case LEAVE -> members.leave(fixture.projectId());
            }
        });
    }

    /** 成功或异常退出都验证没有遗留事务；原Spring代理负责开始、提交与回滚。 */
    private void callAs(UUID accountId, UUID tenantId, Runnable work) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        TenantContext.set(new TenantScope(tenantId, fixture.projectId(), accountId));
        try { work.run(); }
        finally { TenantContext.clear(); assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); }
    }

    /** 新旧项目服务都执行原OWNER角色查询，观察在真实SQL之后，不改变其返回也不强依赖新guard调用。 */
    private CompletableFuture<Integer> captureOwnerRolePid() { return captureRolePid(fixture.ownerId()); }

    /** 只捕获首次业务角色查询的真实APP PID；后续普通读取继续原SQL，不要求它们额外提供事务。 */
    private CompletableFuture<Integer> captureRolePid(UUID actorId) {
        CompletableFuture<Integer> pid = new CompletableFuture<>();
        JdbcProjectRepository target = AopTestUtils.getUltimateTargetObject(repository);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (!pid.isDone()) pid.complete(appPid());
            return result;
        }).when(target).findRole(fixture.projectId(), actorId);
        return pid;
    }

    /** 项目SQL前或后旁观，所有情况仍执行原仓储实现；故障类型与数据库异常明确分离。 */
    private void observeProjectWrite(Operation operation, boolean afterSql, Runnable observation) {
        JdbcProjectRepository target = AopTestUtils.getUltimateTargetObject(repository);
        org.mockito.stubbing.Answer<Object> answer = invocation -> {
            if (!afterSql) observation.run();
            Object result = invocation.callRealMethod();
            if (afterSql) observation.run();
            return result;
        };
        if (operation == Operation.RENAME) doAnswer(answer).when(target).updateName(fixture.projectId(), RENAMED);
        else doAnswer(answer).when(target).softDelete(fixture.projectId());
    }

    /** 只有本例原审计SQL真实完成才暂停，检查未提交成员事实证明屏障位于原业务事务尾部。 */
    private CompletableFuture<Integer> pauseAfterMemberAudit(MemberAction action, CountDownLatch ready, CountDownLatch release) {
        CompletableFuture<Integer> pid = new CompletableFuture<>();
        doAnswer(invocation -> {
            invocation.callRealMethod(); pid.complete(appPid());
            List<String> targetRoles = jdbc.queryForList("SELECT role FROM sys_project_member WHERE project_id=? AND account_id=?", String.class,
                    fixture.projectId(), fixture.targetId());
            assertThat(targetRoles).containsExactlyElementsOf(switch (action) {
                case INVITE -> List.of("OPERATOR");
                case TRANSFER -> List.of("OWNER");
                case LEAVE -> List.of();
            });
            assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_audit_log WHERE project_id=? AND target_id=? AND action=?", Integer.class,
                    fixture.projectId(), fixture.targetId(), action.action)).isEqualTo(1);
            ready.countDown(); await(release); return null;
        }).when(audits).record(argThat(entry -> isTargetAudit(entry, action)));
        return pid;
    }

    /** 屏障只匹配本例稳定账号目标及动作，不能误拦截其他项目/行为。 */
    private boolean isTargetAudit(AuditLogEntry entry, MemberAction action) {
        return entry != null && fixture.projectId().equals(entry.projectId()) && fixture.targetId().equals(entry.targetId())
                && action.action.equals(entry.action());
    }

    /** 真SQL之后读取原连接中的未提交项目事实；不拿看不到未提交行的owner连接冒充证据。 */
    private void assertUncommittedProjectResult(Operation operation) {
        assertThat(jdbc.queryForObject("SELECT tenant_id FROM sys_project WHERE id=?", UUID.class, fixture.projectId())).isEqualTo(fixture.tenantId());
        if (operation == Operation.DELETE) {
            assertThat(jdbc.queryForObject("""
                    SELECT status='DELETING' AND deleted_at IS NOT NULL AND lifecycle_generation=1
                      FROM sys_project WHERE id=?
                    """, Boolean.class, fixture.projectId())).isTrue();
        } else assertThat(jdbc.queryForObject("SELECT name FROM sys_project WHERE id=?", String.class, fixture.projectId())).isEqualTo(RENAMED);
    }

    /** 独立连接确认业务提交，不以被测事务内可见的未提交结果冒称成功。 */
    private void assertProjectResult(Operation operation) throws SQLException {
        try (Connection owner = fixtureOwnerConnection()) {
            assertThat(rows(owner, "SELECT tenant_id::text FROM sys_project WHERE id=?", fixture.projectId())).containsExactly(fixture.tenantId().toString());
            if (operation == Operation.DELETE) {
                assertThat(rows(owner, """
                        SELECT status||':'||lifecycle_generation FROM sys_project
                         WHERE id=? AND deleted_at IS NOT NULL
                        """, fixture.projectId())).containsExactly("DELETING:1");
            } else assertThat(rows(owner, "SELECT name FROM sys_project WHERE id=? AND deleted_at IS NULL", fixture.projectId())).containsExactly(RENAMED);
        }
    }

    /** 失败删除或失权写不能顺便改名或冻结项目。 */
    private void assertActiveOriginalProject() throws SQLException {
        try (Connection owner = fixtureOwnerConnection()) {
            assertThat(rows(owner, """
                    SELECT name||':'||lifecycle_generation FROM sys_project
                     WHERE id=? AND status='ACTIVE' AND deleted_at IS NULL
                    """, fixture.projectId())).containsExactly("保全项目原名:0");
        }
    }

    /** 角色目标是稳定账号，退出后为空；直接读原保全表而不是被生命周期过滤的普通授权。 */
    private void assertTargetRole(String role) throws SQLException {
        try (Connection owner = fixtureOwnerConnection()) {
            assertThat(rows(owner, "SELECT role FROM sys_project_member WHERE project_id=? AND account_id=?", fixture.projectId(), fixture.targetId()))
                    .containsExactlyElementsOf(role == null ? List.of() : List.of(role));
        }
    }

    /** 保全不是重新开放：三普通查询、成员列表及原配额入口均拒绝项目留存的每个成员。 */
    private void assertOrdinaryAccessDenied() {
        for (UUID account : accounts()) {
            assertThat(repository.findMembershipsByAccount(account)).isEmpty();
            assertThat(repository.findMembership(fixture.projectId(), account)).isEmpty();
            assertThat(repository.findRole(fixture.projectId(), account)).isEmpty();
        }
        assertBusinessCode(catchThrowable(() -> callAs(fixture.ownerId(), fixture.ownerTenantId(), () -> members.list(fixture.projectId()))), 50001);
        assertBusinessCode(catchThrowable(() -> callAs(fixture.ownerId(), fixture.ownerTenantId(), () -> quotas.get(fixture.projectId()))), 50001);
    }

    /** 原成员服务审计仍记录原scope租户与动作，项目改名/删除不会额外伪造成员审计。 */
    private void assertOnlyMemberAudit(MemberAction action) throws SQLException {
        try (Connection owner = fixtureOwnerConnection(); PreparedStatement query = owner.prepareStatement(
                "SELECT action,actor_account_id,tenant_id,target_id,target_type FROM sys_audit_log WHERE project_id=? ORDER BY id")) {
            query.setObject(1, fixture.projectId());
            try (ResultSet audit = query.executeQuery()) {
                assertThat(audit.next()).isTrue();
                assertThat(audit.getString(1)).isEqualTo(action.action);
                assertThat(audit.getObject(2, UUID.class)).isEqualTo(action == MemberAction.LEAVE ? fixture.targetId() : fixture.ownerId());
                assertThat(audit.getObject(3, UUID.class)).isEqualTo(action == MemberAction.LEAVE ? fixture.tenantId() : fixture.ownerTenantId());
                assertThat(audit.getObject(4, UUID.class)).isEqualTo(fixture.targetId());
                assertThat(audit.getString(5)).isEqualTo("project_member");
                assertThat(audit.next()).isFalse();
            }
        }
    }

    /** 生命周期拒绝不得留下“成员操作成功”的不可变审计。 */
    private void assertNoAudit() throws SQLException {
        try (Connection owner = fixtureOwnerConnection()) {
            assertThat(rows(owner, "SELECT id::text FROM sys_audit_log WHERE project_id=?", fixture.projectId())).isEmpty();
        }
    }

    /** 活跃目标仅在确需成员竞争的场景插入，正常删除场景始终仅一个ACTIVE OWNER。 */
    private void addActiveTarget(ProjectRole role) throws SQLException {
        try (Connection owner = fixtureOwnerConnection()) {
            execute(owner, "INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?, ?, ?, ?)",
                    Uuid7.generate(), fixture.projectId(), fixture.targetId(), role.name());
        }
    }

    /** 完整行按数据库UTF8编码取hex，逐字段/时间戳/ID/状态均参与字节比较，不用重新构建的对象掩盖改写。 */
    private List<String> memberBytes() throws SQLException { return memberBytesExcept(null); }

    /** 合法成员变动场景只排除明确被业务改变的目标，其余保全成员仍逐字节一致。 */
    private List<String> memberBytesExcept(UUID excluded) throws SQLException {
        String sql = "SELECT encode(convert_to(row_to_json(m)::text,'UTF8'),'hex') FROM sys_project_member m WHERE project_id=?";
        try (Connection owner = fixtureOwnerConnection()) {
            if (excluded == null) return rows(owner, sql + " ORDER BY id", fixture.projectId());
            return rows(owner, sql + " AND account_id<>? ORDER BY id", fixture.projectId(), excluded);
        }
    }

    /** 项目、成员与审计全部行字段用于拒绝/回滚比较，保留完整时间戳及详情。 */
    private List<String> facts() throws SQLException {
        List<String> result = new ArrayList<>();
        try (Connection owner = fixtureOwnerConnection()) {
            result.addAll(rows(owner, "SELECT row_to_json(p)::text FROM sys_project p WHERE id=?", fixture.projectId()));
            result.addAll(rows(owner, "SELECT row_to_json(m)::text FROM sys_project_member m WHERE project_id=? ORDER BY id", fixture.projectId()));
            result.addAll(rows(owner, "SELECT row_to_json(a)::text FROM sys_audit_log a WHERE project_id=? ORDER BY id", fixture.projectId()));
        }
        return List.copyOf(result);
    }

    /** 完整原账号种子也用于验证保全后不会绕过普通查询隔离。 */
    private List<UUID> accounts() {
        return List.of(fixture.ownerId(), fixture.targetId(), fixture.outsiderId(),
                fixture.disabledAdminId(), fixture.disabledOperatorId(), fixture.disabledViewerId());
    }

    /** @Repository可能包装自定义运行时故障，只核验最内层根因而不误认其他SQL/权限失败。 */
    private Throwable rootCause(Throwable failure) {
        assertThat(failure).isNotNull();
        Throwable root = failure;
        while (root.getCause() != null) root = root.getCause();
        return root;
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


    /** 不可变审计及必要身份事实留至专库回收；无审计的独占种子才精确删除，不禁用守卫、不清审计。 */
    @AfterEach
    void cleanup() throws SQLException {
        TenantContext.clear();
        try (Connection owner = fixtureOwnerConnection()) {
            if (!rows(owner, "SELECT id::text FROM sys_audit_log WHERE project_id=?", fixture.projectId()).isEmpty()) return;
            owner.setAutoCommit(false);
            execute(owner, "DELETE FROM sys_project_member WHERE project_id=?", fixture.projectId());
            execute(owner, "DELETE FROM sys_project WHERE id=?", fixture.projectId());
            execute(owner, "DELETE FROM sys_tenant_member WHERE tenant_id IN (?, ?)", fixture.tenantId(), fixture.ownerTenantId());
            for (UUID account : accounts()) execute(owner, "DELETE FROM sys_account WHERE id=?", account);
            execute(owner, "DELETE FROM sys_tenant WHERE id IN (?, ?)", fixture.tenantId(), fixture.ownerTenantId());
            owner.commit();
        }
    }

    /** owner只承担种子、锁竞争和观察，运行/Flyway/基类辅助夹具统一专库。 */
    @Override
    protected Connection fixtureOwnerConnection() throws SQLException {
        return DriverManager.getConnection(DATABASE_URL, PRESERVATION_POSTGRES.getUsername(), PRESERVATION_POSTGRES.getPassword());
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
        PRESERVATION_POSTGRES.start(); return PRESERVATION_POSTGRES.getJdbcUrl();
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

    /** 两个项目写入口共享OWNER许可，业务写法仍分别沿原合同。 */
    private enum Operation {
        /** 改名保留trim。 */ RENAME,
        /** 只软删项目并保全成员。 */ DELETE
    }

    /** 与项目管理共用排他许可的真实成员业务，不用测试SQL代替实际事务。 */
    private enum MemberAction {
        /** 邀请增加活跃成员数。 */ INVITE("project.member.invited"),
        /** 转让改变真实OWNER。 */ TRANSFER("project.member.ownership_transferred"),
        /** 退出减少活跃成员数。 */ LEAVE("project.member.left");
        /** 原有稳定审计动作。 */ private final String action;
        /** @param action 原成员服务审计动作 */ MemberAction(String action) { this.action = action; }
    }

    /** 真实项目SQL之后故障，不能把前置权限或查询失败当成原子回滚。 */
    private static class AfterProjectWriteFailure extends RuntimeException {
        /** 标注故障位置。 */ AfterProjectWriteFailure() { super("项目实际写入后内部故障"); }
    }

    /** @param tenantId 项目归属 @param ownerTenantId 当前OWNER自身租户 @param projectId 项目 @param ownerId OWNER @param targetId 活跃竞争目标 @param outsiderId 非成员 @param disabledAdminId 禁用ADMIN @param disabledOperatorId 禁用OPERATOR @param disabledViewerId 禁用VIEWER */
    private record Fixture(UUID tenantId, UUID ownerTenantId, UUID projectId, UUID ownerId, UUID targetId,
                           UUID outsiderId, UUID disabledAdminId, UUID disabledOperatorId, UUID disabledViewerId) { }
}
