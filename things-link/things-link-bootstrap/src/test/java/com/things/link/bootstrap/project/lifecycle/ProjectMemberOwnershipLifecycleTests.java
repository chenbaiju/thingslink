package com.things.link.bootstrap.project.lifecycle;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.project.application.ProjectMemberService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.ArgumentMatchers.argThat;

/**
 * ADR0064决策2/5、S12-P0-5d3：转让/退出在原生产事务中复核最新角色，保持成员与不可变审计原子性。
 * 独占PG；两个生产成员服务互斥以真实项目锁证明，删除人数与并发合同由5d4独立验证。
 */
@Import(ProjectMemberOwnershipLifecycleTests.IsolatedDatabaseConfiguration.class)
@OwnedTestContainers({"OWNERSHIP_POSTGRES"})
class ProjectMemberOwnershipLifecycleTests extends AbstractIntegrationTest {

    /** D-109：独立物理数据库防止缓存上下文的全局worker干扰，随机项目不能替代数据库隔离。 */
    private static final String DATABASE_NAME = "project_member_ownership_" + UUID.randomUUID().toString().replace("-", "");
    /** 同镜像、同owner的独占集群，避免迁移时角色级变化污染其他测试。 */
    private static final PostgreSQLContainer<?> OWNERSHIP_POSTGRES = new PostgreSQLContainer<>(
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
    /** 原Spring代理创建事务，所有业务调用禁止外包测试TT。 */
    @Autowired private ProjectMemberService members;
    /** 原项目SQL真实执行；仅观察锁前PID，不伪造角色或许可返回。 */
    @MockitoSpyBean private JdbcProjectRepository repository;
    /** 真实审计写之后才设置故障和屏障，拒绝未执行审计的假回滚证据。 */
    @MockitoSpyBean private AuditLogService audits;
    /** 在原业务连接读取未提交角色与审计，owner仅作独立观察。 */
    @Autowired private JdbcTemplate jdbc;
    /** jsonb等价比较核验完整原审计详情而非序列化顺序。 */
    @Autowired private ObjectMapper mapper;
    /** 项目归属tenant与当前OWNER自身tenant不同，目标与管理员身份独立。 */
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
                assertThat(rows.getString(2)).isEqualTo(OWNERSHIP_POSTGRES.getUsername());
            }
            owner.setAutoCommit(false);
            execute(owner, "INSERT INTO sys_tenant (id,name) VALUES (?, '项目归属租户'), (?, '协作者自身租户')", fixture.tenantId(), fixture.collaboratorTenantId());
            for (UUID account : List.of(fixture.ownerId(), fixture.collaboratorId(), fixture.outsiderId(), fixture.targetId())) {
                execute(owner, "INSERT INTO sys_account (id,email,password_hash,display_name) VALUES (?, ?, '{noop}unused', '管理许可测试账号')", account, account + "@example.com");
            }
            execute(owner, "INSERT INTO sys_tenant_member (id,tenant_id,account_id) VALUES (?, ?, ?), (?, ?, ?)",
                    Uuid7.generate(), fixture.collaboratorTenantId(), fixture.ownerId(), Uuid7.generate(), fixture.tenantId(), fixture.collaboratorId());
            execute(owner, "INSERT INTO sys_tenant_member (id,tenant_id,account_id) VALUES (?, ?, ?), (?, ?, ?)",
                    Uuid7.generate(), fixture.tenantId(), fixture.targetId(), Uuid7.generate(), fixture.tenantId(), fixture.outsiderId());
            execute(owner, "INSERT INTO sys_project (id,tenant_id,name,region,project_key) VALUES (?, ?, '管理许可原名', 'sh-1', ?)",
                    fixture.projectId(), fixture.tenantId(), "management_" + fixture.projectId().toString().replace("-", ""));
            execute(owner, "INSERT INTO sys_project_member (id,project_id,account_id,role) VALUES (?, ?, ?, 'OWNER')", Uuid7.generate(), fixture.projectId(), fixture.ownerId());
            // 当前OWNER真实属于另一租户，角色来自明确成员种子，不以项目tenant推定控制权。
            execute(owner, "INSERT INTO sys_project_member (id,project_id,account_id,role) VALUES (?, ?, ?, 'ADMIN'), (?, ?, ?, 'VIEWER')",
                    Uuid7.generate(), fixture.projectId(), fixture.collaboratorId(), Uuid7.generate(), fixture.projectId(), fixture.targetId());
            owner.commit();
        }
    }

    /** 两入口旧路径确定RED；ARCHIVED有效成员含OWNER退出均先50017，不改变双角色或审计。 */
    @ParameterizedTest
    @EnumSource(Operation.class)
    void archivedOwnershipEntryRejectsWithoutChangingRolesOrAudit(Operation operation) throws Exception {
        setProjectState("ARCHIVED");
        List<String> before = facts();
        assertBusinessCode(catchThrowable(() -> invoke(operation)), 50017);
        if (operation == Operation.LEAVE) {
            assertBusinessCode(catchThrowable(() -> callAs(fixture.ownerId(), fixture.collaboratorTenantId(), () -> members.leave(fixture.projectId()))), 50017);
        }
        assertThat(facts()).isEqualTo(before);
    }

    /** ACTIVE转让双角色按原合同降升，普通退出删除自身关系；跨tenant OWNER不误改项目归属或审计tenant。 */
    @ParameterizedTest
    @EnumSource(Operation.class)
    void activeEntryPreservesOriginalRoleAuditAndProjectTenant(Operation operation) throws Exception {
        invoke(operation);
        assertCommittedOutcome(operation, ProjectRole.VIEWER);
    }

    /** 非成员统一50001，转让无权50003/自转50014，ACTIVE OWNER退出50016；DELETING不能泄露仍存成员事实。 */
    @ParameterizedTest
    @EnumSource(Operation.class)
    void rejectedAuthorityAndDeletingStateLeaveNoRoleOrAuditChanges(Operation operation) throws Exception {
        List<String> before = facts();
        assertBusinessCode(catchThrowable(() -> callAs(fixture.outsiderId(), fixture.tenantId(), () -> perform(operation))), 50001);
        if (operation == Operation.TRANSFER) {
            assertBusinessCode(catchThrowable(() -> callAs(fixture.collaboratorId(), fixture.tenantId(), () -> perform(operation))), 50003);
            assertBusinessCode(catchThrowable(() -> callAs(fixture.ownerId(), fixture.collaboratorTenantId(),
                    () -> members.transferOwnership(fixture.projectId(), fixture.ownerId()))), 50014);
        } else {
            assertBusinessCode(catchThrowable(() -> callAs(fixture.ownerId(), fixture.collaboratorTenantId(), () -> perform(operation))), 50016);
        }
        assertThat(facts()).isEqualTo(before);
        setProjectState("DELETING"); before = facts();
        assertBusinessCode(catchThrowable(() -> invoke(operation)), 50001);
        assertThat(facts()).isEqualTo(before);
    }

    /** 双角色/退出和审计已真实写入后，项目非键归档仍被原service许可阻挡直至原事务提交。 */
    @ParameterizedTest
    @EnumSource(Operation.class)
    void originalServiceRetainsProjectLockAfterActualAudit(Operation operation) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch audited = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> servicePid = pauseAfterActualAudit(operation, audited, release);
        CompletableFuture<Integer> archivePid = new CompletableFuture<>();
        try {
            Future<?> service = executor.submit(() -> invoke(operation));
            assertThat(audited.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> archiver = executor.submit(() -> {
                try (Connection owner = fixtureOwnerConnection()) {
                    archivePid.complete(connectionPid(owner));
                    execute(owner, "UPDATE sys_project SET status='ARCHIVED' WHERE id=?", fixture.projectId());
                } catch (SQLException failure) { throw new IllegalStateException(failure); }
            });
            assertBlockedBy(archivePid.get(5, TimeUnit.SECONDS), servicePid.get(5, TimeUnit.SECONDS), archiver);
            release.countDown(); service.get(5, TimeUnit.SECONDS); archiver.get(5, TimeUnit.SECONDS);
            assertCommittedOutcome(operation, ProjectRole.VIEWER);
        } finally { release.countDown(); shutdown(executor); }
    }

    /** 真实JDBC五秒查询预算故障必须原样传播与回滚，释放项目锁后原服务可再次成功。 */
    @ParameterizedTest
    @EnumSource(Operation.class)
    void realQueryTimeoutRollsBackAndServiceRecovers(Operation operation) throws Exception {
        List<String> before = facts();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CompletableFuture<Integer> waiterPid = captureLockPid();
        try (Connection holder = fixtureOwnerConnection()) {
            holder.setAutoCommit(false);
            int holderPid = lockProject(holder);
            long started = System.nanoTime();
            Future<Throwable> waiter = executor.submit(() -> catchThrowable(() -> invoke(operation)));
            assertBlockedBy(waiterPid.get(5, TimeUnit.SECONDS), holderPid, waiter);
            assertThat(hasSqlTimeout(waiter.get(8, TimeUnit.SECONDS))).isTrue();
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isGreaterThanOrEqualTo(4000);
            assertThat(facts()).isEqualTo(before);
            holder.rollback();
        } finally { shutdown(executor); }
        invoke(operation); assertCommittedOutcome(operation, ProjectRole.VIEWER);
    }

    /** 审计真实执行后故障，转让两条角色更新或退出删除均和审计整体回滚，不能留下半个OWNER转让。 */
    @ParameterizedTest
    @EnumSource(Operation.class)
    void failureAfterRealAuditRollsBackAllRolesAndAuditThenRecovers(Operation operation) throws Exception {
        List<String> before = facts();
        AtomicBoolean failOnce = new AtomicBoolean(true);
        doAnswer(invocation -> {
            invocation.callRealMethod(); appPid(); assertUncommittedOutcome(operation);
            if (failOnce.compareAndSet(true, false)) throw new AfterAuditFailure();
            return null;
        }).when(audits).record(argThat(entry -> isTargetAudit(entry, operation)));
        assertThat(catchThrowable(() -> invoke(operation))).isExactlyInstanceOf(AfterAuditFailure.class);
        assertThat(failOnce).isFalse();
        assertThat(facts()).isEqualTo(before);
        invoke(operation); assertCommittedOutcome(operation, ProjectRole.VIEWER);
    }

    /** 目标消失或角色改变发生在项目锁等待期间，必须使用锁后事实及最新审计oldRole。 */
    @ParameterizedTest
    @EnumSource(TransferChange.class)
    void waitingTransferRechecksTargetAndLatestTargetRole(TransferChange change) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CompletableFuture<Integer> waiterPid = captureLockPid();
        try (Connection holder = fixtureOwnerConnection()) {
            holder.setAutoCommit(false);
            int holderPid = lockProject(holder);
            Future<Throwable> waiter = executor.submit(() -> catchThrowable(() -> invoke(Operation.TRANSFER)));
            assertBlockedBy(waiterPid.get(5, TimeUnit.SECONDS), holderPid, waiter);
            // 持锁竞争夹具只改明确已知成员事实，不根据tenant或JWT推定OWNER，也不伪造审计。
            switch (change) {
                case TARGET_REMOVED -> execute(holder, "DELETE FROM sys_project_member WHERE project_id=? AND account_id=?", fixture.projectId(), fixture.targetId());
                case TARGET_ROLE_CHANGED -> execute(holder, "UPDATE sys_project_member SET role='ADMIN' WHERE project_id=? AND account_id=?", fixture.projectId(), fixture.targetId());
            }
            holder.commit();
            Throwable result = waiter.get(5, TimeUnit.SECONDS);
            if (change == TransferChange.TARGET_ROLE_CHANGED) {
                assertThat(result).isNull(); assertCommittedOutcome(Operation.TRANSFER, ProjectRole.ADMIN);
            } else {
                assertBusinessCode(result, 50001);
                assertThat(rows(holder, "SELECT id::text FROM sys_audit_log WHERE project_id=?", fixture.projectId())).isEmpty();
                assertThat(rows(holder, "SELECT role FROM sys_project_member WHERE project_id=? AND account_id=?", fixture.projectId(), fixture.ownerId()))
                        .containsExactly("OWNER");
                assertThat(rows(holder, "SELECT role FROM sys_project_member WHERE project_id=? AND account_id=?", fixture.projectId(), fixture.targetId()))
                        .isEmpty();
            }
        } finally { shutdown(executor); }
    }

    /** 退出等待中成员消失需50001，仍为成员但角色变化则使用最新角色记录oldRole而非旧VIEWER快照。 */
    @ParameterizedTest
    @EnumSource(LeaveChange.class)
    void waitingLeaveRechecksMembershipAndLatestAuditRole(LeaveChange change) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CompletableFuture<Integer> waiterPid = captureLockPid();
        try (Connection holder = fixtureOwnerConnection()) {
            holder.setAutoCommit(false);
            int holderPid = lockProject(holder);
            Future<Throwable> waiter = executor.submit(() -> catchThrowable(() -> invoke(Operation.LEAVE)));
            assertBlockedBy(waiterPid.get(5, TimeUnit.SECONDS), holderPid, waiter);
            if (change == LeaveChange.REMOVED) {
                execute(holder, "DELETE FROM sys_project_member WHERE project_id=? AND account_id=?", fixture.projectId(), fixture.targetId());
            } else {
                execute(holder, "UPDATE sys_project_member SET role='OPERATOR' WHERE project_id=? AND account_id=?", fixture.projectId(), fixture.targetId());
            }
            holder.commit();
            Throwable result = waiter.get(5, TimeUnit.SECONDS);
            if (change == LeaveChange.REMOVED) {
                assertBusinessCode(result, 50001);
                assertThat(rows(holder, "SELECT id::text FROM sys_audit_log WHERE project_id=?", fixture.projectId())).isEmpty();
            } else {
                assertThat(result).isNull(); assertCommittedOutcome(Operation.LEAVE, ProjectRole.OPERATOR);
            }
            assertThat(rows(holder, "SELECT id::text FROM sys_project_member WHERE project_id=? AND account_id=?", fixture.projectId(), fixture.targetId())).isEmpty();
        } finally { shutdown(executor); }
    }

    /** 两个入口在项目锁等待中变为ARCHIVED，都要在原写之前拒绝，OWNER退出业务限制不能抢先代替50017。 */
    @ParameterizedTest
    @EnumSource(Operation.class)
    void archiveCommittedWhileServiceWaitsRejectsOriginalWrite(Operation operation) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CompletableFuture<Integer> waiterPid = captureLockPid();
        try (Connection holder = fixtureOwnerConnection()) {
            holder.setAutoCommit(false);
            int holderPid = lockProject(holder);
            Future<Throwable> waiter = executor.submit(() -> catchThrowable(() -> invoke(operation)));
            assertBlockedBy(waiterPid.get(5, TimeUnit.SECONDS), holderPid, waiter);
            execute(holder, "UPDATE sys_project SET status='ARCHIVED' WHERE id=?", fixture.projectId());
            holder.commit();
            assertBusinessCode(waiter.get(5, TimeUnit.SECONDS), 50017);
            assertInitialRolesAndNoAudit(holder);
        } finally { shutdown(executor); }
    }

    /** 真实transfer持审计屏障，真实leave/remove的原事务等待；新OWNER提交后分别50016/50013保全当前所有者。 */
    @ParameterizedTest
    @EnumSource(CompetingService.class)
    void realTransferSerializesCompetingMemberServiceAndProtectsNewOwner(CompetingService competitor) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch audited = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> transferPid = pauseAfterActualAudit(Operation.TRANSFER, audited, release);
        try {
            Future<?> transfer = executor.submit(() -> invoke(Operation.TRANSFER));
            assertThat(audited.await(5, TimeUnit.SECONDS)).isTrue();
            // 第一服务已持锁并执行真实双角色+审计；后安装旁观spy只捕获第二服务的原APP连接。
            CompletableFuture<Integer> competitorPid = captureLockPid();
            Future<Throwable> competing = executor.submit(() -> catchThrowable(() -> {
                if (competitor == CompetingService.LEAVE) invoke(Operation.LEAVE);
                else callAs(fixture.collaboratorId(), fixture.tenantId(), () -> members.remove(fixture.projectId(), fixture.targetId()));
            }));
            assertBlockedBy(competitorPid.get(5, TimeUnit.SECONDS), transferPid.get(5, TimeUnit.SECONDS), competing);
            release.countDown(); transfer.get(5, TimeUnit.SECONDS);
            assertBusinessCode(competing.get(5, TimeUnit.SECONDS), competitor == CompetingService.LEAVE ? 50016 : 50013);
            assertCommittedOutcome(Operation.TRANSFER, ProjectRole.VIEWER);
        } finally { release.countDown(); shutdown(executor); }
    }

    /** 真实退出先执行审计并持锁，真实转让等待后目标消失；不得降级原OWNER或生成第二条成功审计。 */
    @Test
    void realLeaveBeforeTransferMakesTargetMissingAndPreservesOriginalOwner() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch audited = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> leavePid = pauseAfterActualAudit(Operation.LEAVE, audited, release);
        try {
            Future<?> leave = executor.submit(() -> invoke(Operation.LEAVE));
            assertThat(audited.await(5, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Integer> transferPid = captureLockPid();
            Future<Throwable> transfer = executor.submit(() -> catchThrowable(() -> invoke(Operation.TRANSFER)));
            assertBlockedBy(transferPid.get(5, TimeUnit.SECONDS), leavePid.get(5, TimeUnit.SECONDS), transfer);
            release.countDown(); leave.get(5, TimeUnit.SECONDS);
            assertBusinessCode(transfer.get(5, TimeUnit.SECONDS), 50001);
            assertCommittedOutcome(Operation.LEAVE, ProjectRole.VIEWER);
        } finally { release.countDown(); shutdown(executor); }
    }

    /** 同OWNER两个真实转让争用同项目许可；先提交者把原OWNER降为ADMIN，后者须50003而非再次转移。 */
    @Test
    void firstRealTransferRevokesOriginalOwnerBeforeSecondTransferCanWrite() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch audited = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Integer> firstPid = pauseAfterActualAudit(Operation.TRANSFER, audited, release);
        try {
            Future<?> first = executor.submit(() -> invoke(Operation.TRANSFER));
            assertThat(audited.await(5, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Integer> secondPid = captureLockPid();
            Future<Throwable> second = executor.submit(() -> catchThrowable(() -> callAs(fixture.ownerId(), fixture.collaboratorTenantId(),
                    () -> members.transferOwnership(fixture.projectId(), fixture.collaboratorId()))));
            assertBlockedBy(secondPid.get(5, TimeUnit.SECONDS), firstPid.get(5, TimeUnit.SECONDS), second);
            release.countDown(); first.get(5, TimeUnit.SECONDS);
            assertBusinessCode(second.get(5, TimeUnit.SECONDS), 50003);
            assertCommittedOutcome(Operation.TRANSFER, ProjectRole.VIEWER);
            try (Connection owner = fixtureOwnerConnection()) {
                assertThat(rows(owner, "SELECT role FROM sys_project_member WHERE project_id=? AND account_id=?", fixture.projectId(), fixture.collaboratorId()))
                        .containsExactly("ADMIN");
            }
        } finally { release.countDown(); shutdown(executor); }
    }

    /** 正常业务身份：当前OWNER确属另一租户，退出者是目标VIEWER；项目归属由真实成员授权而非tenant相等推断。 */
    private void invoke(Operation operation) {
        if (operation == Operation.TRANSFER) callAs(fixture.ownerId(), fixture.collaboratorTenantId(), () -> perform(operation));
        else callAs(fixture.targetId(), fixture.tenantId(), () -> perform(operation));
    }

    /** 只分派原生产入口，不在测试中复刻角色变动或审计逻辑。 */
    private void perform(Operation operation) {
        if (operation == Operation.TRANSFER) members.transferOwnership(fixture.projectId(), fixture.targetId());
        else members.leave(fixture.projectId());
    }

    /** 原service负责完整事务；成功或异常退出后都检查未遗留事务，避免TT夹具替代生产边界。 */
    private void callAs(UUID accountId, UUID tenantId, Runnable work) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        TenantContext.set(new TenantScope(tenantId, fixture.projectId(), accountId));
        try { work.run(); }
        finally {
            TenantContext.clear();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        }
    }

    /** 审计callRealMethod完成后才暂停，原APP连接必须已看到全部角色变动与持久审计。 */
    private CompletableFuture<Integer> pauseAfterActualAudit(Operation operation, CountDownLatch audited, CountDownLatch release) {
        CompletableFuture<Integer> pid = new CompletableFuture<>();
        doAnswer(invocation -> {
            invocation.callRealMethod(); pid.complete(appPid()); assertUncommittedOutcome(operation);
            audited.countDown(); await(release); return null;
        }).when(audits).record(argThat(entry -> isTargetAudit(entry, operation)));
        return pid;
    }

    /** 故障/屏障只匹配本例稳定账号目标及原动作，不拦截其他项目审计。 */
    private boolean isTargetAudit(AuditLogEntry entry, Operation operation) {
        return entry != null && fixture.projectId().equals(entry.projectId())
                && fixture.targetId().equals(entry.targetId()) && operation.action.equals(entry.action());
    }

    /** 独立owner不可见未提交内容，所以用原业务JDBC连接证明真正执行了双角色更新/退出和审计。 */
    private void assertUncommittedOutcome(Operation operation) {
        assertThat(jdbc.queryForList("SELECT role FROM sys_project_member WHERE project_id=? AND account_id=?", String.class,
                fixture.projectId(), fixture.ownerId())).containsExactly(operation == Operation.TRANSFER ? "ADMIN" : "OWNER");
        assertThat(jdbc.queryForList("SELECT role FROM sys_project_member WHERE project_id=? AND account_id=?", String.class,
                fixture.projectId(), fixture.targetId())).containsExactlyElementsOf(operation == Operation.TRANSFER ? List.of("OWNER") : List.of());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_audit_log WHERE project_id=? AND target_id=? AND action=?", Integer.class,
                fixture.projectId(), fixture.targetId(), operation.action)).isEqualTo(1);
    }

    /** 完整审计JSON等价及scope维度必须保留，控制权转让绝不改变项目tenant归属。 */
    private void assertCommittedOutcome(Operation operation, ProjectRole previousTargetRole) throws SQLException {
        try (Connection owner = fixtureOwnerConnection()) {
            assertThat(rows(owner, "SELECT tenant_id::text FROM sys_project WHERE id=?", fixture.projectId())).containsExactly(fixture.tenantId().toString());
            assertThat(rows(owner, "SELECT role FROM sys_project_member WHERE project_id=? AND account_id=?", fixture.projectId(), fixture.ownerId()))
                    .containsExactly(operation == Operation.TRANSFER ? "ADMIN" : "OWNER");
            assertThat(rows(owner, "SELECT role FROM sys_project_member WHERE project_id=? AND account_id=?", fixture.projectId(), fixture.targetId()))
                    .containsExactlyElementsOf(operation == Operation.TRANSFER ? List.of("OWNER") : List.of());
            Map<String, String> details = operation == Operation.TRANSFER
                    ? Map.of("oldOwnerId", fixture.ownerId().toString(), "oldOwnerNewRole", "ADMIN",
                            "newOwnerOldRole", previousTargetRole.name(), "newOwnerNewRole", "OWNER")
                    : Map.of("oldRole", previousTargetRole.name());
            try (PreparedStatement query = owner.prepareStatement("""
                    SELECT tenant_id, actor_account_id, target_type, target_id, action, details = ?::jsonb
                    FROM sys_audit_log WHERE project_id=? ORDER BY id
                    """)) {
                query.setString(1, mapper.writeValueAsString(details)); query.setObject(2, fixture.projectId());
                try (ResultSet audit = query.executeQuery()) {
                    assertThat(audit.next()).isTrue();
                    assertThat(audit.getObject(1, UUID.class)).isEqualTo(operation == Operation.TRANSFER ? fixture.collaboratorTenantId() : fixture.tenantId());
                    assertThat(audit.getObject(2, UUID.class)).isEqualTo(operation == Operation.TRANSFER ? fixture.ownerId() : fixture.targetId());
                    assertThat(audit.getString(3)).isEqualTo("project_member");
                    assertThat(audit.getObject(4, UUID.class)).isEqualTo(fixture.targetId());
                    assertThat(audit.getString(5)).isEqualTo(operation.action);
                    assertThat(audit.getBoolean(6)).isTrue();
                    assertThat(audit.next()).isFalse();
                }
            }
        }
    }

    /** 等待后拒绝留下原三名成员全部角色，不能只检查未产生审计。 */
    private void assertInitialRolesAndNoAudit(Connection owner) throws SQLException {
        assertThat(rows(owner, "SELECT role FROM sys_project_member WHERE project_id=? AND account_id=?", fixture.projectId(), fixture.ownerId())).containsExactly("OWNER");
        assertThat(rows(owner, "SELECT role FROM sys_project_member WHERE project_id=? AND account_id=?", fixture.projectId(), fixture.collaboratorId())).containsExactly("ADMIN");
        assertThat(rows(owner, "SELECT role FROM sys_project_member WHERE project_id=? AND account_id=?", fixture.projectId(), fixture.targetId())).containsExactly("VIEWER");
        assertThat(rows(owner, "SELECT id::text FROM sys_audit_log WHERE project_id=?", fixture.projectId())).isEmpty();
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
        return DriverManager.getConnection(DATABASE_URL, OWNERSHIP_POSTGRES.getUsername(), OWNERSHIP_POSTGRES.getPassword());
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
        OWNERSHIP_POSTGRES.start(); return OWNERSHIP_POSTGRES.getJdbcUrl();
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

    /** 两个入口复用同一项目管理锁，但保持各自原角色规则与审计动作。 */
    private enum Operation {
        /** 双角色原子转让。 */ TRANSFER("project.member.ownership_transferred"),
        /** 普通成员主动退出。 */ LEAVE("project.member.left");
        /** 既有稳定动作码。 */ private final String action;
        /** @param action 原成员服务审计动作 */ Operation(String action) { this.action = action; }
    }

    /** 转让必须锁后重读的两类目标事实；原OWNER失权由两个真实服务竞争另验。 */
    private enum TransferChange {
        /** 目标成员被移除。 */ TARGET_REMOVED,
        /** 审计必须记录最新ADMIN。 */ TARGET_ROLE_CHANGED
    }

    /** 非OWNER退出等待中的身份和角色变化。 */
    private enum LeaveChange {
        /** 成员已经退出/被移除。 */ REMOVED,
        /** 最新OPERATOR用于成功退出审计。 */ ROLE_CHANGED
    }

    /** 与真实transfer争用同项目锁的原服务入口。 */
    private enum CompetingService {
        /** 新OWNER不可主动退出。 */ LEAVE,
        /** 5d2管理员不可移除新OWNER。 */ REMOVE
    }

    /** 审计真实写入之后的独立故障类型，不将前置SQL或权限失败误计回滚成功。 */
    private static class AfterAuditFailure extends RuntimeException {
        /** 确定故障发生阶段。 */ AfterAuditFailure() { super("控制权与审计写后内部故障"); }
    }

    /** @param tenantId 项目归属 @param projectId 项目 @param ownerId 当前OWNER @param collaboratorTenantId 当前OWNER自身租户 @param collaboratorId 项目ADMIN @param outsiderId 非成员 @param targetId 新OWNER或退出者 */
    private record Fixture(UUID tenantId, UUID projectId, UUID ownerId, UUID collaboratorTenantId,
                           UUID collaboratorId, UUID outsiderId, UUID targetId) { }
}
