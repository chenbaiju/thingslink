package com.things.link.bootstrap.export;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.export.domain.ProjectExportClaim;
import com.things.link.export.domain.ProjectExportCleanupClaim;
import com.things.link.export.domain.ProjectExportJobRepository;
import com.things.link.shared.id.Uuid7;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockingDetails;

/** ADR0075 决策4：导出任务与每次upload的租约必须由真实PostgreSQL原子围栏。 */
@Import(ProjectExportLeaseMigrationTests.IsolatedDatabaseConfiguration.class)
@OwnedTestContainers({"EXPORT_POSTGRES"})
class ProjectExportLeaseMigrationTests extends AbstractIntegrationTest {

    /** 独占数据库避免全局SKIP LOCKED领取其他测试的任务。 */
    private static final String DATABASE_NAME = "project_export_lease_"
            + UUID.randomUUID().toString().replace("-", "");

    /** 与全量套件相同的真实TimescaleDB/PostgreSQL版本。 */
    private static final PostgreSQLContainer<?> EXPORT_POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName(DATABASE_NAME)
            .withUsername(POSTGRES.getUsername())
            .withPassword(POSTGRES.getPassword());

    /** Spring、Flyway、APP和owner观察者共同使用的独占数据库。 */
    private static final String DATABASE_URL = startDatabase();

    /** 基类启动器指向共享库，本类禁用它以免留下跨库配额事实。 */
    @MockitoBean(enforceOverride = true, name = "relaxRestQuota")
    private ApplicationRunner unusedRestQuotaRelaxation;

    /** 真实APP角色仓储，覆盖SECURITY DEFINER函数与CAS映射。 */
    @Autowired
    private ProjectExportJobRepository jobs;

    /** 用于确认Spring确实落在独占数据库和受限APP角色。 */
    @Autowired
    private JdbcTemplate jdbc;

    /** 全局领取函数要求已存在事务，测试必须复现生产调度代理的事务边界。 */
    @Autowired
    private TransactionTemplate transactionTemplate;

    /** 每例先验证测试基础设施身份，防止迁移未切库却出现假绿。 */
    @BeforeEach
    void verifyInfrastructure() throws SQLException {
        assertThat(mockingDetails(unusedRestQuotaRelaxation).isMock()).isTrue();
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo(DATABASE_NAME);
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        // claim函数是全局队列；即使项目UUID不同，上一例过期任务也会被下一例领取，必须清空两张队列表。
        executeOwner("DELETE FROM sys_project_export_upload_cleanup");
        executeOwner("DELETE FROM sys_project_export_job");
    }

    /**
     * upload登记的清理时间从任务租约复制；任务续租后，即使原清理时间已到也不能领取在途对象。
     */
    @Test
    void cleanupWaitsForRenewedRunningLeaseBeforeClaimingUpload() throws Exception {
        Fixture fixture = fixture();
        ProjectExportClaim claim = createAndClaim(fixture);
        UUID cleanupId = Uuid7.generate();
        UUID uploadId = Uuid7.generate();
        String objectKey = objectKey(fixture, claim, uploadId);
        Instant snapshotAt = Instant.now();

        assertThat(jobs.registerUpload(claim.jobId(), claim.leaseToken(), cleanupId, uploadId,
                objectKey, snapshotAt)).isTrue();
        assertThat(booleanValue("""
                SELECT c.next_attempt_at - j.leased_until = interval '0 seconds'
                  FROM sys_project_export_upload_cleanup c
                  JOIN sys_project_export_job j ON j.id=c.export_id
                 WHERE c.id=?
                """, cleanupId)).isTrue();

        // 把初始清理时刻移到过去，再真实续租任务；清理候选必须看当前任务租约而非旧时间。
        executeOwner("UPDATE sys_project_export_upload_cleanup SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE id=?",
                cleanupId);
        assertThat(jobs.renew(claim.jobId(), claim.leaseToken())).isTrue();
        assertThat(jobs.claimCleanup()).isEmpty();

        executeOwner("UPDATE sys_project_export_job SET leased_until=clock_timestamp()-interval '1 second' WHERE id=?",
                claim.jobId());
        ProjectExportCleanupClaim cleanup = jobs.claimCleanup().orElseThrow();
        assertThat(cleanup.cleanupId()).isEqualTo(cleanupId);
        assertThat(cleanup.objectKey()).isEqualTo(objectKey);
    }

    /** 错token、过期token与512MiB以上对象均不能完成；合法当前身份只能采用自己的upload。 */
    @Test
    void taskAndCleanupCompletionRejectWrongExpiredAndOversizedClaims() throws Exception {
        Fixture fixture = fixture();
        ProjectExportClaim claim = createAndClaim(fixture);
        UUID cleanupId = Uuid7.generate();
        UUID uploadId = Uuid7.generate();
        String objectKey = objectKey(fixture, claim, uploadId);
        String digest = "a".repeat(64);
        assertThat(jobs.registerUpload(claim.jobId(), claim.leaseToken(), cleanupId, uploadId,
                objectKey, Instant.now())).isTrue();

        assertThat(complete(claim.jobId(), Uuid7.generate(), uploadId, objectKey, 3, digest)).isFalse();
        assertThat(jobs.fail(claim.jobId(), Uuid7.generate(), "WRONG_TOKEN", false)).isFalse();
        assertThat(complete(claim.jobId(), claim.leaseToken(), uploadId, objectKey,
                536_870_913L, digest)).isFalse();

        executeOwner("UPDATE sys_project_export_job SET leased_until=clock_timestamp()-interval '1 second' WHERE id=?",
                claim.jobId());
        executeOwner("UPDATE sys_project_export_upload_cleanup SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE id=?",
                cleanupId);
        assertThat(complete(claim.jobId(), claim.leaseToken(), uploadId, objectKey, 3, digest)).isFalse();
        assertThat(jobs.fail(claim.jobId(), claim.leaseToken(), "EXPIRED_TOKEN", false)).isFalse();

        ProjectExportCleanupClaim cleanup = jobs.claimCleanup().orElseThrow();
        assertThat(jobs.completeCleanup(cleanup.cleanupId(), Uuid7.generate())).isFalse();
        assertThat(jobs.failCleanup(cleanup.cleanupId(), Uuid7.generate(), "WRONG_TOKEN")).isFalse();
        executeOwner("UPDATE sys_project_export_upload_cleanup SET leased_until=clock_timestamp()-interval '1 second' WHERE id=?",
                cleanup.cleanupId());
        assertThat(jobs.completeCleanup(cleanup.cleanupId(), cleanup.leaseToken())).isFalse();
        assertThat(jobs.failCleanup(cleanup.cleanupId(), cleanup.leaseToken(), "EXPIRED_TOKEN")).isFalse();
    }

    /** 最终ZIP恰好512MiB可被采用，多一字节已由上一用例证明拒绝。 */
    @Test
    void exactFinalZipSizeBoundaryCanBeAdopted() throws Exception {
        Fixture fixture = fixture();
        ProjectExportClaim claim = createAndClaim(fixture);
        UUID cleanupId = Uuid7.generate();
        UUID uploadId = Uuid7.generate();
        String objectKey = objectKey(fixture, claim, uploadId);
        String digest = "b".repeat(64);
        assertThat(jobs.registerUpload(claim.jobId(), claim.leaseToken(), cleanupId, uploadId,
                objectKey, Instant.now())).isTrue();

        assertThat(complete(claim.jobId(), claim.leaseToken(), uploadId, objectKey,
                536_870_912L, digest)).isTrue();
        assertThat(jobs.findByIdentity(fixture.tenantId(), fixture.projectId(), claim.jobId()))
                .hasValueSatisfying(job -> {
                    assertThat(job.objectSize()).isEqualTo(536_870_912L);
                    assertThat(job.objectSha256()).isEqualTo(digest);
                    assertThat(job.status().name()).isEqualTo("SUCCEEDED");
                });
        assertThat(booleanValue("SELECT status='ADOPTED' FROM sys_project_export_upload_cleanup WHERE id=?",
                cleanupId)).isTrue();
    }

    /** 建立任务并通过真实全局函数领取，保证每项CAS测试从独立已提交租约开始。 */
    private ProjectExportClaim createAndClaim(Fixture fixture) {
        // 生产请求先提交QUEUED任务，调度器才以REQUIRES_NEW领取；同事务领取看不到被挂起的未提交行。
        Boolean created = transactionTemplate.execute(status -> {
            UUID jobId = Uuid7.generate();
            return jobs.create(jobId, fixture.tenantId(), fixture.projectId(), 1, fixture.accountId());
        });
        assertThat(created).isTrue();
        return jobs.claimReady("project-export-test-worker").orElseThrow();
    }

    /** 生产完成服务在事务中执行CAS；夹具保持相同边界以验证MANDATORY仓储合同。 */
    private boolean complete(UUID exportId, UUID leaseToken, UUID uploadId, String objectKey,
                             long objectSize, String digest) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status ->
                jobs.complete(exportId, leaseToken, uploadId, objectKey, objectSize, digest)));
    }

    /** ADR0075固定对象键，不允许测试用另一路径掩盖upload身份。 */
    private static String objectKey(Fixture fixture, ProjectExportClaim claim, UUID uploadId) {
        return "projects/" + fixture.tenantId() + "/" + fixture.projectId()
                + "/generations/1/exports/" + claim.jobId() + "/" + uploadId
                + "/project-export-v1.zip";
    }

    /** 创建满足外键的最小删除项目身份；队列函数本身不负责重复验证OWNER资格。 */
    private Fixture fixture() throws SQLException {
        UUID tenantId = Uuid7.generate();
        UUID accountId = Uuid7.generate();
        UUID projectId = Uuid7.generate();
        try (Connection owner = ownerConnection()) {
            owner.setAutoCommit(false);
            execute(owner, "INSERT INTO sys_tenant(id,name) VALUES (?,?)", tenantId, "导出租户");
            execute(owner, """
                    INSERT INTO sys_account(id,email,password_hash,display_name,email_verified_at)
                    VALUES (?,?,'test-hash','导出OWNER',clock_timestamp())
                    """, accountId, accountId + "@export.example");
            execute(owner, """
                    INSERT INTO sys_project(id,tenant_id,name,region,timezone,project_key,status,
                                            lifecycle_generation,created_at,updated_at,deleted_at)
                    VALUES (?,?,'待导出项目','sh-1','Asia/Shanghai',?,'DELETING',1,
                            clock_timestamp(),clock_timestamp(),clock_timestamp()-interval '1 day')
                    """, projectId, tenantId, "export_" + projectId.toString().replace("-", ""));
            owner.commit();
        }
        return new Fixture(tenantId, accountId, projectId);
    }

    /** APP结果中的布尔表达式仍以owner连接观察，避免RLS范围影响测试判断。 */
    private boolean booleanValue(String sql, Object... values) throws SQLException {
        try (Connection owner = ownerConnection(); PreparedStatement statement = owner.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getBoolean(1);
            }
        }
    }

    /** owner直接修改时钟边界，只作用于本测试的UUID事实。 */
    private void executeOwner(String sql, Object... values) throws SQLException {
        try (Connection owner = ownerConnection()) {
            execute(owner, sql, values);
        }
    }

    /** 创建owner连接用于夹具与越过RLS的事实观察。 */
    private static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(DATABASE_URL, EXPORT_POSTGRES.getUsername(), EXPORT_POSTGRES.getPassword());
    }

    /** 执行带参数的owner语句。 */
    private static void execute(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            statement.executeUpdate();
        }
    }

    /** Instant显式转JDBC时间，避免PG驱动无法推断java.time类型。 */
    private static void bind(PreparedStatement statement, Object... values) throws SQLException {
        for (int index = 0; index < values.length; index++) {
            Object value = values[index];
            statement.setObject(index + 1, value instanceof Instant instant ? Timestamp.from(instant) : value);
        }
    }

    /** 独占PG必须在Spring与Flyway读取动态属性前启动。 */
    private static String startDatabase() {
        EXPORT_POSTGRES.start();
        return EXPORT_POSTGRES.getJdbcUrl();
    }

    /** 把APP与Flyway切到独占数据库，并停用全部无关后台领取。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class IsolatedDatabaseConfiguration {
        /** 注册独占数据库和调度开关。 */
        @Bean
        DynamicPropertyRegistrar isolatedDatabaseProperties() {
            return registry -> {
                registry.add("spring.datasource.url", () -> DATABASE_URL);
                registry.add("spring.flyway.url", () -> DATABASE_URL);
                registry.add("things-link.outbox.publisher.enabled", () -> "false");
                registry.add("things-link.notification.retry.enabled", () -> "false");
                registry.add("spring.kafka.listener.auto-startup", () -> "false");
                // ADR0075的全局领取会抢走本类独占库刚创建的任务；六项同时后移，保留手工调用验证。
                registry.add("things-link.export.worker-fixed-delay-millis", () -> "3600000");
                registry.add("things-link.export.worker-initial-delay-millis", () -> "3600000");
                registry.add("things-link.export.cleanup-fixed-delay-millis", () -> "3600000");
                registry.add("things-link.export.cleanup-initial-delay-millis", () -> "3600000");
                registry.add("things-link.export.expiry-fixed-delay-millis", () -> "3600000");
                registry.add("things-link.export.expiry-initial-delay-millis", () -> "3600000");
            };
        }
    }

    /** 一条导出队列测试所需的持久外键。 */
    private record Fixture(UUID tenantId, UUID accountId, UUID projectId) {
    }
}
