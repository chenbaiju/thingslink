package com.things.link.bootstrap.project.cleanup;

import com.things.link.project.application.AccountDirectory;
import com.things.link.project.application.ProjectCleanupAdmissionService;
import com.things.link.project.application.ProjectCleanupClaim;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectRecoveryService;
import com.things.link.project.infrastructure.persistence.JdbcProjectCleanupRepository;
import com.things.link.project.infrastructure.persistence.JdbcProjectRepository;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.audit.AuditLogService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * ADR0076：以独占真实PG、APP角色和Spring事务代理验证清理准入，避免全局领取污染其他测试。
 * 仅装载support/project/iam迁移，不启动后台worker；恢复仍调用真实项目仓储与恢复服务。
 */
@Testcontainers
class ProjectCleanupAdmissionTests {

    /** 与正式套件相同的Timescale版本，旧库与新库共享这一隔离实例。 */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("project_cleanup_admission").withUsername("thingslink").withPassword("thingslink");
    /** 旧库不应被升级重写的项目。 */
    private static final UUID LEGACY_PROJECT = UUID.randomUUID();
    /** owner仅用于夹具与目录观察，业务必须通过APP连接。 */
    private static JdbcTemplate owner;
    /** 真实受限角色连接；项目表豁免RLS的行为也必须在此身份验证。 */
    private JdbcTemplate app;
    /** 真实项目写事务管理器。 */
    private DataSourceTransactionManager transactions;
    /** 被测Spring事务代理。 */
    private ProjectCleanupAdmissionService cleanup;
    /** 真实恢复用例与OWNER前后校验。 */
    private ProjectRecoveryService recovery;
    /** 读取门禁使用实际仓储，不能以枚举断言代替拒绝行为。 */
    private ProjectLifecycleAccessService lifecycle;
    /** 原审计INSERT后注入故障，用于证明准入原子回滚。 */
    private AuditLogService audits;

    /** 从真实0240旧库升级到0280，确保只新增清理元数据而不补造历史清理。 */
    @BeforeAll
    static void migrateLegacyDatabase() {
        owner = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "thingslink", "thingslink"));
        flyway("20260904.0240").migrate();
        UUID tenant = UUID.randomUUID();
        owner.update("INSERT INTO sys_tenant(id,name) VALUES (?, '旧租户')", tenant);
        owner.update("""
                INSERT INTO sys_project(id,tenant_id,name,project_key,status,lifecycle_generation)
                VALUES (?,?,'旧项目',?,'ACTIVE',7)
                """, LEGACY_PROJECT, tenant, "legacy_" + LEGACY_PROJECT.toString().replace("-", ""));
        assertThat(flyway("20260904.0280").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway("20260904.0280").migrate().migrationsExecuted).isZero();
    }

    /** 每例移除本类旧夹具候选；审计历史不删除，不改其他测试数据库。 */
    @BeforeEach
    void configureRealServices() {
        owner.update("DELETE FROM sys_project_member WHERE project_id <> ?", LEGACY_PROJECT);
        owner.update("DELETE FROM sys_project WHERE id <> ?", LEGACY_PROJECT);
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "thingslink_app", "thingslink");
        app = new JdbcTemplate(dataSource);
        transactions = new DataSourceTransactionManager(dataSource);
        audits = spy(new AuditLogService(app, new ObjectMapper()));
        cleanup = transactional(new ProjectCleanupAdmissionService(new JdbcProjectCleanupRepository(app), audits, app));
        JdbcProjectRepository projects = new JdbcProjectRepository(app);
        AccountDirectory accounts = mock(AccountDirectory.class);
        when(accounts.isActive(any())).thenReturn(true);
        recovery = transactional(new ProjectRecoveryService(projects, accounts, audits));
        lifecycle = transactional(new ProjectLifecycleAccessService(projects));
        assertThat(app.queryForObject("SELECT current_user", String.class)).isEqualTo("thingslink_app");
    }

    /** 旧行身份与默认清理状态保留，新增列均有目录注释且APP可读取。 */
    @Test
    void migrationPreservesLegacyFactsAndComments() {
        assertThat(app.queryForMap("""
                SELECT name,status,lifecycle_generation,cleanup_stage,cleanup_batches,cleanup_rows
                  FROM sys_project WHERE id=?
                """, LEGACY_PROJECT)).containsEntry("name", "旧项目").containsEntry("status", "ACTIVE")
                .containsEntry("lifecycle_generation", 7L).containsEntry("cleanup_stage", null)
                .containsEntry("cleanup_batches", 0L).containsEntry("cleanup_rows", 0L);
        assertThat(owner.queryForObject("""
                SELECT count(*) FROM pg_attribute WHERE attrelid='sys_project'::regclass
                 AND attname LIKE 'cleanup_%' AND col_description(attrelid,attnum) IS NOT NULL
                """, Integer.class)).isEqualTo(9);
    }

    /** 三十天内、ACTIVE/ARCHIVED、缺deleted_at均不可领取；到期项目恰好受理一次并禁止恢复/普通读取。 */
    @Test
    void admitsOnlyExpiredDeletingProjectAndWritesOneAudit() {
        fixture("DELETING", "1 day");
        fixture("DELETING", null);
        fixture("ACTIVE", null);
        fixture("ARCHIVED", null);
        assertThat(cleanup.claimNext()).isEmpty();
        Fixture expired = fixture("DELETING", "30 days");
        ProjectCleanupClaim claim = cleanup.claimNext().orElseThrow();
        assertThat(claim.projectId()).isEqualTo(expired.project());
        assertThat(claim.tenantId()).isEqualTo(expired.tenant());
        assertThat(claim.generation()).isEqualTo(1);
        assertThat(claim.stage()).isEqualTo("WAIT_EXPORT");
        assertThat(claim.newlyAdmitted()).isTrue();
        assertThat(cleanup.claimNext()).isEmpty();
        assertThat(auditCount(expired.project())).isEqualTo(1);
        assertThat(lifecycle.snapshot(expired.tenant(), expired.project()).readAllowed()).isFalse();
        TenantContext.set(new TenantScope(expired.tenant(), null, expired.account()));
        try {
            assertThat(recovery.listRecycleBin()).noneMatch(p -> p.project().id().equals(expired.project()));
            assertThatThrownBy(() -> recovery.restore(expired.project())).isInstanceOf(BusinessException.class);
        } finally {
            TenantContext.clear();
        }
        assertThat(status(expired.project())).isEqualTo("PURGING");
    }

    /** 租约过期必须换token；旧token和四种伪造身份不能锁定或退避新领取。 */
    @Test
    void takeoverFencesExpiredAndForgedIdentities() {
        Fixture fixture = fixture("DELETING", "31 days");
        ProjectCleanupClaim old = cleanup.claimNext().orElseThrow();
        owner.update("UPDATE sys_project SET cleanup_lease_until=clock_timestamp()-interval '1 second' WHERE id=?", fixture.project());
        assertThat(cleanup.defer(old, "STORAGE_UNAVAILABLE")).isFalse();
        ProjectCleanupClaim current = cleanup.claimNext().orElseThrow();
        assertThat(current.leaseToken()).isNotEqualTo(old.leaseToken());
        assertThat(current.newlyAdmitted()).isFalse();
        List<ProjectCleanupClaim> rejected = List.of(old,
                altered(current, UUID.randomUUID(), current.projectId(), current.generation(), current.stage()),
                altered(current, current.tenantId(), UUID.randomUUID(), current.generation(), current.stage()),
                altered(current, current.tenantId(), current.projectId(), 99, current.stage()),
                altered(current, current.tenantId(), current.projectId(), current.generation(), "TASK"));
        for (ProjectCleanupClaim claim : rejected) {
            assertThat(new TransactionTemplate(transactions).<Boolean>execute(s -> cleanup.lockCurrent(claim))).isFalse();
            assertThat(cleanup.defer(claim, "REJECTED")).isFalse();
        }
        assertThat(new TransactionTemplate(transactions).<Boolean>execute(s -> cleanup.lockCurrent(current))).isTrue();
        assertThat(auditCount(fixture.project())).isEqualTo(1);
    }

    /** 失败退避保留阶段、清理计数和准入事实，不能立即忙轮询或重复写首次审计。 */
    @Test
    void deferredClaimKeepsProgressAndCanBeReclaimedWhenDue() {
        Fixture fixture = fixture("DELETING", "31 days");
        ProjectCleanupClaim claim = cleanup.claimNext().orElseThrow();
        assertThatThrownBy(() -> cleanup.defer(claim, "contains secret text")).isInstanceOf(IllegalArgumentException.class);
        assertThat(cleanup.defer(claim, "STORAGE_UNAVAILABLE")).isTrue();
        assertThat(cleanup.claimNext()).isEmpty();
        assertThat(owner.queryForMap("""
                SELECT cleanup_stage,cleanup_failure_code,cleanup_lease_token,cleanup_batches,cleanup_rows,
                       cleanup_next_attempt_at > clock_timestamp()+interval '20 seconds' AS backed_off
                  FROM sys_project WHERE id=?
                """, fixture.project())).containsEntry("cleanup_stage", "WAIT_EXPORT")
                .containsEntry("cleanup_failure_code", "STORAGE_UNAVAILABLE").containsEntry("cleanup_lease_token", null)
                .containsEntry("cleanup_batches", 0L).containsEntry("cleanup_rows", 0L).containsEntry("backed_off", true);
        owner.update("UPDATE sys_project SET cleanup_next_attempt_at=clock_timestamp()-interval '1 second' WHERE id=?", fixture.project());
        assertThat(cleanup.claimNext().orElseThrow().newlyAdmitted()).isFalse();
        assertThat(auditCount(fixture.project())).isEqualTo(1);
    }

    /** 审计已经真实INSERT后抛错，项目准入、租约与审计必须全部回滚，下一次仍可首次受理。 */
    @Test
    void auditFailureRollsBackAdmissionAndLease() {
        Fixture fixture = fixture("DELETING", "31 days");
        doAnswer(call -> {
            call.callRealMethod();
            throw new IllegalStateException("controlled audit commit failure");
        }).when(audits).record(argThat(entry -> fixture.project().equals(entry.projectId())));
        assertThatThrownBy(cleanup::claimNext).isInstanceOf(IllegalStateException.class);
        assertThat(status(fixture.project())).isEqualTo("DELETING");
        assertThat(auditCount(fixture.project())).isZero();
        assertThat(owner.queryForObject("SELECT cleanup_lease_token IS NULL FROM sys_project WHERE id=?",
                Boolean.class, fixture.project())).isTrue();
        reset(audits);
        assertThat(cleanup.claimNext().orElseThrow().newlyAdmitted()).isTrue();
    }

    /** 真实恢复先提交则不再受理，清理不可逆后也不能经SQL回拨状态或代次。 */
    @Test
    void restoredProjectIsNotClaimedAndPurgingCannotBeReactivated() {
        Fixture recent = fixture("DELETING", "1 day");
        TenantContext.set(new TenantScope(recent.tenant(), null, recent.account()));
        try {
            recovery.restore(recent.project());
        } finally {
            TenantContext.clear();
        }
        assertThat(cleanup.claimNext()).isEmpty();
        Fixture expired = fixture("DELETING", "31 days");
        cleanup.claimNext().orElseThrow();
        assertThatThrownBy(() -> owner.update("UPDATE sys_project SET status='ACTIVE' WHERE id=?", expired.project()))
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThatThrownBy(() -> owner.update("UPDATE sys_project SET lifecycle_generation=0 WHERE id=?", expired.project()))
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThat(status(expired.project())).isEqualTo("PURGING");
    }

    /** 已被另一个真实事务锁定的过期项目必须被跳过，仍可领取另一个项目而不等待释放。 */
    @Test
    void claimSkipsLockedProjectWithoutCrossProjectMutation() throws Exception {
        Fixture locked = fixture("DELETING", "32 days");
        Fixture other = fixture("DELETING", "31 days");
        try (Connection connection = owner.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM sys_project WHERE id=? FOR UPDATE")) {
                statement.setObject(1, locked.project());
                statement.executeQuery().close();
            }
            assertThat(cleanup.claimNext().orElseThrow().projectId()).isEqualTo(other.project());
            assertThat(status(locked.project())).isEqualTo("DELETING");
            connection.rollback();
        }
        assertThat(cleanup.claimNext().orElseThrow().projectId()).isEqualTo(locked.project());
    }

    /** 锁等待结束后按实际时钟拒绝过期token，而不是沿用等待前资格。 */
    @Test
    void rechecksLeaseAfterWaitingForProjectLock() throws Exception {
        Fixture fixture = fixture("DELETING", "31 days");
        ProjectCleanupClaim claim = cleanup.claimNext().orElseThrow();
        CountDownLatch started = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor(); Connection lock = owner.getDataSource().getConnection()) {
            lock.setAutoCommit(false);
            try (PreparedStatement statement = lock.prepareStatement("""
                    UPDATE sys_project SET cleanup_lease_until=clock_timestamp()-interval '1 second' WHERE id=?
                    """)) {
                statement.setObject(1, fixture.project());
                statement.executeUpdate();
            }
            var result = executor.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                started.countDown();
                return cleanup.lockCurrent(claim);
            }));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            long waitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean blocked = false;
            while (System.nanoTime() < waitDeadline && !blocked) {
                blocked = Boolean.TRUE.equals(owner.queryForObject("""
                        SELECT EXISTS(SELECT 1 FROM pg_stat_activity
                          WHERE usename='thingslink_app' AND wait_event_type='Lock'
                            AND query LIKE '%SELECT id FROM public.sys_project%')
                        """, Boolean.class));
                if (!blocked) {
                    Thread.sleep(10);
                }
            }
            assertThat(blocked).as("确认工作线程真实等待项目行锁").isTrue();
            lock.commit();
            assertThat(result.get(5, TimeUnit.SECONDS)).isFalse();
        }
    }

    /** 无事务、只读或旧快照隔离级不能获取清理批次许可。 */
    @Test
    void rejectsMissingReadOnlyAndRepeatableReadTransactions() {
        fixture("DELETING", "31 days");
        ProjectCleanupClaim claim = cleanup.claimNext().orElseThrow();
        assertThatThrownBy(() -> cleanup.lockCurrent(claim)).isInstanceOf(IllegalTransactionStateException.class);
        TransactionTemplate readOnly = new TransactionTemplate(transactions);
        readOnly.setReadOnly(true);
        assertThatThrownBy(() -> readOnly.execute(s -> cleanup.lockCurrent(claim))).isInstanceOf(IllegalStateException.class);
        TransactionTemplate repeatable = new TransactionTemplate(transactions);
        repeatable.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThatThrownBy(() -> repeatable.execute(s -> cleanup.lockCurrent(claim))).isInstanceOf(IllegalStateException.class);
    }

    /** 创建真实项目与保留OWNER；身份和时间间隔均参数绑定。 */
    private Fixture fixture(String status, String deletedAgo) {
        UUID tenant = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        owner.update("INSERT INTO sys_tenant(id,name) VALUES (?, '清理测试租户')", tenant);
        owner.update("INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?,?,'hash','OWNER')", account, account + "@test.example");
        owner.update("""
                INSERT INTO sys_project(id,tenant_id,name,project_key,status,lifecycle_generation,deleted_at)
                VALUES (?,?,'待清理项目',?,?,1,CASE WHEN ?::text IS NULL THEN NULL ELSE clock_timestamp()-?::interval END)
                """, project, tenant, "cleanup_" + project.toString().replace("-", ""), status, deletedAgo, deletedAgo);
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role,status) VALUES (?,?,?,'OWNER','ACTIVE')",
                UUID.randomUUID(), project, account);
        return new Fixture(tenant, account, project);
    }

    /** @param project 目标项目 @return 权威项目状态 */
    private String status(UUID project) {
        return owner.queryForObject("SELECT status FROM sys_project WHERE id=?", String.class, project);
    }

    /** @param project 目标项目 @return 首次清理准入审计数量 */
    private long auditCount(UUID project) {
        return owner.queryForObject("SELECT count(*) FROM sys_audit_log WHERE project_id=? AND action='project.cleanup.started'", Long.class, project);
    }

    /** 构造各维度伪造身份，保留真实token才能验证SQL没有遗漏其他条件。 */
    private ProjectCleanupClaim altered(ProjectCleanupClaim claim, UUID tenant, UUID project, long generation, String stage) {
        return new ProjectCleanupClaim(tenant, project, generation, stage, claim.leaseToken(), claim.leaseUntil(), false);
    }

    /** 使用生产注解创建真实事务代理，避免手工事务掩盖MANDATORY或提交边界错误。 */
    @SuppressWarnings("unchecked")
    private <T> T transactional(T target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    /** @param target 明确的迁移终点 @return 不受未来迁移数量变化影响的Flyway */
    private static Flyway flyway(String target) {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), "thingslink", "thingslink")
                .locations("classpath:db/migration/support", "classpath:db/migration/project", "classpath:db/migration/iam")
                .placeholders(Map.of("app_role_password", "thingslink")).target(target).load();
    }

    /** @param tenant 项目持久归属 @param account 保留OWNER @param project 目标项目 */
    private record Fixture(UUID tenant, UUID account, UUID project) {
    }
}
