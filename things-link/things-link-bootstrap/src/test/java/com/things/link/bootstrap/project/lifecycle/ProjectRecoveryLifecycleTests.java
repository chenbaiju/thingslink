package com.things.link.bootstrap.project.lifecycle;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.iam.application.AuthRateLimiter;
import com.things.link.project.application.ProjectRecoveryService;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import com.things.link.support.scheduling.NotificationWorkCoordinator;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * ADR0073 决策2：回收站和恢复入口必须以保留OWNER事实、数据库期限与项目排他锁为准。
 *
 * <p>本类使用独占 PostgreSQL，HTTP 仍走真实登录、JWT过滤器、Controller、事务代理与APP数据库角色。
 * 项目表和成员表豁免RLS，因此每个反例都同时放入别人的删除项目，防止漏写accountId的SQL静默越权。
 */
@AutoConfigureMockMvc
@Import(ProjectRecoveryLifecycleTests.IsolatedDatabaseConfiguration.class)
@OwnedTestContainers({"RECOVERY_POSTGRES"})
class ProjectRecoveryLifecycleTests extends AbstractIntegrationTest {

    /** 独占数据库防止并发恢复、审计故障和全局后台任务与其他测试互相观察。 */
    private static final String DATABASE_NAME = "project_recovery_" + UUID.randomUUID().toString().replace("-", "");
    /** 与全量测试相同版本的真实TimescaleDB/PostgreSQL。 */
    private static final PostgreSQLContainer<?> RECOVERY_POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName(DATABASE_NAME).withUsername(POSTGRES.getUsername()).withPassword(POSTGRES.getPassword());
    /** Flyway、应用与owner观察都固定到同一个独占数据库。 */
    private static final String DATABASE_URL = startDatabase();
    /** 登录口令只用于本类真实认证夹具。 */
    private static final String PASSWORD = "correct-horse-battery-staple";
    /** HTTP JSON语义读取器。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 基类放宽器连接共享库，本类以mock阻止跨库修改。 */
    @MockitoBean(enforceOverride = true, name = "relaxRestQuota")
    private ApplicationRunner unusedRestQuotaRelaxation;
    /** 本片不运行通知后台领取，避免恢复测试产生无关并发。 */
    @MockitoBean(enforceOverride = true)
    private NotificationWorkCoordinator unusedNotificationWorkCoordinator;
    /** 真实MockMvc覆盖认证、错误翻译和事务提交。 */
    @Autowired
    private MockMvc mockMvc;
    /** 应用连接用于验证APP实际落点；业务准备和观察仍使用owner连接。 */
    @Autowired
    private JdbcTemplate jdbc;
    /** 真实密码编码器避免测试绕开登录凭据合同。 */
    @Autowired
    private PasswordEncoder passwords;
    /** 登录限流在共享Redis，逐例清理本类快速登录造成的计数。 */
    @Autowired
    private AuthRateLimiter rateLimiter;
    /** 故障注入用例仍调用真实Spring事务代理，只省去HTTP异常翻译的不确定性。 */
    @Autowired
    private ProjectRecoveryService recovery;
    /** 只在原审计真实INSERT之后注入一次失败，验证恢复与审计同事务回滚。 */
    @MockitoSpyBean
    private AuditLogService audits;

    /** 每例确认独占数据库与运行角色，避免专库配置失效后测试仍假绿。 */
    @BeforeEach
    void verifyInfrastructure() {
        rateLimiter.clear();
        assertThat(mockingDetails(unusedRestQuotaRelaxation).isMock()).isTrue();
        assertThat(mockingDetails(unusedNotificationWorkCoordinator).isMock()).isTrue();
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo(DATABASE_NAME);
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
    }

    /** 回收站列出当前账号保留的全部DELETING OWNER项目，过期项仍可见但不可恢复。 */
    @Test
    void recycleBinIsAccountScopedCrossTenantAndReportsDeadline() throws Exception {
        Fixture fixture = fixture();
        Instant recent = Instant.now().minus(Duration.ofDays(1));
        Instant expired = Instant.now().minus(Duration.ofDays(31));
        UUID recentProject = project(fixture.projectTenant(), "近期删除", "Asia/Shanghai", "DELETING", recent, 1);
        UUID expiredProject = project(fixture.projectTenant(), "过期删除", "Asia/Urumqi", "DELETING", expired, 3);
        UUID activeProject = project(fixture.projectTenant(), "正常项目", "Asia/Shanghai", "ACTIVE", null, 0);
        UUID otherProject = project(fixture.projectTenant(), "他人项目", "Asia/Shanghai", "DELETING", recent, 1);
        UUID disabledMembership = project(fixture.projectTenant(), "禁用OWNER", "Asia/Shanghai", "DELETING", recent, 1);
        member(recentProject, fixture.ownerAccount(), "OWNER", "ACTIVE");
        member(expiredProject, fixture.ownerAccount(), "OWNER", "ACTIVE");
        member(activeProject, fixture.ownerAccount(), "OWNER", "ACTIVE");
        member(otherProject, fixture.otherAccount(), "OWNER", "ACTIVE");
        member(disabledMembership, fixture.ownerAccount(), "OWNER", "DISABLED");

        MvcResult response = getRecycleBin(login(fixture.ownerEmail()));
        assertThat(response.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json(response);

        assertThat(body.isArray()).isTrue();
        assertThat(body.size()).isEqualTo(2);
        assertThat(body.get(0).get("id").asString()).isEqualTo(recentProject.toString());
        assertThat(body.get(0).get("name").asString()).isEqualTo("近期删除");
        assertThat(body.get(0).get("region").asString()).isEqualTo("sh-1");
        assertThat(body.get(0).get("timezone").asString()).isEqualTo("Asia/Shanghai");
        assertThat(body.get(0).get("restorable").asBoolean()).isTrue();
        assertThat(Instant.parse(body.get(0).get("restoreDeadline").asString()))
                .isEqualTo(Instant.parse(body.get(0).get("deletedAt").asString()).plus(Duration.ofDays(30)));
        assertThat(body.get(1).get("id").asString()).isEqualTo(expiredProject.toString());
        assertThat(body.get(1).get("timezone").asString()).isEqualTo("Asia/Urumqi");
        assertThat(body.get(1).get("restorable").asBoolean()).isFalse();
        assertThat(fieldNames(body.get(0))).containsExactlyInAnyOrder(
                "id", "name", "region", "timezone", "deletedAt", "restoreDeadline", "restorable");
    }

    /** 跨租户OWNER恢复仅改变生命周期列、保留代次和成员字节，并写一条归属真实项目tenant的审计。 */
    @Test
    void crossTenantOwnerRestoresWithinWindowAndPreservesGenerationAndMembers() throws Exception {
        Fixture fixture = fixture();
        UUID projectId = project(fixture.projectTenant(), "可恢复项目", "Asia/Urumqi", "DELETING",
                Instant.now().minus(Duration.ofDays(5)), 7);
        member(projectId, fixture.ownerAccount(), "OWNER", "ACTIVE");
        member(projectId, fixture.otherAccount(), "VIEWER", "DISABLED");
        List<String> membersBefore = rows("SELECT row_to_json(m)::text FROM sys_project_member m WHERE project_id=? ORDER BY id", projectId);
        Instant updatedBefore = instant("SELECT updated_at FROM sys_project WHERE id=?", projectId);

        MvcResult restored = restore(login(fixture.ownerEmail()), projectId);

        assertThat(restored.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json(restored);
        assertThat(body.get("id").asString()).isEqualTo(projectId.toString());
        assertThat(body.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(rows("SELECT status||':'||coalesce(deleted_at::text,'NULL')||':'||lifecycle_generation FROM sys_project WHERE id=?", projectId))
                .containsExactly("ACTIVE:NULL:7");
        assertThat(instant("SELECT updated_at FROM sys_project WHERE id=?", projectId)).isAfter(updatedBefore);
        assertThat(rows("SELECT row_to_json(m)::text FROM sys_project_member m WHERE project_id=? ORDER BY id", projectId))
                .isEqualTo(membersBefore);
        assertThat(rows("SELECT tenant_id::text||':'||actor_account_id::text||':'||target_type||':'||target_id::text||':'||action FROM sys_audit_log WHERE project_id=?", projectId))
                .containsExactly(fixture.projectTenant() + ":" + fixture.ownerAccount() + ":project:" + projectId + ":project.restored");
    }

    /** 截止前可恢复；请求到达时截止已经到达或超过30天，已证明的OWNER得到50018且项目与审计不变。 */
    @Test
    void recoveryWindowUsesStrictBeforeBoundary() throws Exception {
        Fixture fixture = fixture();
        String token = login(fixture.ownerEmail());
        UUID before = project(fixture.projectTenant(), "截止前", "Asia/Shanghai", "DELETING",
                Instant.now().minus(Duration.ofDays(30)).plusSeconds(10), 1);
        UUID atDeadline = projectAtDatabaseBoundary(fixture.projectTenant(), "截止已到", "0 seconds");
        UUID after = projectAtDatabaseBoundary(fixture.projectTenant(), "超过截止", "1 second");
        for (UUID projectId : List.of(before, atDeadline, after)) member(projectId, fixture.ownerAccount(), "OWNER", "ACTIVE");

        assertThat(restore(token, before).getResponse().getStatus()).isEqualTo(200);
        for (UUID projectId : List.of(atDeadline, after)) {
            MvcResult rejected = restore(token, projectId);
            assertThat(rejected.getResponse().getStatus()).isEqualTo(409);
            assertThat(code(rejected)).isEqualTo(50018);
            assertThat(rows("SELECT status||':'||(deleted_at IS NOT NULL)::text FROM sys_project WHERE id=?", projectId))
                    .containsExactly("DELETING:true");
            assertThat(number("SELECT count(*) FROM sys_audit_log WHERE project_id=? AND action='project.restored'", projectId)).isZero();
        }
    }

    /** 非OWNER、失效OWNER关系和不存在项目统一50001，不能通过回收站或恢复响应枚举事实。 */
    @Test
    void restoreRequiresRetainedActiveOwnerWithoutLeakingProjects() throws Exception {
        Fixture fixture = fixture();
        UUID adminProject = project(fixture.projectTenant(), "ADMIN不可恢复", "Asia/Shanghai", "DELETING",
                Instant.now().minus(Duration.ofDays(1)), 1);
        UUID disabledOwnerProject = project(fixture.projectTenant(), "失效OWNER", "Asia/Shanghai", "DELETING",
                Instant.now().minus(Duration.ofDays(1)), 1);
        UUID disabledAccountProject = project(fixture.projectTenant(), "失效账号", "Asia/Shanghai", "DELETING",
                Instant.now().minus(Duration.ofDays(1)), 1);
        member(adminProject, fixture.ownerAccount(), "ADMIN", "ACTIVE");
        member(disabledOwnerProject, fixture.ownerAccount(), "OWNER", "DISABLED");
        member(disabledAccountProject, fixture.ownerAccount(), "OWNER", "ACTIVE");
        String token = login(fixture.ownerEmail());

        for (UUID projectId : List.of(adminProject, disabledOwnerProject, Uuid7.generate())) {
            List<String> before = rows("SELECT row_to_json(p)::text FROM sys_project p WHERE id=?", projectId);
            MvcResult rejected = restore(token, projectId);
            assertThat(rejected.getResponse().getStatus()).isEqualTo(404);
            assertThat(code(rejected)).isEqualTo(50001);
            assertThat(rows("SELECT row_to_json(p)::text FROM sys_project p WHERE id=?", projectId)).isEqualTo(before);
            assertThat(number("SELECT count(*) FROM sys_audit_log WHERE project_id=?", projectId)).isZero();
        }

        try (Connection owner = ownerConnection()) {
            execute(owner, "UPDATE sys_account SET status='DISABLED' WHERE id=?", fixture.ownerAccount());
        }
        MvcResult disabledAccount = restore(token, disabledAccountProject);
        assertThat(disabledAccount.getResponse().getStatus()).isEqualTo(404);
        assertThat(code(disabledAccount)).isEqualTo(50001);
        assertThat(rows("SELECT status FROM sys_project WHERE id=?", disabledAccountProject))
                .containsExactly("DELETING");
        assertThat(number("SELECT count(*) FROM sys_audit_log WHERE project_id=?", disabledAccountProject)).isZero();
    }

    /** 两个已通过锁前资格的恢复竞争同一项目锁，最终只能提交一次状态迁移和一次审计。 */
    @Test
    void concurrentRestoreCommitsOnceWithOneAudit() throws Exception {
        Fixture fixture = fixture();
        UUID projectId = project(fixture.projectTenant(), "并发恢复", "Asia/Shanghai", "DELETING",
                Instant.now().minus(Duration.ofDays(1)), 1);
        member(projectId, fixture.ownerAccount(), "OWNER", "ACTIVE");
        String token = login(fixture.ownerEmail());
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try (Connection holder = ownerConnection()) {
            holder.setAutoCommit(false);
            int holderPid = lockProject(holder, projectId);
            Future<MvcResult> first = workers.submit(() -> restore(token, projectId));
            Future<MvcResult> second = workers.submit(() -> restore(token, projectId));
            awaitBlockedBy(holderPid, first, second);
            holder.commit();
            List<Integer> statuses = List.of(first.get(8, TimeUnit.SECONDS).getResponse().getStatus(),
                    second.get(8, TimeUnit.SECONDS).getResponse().getStatus());
            assertThat(statuses).containsExactlyInAnyOrder(200, 404);
        } finally {
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(rows("SELECT status||':'||lifecycle_generation FROM sys_project WHERE id=?", projectId))
                .containsExactly("ACTIVE:1");
        assertThat(number("SELECT count(*) FROM sys_audit_log WHERE project_id=? AND action='project.restored'", projectId))
                .isEqualTo(1);
    }

    /** 事务虽在截止前进入恢复，但等待项目锁跨过数据库墙钟截止后仍须50018且零写。 */
    @Test
    void lockWaitCrossingDeadlineCannotRestoreFromTransactionStartTime() throws Exception {
        Fixture fixture = fixture();
        String token = login(fixture.ownerEmail());
        UUID projectId = project(fixture.projectTenant(), "等待跨截止", "Asia/Shanghai", "DELETING",
                Instant.now().minus(Duration.ofDays(30)).plusSeconds(2), 2);
        member(projectId, fixture.ownerAccount(), "OWNER", "ACTIVE");
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (Connection holder = ownerConnection()) {
            holder.setAutoCommit(false);
            int holderPid = lockProject(holder, projectId);
            Future<MvcResult> recovery = worker.submit(() -> restore(token, projectId));
            awaitBlockedBy(holderPid, recovery, recovery);
            awaitDatabaseDeadline(projectId);
            holder.commit();

            MvcResult rejected = recovery.get(5, TimeUnit.SECONDS);
            assertThat(rejected.getResponse().getStatus()).isEqualTo(409);
            assertThat(code(rejected)).isEqualTo(50018);
        } finally {
            worker.shutdownNow();
            assertThat(worker.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(rows("SELECT status||':'||(deleted_at IS NOT NULL)::text||':'||lifecycle_generation FROM sys_project WHERE id=?", projectId))
                .containsExactly("DELETING:true:2");
        assertThat(number("SELECT count(*) FROM sys_audit_log WHERE project_id=? AND action='project.restored'", projectId))
                .isZero();
    }

    /** 项目恢复等待排他锁超过控制面SQL预算时传播真实57014；释放锁后原项目仍可恢复一次。 */
    @Test
    void projectLockTimeoutRollsBackAndRetryRestoresOnce() throws Exception {
        Fixture fixture = fixture();
        UUID projectId = project(fixture.projectTenant(), "锁超时恢复", "Asia/Shanghai", "DELETING",
                Instant.now().minus(Duration.ofDays(1)), 5);
        member(projectId, fixture.ownerAccount(), "OWNER", "ACTIVE");
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (Connection holder = ownerConnection()) {
            holder.setAutoCommit(false);
            int holderPid = lockProject(holder, projectId);
            Future<Throwable> recoveryFailure = worker.submit(
                    () -> catchThrowable(() -> restoreDirect(fixture, projectId)));
            awaitBlockedBy(holderPid, recoveryFailure, recoveryFailure);

            Throwable failure = recoveryFailure.get(8, TimeUnit.SECONDS);
            assertThat(hasSqlState(failure, "57014"))
                    .as("项目锁等待必须由PostgreSQL query_canceled终止：%s", failure)
                    .isTrue();
            assertThat(rows("SELECT status||':'||(deleted_at IS NOT NULL)::text||':'||lifecycle_generation FROM sys_project WHERE id=?", projectId))
                    .containsExactly("DELETING:true:5");
            assertThat(number("SELECT count(*) FROM sys_audit_log WHERE project_id=? AND action='project.restored'", projectId))
                    .isZero();
            holder.rollback();
        } finally {
            worker.shutdownNow();
            assertThat(worker.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        restoreDirect(fixture, projectId);
        assertThat(rows("SELECT status||':'||coalesce(deleted_at::text,'NULL')||':'||lifecycle_generation FROM sys_project WHERE id=?", projectId))
                .containsExactly("ACTIVE:NULL:5");
        assertThat(number("SELECT count(*) FROM sys_audit_log WHERE project_id=? AND action='project.restored'", projectId))
                .isEqualTo(1);
    }

    /** 延迟到事务提交阶段的23514必须同时回滚恢复UPDATE与审计INSERT，删除故障后可正常恢复。 */
    @Test
    void deferredCommitFailureRollsBackProjectAndAuditThenRecovers() throws Exception {
        Fixture fixture = fixture();
        UUID projectId = project(fixture.projectTenant(), "提交失败恢复", "Asia/Shanghai", "DELETING",
                Instant.now().minus(Duration.ofDays(1)), 6);
        member(projectId, fixture.ownerAccount(), "OWNER", "ACTIVE");
        String suffix = projectId.toString().replace("-", "");
        String functionName = "f_prj_restore_" + suffix;
        String triggerName = "t_prj_restore_" + suffix;
        installDeferredAuditFailure(functionName, triggerName, projectId);
        try {
            Throwable failure = catchThrowable(() -> restoreDirect(fixture, projectId));
            assertThat(hasSqlState(failure, "23514"))
                    .as("故障必须发生在延迟约束提交阶段：%s", failure)
                    .isTrue();
            assertThat(rows("SELECT status||':'||(deleted_at IS NOT NULL)::text||':'||lifecycle_generation FROM sys_project WHERE id=?", projectId))
                    .containsExactly("DELETING:true:6");
            assertThat(number("SELECT count(*) FROM sys_audit_log WHERE project_id=? AND action='project.restored'", projectId))
                    .isZero();
        } finally {
            removeDeferredAuditFailure(functionName, triggerName);
        }

        restoreDirect(fixture, projectId);
        assertThat(rows("SELECT status||':'||coalesce(deleted_at::text,'NULL')||':'||lifecycle_generation FROM sys_project WHERE id=?", projectId))
                .containsExactly("ACTIVE:NULL:6");
        assertThat(number("SELECT count(*) FROM sys_audit_log WHERE project_id=? AND action='project.restored'", projectId))
                .isEqualTo(1);
    }

    /** 审计真实INSERT之后的内部失败必须回滚项目恢复；故障解除后原项目仍可恢复且只有一条审计。 */
    @Test
    void auditFailureRollsBackRestoreAndRetryRecovers() throws Exception {
        Fixture fixture = fixture();
        UUID projectId = project(fixture.projectTenant(), "审计回滚", "Asia/Shanghai", "DELETING",
                Instant.now().minus(Duration.ofDays(1)), 4);
        member(projectId, fixture.ownerAccount(), "OWNER", "ACTIVE");
        String token = login(fixture.ownerEmail());
        AtomicBoolean failOnce = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Object answer = invocation.callRealMethod();
            AuditLogEntry entry = invocation.getArgument(0);
            if (projectId.equals(entry.projectId()) && "project.restored".equals(entry.action())
                    && failOnce.compareAndSet(true, false)) {
                throw new AfterAuditFailure();
            }
            return answer;
        }).when(audits).record(argThat(entry -> projectId.equals(entry.projectId())));

        TenantContext.set(new TenantScope(fixture.ownerTenant(), null, fixture.ownerAccount()));
        Throwable failure;
        try {
            failure = catchThrowable(() -> recovery.restore(projectId));
        } finally {
            TenantContext.clear();
        }
        assertThat(failure).isExactlyInstanceOf(AfterAuditFailure.class);
        assertThat(failOnce).isFalse();
        assertThat(rows("SELECT status||':'||(deleted_at IS NOT NULL)::text||':'||lifecycle_generation FROM sys_project WHERE id=?", projectId))
                .containsExactly("DELETING:true:4");
        assertThat(number("SELECT count(*) FROM sys_audit_log WHERE project_id=? AND action='project.restored'", projectId)).isZero();

        assertThat(restore(token, projectId).getResponse().getStatus()).isEqualTo(200);
        assertThat(number("SELECT count(*) FROM sys_audit_log WHERE project_id=? AND action='project.restored'", projectId))
                .isEqualTo(1);
    }

    /** 建立两个租户、两个有效账号；OWNER账号自身tenant刻意不同于项目归属tenant。 */
    private Fixture fixture() throws SQLException {
        UUID projectTenant = Uuid7.generate();
        UUID ownerTenant = Uuid7.generate();
        UUID ownerAccount = Uuid7.generate();
        UUID otherAccount = Uuid7.generate();
        String ownerEmail = ownerAccount + "@recovery.example";
        try (Connection owner = ownerConnection()) {
            owner.setAutoCommit(false);
            execute(owner, "INSERT INTO sys_tenant(id,name) VALUES (?, '恢复项目租户'), (?, '恢复账号租户')",
                    projectTenant, ownerTenant);
            execute(owner, "INSERT INTO sys_account(id,email,password_hash,display_name,email_verified_at) VALUES (?,?,?,?,clock_timestamp()), (?,?,?,?,clock_timestamp())",
                    ownerAccount, ownerEmail, passwords.encode(PASSWORD), "恢复OWNER",
                    otherAccount, otherAccount + "@recovery.example", passwords.encode(PASSWORD), "其他账号");
            execute(owner, "INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?,?,?), (?,?,?)",
                    Uuid7.generate(), ownerTenant, ownerAccount, Uuid7.generate(), projectTenant, otherAccount);
            owner.commit();
        }
        return new Fixture(projectTenant, ownerTenant, ownerAccount, otherAccount, ownerEmail);
    }

    /** 插入一个精确生命周期项目，deletedAt为空时表示未删除。 */
    private UUID project(UUID tenantId, String name, String timezone, String status,
                         Instant deletedAt, long generation) throws SQLException {
        UUID projectId = Uuid7.generate();
        try (Connection owner = ownerConnection()) {
            execute(owner, """
                    INSERT INTO sys_project(id,tenant_id,name,region,timezone,project_key,status,lifecycle_generation,
                                            created_at,updated_at,deleted_at)
                    VALUES (?,? ,?,'sh-1',?,?,?, ?,clock_timestamp(),clock_timestamp(),?)
                    """, projectId, tenantId, name, timezone,
                    "recovery_" + projectId.toString().replace("-", ""), status, generation, deletedAt);
        }
        return projectId;
    }

    /** 由同一数据库语句产生截止已到或已超过的项目，避免Java时钟参与边界夹具。 */
    private UUID projectAtDatabaseBoundary(UUID tenantId, String name, String overdue) throws SQLException {
        UUID projectId = Uuid7.generate();
        try (Connection owner = ownerConnection(); PreparedStatement statement = owner.prepareStatement("""
                INSERT INTO sys_project(id,tenant_id,name,region,timezone,project_key,status,lifecycle_generation,
                                        created_at,updated_at,deleted_at)
                VALUES (?, ?, ?, 'sh-1', 'Asia/Shanghai', ?, 'DELETING', 1,
                        transaction_timestamp(), transaction_timestamp(),
                        transaction_timestamp() - interval '30 days' - (?::interval))
                """)) {
            statement.setObject(1, projectId);
            statement.setObject(2, tenantId);
            statement.setString(3, name);
            statement.setString(4, "recovery_" + projectId.toString().replace("-", ""));
            statement.setString(5, overdue);
            statement.executeUpdate();
        }
        return projectId;
    }

    /** 建立指定角色与状态的保留成员事实。 */
    private void member(UUID projectId, UUID accountId, String role, String status) throws SQLException {
        try (Connection owner = ownerConnection()) {
            execute(owner, "INSERT INTO sys_project_member(id,project_id,account_id,role,status) VALUES (?,?,?,?,?)",
                    Uuid7.generate(), projectId, accountId, role, status);
        }
    }

    /** 真实登录得到不含pid的控制台访问令牌，恢复入口因此不依赖已选项目。 */
    private String login(String email) throws Exception {
        rateLimiter.clear();
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json(result).get("accessToken").asString();
    }

    /** 调用回收站公开入口。 */
    private MvcResult getRecycleBin(String token) throws Exception {
        return mockMvc.perform(get("/api/v1/projects/recycle-bin")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn();
    }

    /** 调用恢复公开入口。 */
    private MvcResult restore(String token, UUID projectId) throws Exception {
        return mockMvc.perform(post("/api/v1/projects/{projectId}/restore", projectId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn();
    }

    /** 直接调用真实恢复事务代理；仅供需要检查底层SQLSTATE或提交异常的用例使用。 */
    private void restoreDirect(Fixture fixture, UUID projectId) {
        TenantContext.set(new TenantScope(fixture.ownerTenant(), null, fixture.ownerAccount()));
        try {
            recovery.restore(projectId);
        } finally {
            TenantContext.clear();
        }
    }

    /** JSON响应读取。 */
    private static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    /** 统一读取业务错误码。 */
    private static int code(MvcResult result) throws Exception {
        return json(result).get("code").asInt();
    }

    /** 读取对象字段名，验证回收站不会泄露tenantId或projectKey。 */
    private static Set<String> fieldNames(JsonNode node) {
        Set<String> fields = new HashSet<>();
        node.propertyNames().forEach(fields::add);
        return fields;
    }

    /** owner查询单值数字。 */
    private long number(String sql, Object... values) throws SQLException {
        return Long.parseLong(rows(sql, values).getFirst());
    }

    /** owner查询单个时间。 */
    private Instant instant(String sql, Object... values) throws SQLException {
        try (Connection owner = ownerConnection(); PreparedStatement statement = owner.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getTimestamp(1).toInstant();
            }
        }
    }

    /** owner按第一列读取事实列表。 */
    private List<String> rows(String sql, Object... values) throws SQLException {
        try (Connection owner = ownerConnection(); PreparedStatement statement = owner.prepareStatement(sql)) {
            bind(statement, values);
            List<String> rows = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) rows.add(result.getString(1));
            }
            return List.copyOf(rows);
        }
    }

    /** 项目FOR UPDATE持锁并返回holder PID。 */
    private int lockProject(Connection connection, UUID projectId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pg_backend_pid() FROM sys_project WHERE id=? FOR UPDATE")) {
            statement.setObject(1, projectId);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getInt(1);
            }
        }
    }

    /** 以pg_blocking_pids证明至少一个真实恢复事务正等待holder，而不是线程尚未调度。 */
    private void awaitBlockedBy(int holderPid, Future<?> first, Future<?> second) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        try (Connection observer = ownerConnection(); PreparedStatement statement = observer.prepareStatement("""
                SELECT count(*) FROM pg_stat_activity a
                 WHERE a.datname=current_database() AND ?=ANY(pg_blocking_pids(a.pid))
                """)) {
            statement.setInt(1, holderPid);
            while (System.nanoTime() < deadline) {
                try (ResultSet result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    if (result.getInt(1) > 0) return;
                }
                if (first.isDone() && second.isDone()) throw new AssertionError("两个恢复请求均未等待项目排他锁");
                Thread.sleep(5);
            }
        }
        throw new AssertionError("未观察到恢复事务等待项目排他锁");
    }

    /** 以数据库clock_timestamp判断真实截止，不用JVM时钟或固定sleep猜测窗口已经越过。 */
    private void awaitDatabaseDeadline(UUID projectId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        try (Connection observer = ownerConnection(); PreparedStatement statement = observer.prepareStatement("""
                SELECT clock_timestamp() >= deleted_at + interval '30 days'
                  FROM sys_project WHERE id=?
                """)) {
            statement.setObject(1, projectId);
            while (System.nanoTime() < deadline) {
                try (ResultSet result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    if (result.getBoolean(1)) return;
                }
                Thread.sleep(5);
            }
        }
        throw new AssertionError("数据库墙钟未在预算内越过项目恢复截止");
    }

    /** 安装仅命中本项目project.restored审计的延迟约束故障，不触碰既有不可变审计触发器。 */
    private void installDeferredAuditFailure(String functionName, String triggerName, UUID projectId) throws SQLException {
        try (Connection owner = ownerConnection()) {
            execute(owner, """
                    CREATE FUNCTION %s() RETURNS trigger LANGUAGE plpgsql AS $body$
                    BEGIN
                        IF NEW.project_id = '%s'::uuid AND NEW.action = 'project.restored' THEN
                            RAISE EXCEPTION 'test deferred project restore failure' USING ERRCODE = '23514';
                        END IF;
                        RETURN NEW;
                    END
                    $body$
                    """.formatted(functionName, projectId));
            execute(owner, """
                    CREATE CONSTRAINT TRIGGER %s
                    AFTER INSERT ON sys_audit_log
                    DEFERRABLE INITIALLY DEFERRED
                    FOR EACH ROW EXECUTE FUNCTION %s()
                    """.formatted(triggerName, functionName));
        }
    }

    /** 删除本例附加故障；既有sys_audit_log禁止UPDATE/DELETE触发器始终保留。 */
    private void removeDeferredAuditFailure(String functionName, String triggerName) throws SQLException {
        try (Connection owner = ownerConnection()) {
            execute(owner, "DROP TRIGGER IF EXISTS " + triggerName + " ON sys_audit_log");
            execute(owner, "DROP FUNCTION IF EXISTS " + functionName + "()");
        }
    }

    /** 沿异常链精确匹配PostgreSQL SQLSTATE，禁止把连接失败或任意系统异常算作目标故障。 */
    private static boolean hasSqlState(Throwable failure, String expected) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException && expected.equals(sqlException.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    /** owner连接只用于夹具、竞争锁和提交后观察。 */
    private Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(DATABASE_URL, RECOVERY_POSTGRES.getUsername(), RECOVERY_POSTGRES.getPassword());
    }

    /** 预编译参数统一处理Instant，避免PG驱动无法推断java.time.Instant类型。 */
    private static void bind(PreparedStatement statement, Object... values) throws SQLException {
        for (int index = 0; index < values.length; index++) {
            Object value = values[index];
            statement.setObject(index + 1, value instanceof Instant instant ? Timestamp.from(instant) : value);
        }
    }

    /** 执行夹具写入。 */
    private static void execute(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            statement.executeUpdate();
        }
    }

    /** 独占PG必须在Spring/Flyway读取动态属性前启动。 */
    private static String startDatabase() {
        RECOVERY_POSTGRES.start();
        return RECOVERY_POSTGRES.getJdbcUrl();
    }

    /** APP与Flyway同库，并停用与恢复合同无关的后台执行器。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class IsolatedDatabaseConfiguration {
        /** 注册独占数据库及后台开关。 */
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

    /** 在审计已写入后模拟业务内部故障。 */
    private static final class AfterAuditFailure extends RuntimeException {
        /** 固定消息便于失败栈定位注入点。 */
        private AfterAuditFailure() {
            super("project.restored审计写入后的测试故障");
        }
    }

    /** 跨租户恢复所需的最小身份夹具。 */
    private record Fixture(UUID projectTenant, UUID ownerTenant, UUID ownerAccount,
                           UUID otherAccount, String ownerEmail) {
    }
}
