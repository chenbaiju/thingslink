package com.things.link.bootstrap.dashboard;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.dashboard.application.ApplicationManagementService;
import com.things.link.dashboard.domain.ApplicationCatalogEntry;
import com.things.link.dashboard.domain.ApplicationDraft;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.RlsScopeContext;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import com.things.link.support.scheduling.NotificationWorkCoordinator;
import com.things.link.task.application.TaskSchedulingScanner;
import com.things.link.telemetry.application.DeviceCommandTimeoutScanner;
import com.things.link.telemetry.application.PropertyAggregateBackfillScanner;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mockingDetails;

/**
 * S12-1a2b应用目录与草稿管理及S12-1d2b域级幂等创建的真实PostgreSQL验收。
 *
 * <p>本类覆盖无HTTP管理边界：项目owner tenant归属、角色与归档许可、草稿CAS竞争、创建恢复身份及同事务审计。
 * 独占数据库避免全表后台扫描或其他测试候选改变并发结果；发布候选与版本内容不在本类重复验证。</p>
 */
@Import(ApplicationManagementServiceIntegrationTests.IsolatedDatabaseConfiguration.class)
@OwnedTestContainers({"APPLICATION_POSTGRES"})
class ApplicationManagementServiceIntegrationTests extends AbstractIntegrationTest {

    /** 告警、任务和全局领取均不应与应用管理验收共享物理候选集合。 */
    private static final String DATABASE_NAME = "application_management_"
            + UUID.randomUUID().toString().replace("-", "");

    /** PostgreSQL/Timescale版本沿用全仓真实验收镜像。 */
    private static final PostgreSQLContainer<?> APPLICATION_POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName(DATABASE_NAME)
            .withUsername(POSTGRES.getUsername())
            .withPassword(POSTGRES.getPassword());

    /** Spring、Flyway、运行角色和owner观察统一落在独占库。 */
    private static final String DATABASE_URL = startDatabase();

    /** 独占库无需共享配额runner修改策略。 */
    @MockitoBean(enforceOverride = true, name = "relaxRestQuota")
    private ApplicationRunner unusedQuotaRunner;

    /** 应用管理不投递通知，禁用全表通知领取。 */
    @MockitoBean(enforceOverride = true)
    private NotificationWorkCoordinator unusedNotificationCoordinator;

    /** 任务扫描与应用目录管理正交。 */
    @MockitoBean(enforceOverride = true)
    private TaskSchedulingScanner unusedTaskScanner;

    /** 命令生命周期不得在独占库制造旁路候选。 */
    @MockitoBean(enforceOverride = true)
    private DeviceCommandTimeoutScanner unusedCommandScanner;

    /** 属性聚合回补不参与应用草稿保存。 */
    @MockitoBean(enforceOverride = true)
    private PropertyAggregateBackfillScanner unusedBackfillScanner;

    /** 被测真实事务服务及其真实仓储、项目许可与审计依赖。 */
    @Autowired
    private ApplicationManagementService applications;

    /** 对真实审计服务只注入单次、可清除的写后故障，验证同事务回滚而不替换持久实现。 */
    @MockitoSpyBean
    private AuditLogService audits;

    /** 普通运行角色连接，用于校验专库落点和运行身份。 */
    @Autowired
    private JdbcTemplate jdbc;

    /** 每例确认专库、真实运行角色和无关调度器替身均已生效。 */
    @BeforeEach
    void verifyEnvironment() {
        assertThat(DATABASE_URL).isNotEqualTo(POSTGRES.getJdbcUrl());
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo(DATABASE_NAME);
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        assertThat(jdbc.queryForObject("SHOW transaction_isolation", String.class)).isEqualTo("read committed");
        for (Object disabled : List.of(unusedQuotaRunner, unusedNotificationCoordinator, unusedTaskScanner,
                unusedCommandScanner, unusedBackfillScanner)) {
            assertThat(mockingDetails(disabled).isMock()).isTrue();
        }
    }

    /** OWNER与跨租户ADMIN创建均写项目owner tenant，成功create/rename/save各只追加一条审计。 */
    @Test
    void ownerAndCrossTenantAdminPersistProjectTenantWithSingleAudits() throws Exception {
        Fixture fixture = seed();

        ApplicationCatalogEntry owned = as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> applications.create(fixture.projectId(), "OWNER应用", draft("OWNER初始")));
        assertThat(auditCount(fixture.projectId())).isOne();
        ApplicationCatalogEntry administered = as(
                fixture.collaboratorTenantId(), fixture.projectId(), fixture.adminId(),
                () -> applications.create(fixture.projectId(), "ADMIN应用", draft("ADMIN初始")));
        assertThat(auditCount(fixture.projectId())).isEqualTo(2);

        as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> applications.rename(fixture.projectId(), owned.id(), "OWNER已改名"));
        assertThat(auditCount(fixture.projectId())).isEqualTo(3);
        ApplicationDraft saved = as(
                fixture.collaboratorTenantId(), fixture.projectId(), fixture.adminId(),
                () -> applications.saveDraft(fixture.projectId(), administered.id(), "0", draft("ADMIN已保存")));
        List<UUID> persistedTenants = persistedTenants(fixture.projectId());
        long finalAuditCount = auditCount(fixture.projectId());
        List<UUID> auditTenants = auditTenants(fixture.projectId());
        List<UUID> auditActors = auditActors(fixture.projectId());
        List<String> auditActions = auditActions(fixture.projectId());
        List<UUID> auditTargets = auditTargets(fixture.projectId());

        assertSoftly(softly -> {
            softly.assertThat(owned.tenantId()).isEqualTo(fixture.ownerTenantId());
            softly.assertThat(administered.tenantId()).isEqualTo(fixture.ownerTenantId());
            softly.assertThat(saved.tenantId()).isEqualTo(fixture.ownerTenantId());
            softly.assertThat(saved.revision()).isOne();
            softly.assertThat(persistedTenants).containsOnly(fixture.ownerTenantId());
            softly.assertThat(finalAuditCount).isEqualTo(4);
            softly.assertThat(auditTenants).containsOnly(fixture.ownerTenantId());
            softly.assertThat(auditActors).contains(fixture.ownerId(), fixture.adminId());
            softly.assertThat(auditActions).containsExactlyInAnyOrder(
                    "application.created", "application.created",
                    "application.renamed", "application.draft_saved");
            softly.assertThat(auditTargets).containsExactlyInAnyOrder(
                    owned.id(), administered.id(), owned.id(), administered.id());
        });
    }

    /** 审计真实INSERT后失败时目录与草稿必须一并回滚；移除故障后同一请求可安全重试。 */
    @Test
    void createAuditFailureRollsBackApplicationAndAllowsRetry() throws Exception {
        Fixture fixture = seed();
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            AuditLogEntry entry = invocation.getArgument(0, AuditLogEntry.class);
            if ("application.created".equals(entry.action())) {
                throw new AuditFailureAfterInsertException();
            }
            return result;
        }).when(audits).record(any(AuditLogEntry.class));

        try {
            Throwable failure = catchThrowable(() -> as(
                    fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                    () -> applications.createIdempotent(
                            fixture.projectId(), "audit-retry", "审计失败应用", draft("失败候选"))));

            assertThat(failure).isInstanceOf(AuditFailureAfterInsertException.class);
            assertThat(facts(fixture.projectId())).isEmpty();
            assertThat(auditCount(fixture.projectId())).isZero();

            // 明确清除故障注入后再重试，避免同一Spring spy影响本例重试或后续用例。
            doCallRealMethod().when(audits).record(any(AuditLogEntry.class));
            ApplicationCatalogEntry retried = as(
                    fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                    () -> applications.createIdempotent(
                            fixture.projectId(), "audit-retry", "审计失败应用", draft("失败候选")));
            List<String> retriedFacts = facts(fixture.projectId());
            long retriedAuditCount = auditCount(fixture.projectId());
            List<String> retriedAuditActions = auditActions(fixture.projectId());
            List<UUID> retriedAuditTargets = auditTargets(fixture.projectId());

            assertSoftly(softly -> {
                softly.assertThat(retriedFacts).hasSize(3);
                softly.assertThat(retriedAuditCount).isOne();
                softly.assertThat(retriedAuditActions).containsExactly("application.created");
                softly.assertThat(retriedAuditTargets).containsExactly(retried.id());
            });
        } finally {
            doCallRealMethod().when(audits).record(any(AuditLogEntry.class));
        }
    }

    /** 同身份同键并发创建由advisory串行，双方恢复同一服务端身份且只提交一次聚合与审计。 */
    @Test
    void concurrentIdenticalCreationRequestsReturnOneStableIdentity() throws Exception {
        Fixture fixture = seed();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ApplicationCatalogEntry> first = executor.submit(
                    () -> createConcurrently(fixture, "same-key", "相同创建", "相同草稿", ready, start));
            Future<ApplicationCatalogEntry> second = executor.submit(
                    () -> createConcurrently(fixture, "same-key", "相同创建", "相同草稿", ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            ApplicationCatalogEntry firstResult = first.get(10, TimeUnit.SECONDS);
            ApplicationCatalogEntry secondResult = second.get(10, TimeUnit.SECONDS);
            List<String> persistedFacts = facts(fixture.projectId());
            long persistedMappings = creationResultCount(fixture.projectId());
            long persistedAudits = auditCount(fixture.projectId());
            List<String> persistedActions = auditActions(fixture.projectId());
            assertSoftly(softly -> {
                softly.assertThat(secondResult.id()).isEqualTo(firstResult.id());
                softly.assertThat(secondResult.appKey()).isEqualTo(firstResult.appKey());
                softly.assertThat(secondResult.createdAt()).isEqualTo(firstResult.createdAt());
                softly.assertThat(persistedFacts).hasSize(3);
                softly.assertThat(persistedMappings).isOne();
                softly.assertThat(persistedAudits).isOne();
                softly.assertThat(persistedActions).containsExactly("application.created");
            });
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** 同键异请求不得复用首个身份，且失败请求不增加目录、草稿、映射或审计。 */
    @Test
    void reusedCreationKeyWithDifferentRequestReturnsConflictWithoutDrift() throws Exception {
        Fixture fixture = seed();
        ApplicationCatalogEntry created = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> applications.createIdempotent(
                        fixture.projectId(), "conflict-key", "首个创建", draft("首个草稿")));

        Throwable failure = catchThrowable(() -> as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> applications.createIdempotent(
                        fixture.projectId(), "conflict-key", "另一创建", draft("另一草稿"))));
        List<String> persistedFacts = facts(fixture.projectId());
        long persistedMappings = creationResultCount(fixture.projectId());
        long persistedAudits = auditCount(fixture.projectId());
        List<UUID> persistedTargets = auditTargets(fixture.projectId());

        assertSoftly(softly -> {
            softly.assertThat(errorCode(failure)).isEqualTo(10009);
            softly.assertThat(persistedFacts).hasSize(3);
            softly.assertThat(persistedMappings).isOne();
            softly.assertThat(persistedAudits).isOne();
            softly.assertThat(persistedTargets).containsExactly(created.id());
        });
    }

    /** 受控软删持有目录锁时重放必须等待提交，并在新快照读取deletedAt后稳定返回10014。 */
    @Test
    void creationReplaySerializesBehindConcurrentControlledSoftDelete() throws Exception {
        Fixture fixture = seed();
        ApplicationCatalogEntry created = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> applications.createIdempotent(
                        fixture.projectId(), "deleted-key", "待删创建", draft("待删草稿")));
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (Connection deletion = DriverManager.getConnection(DATABASE_URL, APP_ROLE, APP_ROLE_PASSWORD)) {
            deletion.setAutoCommit(false);
            try (PreparedStatement scope = deletion.prepareStatement(
                    "SELECT set_config('app.project_id', ?, true)")) {
                scope.setString(1, fixture.projectId().toString());
                assertThat(scope.executeQuery().next()).isTrue();
            }
            try (PreparedStatement lock = deletion.prepareStatement(
                    "SELECT id FROM app_application WHERE project_id=? AND id=? FOR UPDATE")) {
                lock.setObject(1, fixture.projectId());
                lock.setObject(2, created.id());
                assertThat(lock.executeQuery().next()).isTrue();
            }

            Future<Throwable> replay = executor.submit(() -> catchThrowable(() -> as(
                    fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                    () -> applications.createIdempotent(
                            fixture.projectId(), "deleted-key", "待删创建", draft("待删草稿")))));
            awaitApplicationLockWait();
            try (PreparedStatement remove = deletion.prepareStatement("""
                    SELECT deletion_status
                      FROM application_soft_delete(?, ?, 0, ?, clock_timestamp())
                    """)) {
                remove.setObject(1, fixture.projectId());
                remove.setObject(2, created.id());
                remove.setObject(3, fixture.ownerId());
                try (ResultSet result = remove.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("DELETED");
                }
            }
            deletion.commit();

            assertThat(errorCode(replay.get(10, TimeUnit.SECONDS))).isEqualTo(10014);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(creationResultCount(fixture.projectId())).isOne();
        assertThat(auditCount(fixture.projectId())).isOne();
    }

    /** 创建恢复表必须具备完整注释、项目RLS、显式NO ACTION外键、清理索引与最小运行权限。 */
    @Test
    void creationResultMigrationEnforcesIsolationIntegrityAndLeastPrivilege() throws Exception {
        try (Connection owner = owner()) {
            assertThat(singleBoolean(owner, """
                    SELECT relrowsecurity
                      FROM pg_class
                     WHERE oid = 'app_application_creation_result'::regclass
                    """)).isTrue();
            assertThat(singleLong(owner, """
                    SELECT count(*)
                      FROM information_schema.columns column_info
                     WHERE column_info.table_schema = 'public'
                       AND column_info.table_name = 'app_application_creation_result'
                       AND col_description(
                           'public.app_application_creation_result'::regclass,
                           column_info.ordinal_position) IS NULL
                    """)).isZero();
            assertThat(singleBoolean(owner, """
                    SELECT obj_description(
                        'app_application_creation_result'::regclass, 'pg_class') IS NOT NULL
                    """)).isTrue();
            assertThat(singleLong(owner, """
                    SELECT count(*) FROM pg_policies
                     WHERE schemaname = 'public'
                       AND tablename = 'app_application_creation_result'
                       AND policyname = 'project_isolation'
                       AND qual LIKE '%app_current_project%'
                       AND with_check LIKE '%app_current_project%'
                    """)).isOne();
            assertThat(singleLong(owner, """
                    SELECT count(*) FROM pg_constraint
                     WHERE conrelid = 'app_application_creation_result'::regclass
                       AND conname = 'app_application_creation_result_application_fk'
                       AND confdeltype = 'a'
                    """)).isOne();
            assertThat(singleLong(owner, """
                    SELECT count(*) FROM pg_constraint
                     WHERE conrelid = 'app_application_creation_result'::regclass
                       AND conname = 'app_application_creation_result_application_uk'
                       AND contype = 'u'
                    """)).isOne();
            assertThat(singleLong(owner, """
                    SELECT count(*) FROM pg_indexes
                     WHERE schemaname = 'public'
                       AND tablename = 'app_application_creation_result'
                       AND indexname = 'app_application_creation_result_purge_scope_idx'
                       AND indexdef LIKE '%tenant_id, project_id, application_id, account_id, idempotency_key_digest%'
                    """)).isOne();
            assertThat(singleBoolean(owner, """
                    SELECT has_table_privilege(
                               'thingslink_app', 'app_application_creation_result', 'SELECT,INSERT')
                       AND NOT has_table_privilege(
                               'thingslink_app', 'app_application_creation_result', 'UPDATE')
                       AND NOT has_table_privilege(
                               'thingslink_app', 'app_application_creation_result', 'DELETE')
                       AND NOT has_table_privilege(
                               'thingslink_app', 'app_application_creation_result', 'TRUNCATE')
                    """)).isTrue();
        }
    }

    /** DASHBOARD清理先独立删除最多500条恢复映射，随后才处理应用父事实并完成十表复核。 */
    @Test
    void dashboardCleanupDeletesCreationResultsBeforeApplicationAggregate() throws Exception {
        Fixture fixture = seed();
        Fixture neighbor = seed();
        as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> applications.createIdempotent(
                        fixture.projectId(), "cleanup-key", "待清理应用", draft("待清理草稿")));
        as(neighbor.ownerTenantId(), neighbor.projectId(), neighbor.ownerId(),
                () -> applications.createIdempotent(
                        neighbor.projectId(), "neighbor-key", "邻居应用", draft("邻居草稿")));
        List<String> neighborBefore = facts(neighbor.projectId());
        UUID cleanupToken = Uuid7.generate();
        try (Connection owner = owner()) {
            execute(owner, """
                    WITH created AS (
                        INSERT INTO app_application(
                            id, tenant_id, project_id, app_key, management_name,
                            publication_revision, current_version_id, created_by, updated_by,
                            created_at, updated_at, deleted_at)
                        SELECT gen_random_uuid(), ?, ?, 'app_' || md5('cleanup-extra-' || sequence),
                               '清理夹具' || sequence, 0, NULL, ?, ?,
                               clock_timestamp(), clock_timestamp(), NULL
                          FROM generate_series(1, 500) sequence
                        RETURNING id, tenant_id, project_id
                    )
                    INSERT INTO app_application_creation_result(
                        tenant_id, project_id, account_id, idempotency_key_digest,
                        request_digest, application_id, created_at)
                    SELECT tenant_id, project_id, ?, encode(digest(id::text, 'sha256'), 'hex'),
                           repeat('a', 64), id, clock_timestamp()
                      FROM created
                    """, fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                    fixture.ownerId(), fixture.ownerId());
            execute(owner, """
                    UPDATE sys_project
                       SET status='PURGING', lifecycle_generation=1,
                           deleted_at=clock_timestamp() - interval '31 days',
                           cleanup_stage='DASHBOARD',
                           cleanup_started_at=clock_timestamp() - interval '60 seconds',
                           cleanup_next_attempt_at=clock_timestamp() - interval '1 second',
                           cleanup_lease_token=?, cleanup_lease_until=clock_timestamp() + interval '10 minutes'
                     WHERE id=?
                    """, cleanupToken, fixture.projectId());
        }

        CleanupOutcome first = cleanup(fixture, cleanupToken);
        long firstMappingCount = creationResultCount(fixture.projectId());
        long firstApplicationCount = tableCount("app_application", fixture.projectId());
        long firstDraftCount = tableCount("app_application_draft", fixture.projectId());
        List<String> firstNeighborFacts = facts(neighbor.projectId());
        assertSoftly(softly -> {
            softly.assertThat(first).isEqualTo(new CleanupOutcome(500, false, null));
            softly.assertThat(firstMappingCount).isOne();
            softly.assertThat(firstApplicationCount).isEqualTo(501);
            softly.assertThat(firstDraftCount).isOne();
            softly.assertThat(firstNeighborFacts).isEqualTo(neighborBefore);
        });

        CleanupOutcome second = cleanup(fixture, cleanupToken);
        assertThat(second).isEqualTo(new CleanupOutcome(1, false, null));
        assertThat(creationResultCount(fixture.projectId())).isZero();
        assertThat(tableCount("app_application", fixture.projectId())).isEqualTo(501);
        assertThat(facts(neighbor.projectId())).isEqualTo(neighborBefore);

        CleanupOutcome result = second;
        for (int attempt = 0; attempt < 10 && !result.complete(); attempt++) {
            result = cleanup(fixture, cleanupToken);
            assertThat(result.deletedRows()).isBetween(0, 500);
            assertThat(result.blockedReason()).isNull();
        }
        assertThat(result).isEqualTo(new CleanupOutcome(0, true, null));
        assertThat(dashboardFactCount(fixture.projectId())).isZero();
        assertThat(facts(neighbor.projectId())).isEqualTo(neighborBefore);
    }

    /** OPERATOR与VIEWER可读目录/草稿，但保存均稳定60031且业务与审计事实零漂移。 */
    @ParameterizedTest
    @EnumSource(ReadOnlyMember.class)
    void readOnlyMembersCanReadButCannotSaveDraft(ReadOnlyMember member) throws Exception {
        Fixture fixture = seed();
        ApplicationCatalogEntry application = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> applications.create(fixture.projectId(), "只读角色观察", draft("只读前")));
        List<String> before = facts(fixture.projectId());
        long auditsBefore = auditCount(fixture.projectId());

        ApplicationCatalogEntry visible = as(
                fixture.collaboratorTenantId(), fixture.projectId(), member.accountId(fixture),
                () -> applications.find(fixture.projectId(), application.id()));
        ApplicationDraft readableDraft = as(
                fixture.collaboratorTenantId(), fixture.projectId(), member.accountId(fixture),
                () -> applications.getDraft(fixture.projectId(), application.id()));
        Throwable failure = catchThrowable(() -> as(
                fixture.collaboratorTenantId(), fixture.projectId(), member.accountId(fixture),
                () -> applications.saveDraft(fixture.projectId(), application.id(), "0", draft("越权保存"))));
        List<String> after = facts(fixture.projectId());
        long auditsAfter = auditCount(fixture.projectId());

        assertSoftly(softly -> {
            softly.assertThat(visible.id()).isEqualTo(application.id());
            softly.assertThat(readableDraft.revision()).isZero();
            softly.assertThat(errorCode(failure)).isEqualTo(60031);
            softly.assertThat(after).isEqualTo(before);
            softly.assertThat(auditsAfter).isEqualTo(auditsBefore);
        });
    }

    /** ARCHIVED项目保留四角色管理读取，但OWNER保存稳定50017且不产生业务或审计漂移。 */
    @Test
    void archivedProjectRemainsReadableAndRejectsWrites() throws Exception {
        Fixture fixture = seed();
        ApplicationCatalogEntry application = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> applications.create(fixture.projectId(), "归档前应用", draft("归档前")));
        archive(fixture.projectId());
        List<String> before = facts(fixture.projectId());
        long auditsBefore = auditCount(fixture.projectId());

        ApplicationDraft readable = as(
                fixture.collaboratorTenantId(), fixture.projectId(), fixture.viewerId(),
                () -> applications.getDraft(fixture.projectId(), application.id()));
        Throwable failure = catchThrowable(() -> as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> applications.saveDraft(fixture.projectId(), application.id(), "0", draft("归档后写入"))));
        List<String> after = facts(fixture.projectId());
        long auditsAfter = auditCount(fixture.projectId());

        assertSoftly(softly -> {
            softly.assertThat(readable.applicationId()).isEqualTo(application.id());
            softly.assertThat(readable.revision()).isZero();
            softly.assertThat(errorCode(failure)).isEqualTo(50017);
            softly.assertThat(after).isEqualTo(before);
            softly.assertThat(auditsAfter).isEqualTo(auditsBefore);
        });
    }

    /** 两个相同旧revision的真实事务同时保存时仅一方提交，失败方稳定60033且没有假审计。 */
    @Test
    void concurrentDraftSavesCommitOneWinnerAndOneStableConflict() throws Exception {
        Fixture fixture = seed();
        ApplicationCatalogEntry application = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> applications.create(fixture.projectId(), "并发草稿", draft("初始")));
        long auditsBefore = auditCount(fixture.projectId());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<SaveOutcome> first = executor.submit(() -> saveConcurrently(
                    fixture, application.id(), "候选甲", ready, start));
            Future<SaveOutcome> second = executor.submit(() -> saveConcurrently(
                    fixture, application.id(), "候选乙", ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<SaveOutcome> outcomes = List.of(
                    first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            ApplicationDraft winner = outcomes.stream()
                    .filter(SaveOutcome::success)
                    .map(SaveOutcome::draft)
                    .findFirst()
                    .orElseThrow();
            ApplicationDraft persisted = as(
                    fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                    () -> applications.getDraft(fixture.projectId(), application.id()));
            long auditsAfter = auditCount(fixture.projectId());

            assertSoftly(softly -> {
                softly.assertThat(outcomes).filteredOn(SaveOutcome::success).hasSize(1);
                softly.assertThat(outcomes).filteredOn(outcome -> !outcome.success()).hasSize(1);
                softly.assertThat(outcomes).filteredOn(outcome -> !outcome.success())
                        .extracting(SaveOutcome::errorCode).containsExactly(60033);
                softly.assertThat(winner.revision()).isOne();
                softly.assertThat(persisted.revision()).isOne();
                softly.assertThat(persisted.content()).isEqualTo(winner.content());
                softly.assertThat(auditsAfter).isEqualTo(auditsBefore + 1);
            });
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** 同步起跑两个真实服务事务，并把业务异常压缩为可比较结果。 */
    private SaveOutcome saveConcurrently(
            Fixture fixture, UUID applicationId, String displayName,
            CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("并发保存未按时放行");
            }
            ApplicationDraft saved = as(
                    fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                    () -> applications.saveDraft(fixture.projectId(), applicationId, "0", draft(displayName)));
            return SaveOutcome.saved(saved);
        } catch (Throwable failure) {
            return SaveOutcome.failed(errorCode(failure));
        }
    }

    /** 同步起跑真实应用创建事务；advisory lock必须在服务内部完成串行。 */
    private ApplicationCatalogEntry createConcurrently(
            Fixture fixture,
            String idempotencyKey,
            String managementName,
            String displayName,
            CountDownLatch ready,
            CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("并发创建未按时放行");
        }
        return as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> applications.createIdempotent(
                        fixture.projectId(), idempotencyKey, managementName, draft(displayName)));
    }

    /** 等待重放事务真实阻塞于应用目录行锁，避免用固定睡眠猜测并发时序。 */
    private void awaitApplicationLockWait() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            try (Connection owner = owner(); PreparedStatement query = owner.prepareStatement("""
                    SELECT EXISTS (
                        SELECT 1 FROM pg_stat_activity
                         WHERE datname = current_database()
                           AND usename = 'thingslink_app'
                           AND state = 'active'
                           AND wait_event_type = 'Lock'
                           AND query LIKE '%FROM app_application%'
                           AND query LIKE '%FOR UPDATE%')
                    """)) {
                query.setQueryTimeout(5);
                try (ResultSet result = query.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    if (result.getBoolean(1)) {
                        return;
                    }
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("应用创建重放未阻塞于并发软删目录锁");
    }

    /** 创建真实项目owner、跨租户ADMIN及两个只读角色。 */
    private Fixture seed() throws SQLException {
        Fixture fixture = new Fixture(
                Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                Uuid7.generate(), Uuid7.generate(), Uuid7.generate());
        try (Connection owner = owner()) {
            owner.setAutoCommit(false);
            execute(owner, "INSERT INTO sys_tenant(id,name) VALUES (?, '应用OWNER租户'), (?, '应用协作者租户')",
                    fixture.ownerTenantId(), fixture.collaboratorTenantId());
            execute(owner, "INSERT INTO sys_account(id,email,password_hash,display_name) VALUES "
                            + "(?,?,'{noop}unused','应用OWNER'),(?,?,'{noop}unused','应用ADMIN'),"
                            + "(?,?,'{noop}unused','应用OPERATOR'),(?,?,'{noop}unused','应用VIEWER')",
                    fixture.ownerId(), fixture.ownerId() + "@example.com",
                    fixture.adminId(), fixture.adminId() + "@example.com",
                    fixture.operatorId(), fixture.operatorId() + "@example.com",
                    fixture.viewerId(), fixture.viewerId() + "@example.com");
            execute(owner, "INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES "
                            + "(?,?,?),(?,?,?),(?,?,?),(?,?,?)",
                    Uuid7.generate(), fixture.ownerTenantId(), fixture.ownerId(),
                    Uuid7.generate(), fixture.collaboratorTenantId(), fixture.adminId(),
                    Uuid7.generate(), fixture.collaboratorTenantId(), fixture.operatorId(),
                    Uuid7.generate(), fixture.collaboratorTenantId(), fixture.viewerId());
            execute(owner, "INSERT INTO sys_project(id,tenant_id,name,region,project_key) "
                            + "VALUES (?,?,'应用管理项目','sh-1',?)",
                    fixture.projectId(), fixture.ownerTenantId(), "app_management_" + compact(fixture.projectId()));
            execute(owner, "INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES "
                            + "(?,?,?,'OWNER'),(?,?,?,'ADMIN'),(?,?,?,'OPERATOR'),(?,?,?,'VIEWER')",
                    Uuid7.generate(), fixture.projectId(), fixture.ownerId(),
                    Uuid7.generate(), fixture.projectId(), fixture.adminId(),
                    Uuid7.generate(), fixture.projectId(), fixture.operatorId(),
                    Uuid7.generate(), fixture.projectId(), fixture.viewerId());
            owner.commit();
        }
        return fixture;
    }

    /** 将项目独立提交为ARCHIVED，成员与应用事实保持不变。 */
    private void archive(UUID projectId) throws SQLException {
        try (Connection owner = owner()) {
            execute(owner, "UPDATE sys_project SET status='ARCHIVED' WHERE id=?", projectId);
        }
    }

    /** 返回目录与草稿的完整稳定快照，拒绝路径不能改变任意审计字段或JSON内容。 */
    private List<String> facts(UUID projectId) throws SQLException {
        List<String> facts = new ArrayList<>();
        try (Connection owner = owner()) {
            for (String table : List.of(
                    "app_application", "app_application_draft", "app_application_creation_result")) {
                try (PreparedStatement query = owner.prepareStatement(
                        "SELECT row_to_json(row_data)::text FROM (SELECT * FROM " + table
                                + " WHERE project_id=? ORDER BY 1) row_data")) {
                    query.setQueryTimeout(5);
                    query.setObject(1, projectId);
                    try (ResultSet rows = query.executeQuery()) {
                        while (rows.next()) {
                            facts.add(table + ':' + rows.getString(1));
                        }
                    }
                }
            }
        }
        return facts;
    }

    /** 查询项目内域级创建恢复映射数量，重放与冲突均不得增加第二行。 */
    private long creationResultCount(UUID projectId) throws SQLException {
        try (Connection owner = owner(); PreparedStatement query = owner.prepareStatement(
                "SELECT count(*) FROM app_application_creation_result WHERE project_id=?")) {
            query.setQueryTimeout(5);
            query.setObject(1, projectId);
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        }
    }

    /**
     * 汇总DASHBOARD清理函数声明负责的十张项目事实表。
     * 这里显式列出表名，避免新增事实遗漏在最终残留证据之外后仍误判清理完成。
     */
    private long dashboardFactCount(UUID projectId) throws SQLException {
        try (Connection owner = owner(); PreparedStatement query = owner.prepareStatement("""
                SELECT sum(fact_count)
                  FROM (
                        SELECT count(*) AS fact_count FROM app_application_creation_result WHERE project_id=?
                        UNION ALL SELECT count(*) FROM app_application WHERE project_id=?
                        UNION ALL SELECT count(*) FROM app_application_draft WHERE project_id=?
                        UNION ALL SELECT count(*) FROM app_application_version_dashboard_ref WHERE project_id=?
                        UNION ALL SELECT count(*) FROM app_application_version WHERE project_id=?
                        UNION ALL SELECT count(*) FROM dash_dashboard WHERE project_id=?
                        UNION ALL SELECT count(*) FROM dash_dashboard_draft_model_ref WHERE project_id=?
                        UNION ALL SELECT count(*) FROM dash_dashboard_draft WHERE project_id=?
                        UNION ALL SELECT count(*) FROM dash_dashboard_version_model_ref WHERE project_id=?
                        UNION ALL SELECT count(*) FROM dash_dashboard_version WHERE project_id=?
                       ) facts
                """)) {
            query.setQueryTimeout(5);
            for (int parameter = 1; parameter <= 10; parameter++) {
                query.setObject(parameter, projectId);
            }
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        }
    }

    /** 只允许本测试声明的应用父子表进入动态表名计数，避免辅助SQL扩大注入面。 */
    private long tableCount(String table, UUID projectId) throws SQLException {
        assertThat(table).isIn("app_application", "app_application_draft");
        try (Connection owner = owner(); PreparedStatement query = owner.prepareStatement(
                "SELECT count(*) FROM " + table + " WHERE project_id=?")) {
            query.setQueryTimeout(5);
            query.setObject(1, projectId);
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        }
    }

    /** 用普通运行身份和真实项目RLS调用一次DASHBOARD有界清理。 */
    private CleanupOutcome cleanup(Fixture fixture, UUID cleanupToken) throws SQLException {
        try (Connection application = DriverManager.getConnection(DATABASE_URL, APP_ROLE, APP_ROLE_PASSWORD)) {
            application.setAutoCommit(false);
            try (PreparedStatement scope = application.prepareStatement(
                    "SELECT set_config('app.project_id', ?, true)")) {
                scope.setString(1, fixture.projectId().toString());
                assertThat(scope.executeQuery().next()).isTrue();
            }
            CleanupOutcome outcome;
            try (PreparedStatement query = application.prepareStatement("""
                    SELECT deleted_rows, complete, blocked_reason
                      FROM dashboard_project_cleanup_batch(?, ?, 1, ?)
                    """)) {
                query.setQueryTimeout(5);
                query.setObject(1, fixture.ownerTenantId());
                query.setObject(2, fixture.projectId());
                query.setObject(3, cleanupToken);
                try (ResultSet result = query.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    outcome = new CleanupOutcome(
                            result.getInt("deleted_rows"), result.getBoolean("complete"),
                            result.getString("blocked_reason"));
                    assertThat(result.next()).isFalse();
                }
            }
            application.commit();
            return outcome;
        }
    }

    /** 执行无参数单布尔目录查询。 */
    private static boolean singleBoolean(Connection connection, String sql) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setQueryTimeout(5);
            try (ResultSet result = query.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getBoolean(1);
            }
        }
    }

    /** 执行无参数单Long目录查询。 */
    private static long singleLong(Connection connection, String sql) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setQueryTimeout(5);
            try (ResultSet result = query.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        }
    }

    /** 查询目录与草稿落库tenant，不能只相信服务返回对象。 */
    private List<UUID> persistedTenants(UUID projectId) throws SQLException {
        List<UUID> tenants = new ArrayList<>();
        try (Connection owner = owner(); PreparedStatement query = owner.prepareStatement("""
                SELECT tenant_id FROM app_application WHERE project_id=?
                UNION ALL
                SELECT tenant_id FROM app_application_draft WHERE project_id=?
                """)) {
            query.setQueryTimeout(5);
            query.setObject(1, projectId);
            query.setObject(2, projectId);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    tenants.add(rows.getObject(1, UUID.class));
                }
            }
        }
        return tenants;
    }

    /** 查询本项目不可变审计数量。 */
    private long auditCount(UUID projectId) throws SQLException {
        try (Connection owner = owner(); PreparedStatement query = owner.prepareStatement(
                "SELECT count(*) FROM sys_audit_log WHERE project_id=?")) {
            query.setQueryTimeout(5);
            query.setObject(1, projectId);
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        }
    }

    /** 查询审计归属tenant，跨租户操作者不能把审计写入自己的tenant。 */
    private List<UUID> auditTenants(UUID projectId) throws SQLException {
        return auditColumn(projectId, "tenant_id");
    }

    /** 查询审计行为人，OWNER与跨租户ADMIN都应保留真实账号。 */
    private List<UUID> auditActors(UUID projectId) throws SQLException {
        return auditColumn(projectId, "actor_account_id");
    }

    /** 查询审计动作，确保每个成功管理用例只记录自己的稳定动作。 */
    private List<String> auditActions(UUID projectId) throws SQLException {
        List<String> actions = new ArrayList<>();
        try (Connection owner = owner(); PreparedStatement query = owner.prepareStatement(
                "SELECT action FROM sys_audit_log WHERE project_id=? ORDER BY created_at,id")) {
            query.setQueryTimeout(5);
            query.setObject(1, projectId);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    actions.add(rows.getString(1));
                }
            }
        }
        return actions;
    }

    /** 查询审计目标，创建、改名和草稿保存必须绑定实际应用ID。 */
    private List<UUID> auditTargets(UUID projectId) throws SQLException {
        return auditColumn(projectId, "target_id");
    }

    /** 从固定白名单审计列读取UUID，列名只由本类两个封闭调用点提供。 */
    private List<UUID> auditColumn(UUID projectId, String column) throws SQLException {
        assertThat(column).isIn("tenant_id", "actor_account_id", "target_id");
        List<UUID> values = new ArrayList<>();
        try (Connection owner = owner(); PreparedStatement query = owner.prepareStatement(
                "SELECT " + column + " FROM sys_audit_log WHERE project_id=? ORDER BY created_at,id")) {
            query.setQueryTimeout(5);
            query.setObject(1, projectId);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    values.add(rows.getObject(1, UUID.class));
                }
            }
        }
        return values;
    }

    /** 建立符合tc.application/v1冻结合同的独立草稿原文。 */
    private static byte[] draft(String displayName) {
        return ("""
                {"formatVersion":"tc.application/v1","displayName":"%s",\
                "hostCompatibility":{"minInclusive":"1.0.0","maxExclusive":"2.0.0"},\
                "dashboardRefs":[],"entryDashboardId":null}
                """).formatted(displayName).getBytes(StandardCharsets.UTF_8);
    }

    /** 真实控制台范围调用后始终清空租户与RLS线程上下文。 */
    private static <T> T as(
            UUID tenantId, UUID projectId, UUID accountId,
            java.util.concurrent.Callable<T> action) throws Exception {
        TenantContext.set(new TenantScope(tenantId, projectId, accountId));
        try {
            return action.call();
        } finally {
            TenantContext.clear();
            RlsScopeContext.clear();
        }
    }

    /** 业务异常公开码；基础设施失败或没有异常均返回空。 */
    private static Integer errorCode(Throwable failure) {
        return failure instanceof BusinessException business ? business.errorCode().code() : null;
    }

    /** UUID去除连字符后用于项目公开定位夹具。 */
    private static String compact(UUID value) {
        return value.toString().replace("-", "");
    }

    /** owner只准备夹具、生命周期状态并观察已提交事实。 */
    private Connection owner() throws SQLException {
        return DriverManager.getConnection(
                DATABASE_URL, APPLICATION_POSTGRES.getUsername(), APPLICATION_POSTGRES.getPassword());
    }

    /** 所有测试SQL使用五秒语句预算。 */
    private static void execute(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            statement.executeUpdate();
        }
    }

    /** 静态启动必须早于动态属性注册。 */
    private static String startDatabase() {
        APPLICATION_POSTGRES.start();
        return APPLICATION_POSTGRES.getJdbcUrl();
    }

    /** 独占容器由OwnedTestContainers在本类上下文物理关闭后回收；测试只需清理线程范围。 */
    @AfterEach
    void clearContext() {
        TenantContext.clear();
        RlsScopeContext.clear();
    }

    /** 专库禁用无关全表领取，保留真实应用、项目、草稿校验、仓储和审计服务。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class IsolatedDatabaseConfiguration {
        /** 数据源和Flyway统一使用专库，并关闭消息/通知后台入口。 */
        @Bean
        DynamicPropertyRegistrar isolatedDatabaseProperties() {
            return registry -> {
                registry.add("spring.datasource.url", () -> DATABASE_URL);
                registry.add("spring.flyway.url", () -> DATABASE_URL);
                registry.add("things-link.outbox.publisher.enabled", () -> "false");
                registry.add("spring.kafka.listener.auto-startup", () -> "false");
                registry.add("things-link.notification.retry.enabled", () -> "false");
            };
        }
    }

    /** 允许读取但不允许管理应用的两个项目角色。 */
    private enum ReadOnlyMember {
        /** 运行操作员。 */
        OPERATOR {
            /** {@inheritDoc} */
            @Override UUID accountId(Fixture fixture) {
                return fixture.operatorId();
            }
        },
        /** 只读访客。 */
        VIEWER {
            /** {@inheritDoc} */
            @Override UUID accountId(Fixture fixture) {
                return fixture.viewerId();
            }
        };

        /** @return 当前角色对应的真实项目成员账号 */
        abstract UUID accountId(Fixture fixture);
    }

    /** 两个租户、一个项目和四个项目角色的真实身份夹具。 */
    private record Fixture(
            UUID ownerTenantId,
            UUID collaboratorTenantId,
            UUID projectId,
            UUID ownerId,
            UUID adminId,
            UUID operatorId,
            UUID viewerId) {
    }

    /** 并发保存的成功草稿或稳定业务错误码。 */
    private record SaveOutcome(ApplicationDraft draft, Integer errorCode) {
        /** @return 携带成功草稿的结果 */
        static SaveOutcome saved(ApplicationDraft draft) {
            return new SaveOutcome(draft, null);
        }

        /** @return 携带稳定错误码的失败结果 */
        static SaveOutcome failed(Integer errorCode) {
            return new SaveOutcome(null, errorCode);
        }

        /** @return 是否为成功提交 */
        boolean success() {
            return draft != null;
        }

        /** @return 成功草稿内容；失败结果为空 */
        tools.jackson.databind.JsonNode content() {
            return draft == null ? null : draft.content();
        }
    }

    /** DASHBOARD受控清理一次调用的封闭返回值。 */
    private record CleanupOutcome(int deletedRows, boolean complete, String blockedReason) {
    }

    /** 专用于证明审计INSERT之后异常仍由外层应用事务统一回滚。 */
    private static final class AuditFailureAfterInsertException extends RuntimeException {
        /** 用固定消息标识该故障来自验收注入而非真实审计设施。 */
        private AuditFailureAfterInsertException() {
            super("应用审计已写入后注入失败");
        }
    }
}
