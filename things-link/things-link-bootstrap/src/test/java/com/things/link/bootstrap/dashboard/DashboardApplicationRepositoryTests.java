package com.things.link.bootstrap.dashboard;

import com.things.link.dashboard.domain.ApplicationCatalogEntry;
import com.things.link.dashboard.domain.ApplicationDraft;
import com.things.link.dashboard.domain.ApplicationDraftSaveResult;
import com.things.link.dashboard.domain.ApplicationKeyCollisionException;
import com.things.link.dashboard.domain.ApplicationPublicationState;
import com.things.link.dashboard.domain.ApplicationRepository;
import com.things.link.dashboard.domain.ApplicationSoftDeleteResult;
import com.things.link.dashboard.domain.ApplicationVersion;
import com.things.link.dashboard.infrastructure.persistence.JdbcApplicationRepository;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.page.Cursor;
import com.things.link.shared.page.CursorPage;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S12-1a1b应用目录、草稿与只读版本投影及S12-1c5受控软删仓储的真实PostgreSQL验收。
 *
 * <p>本类验证运行角色、项目RLS及实际JDBC映射，不启动Spring容器。应用版本仍由owner准备只追加历史；
 * 软删只经最终受控函数推进发布轴，并与草稿保存共享应用目录锁。</p>
 */
@Testcontainers
@DisplayName("Dashboard应用持久仓储")
class DashboardApplicationRepositoryTests {

    /** 与生产迁移验收相同的真实PostgreSQL/TimescaleDB镜像。 */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("dashboard_application_repository")
            .withUsername("thingslink")
            .withPassword("thingslink");

    /** Bootstrap登记的全部迁移位置，仓储测试不能用手建简化表代替真实约束。 */
    private static final String[] MIGRATION_LOCATIONS = {
            "classpath:db/migration/support", "classpath:db/migration/project",
            "classpath:db/migration/device", "classpath:db/migration/telemetry",
            "classpath:db/migration/alarm", "classpath:db/migration/task",
            "classpath:db/migration/rule", "classpath:db/migration/iam",
            "classpath:db/migration/enduser", "classpath:db/migration/export",
            "classpath:db/migration/dashboard", "classpath:db/migration/ota"
    };

    /** S12-1c5受控软删取代遗留直接更新，因此仓储验收迁移到该安全入口。 */
    private static final String MIGRATION_TARGET = "20260906.0210";

    /** JSON树用于构造精确的草稿与版本内容。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 迁移owner只准备身份、版本历史和观察最终数据库事实。 */
    private static JdbcTemplate owner;

    /** 普通生产角色连接，所有被测仓储调用都使用本连接。 */
    private JdbcTemplate application;

    /** 被测单一应用聚合持久端口。 */
    private ApplicationRepository repository;

    /** RLS上下文必须和仓储调用位于同一个真实物理事务。 */
    private TransactionTemplate transactions;

    /**
     * 执行完整生产迁移并建立普通运行角色，避免H2或owner掩盖RLS与权限问题。
     */
    @BeforeAll
    static void migrate() {
        owner = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(MIGRATION_LOCATIONS)
                .placeholders(Map.of("app_role_password", "thingslink"))
                .target(MIGRATION_TARGET)
                .load()
                .migrate();
    }

    /**
     * 每例清空独占应用事实与项目身份，并重新创建真实运行角色仓储。
     */
    @BeforeEach
    void resetDatabase() {
        owner.update("UPDATE app_application SET current_version_id = NULL");
        owner.update("DELETE FROM app_application_draft");
        owner.update("DELETE FROM app_application_version");
        owner.update("DELETE FROM app_application");
        owner.update("DELETE FROM sys_project_member");
        owner.update("DELETE FROM sys_project");
        owner.update("DELETE FROM sys_tenant_member");
        owner.update("DELETE FROM sys_tenant");
        owner.update("DELETE FROM sys_account");

        DriverManagerDataSource source = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "thingslink_app", "thingslink");
        application = new JdbcTemplate(source);
        repository = new JdbcApplicationRepository(application);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    /**
     * 目录与初始草稿必须原子创建；查询、改名和软删只作用于当前项目并保留稳定appKey。
     */
    @Test
    @DisplayName("目录原子创建、改名与软删遵守项目范围")
    void catalogLifecycleUsesCurrentProjectScopeAndRetainsStableIdentity() {
        Fixture first = fixture(null);
        Fixture neighbor = fixture(first.tenantId());
        Instant createdAt = Instant.parse("2026-09-06T09:10:00.123456Z");
        UUID firstId = UUID.randomUUID();
        UUID neighborId = UUID.randomUUID();
        ApplicationCatalogEntry firstEntry = catalog(first, firstId, "生产监控", createdAt);
        ApplicationDraft firstDraft = draft(first, firstId, 0, 1, createdAt);

        inProject(first, () -> {
            repository.create(firstEntry, firstDraft);
            return null;
        });
        assertThatThrownBy(() -> inProject(first, () -> {
            repository.create(catalog(neighbor, neighborId, "越界创建", createdAt),
                    draft(neighbor, neighborId, 0, 2, createdAt));
            return null;
        })).isInstanceOf(DataAccessException.class);
        assertThat(owner.queryForObject(
                "SELECT count(*) FROM app_application WHERE id = ?", Long.class, neighborId)).isZero();
        inProject(neighbor, () -> {
            repository.create(catalog(neighbor, neighborId, "邻居应用", createdAt),
                    draft(neighbor, neighborId, 0, 2, createdAt));
            return null;
        });

        assertThat(inProject(first, () -> repository.find(first.projectId(), firstId)))
                .contains(firstEntry);
        assertThat(inProject(first, () -> repository.find(neighbor.projectId(), neighborId)))
                .isEmpty();
        assertThat(inProject(first, () -> repository.rename(
                neighbor.projectId(), neighborId, "越界改名", first.accountId(), createdAt.plusSeconds(1))))
                .isFalse();
        assertThat(inProject(first, () -> repository.rename(
                first.projectId(), firstId, "现场总览", first.accountId(), createdAt.plusSeconds(2))))
                .isTrue();

        ApplicationCatalogEntry renamed = inProject(first,
                () -> repository.find(first.projectId(), firstId)).orElseThrow();
        assertThat(renamed.managementName()).isEqualTo("现场总览");
        assertThat(renamed.appKey()).isEqualTo(firstEntry.appKey());
        assertThat(renamed.currentVersionId()).isNull();
        assertThat(renamed.publicationRevision()).isZero();

        assertThat(inProject(first, () -> repository.softDelete(
                first.projectId(), firstId, 0, first.accountId(), createdAt.plusSeconds(3))))
                .satisfies(result -> {
                    assertThat(result.status()).isEqualTo(ApplicationSoftDeleteResult.Status.DELETED);
                    assertThat(result.deletedPreviousVersionId()).isEmpty();
                    assertThat(result.observedPublicationRevision()).contains(1L);
                    assertThat(result.observedDeletedAt()).contains(createdAt.plusSeconds(3));
                });
        assertThat(inProject(first, () -> repository.rename(
                first.projectId(), firstId, "删除后改名", first.accountId(), createdAt.plusSeconds(4))))
                .isFalse();
        assertThat(inProject(first, () -> repository.softDelete(
                first.projectId(), firstId, 1, first.accountId(), createdAt.plusSeconds(5))).status())
                .isEqualTo(ApplicationSoftDeleteResult.Status.NOT_FOUND);
        assertThat(inProject(first, () -> repository.saveDraft(
                first.projectId(), firstId, 0, content(3), first.accountId(), createdAt.plusSeconds(6))))
                .extracting(ApplicationDraftSaveResult::status)
                .isEqualTo(ApplicationDraftSaveResult.Status.NOT_FOUND);
        assertThat(inProject(first, () -> repository.find(first.projectId(), firstId))).isEmpty();
        assertThat(inProject(first, () -> repository.findDraft(first.projectId(), firstId))).isEmpty();
        ApplicationPublicationState deleted = inProject(first,
                () -> repository.findPublicationState(first.projectId(), firstId)).orElseThrow();
        assertThat(deleted.deletedAt()).isEqualTo(createdAt.plusSeconds(3));
        assertThat(deleted.currentVersionId()).isNull();
        assertThat(owner.queryForObject(
                "SELECT revision FROM app_application_draft WHERE application_id = ?",
                Long.class, firstId)).isZero();
        assertThat(owner.queryForObject(
                "SELECT count(*) FROM app_application WHERE id = ? AND app_key = ?",
                Long.class, firstId, firstEntry.appKey())).isOne();
        assertThat(inProject(neighbor, () -> repository.find(neighbor.projectId(), neighborId)))
                .isPresent();
    }

    /**
     * 创建目录后的草稿读取与CAS保存必须返回新revision；陈旧revision失败且不能覆盖成功内容。
     */
    @Test
    @DisplayName("草稿保存以revision执行精确CAS")
    void draftSaveRejectsStaleRevisionWithoutChangingSuccessfulContent() {
        Fixture fixture = fixture(null);
        Fixture neighbor = fixture(fixture.tenantId());
        Instant createdAt = Instant.parse("2026-09-06T09:20:00.234567Z");
        UUID applicationId = UUID.randomUUID();
        ApplicationDraft initial = draft(fixture, applicationId, 0, 1, createdAt);
        inProject(fixture, () -> {
            repository.create(catalog(fixture, applicationId, "草稿CAS", createdAt), initial);
            return null;
        });

        assertThat(inProject(fixture, () -> repository.findDraft(
                fixture.projectId(), applicationId))).contains(initial);
        assertThat(inProject(neighbor, () -> repository.findDraft(
                fixture.projectId(), applicationId))).isEmpty();
        ApplicationDraftSaveResult missing = inProject(fixture, () -> repository.saveDraft(
                fixture.projectId(), UUID.randomUUID(), 0, content(9),
                fixture.accountId(), createdAt));
        assertThat(missing.status()).isEqualTo(ApplicationDraftSaveResult.Status.NOT_FOUND);
        assertThat(missing.savedDraft()).isEmpty();

        ObjectNode savedContent = (ObjectNode) content(2);
        JsonNode expectedContent = savedContent.deepCopy();
        ApplicationDraftSaveResult saved = inProject(fixture, () -> repository.saveDraft(
                fixture.projectId(), applicationId, 0, savedContent,
                fixture.accountId(), createdAt.plusSeconds(1)));
        assertThat(saved.status()).isEqualTo(ApplicationDraftSaveResult.Status.SAVED);
        assertThat(saved.savedDraft().orElseThrow().revision()).isEqualTo(1);
        assertThat(saved.savedDraft().orElseThrow().content()).isEqualTo(expectedContent);

        // 调用方修改输入或返回副本都不能改变同一个revision对应的持久事实。
        savedContent.put("displayName", "调用方改写输入");
        ((ObjectNode) saved.savedDraft().orElseThrow().content()).put(
                "displayName", "调用方改写返回副本");

        ApplicationDraftSaveResult conflict = inProject(fixture, () -> repository.saveDraft(
                fixture.projectId(), applicationId, 0, content(3),
                fixture.accountId(), createdAt.plusSeconds(2)));
        assertThat(conflict.status()).isEqualTo(ApplicationDraftSaveResult.Status.REVISION_CONFLICT);
        assertThat(conflict.savedDraft()).isEmpty();
        ApplicationDraft afterConflict = inProject(fixture, () -> repository.findDraft(
                fixture.projectId(), applicationId)).orElseThrow();
        assertThat(afterConflict.revision()).isEqualTo(1);
        assertThat(afterConflict.content()).isEqualTo(expectedContent);
        assertThat(afterConflict.updatedAt()).isEqualTo(createdAt.plusSeconds(1));

        // 更新守卫正确禁止跳号；迁移owner重建一条合法极限事实以覆盖再也无法推进的真实数据库状态。
        owner.update("DELETE FROM app_application_draft WHERE application_id = ?", applicationId);
        owner.update("""
                INSERT INTO app_application_draft(
                    application_id, tenant_id, project_id, content, revision,
                    updated_by, created_at, updated_at)
                VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                """, applicationId, fixture.tenantId(), fixture.projectId(), expectedContent.toString(),
                Long.MAX_VALUE, fixture.accountId(), Timestamp.from(createdAt),
                Timestamp.from(createdAt.plusSeconds(1)));
        ApplicationDraftSaveResult exhausted = inProject(fixture, () -> repository.saveDraft(
                fixture.projectId(), applicationId, Long.MAX_VALUE, content(4),
                fixture.accountId(), createdAt.plusSeconds(3)));
        assertThat(exhausted.status()).isEqualTo(ApplicationDraftSaveResult.Status.REVISION_EXHAUSTED);
        assertThat(exhausted.savedDraft()).isEmpty();
        assertThat(owner.queryForObject(
                "SELECT revision FROM app_application_draft WHERE application_id = ?",
                Long.class, applicationId)).isEqualTo(Long.MAX_VALUE);

        owner.update("DELETE FROM app_application_draft WHERE application_id = ?", applicationId);
        assertThatThrownBy(() -> inProject(fixture, () -> repository.saveDraft(
                fixture.projectId(), applicationId, Long.MAX_VALUE, content(5),
                fixture.accountId(), createdAt.plusSeconds(4))))
                .isInstanceOf(DataAccessException.class);
    }

    /**
     * 两个真实连接用相同revision竞争时只能一个成功，证明CAS不是进程内先查后写。
     */
    @Test
    @DisplayName("并发草稿保存只接受一个相同revision")
    void concurrentDraftSavesAcceptExactlyOneExpectedRevision() throws Exception {
        Fixture fixture = fixture(null);
        Instant createdAt = Instant.parse("2026-09-06T09:25:00.234567Z");
        UUID applicationId = UUID.randomUUID();
        inProject(fixture, () -> {
            repository.create(catalog(fixture, applicationId, "并发草稿", createdAt),
                    draft(fixture, applicationId, 0, 1, createdAt));
            return null;
        });
        ConnectionScope first = connectionScope();
        ConnectionScope second = connectionScope();
        CountDownLatch firstSaveReturned = new CountDownLatch(1);
        CountDownLatch releaseFirstCommit = new CountDownLatch(1);
        CountDownLatch secondSaveEntered = new CountDownLatch(1);
        AtomicInteger secondBackendPid = new AtomicInteger();

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<ApplicationDraftSaveResult> firstResult = executor.submit(() ->
                    first.transactions().execute(status -> {
                        setProjectContext(first.jdbc(), fixture);
                        ApplicationDraftSaveResult result = first.repository().saveDraft(
                                fixture.projectId(), applicationId, 0, content(2),
                                fixture.accountId(), createdAt.plusSeconds(1));
                        firstSaveReturned.countDown();
                        await(releaseFirstCommit);
                        return result;
                    }));
            assertThat(firstSaveReturned.await(5, TimeUnit.SECONDS)).isTrue();
            Future<ApplicationDraftSaveResult> secondResult = executor.submit(() ->
                    inProject(second, fixture, () -> {
                        secondBackendPid.set(second.jdbc().queryForObject(
                                "SELECT pg_backend_pid()", Integer.class));
                        secondSaveEntered.countDown();
                        return second.repository().saveDraft(
                                fixture.projectId(), applicationId, 0, content(3),
                                fixture.accountId(), createdAt.plusSeconds(2));
                    }));
            try {
                assertThat(secondSaveEntered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(awaitDatabaseLock(secondBackendPid.get())).isTrue();
            } finally {
                // 即使锁等待断言失败也提交首个事务，避免第二线程滞留至外部测试超时。
                releaseFirstCommit.countDown();
            }
            ApplicationDraftSaveResult winner = firstResult.get();
            ApplicationDraftSaveResult rejected = secondResult.get();
            ApplicationDraft persisted = inProject(fixture, () -> repository.findDraft(
                    fixture.projectId(), applicationId)).orElseThrow();

            assertThat(winner.status()).isEqualTo(ApplicationDraftSaveResult.Status.SAVED);
            assertThat(rejected.status()).isEqualTo(ApplicationDraftSaveResult.Status.REVISION_CONFLICT);
            assertThat(persisted.revision()).isEqualTo(1);
            assertThat(persisted.content()).isEqualTo(winner.savedDraft().orElseThrow().content());
        }
    }

    /**
     * 草稿保存与软删除必须锁同一目录行，避免删除先提交后旧保存仍成功形成逆序事实。
     */
    @Test
    @DisplayName("草稿保存与软删除按目录行串行提交")
    void draftSaveAndSoftDeleteSerializeOnApplicationRow() throws Exception {
        Fixture fixture = fixture(null);
        Instant createdAt = Instant.parse("2026-09-06T09:27:00.234567Z");
        UUID applicationId = UUID.randomUUID();
        inProject(fixture, () -> {
            repository.create(catalog(fixture, applicationId, "删除串行", createdAt),
                    draft(fixture, applicationId, 0, 1, createdAt));
            return null;
        });
        ConnectionScope saver = connectionScope();
        ConnectionScope deleter = connectionScope();
        CountDownLatch saveReturned = new CountDownLatch(1);
        CountDownLatch releaseSaveCommit = new CountDownLatch(1);
        CountDownLatch deleteEntered = new CountDownLatch(1);
        AtomicInteger deleterBackendPid = new AtomicInteger();

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<ApplicationDraftSaveResult> saved = executor.submit(() ->
                    saver.transactions().execute(status -> {
                        setProjectContext(saver.jdbc(), fixture);
                        ApplicationDraftSaveResult result = saver.repository().saveDraft(
                                fixture.projectId(), applicationId, 0, content(2),
                                fixture.accountId(), createdAt.plusSeconds(1));
                        saveReturned.countDown();
                        await(releaseSaveCommit);
                        return result;
                    }));
            assertThat(saveReturned.await(5, TimeUnit.SECONDS)).isTrue();
            Future<ApplicationSoftDeleteResult> deleted = executor.submit(() -> inProject(deleter, fixture, () -> {
                deleterBackendPid.set(deleter.jdbc().queryForObject(
                        "SELECT pg_backend_pid()", Integer.class));
                deleteEntered.countDown();
                return deleter.repository().softDelete(
                        fixture.projectId(), applicationId, 0,
                        fixture.accountId(), createdAt.plusSeconds(2));
            }));
            try {
                assertThat(deleteEntered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(awaitDatabaseLock(deleterBackendPid.get())).isTrue();
            } finally {
                // 断言失败也释放保存事务，避免测试线程池在锁等待中拖到外部超时。
                releaseSaveCommit.countDown();
            }
            assertThat(saved.get().status()).isEqualTo(ApplicationDraftSaveResult.Status.SAVED);
            assertThat(deleted.get().status()).isEqualTo(ApplicationSoftDeleteResult.Status.DELETED);
        }
        assertThat(inProject(fixture, () -> repository.saveDraft(
                fixture.projectId(), applicationId, 1, content(3),
                fixture.accountId(), createdAt.plusSeconds(3))).status())
                .isEqualTo(ApplicationDraftSaveResult.Status.NOT_FOUND);
    }

    /**
     * 删除事务先持有目录行锁时，随后进入的旧草稿保存必须等待删除提交并稳定返回不存在。
     */
    @Test
    @DisplayName("删除先提交后等待中的草稿保存返回不存在")
    void committedSoftDeleteMakesWaitingDraftSaveNotFound() throws Exception {
        Fixture fixture = fixture(null);
        Instant createdAt = Instant.parse("2026-09-06T09:28:00.234567Z");
        UUID applicationId = UUID.randomUUID();
        inProject(fixture, () -> {
            repository.create(catalog(fixture, applicationId, "删除先行", createdAt),
                    draft(fixture, applicationId, 0, 1, createdAt));
            return null;
        });
        String draftBefore = owner.queryForObject("""
                SELECT row_to_json(draft_row)::text
                  FROM (SELECT * FROM app_application_draft WHERE application_id = ?) draft_row
                """, String.class, applicationId);
        ConnectionScope deleter = connectionScope();
        ConnectionScope saver = connectionScope();
        CountDownLatch deleteUpdated = new CountDownLatch(1);
        CountDownLatch releaseDeleteCommit = new CountDownLatch(1);
        CountDownLatch saveEntered = new CountDownLatch(1);
        AtomicInteger saverBackendPid = new AtomicInteger();

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<ApplicationSoftDeleteResult> deleted = executor.submit(() ->
                    deleter.transactions().execute(status -> {
                        setProjectContext(deleter.jdbc(), fixture);
                        ApplicationSoftDeleteResult result = deleter.repository().softDelete(
                                fixture.projectId(), applicationId, 0, fixture.accountId(),
                                createdAt.plusSeconds(1));
                        deleteUpdated.countDown();
                        await(releaseDeleteCommit);
                        return result;
                    }));
            assertThat(deleteUpdated.await(5, TimeUnit.SECONDS)).isTrue();
            Future<ApplicationDraftSaveResult> saved = executor.submit(() ->
                    inProject(saver, fixture, () -> {
                        saverBackendPid.set(saver.jdbc().queryForObject(
                                "SELECT pg_backend_pid()", Integer.class));
                        saveEntered.countDown();
                        return saver.repository().saveDraft(
                                fixture.projectId(), applicationId, 0, content(2),
                                fixture.accountId(), createdAt.plusSeconds(2));
                    }));
            try {
                assertThat(saveEntered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(awaitDatabaseLock(saverBackendPid.get())).isTrue();
            } finally {
                // 释放删除提交后，等待中的保存才有资格对已删除事实作稳定分类。
                releaseDeleteCommit.countDown();
            }

            assertThat(deleted.get().status()).isEqualTo(ApplicationSoftDeleteResult.Status.DELETED);
            assertThat(saved.get().status()).isEqualTo(ApplicationDraftSaveResult.Status.NOT_FOUND);
        }
        String draftAfter = owner.queryForObject("""
                SELECT row_to_json(draft_row)::text
                  FROM (SELECT * FROM app_application_draft WHERE application_id = ?) draft_row
                """, String.class, applicationId);
        assertThat(draftAfter).isEqualTo(draftBefore);
    }

    /**
     * 目录与草稿的一次创建不能留下半事实；外层草稿外键失败必须回滚同语句内已创建的目录候选。
     */
    @Test
    @DisplayName("目录与初始草稿创建保持原子")
    void createRollsBackCatalogWhenDraftPersistenceFails() {
        Fixture first = fixture(null);
        Fixture neighbor = fixture(first.tenantId());
        Instant createdAt = Instant.parse("2026-09-06T09:30:00.345678Z");
        UUID applicationId = UUID.randomUUID();
        ApplicationCatalogEntry entry = catalog(first, applicationId, "原子创建", createdAt);
        ApplicationDraft wrongDraft = draft(neighbor, applicationId, 0, 1, createdAt);

        assertThatThrownBy(() -> inProject(first, () -> {
            repository.create(entry, wrongDraft);
            return null;
        })).isInstanceOf(IllegalArgumentException.class);

        UUID missingAccountId = UUID.randomUUID();
        ApplicationDraft invalidAuditDraft = new ApplicationDraft(
                applicationId, first.tenantId(), first.projectId(), content(1), 0,
                missingAccountId, createdAt, createdAt);
        assertThatThrownBy(() -> inProject(first, () -> {
            repository.create(entry, invalidAuditDraft);
            return null;
        })).isInstanceOf(DataAccessException.class)
                .isNotInstanceOf(ApplicationKeyCollisionException.class);

        assertThat(owner.queryForObject(
                "SELECT count(*) FROM app_application WHERE id = ?", Long.class, applicationId)).isZero();
        assertThat(owner.queryForObject(
                "SELECT count(*) FROM app_application_draft WHERE application_id = ?",
                Long.class, applicationId)).isZero();
    }

    /**
     * appKey唯一索引冲突必须成为可重试的精确领域结果；主键冲突仍保留数据库异常。
     */
    @Test
    @DisplayName("只翻译appKey碰撞并允许同事务换key重试")
    void appKeyCollisionIsTypedWithoutSwallowingOtherConstraints() {
        Fixture fixture = fixture(null);
        Instant createdAt = Instant.parse("2026-09-06T09:32:00.345678Z");
        UUID existingId = UUID.randomUUID();
        UUID collidingId = UUID.randomUUID();
        UUID retriedId = UUID.randomUUID();
        String occupiedKey = "app_" + compact(UUID.randomUUID());
        String retryKey = "app_" + compact(UUID.randomUUID());
        inProject(fixture, () -> {
            repository.create(catalog(fixture, existingId, occupiedKey, "既有应用", createdAt),
                    draft(fixture, existingId, 0, 1, createdAt));
            return null;
        });

        inProject(fixture, () -> {
            assertThatThrownBy(() -> repository.create(
                    catalog(fixture, collidingId, occupiedKey, "碰撞候选", createdAt.plusSeconds(1)),
                    draft(fixture, collidingId, 0, 2, createdAt.plusSeconds(1))))
                    .isExactlyInstanceOf(ApplicationKeyCollisionException.class);
            repository.create(catalog(fixture, retriedId, retryKey, "重试成功", createdAt.plusSeconds(2)),
                    draft(fixture, retriedId, 0, 3, createdAt.plusSeconds(2)));
            return null;
        });

        assertThat(owner.queryForObject(
                "SELECT count(*) FROM app_application WHERE id IN (?, ?)",
                Long.class, existingId, retriedId)).isEqualTo(2);
        assertThat(owner.queryForObject(
                "SELECT count(*) FROM app_application WHERE id = ?", Long.class, collidingId)).isZero();
        assertThat(owner.queryForObject(
                "SELECT count(*) FROM app_application_draft WHERE application_id = ?",
                Long.class, collidingId)).isZero();

        assertThatThrownBy(() -> inProject(fixture, () -> {
            repository.create(catalog(fixture, existingId,
                            "app_" + compact(UUID.randomUUID()), "重复主键", createdAt.plusSeconds(3)),
                    draft(fixture, existingId, 0, 4, createdAt.plusSeconds(3)));
            return null;
        })).isInstanceOf(DataAccessException.class)
                .isNotInstanceOf(ApplicationKeyCollisionException.class);
        assertThat(owner.queryForObject(
                "SELECT count(*) FROM app_application WHERE id = ? AND app_key = ?",
                Long.class, existingId, occupiedKey)).isOne();
    }

    /**
     * 应用目录必须消费迁移的复合排序索引，以不透明键集游标稳定越过同一更新时间的边界。
     */
    @Test
    @DisplayName("应用目录按更新时间与稳定ID执行有界分页")
    void applicationDirectoryUsesStableKeysetPagination() {
        Fixture fixture = fixture(null);
        Fixture neighbor = fixture(fixture.tenantId());
        Instant createdAt = Instant.parse("2026-09-06T09:35:00.123456Z");
        UUID firstId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID secondId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID thirdId = UUID.fromString("00000000-0000-0000-0000-000000000003");
        inProject(fixture, () -> {
            repository.create(catalog(fixture, thirdId, "目录三", createdAt),
                    draft(fixture, thirdId, 0, 3, createdAt));
            repository.create(catalog(fixture, firstId, "目录一", createdAt),
                    draft(fixture, firstId, 0, 1, createdAt));
            repository.create(catalog(fixture, secondId, "目录二", createdAt),
                    draft(fixture, secondId, 0, 2, createdAt));
            return null;
        });

        CursorPage<ApplicationCatalogEntry> firstPage = inProject(fixture,
                () -> repository.page(fixture.projectId(), null, 2));
        assertThat(firstPage.items()).extracting(ApplicationCatalogEntry::id)
                .containsExactly(firstId, secondId);
        assertThat(firstPage.hasMore()).isTrue();
        assertThat(firstPage.nextCursor()).isNotBlank();
        CursorPage<ApplicationCatalogEntry> lastPage = inProject(fixture,
                () -> repository.page(fixture.projectId(), firstPage.nextCursor(), 2));
        assertThat(lastPage.items()).extracting(ApplicationCatalogEntry::id)
                .containsExactly(thirdId);
        assertThat(lastPage.hasMore()).isFalse();
        assertThat(inProject(neighbor, () -> repository.page(fixture.projectId(), null, 2)).items())
                .isEmpty();
        assertThatThrownBy(() -> inProject(fixture,
                () -> repository.page(fixture.projectId(), "invalid", 2)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> inProject(fixture,
                () -> repository.page(fixture.projectId(),
                        Cursor.encode("not-an-instant|" + firstId), 2)))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * 精确版本和最大版本号由三元身份读取，latest不按发布时间选择；目录另行映射当前发布状态。
     */
    @Test
    @DisplayName("精确版本与最大版本号查询不混淆当前发布状态")
    void versionQueriesUseApplicationIdentityAndVersionNumberOrdering() {
        Fixture fixture = fixture(null);
        Fixture neighbor = fixture(fixture.tenantId());
        Instant createdAt = Instant.parse("2026-09-06T09:40:00.456789Z");
        UUID applicationId = UUID.randomUUID();
        inProject(fixture, () -> {
            repository.create(catalog(fixture, applicationId, "版本历史", createdAt),
                    draft(fixture, applicationId, 0, 1, createdAt));
            return null;
        });
        ApplicationVersion first = version(
                fixture, applicationId, UUID.randomUUID(), 1, 0, 1, createdAt.plusSeconds(20));
        ApplicationVersion latest = version(
                fixture, applicationId, UUID.randomUUID(), 2, 1, 2, createdAt.plusSeconds(10));
        appendVersion(first);
        appendVersion(latest);

        assertThat(inProject(fixture, () -> repository.findVersion(
                fixture.projectId(), applicationId, first.id()))).contains(first);
        ((ObjectNode) first.snapshot()).put("displayName", "调用方改写版本副本");
        assertThat(inProject(fixture, () -> repository.findVersion(
                fixture.projectId(), applicationId, first.id())).orElseThrow().snapshot())
                .isEqualTo(publishedContent(1));
        assertThat(inProject(fixture, () -> repository.findLatestVersion(
                fixture.projectId(), applicationId))).contains(latest);
        assertThat(inProject(neighbor, () -> repository.findVersion(
                fixture.projectId(), applicationId, first.id()))).isEmpty();
        assertThat(inProject(fixture, () -> repository.findVersion(
                fixture.projectId(), UUID.randomUUID(), first.id()))).isEmpty();

        owner.update("""
                UPDATE app_application
                   SET current_version_id = ?, publication_revision = 3,
                       updated_by = ?, updated_at = ?
                 WHERE id = ?
                """, first.id(), fixture.accountId(), Timestamp.from(createdAt.plusSeconds(30)), applicationId);
        ApplicationPublicationState publicationState = inProject(fixture,
                () -> repository.findPublicationState(fixture.projectId(), applicationId)).orElseThrow();
        assertThat(publicationState.draftRevision()).isZero();
        assertThat(publicationState.publicationRevision()).isEqualTo(3);
        assertThat(publicationState.currentVersionId()).isEqualTo(first.id());
        assertThat(publicationState.latestVersionNumber()).isEqualTo(2);
        assertThat(publicationState.deletedAt()).isNull();
        assertThat(inProject(fixture, () -> repository.findLatestVersion(
                fixture.projectId(), applicationId))).contains(latest);
    }

    /**
     * 创建测试账号、租户与ACTIVE项目；复用租户时仍使用独立项目和审计账号。
     *
     * @param existingTenant 可复用租户；为空时创建新租户。
     * @return 项目范围及审计账号夹具。
     */
    private Fixture fixture(UUID existingTenant) {
        UUID tenantId = existingTenant == null ? UUID.randomUUID() : existingTenant;
        UUID projectId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        owner.update("""
                INSERT INTO sys_account(id, email, password_hash, display_name)
                VALUES (?, ?, '{noop}dashboard-repository', '应用仓储测试账号')
                """, accountId, accountId + "@dashboard-repository.test");
        if (existingTenant == null) {
            owner.update("INSERT INTO sys_tenant(id, name) VALUES (?, '应用仓储测试租户')", tenantId);
        }
        owner.update("""
                INSERT INTO sys_project(id, tenant_id, name, project_key)
                VALUES (?, ?, '应用仓储测试项目', ?)
                """, projectId, tenantId, "dash_repo_" + compact(projectId));
        return new Fixture(tenantId, projectId, accountId);
    }

    /**
     * 构造尚未发布且未删除的应用目录事实。
     *
     * @param fixture 归属项目。
     * @param applicationId 应用ID。
     * @param name Console管理名称。
     * @param at 创建时刻。
     * @return 可交给仓储原子创建的目录事实。
     */
    private ApplicationCatalogEntry catalog(
            Fixture fixture, UUID applicationId, String name, Instant at) {
        return catalog(fixture, applicationId, "app_" + compact(UUID.randomUUID()), name, at);
    }

    /**
     * 以指定公开定位符构造应用目录，用于真实唯一冲突仲裁反例。
     *
     * @param fixture 归属项目。
     * @param applicationId 应用ID。
     * @param appKey 指定公开定位符。
     * @param name Console管理名称。
     * @param at 创建时刻。
     * @return 可交给仓储原子创建的目录事实。
     */
    private ApplicationCatalogEntry catalog(
            Fixture fixture, UUID applicationId, String appKey, String name, Instant at) {
        return new ApplicationCatalogEntry(
                applicationId, fixture.tenantId(), fixture.projectId(),
                appKey, name, 0, null,
                fixture.accountId(), fixture.accountId(), at, at, null);
    }

    /**
     * 构造初始或预期保存后的独立草稿事实。
     *
     * @param fixture 归属项目。
     * @param applicationId 应用ID。
     * @param revision 草稿revision。
     * @param discriminator JSON内容区分值。
     * @param at 保存时刻。
     * @return 草稿事实。
     */
    private ApplicationDraft draft(
            Fixture fixture, UUID applicationId, long revision, int discriminator, Instant at) {
        return new ApplicationDraft(
                applicationId, fixture.tenantId(), fixture.projectId(), content(discriminator),
                revision, fixture.accountId(), at, at);
    }

    /**
     * 构造不可变应用版本，发布时间刻意可与版本号顺序不同。
     *
     * @param fixture 归属项目。
     * @param applicationId 应用ID。
     * @param versionId 版本ID。
     * @param number 应用内版本号。
     * @param sourceRevision 封存草稿revision。
     * @param discriminator 快照内容区分值。
     * @param publishedAt 发布时间。
     * @return 可由owner追加的版本事实。
     */
    private ApplicationVersion version(
            Fixture fixture, UUID applicationId, UUID versionId, long number,
            long sourceRevision, int discriminator, Instant publishedAt) {
        JsonNode snapshot = publishedContent(discriminator);
        String digest = owner.queryForObject("""
                SELECT encode(digest(convert_to(?::jsonb::text, 'UTF8'), 'sha256'), 'hex')
                """, String.class, snapshot.toString());
        return new ApplicationVersion(
                versionId, fixture.tenantId(), fixture.projectId(), applicationId,
                number, sourceRevision, snapshot,
                ApplicationVersion.SNAPSHOT_DIGEST_ALGORITHM,
                digest,
                fixture.accountId(), publishedAt);
    }

    /**
     * 以迁移owner追加已经完整校验的不可变版本夹具，不借测试引入生产版本写入口。
     *
     * @param value 待追加版本事实。
     */
    private void appendVersion(ApplicationVersion value) {
        owner.update("""
                INSERT INTO app_application_version(
                    id, tenant_id, project_id, application_id, version_number,
                    source_draft_revision, snapshot, snapshot_digest_algorithm,
                    snapshot_digest, published_by_account_id, published_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                """, value.id(), value.tenantId(), value.projectId(), value.applicationId(),
                value.versionNumber(), value.sourceDraftRevision(), value.snapshot().toString(),
                value.snapshotDigestAlgorithm(), value.snapshotDigest(),
                value.publishedByAccountId(), Timestamp.from(value.publishedAt()));
    }

    /**
     * 在同一事务设置租户与项目上下文后执行仓储动作。
     *
     * @param fixture 当前可信范围。
     * @param action 被测仓储动作。
     * @param <T> 返回类型。
     * @return 仓储动作结果。
     */
    private <T> T inProject(Fixture fixture, Supplier<T> action) {
        return inProject(new ConnectionScope(application, transactions, repository), fixture, action);
    }

    /** 在指定独立连接的事务中设置项目RLS上下文并执行动作。 */
    private <T> T inProject(ConnectionScope scope, Fixture fixture, Supplier<T> action) {
        return scope.transactions().execute(status -> {
            setProjectContext(scope.jdbc(), fixture);
            return action.get();
        });
    }

    /** 为并发反例创建不共享物理连接池状态的仓储与事务入口。 */
    private ConnectionScope connectionScope() {
        DriverManagerDataSource source = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "thingslink_app", "thingslink");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        return new ConnectionScope(jdbc, new TransactionTemplate(new DataSourceTransactionManager(source)),
                new JdbcApplicationRepository(jdbc));
    }

    /** 在当前物理事务内写入数据库RLS三轴上下文。 */
    private static void setProjectContext(JdbcTemplate jdbc, Fixture fixture) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                String.class, fixture.tenantId().toString());
        jdbc.queryForObject("SELECT set_config('app.project_id', ?, true)",
                String.class, fixture.projectId().toString());
    }

    /** 等待并发测试屏障；中断时恢复线程标志并把测试标记为失败。 */
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("并发测试屏障等待超时");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("并发测试屏障被中断", exception);
        }
    }

    /** 轮询真实PostgreSQL会话，确认删除事务正在等待保存事务持有的目录行锁。 */
    private static boolean awaitDatabaseLock(int backendPid) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Boolean waiting = owner.queryForObject("""
                    SELECT wait_event_type = 'Lock'
                      FROM pg_stat_activity
                     WHERE pid = ?
                    """, Boolean.class, backendPid);
            if (Boolean.TRUE.equals(waiting)) {
                return true;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待数据库锁时被中断", exception);
            }
        }
        return false;
    }

    /**
     * 创建最小合法tc.application/v1 JSON树。
     *
     * @param discriminator 内容区分值。
     * @return 新JSON树。
     */
    private JsonNode content(int discriminator) {
        return JSON.readTree("""
                {
                  "formatVersion":"tc.application/v1",
                  "displayName":"测试应用%s",
                  "hostCompatibility":{"minInclusive":"1.0.0","maxExclusive":"2.0.0"},
                  "dashboardRefs":[],
                  "entryDashboardId":null
                }
                """.formatted(discriminator));
    }

    /**
     * 创建符合0b3a合同的最小已发布应用快照，避免测试通过owner写入语义不可能的版本事实。
     *
     * @param discriminator 内容区分值。
     * @return 含一个精确Dashboard引用的完整应用快照。
     */
    private JsonNode publishedContent(int discriminator) {
        String dashboardId = "00000000-0000-0000-0000-%012d".formatted(discriminator);
        String dashboardVersionId = "10000000-0000-0000-0000-%012d".formatted(discriminator);
        return JSON.readTree("""
                {
                  "formatVersion":"tc.application/v1",
                  "displayName":"已发布应用%s",
                  "hostCompatibility":{"minInclusive":"1.0.0","maxExclusive":"2.0.0"},
                  "dashboardRefs":[{
                    "dashboardId":"%s",
                    "dashboardVersionId":"%s",
                    "dashboardVersionNumber":"1",
                    "title":"总览",
                    "schemaVersion":"tc.dashboard/v1",
                    "schemaDigestAlgorithm":"PG_JSONB_TEXT_V1_SHA256",
                    "schemaDigest":"%s",
                    "pages":[{"id":"home","title":"首页"}]
                  }],
                  "entryDashboardId":"%s",
                  "requiredSchemas":["tc.dashboard/v1"],
                  "requiredComponents":[],
                  "requiredResources":[]
                }
                """.formatted(discriminator, dashboardId, dashboardVersionId,
                "c".repeat(64), dashboardId));
    }

    /**
     * 将UUID转为无分隔符小写十六进制，用于满足稳定key语法。
     *
     * @param value UUID。
     * @return 32位小写十六进制。
     */
    private static String compact(UUID value) {
        return value.toString().replace("-", "");
    }

    /**
     * 仓储真实项目与审计账号夹具。
     *
     * @param tenantId 项目租户。
     * @param projectId 项目ID。
     * @param accountId 审计账号ID。
     */
    private record Fixture(UUID tenantId, UUID projectId, UUID accountId) {
    }

    /**
     * 独立真实连接上的仓储测试入口。
     *
     * @param jdbc RLS上下文写入入口。
     * @param transactions 独立事务模板。
     * @param repository 被测仓储。
     */
    private record ConnectionScope(
            JdbcTemplate jdbc, TransactionTemplate transactions, ApplicationRepository repository) {
    }
}
