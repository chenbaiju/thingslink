package com.things.link.bootstrap.dashboard;

import com.things.link.dashboard.domain.DashboardCatalogEntry;
import com.things.link.dashboard.domain.DashboardDraft;
import com.things.link.dashboard.domain.DashboardDraftSaveResult;
import com.things.link.dashboard.domain.DashboardModelReference;
import com.things.link.dashboard.domain.DashboardPublicationState;
import com.things.link.dashboard.domain.DashboardRepository;
import com.things.link.dashboard.domain.DashboardVersion;
import com.things.link.dashboard.domain.DashboardVersionLookupResult;
import com.things.link.dashboard.domain.DashboardVersionSummary;
import com.things.link.dashboard.infrastructure.persistence.JdbcDashboardRepository;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.page.Cursor;
import com.things.link.shared.page.CursorPage;
import com.things.link.shared.tenant.RlsScope;
import com.things.link.shared.tenant.RlsScopeContext;
import com.things.link.support.tenant.TenantAwareDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * 看板目录、草稿、版本与精确模型关系仓储的真实PostgreSQL验收。
 *
 * <p>S12-1b2a只开放既有持久事实的基础适配。测试以普通运行角色调用仓储，以迁移owner准备
 * 不可变版本历史，证明草稿CAS和模型关系整组替换共享一个真实数据库事务。</p>
 */
@Testcontainers
@DisplayName("看板持久仓储")
class DashboardRepositoryTests {

    /** 与生产迁移验收相同的PostgreSQL/TimescaleDB镜像。 */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("dashboard_repository")
            .withUsername("thingslink")
            .withPassword("thingslink");

    /** Bootstrap登记的完整迁移位置，避免手工简化表掩盖RLS、权限或复合外键。 */
    private static final String[] MIGRATION_LOCATIONS = {
            "classpath:db/migration/support", "classpath:db/migration/project",
            "classpath:db/migration/device", "classpath:db/migration/telemetry",
            "classpath:db/migration/alarm", "classpath:db/migration/task",
            "classpath:db/migration/rule", "classpath:db/migration/iam",
            "classpath:db/migration/enduser", "classpath:db/migration/export",
            "classpath:db/migration/dashboard", "classpath:db/migration/ota"
    };

    /** 本仓储依赖核心看板表及三类精确关系的迁移终点。 */
    private static final String MIGRATION_TARGET = "20260906.0130";

    /** 测试JSON树的唯一构造器。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 迁移owner只用于准备项目、模型、版本历史和观察最终事实。 */
    private static JdbcTemplate owner;

    /** 普通生产角色连接，被测仓储只使用该身份。 */
    private JdbcTemplate application;

    /** 被测单一看板聚合持久端口。 */
    private DashboardRepository repository;

    /** RLS上下文和仓储写入必须位于同一个物理事务。 */
    private TransactionTemplate transactions;

    /** 执行完整生产迁移并创建普通运行角色。 */
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

    /** 清空独占事实并重新创建普通角色仓储。 */
    @BeforeEach
    void resetDatabase() {
        owner.update("UPDATE dash_dashboard SET current_version_id = NULL");
        owner.update("DELETE FROM app_application_version_dashboard_ref");
        owner.update("DELETE FROM dash_dashboard_draft_model_ref");
        owner.update("DELETE FROM dash_dashboard_version_model_ref");
        owner.update("DELETE FROM dash_dashboard_draft");
        owner.update("DELETE FROM dash_dashboard_version");
        owner.update("DELETE FROM dash_dashboard");
        owner.update("DELETE FROM dev_thing_model_version");
        owner.update("DELETE FROM dev_type");
        owner.update("DELETE FROM sys_project_member");
        owner.update("DELETE FROM sys_project");
        owner.update("DELETE FROM sys_tenant_member");
        owner.update("DELETE FROM sys_tenant");
        owner.update("DELETE FROM sys_account");

        DriverManagerDataSource source = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "thingslink_app", "thingslink");
        application = new JdbcTemplate(source);
        repository = new JdbcDashboardRepository(application);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    /**
     * 目录创建、查询、改名及键集分页必须服从当前项目RLS，并稳定处理相同更新时间。
     */
    @Test
    @DisplayName("目录生命周期与分页服从项目范围")
    void catalogLifecycleAndPaginationUseCurrentProjectScope() {
        Fixture fixture = fixture(null);
        Fixture neighbor = fixture(fixture.tenantId());
        Instant at = Instant.parse("2026-09-06T10:00:00.123456Z");
        UUID firstId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID secondId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID thirdId = UUID.fromString("00000000-0000-0000-0000-000000000003");
        UUID neighborId = UUID.randomUUID();

        inProject(fixture, () -> {
            repository.create(catalog(fixture, thirdId, "目录三", at), draft(fixture, thirdId, 3, at, List.of()));
            repository.create(catalog(fixture, firstId, "目录一", at), draft(fixture, firstId, 1, at, List.of()));
            repository.create(catalog(fixture, secondId, "目录二", at), draft(fixture, secondId, 2, at, List.of()));
            return null;
        });
        assertThatThrownBy(() -> inProject(fixture, () -> {
            repository.create(catalog(neighbor, neighborId, "越界目录", at),
                    draft(neighbor, neighborId, 4, at, List.of()));
            return null;
        })).isInstanceOf(DataAccessException.class);
        assertThat(owner.queryForObject(
                "SELECT count(*) FROM dash_dashboard WHERE id = ?", Long.class, neighborId)).isZero();
        inProject(neighbor, () -> {
            repository.create(catalog(neighbor, neighborId, "邻居目录", at),
                    draft(neighbor, neighborId, 4, at, List.of()));
            return null;
        });

        assertThat(inProject(fixture, () -> repository.find(fixture.projectId(), firstId)))
                .contains(catalog(fixture, firstId, "目录一", at));
        assertThat(inProject(fixture, () -> repository.find(neighbor.projectId(), neighborId))).isEmpty();
        assertThat(inProject(fixture,
                () -> repository.findDraft(neighbor.projectId(), neighborId))).isEmpty();
        assertThat(inProject(fixture, () -> repository.saveDraft(
                neighbor.projectId(), neighborId, 0, content(5), List.of(),
                fixture.accountId(), at.plusSeconds(1))).status())
                .isEqualTo(DashboardDraftSaveResult.Status.NOT_FOUND);
        assertThat(inProject(fixture, () -> repository.rename(
                neighbor.projectId(), neighborId, "越界改名", fixture.accountId(), at.plusSeconds(1))))
                .isFalse();
        assertThat(inProject(fixture, () -> repository.rename(
                fixture.projectId(), firstId, "生产总览", fixture.accountId(), at.plusSeconds(1))))
                .isTrue();
        assertThat(inProject(fixture, () -> repository.find(fixture.projectId(), firstId)))
                .get().extracting(DashboardCatalogEntry::managementName).isEqualTo("生产总览");

        CursorPage<DashboardCatalogEntry> firstPage = inProject(fixture,
                () -> repository.page(fixture.projectId(), null, 2));
        assertThat(firstPage.items()).extracting(DashboardCatalogEntry::id)
                .containsExactly(firstId, secondId);
        assertThat(firstPage.hasMore()).isTrue();
        CursorPage<DashboardCatalogEntry> lastPage = inProject(fixture,
                () -> repository.page(fixture.projectId(), firstPage.nextCursor(), 2));
        assertThat(lastPage.items()).extracting(DashboardCatalogEntry::id).containsExactly(thirdId);
        assertThat(lastPage.hasMore()).isFalse();
        assertThat(inProject(neighbor,
                () -> repository.page(fixture.projectId(), null, 2)).items()).isEmpty();
        assertThatThrownBy(() -> inProject(fixture,
                () -> repository.page(fixture.projectId(), "invalid", 2)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> inProject(fixture,
                () -> repository.page(fixture.projectId(),
                        Cursor.encode("not-an-instant|" + firstId), 2)))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * 成功草稿CAS必须同时替换完整模型关系；陈旧revision和Long耗尽不得改变任何事实。
     */
    @Test
    @DisplayName("草稿CAS原子替换完整模型关系")
    void draftCasReplacesModelReferencesAndClassifiesStableFailures() {
        Fixture fixture = fixture(null);
        ModelFact firstModel = model(fixture, 1);
        ModelFact secondModel = model(fixture, 2);
        UUID dashboardId = UUID.randomUUID();
        Instant at = Instant.parse("2026-09-06T10:10:00.123456Z");
        List<DashboardModelReference> initialReferences = List.of(reference(0, "initial_model", firstModel));
        inProject(fixture, () -> {
            repository.create(catalog(fixture, dashboardId, "草稿CAS", at),
                    draft(fixture, dashboardId, 1, at, initialReferences));
            return null;
        });
        String initialSnapshot = draftSnapshot(dashboardId);
        List<DashboardModelReference> gappedReferences =
                List.of(reference(1, "gap_model", secondModel));
        assertThatThrownBy(() -> inProject(fixture, () -> repository.saveDraft(
                fixture.projectId(), dashboardId, 0, content(2, gappedReferences),
                gappedReferences,
                fixture.accountId(), at.plusSeconds(1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(draftSnapshot(dashboardId)).isEqualTo(initialSnapshot);

        List<DashboardModelReference> expectedSavedReferences = List.of(
                reference(0, "secondary_model", secondModel),
                reference(1, "primary_model", firstModel));
        ObjectNode savedInput = content(2, expectedSavedReferences);
        ArrayList<DashboardModelReference> savedReferences = new ArrayList<>(expectedSavedReferences);
        DashboardDraftSaveResult saved = inProject(fixture, () -> repository.saveDraft(
                fixture.projectId(), dashboardId, 0, savedInput, savedReferences,
                fixture.accountId(), at.plusSeconds(1)));
        assertThat(saved.status()).isEqualTo(DashboardDraftSaveResult.Status.SAVED);
        assertThat(saved.savedDraft().orElseThrow().revision()).isEqualTo(1);
        assertThat(saved.savedDraft().orElseThrow().modelReferences()).containsExactlyElementsOf(savedReferences);

        // 调用方对输入、返回JSON和输入列表的后续改写不能改变同一revision代表的数据库事实。
        savedInput.put("title", "调用方改写输入");
        savedReferences.clear();
        ((ObjectNode) saved.savedDraft().orElseThrow().content()).put("title", "调用方改写返回值");
        DashboardDraft persisted = inProject(fixture,
                () -> repository.findDraft(fixture.projectId(), dashboardId)).orElseThrow();
        assertThat(persisted.content()).isEqualTo(content(2, expectedSavedReferences));
        assertThat(persisted.modelReferences()).extracting(DashboardModelReference::modelKey)
                .containsExactly("secondary_model", "primary_model");

        DashboardDraftSaveResult conflict = inProject(fixture, () -> repository.saveDraft(
                fixture.projectId(), dashboardId, 0, content(3), List.of(),
                fixture.accountId(), at.plusSeconds(2)));
        assertThat(conflict.status()).isEqualTo(DashboardDraftSaveResult.Status.REVISION_CONFLICT);
        assertThat(conflict.savedDraft()).isEmpty();
        assertThat(inProject(fixture,
                () -> repository.findDraft(fixture.projectId(), dashboardId))).contains(persisted);

        owner.update("DELETE FROM dash_dashboard_draft_model_ref WHERE dashboard_id = ?", dashboardId);
        owner.update("DELETE FROM dash_dashboard_draft WHERE dashboard_id = ?", dashboardId);
        owner.update("""
                INSERT INTO dash_dashboard_draft(
                    dashboard_id, tenant_id, project_id, content, revision, updated_by, created_at, updated_at)
                VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                """, dashboardId, fixture.tenantId(), fixture.projectId(), content(2).toString(),
                Long.MAX_VALUE, fixture.accountId(), Timestamp.from(at), Timestamp.from(at.plusSeconds(1)));
        DashboardDraftSaveResult exhausted = inProject(fixture, () -> repository.saveDraft(
                fixture.projectId(), dashboardId, Long.MAX_VALUE, content(4), List.of(),
                fixture.accountId(), at.plusSeconds(3)));
        assertThat(exhausted.status()).isEqualTo(DashboardDraftSaveResult.Status.REVISION_EXHAUSTED);
        assertThat(exhausted.savedDraft()).isEmpty();
        assertThat(owner.queryForObject(
                "SELECT revision FROM dash_dashboard_draft WHERE dashboard_id = ?",
                Long.class, dashboardId)).isEqualTo(Long.MAX_VALUE);
        assertThat(owner.queryForObject(
                "SELECT content = ?::jsonb FROM dash_dashboard_draft WHERE dashboard_id = ?",
                Boolean.class, content(2).toString(), dashboardId)).isTrue();
    }

    /** 目录已经软删除时，草稿保存必须稳定返回不存在且不改变仍待清理的草稿与关系事实。 */
    @Test
    @DisplayName("软删除目录拒绝草稿保存且保留待清理事实")
    void softDeletedCatalogRejectsDraftSaveWithoutFactDrift() {
        Fixture fixture = fixture(null);
        ModelFact currentModel = model(fixture, 1);
        ModelFact candidateModel = model(fixture, 2);
        UUID dashboardId = UUID.randomUUID();
        Instant at = Instant.parse("2026-09-06T10:11:00.123456Z");
        List<DashboardModelReference> currentReferences =
                List.of(reference(0, "current_model", currentModel));
        List<DashboardModelReference> candidateReferences =
                List.of(reference(0, "candidate_model", candidateModel));
        inProject(fixture, () -> {
            repository.create(catalog(fixture, dashboardId, "软删保存", at),
                    draft(fixture, dashboardId, 1, at, currentReferences));
            return null;
        });
        String before = draftSnapshot(dashboardId);
        owner.update("UPDATE dash_dashboard SET deleted_at = ? WHERE id = ?",
                Timestamp.from(at.plusSeconds(1)), dashboardId);

        DashboardDraftSaveResult result = inProject(fixture, () -> repository.saveDraft(
                fixture.projectId(), dashboardId, 0, content(2, candidateReferences),
                candidateReferences, fixture.accountId(), at.plusSeconds(2)));

        assertThat(result.status()).isEqualTo(DashboardDraftSaveResult.Status.NOT_FOUND);
        assertThat(result.savedDraft()).isEmpty();
        assertThat(draftSnapshot(dashboardId)).isEqualTo(before);
        assertThat(inProject(fixture,
                () -> repository.findDraft(fixture.projectId(), dashboardId))).isEmpty();
    }

    /**
     * Schema模型投影与关系候选的数量、key或versionId不一致时，必须在任何CAS SQL前拒绝。
     */
    @Test
    @DisplayName("草稿保存拒绝Schema与模型关系错配且不触碰数据库")
    void draftSaveRejectsSchemaModelProjectionMismatchBeforeSql() {
        Fixture fixture = fixture(null);
        ModelFact firstModel = model(fixture, 1);
        ModelFact secondModel = model(fixture, 2);
        UUID dashboardId = UUID.randomUUID();
        Instant at = Instant.parse("2026-09-06T10:12:00.123456Z");
        List<DashboardModelReference> initialReferences =
                List.of(reference(0, "initial_model", firstModel));
        inProject(fixture, () -> {
            repository.create(catalog(fixture, dashboardId, "投影核对", at),
                    draft(fixture, dashboardId, 1, at, initialReferences));
            return null;
        });
        String before = draftSnapshot(dashboardId);
        List<DashboardModelReference> twoSchemaModels = List.of(
                reference(0, "first_model", firstModel),
                reference(1, "second_model", secondModel));
        List<DashboardModelReference> firstRelationOnly =
                List.of(reference(0, "first_model", firstModel));

        assertThatThrownBy(() -> inProject(fixture, () -> repository.saveDraft(
                fixture.projectId(), dashboardId, 0, content(2, twoSchemaModels), firstRelationOnly,
                fixture.accountId(), at.plusSeconds(1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(draftSnapshot(dashboardId)).isEqualTo(before);

        assertThatThrownBy(() -> inProject(fixture, () -> repository.saveDraft(
                fixture.projectId(), dashboardId, 0, content(2, firstRelationOnly), twoSchemaModels,
                fixture.accountId(), at.plusSeconds(1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(draftSnapshot(dashboardId)).isEqualTo(before);

        List<DashboardModelReference> wrongKey =
                List.of(reference(0, "wrong_model", firstModel));
        assertThatThrownBy(() -> inProject(fixture, () -> repository.saveDraft(
                fixture.projectId(), dashboardId, 0, content(2, firstRelationOnly), wrongKey,
                fixture.accountId(), at.plusSeconds(1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(draftSnapshot(dashboardId)).isEqualTo(before);

        List<DashboardModelReference> wrongVersion =
                List.of(reference(0, "first_model", secondModel));
        assertThatThrownBy(() -> inProject(fixture, () -> repository.saveDraft(
                fixture.projectId(), dashboardId, 0, content(2, firstRelationOnly), wrongVersion,
                fixture.accountId(), at.plusSeconds(1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(draftSnapshot(dashboardId)).isEqualTo(before);
    }

    /**
     * 两个独立连接持同一revision竞争时，第二连接必须真实等待目录行锁并在提交后稳定冲突。
     */
    @Test
    @DisplayName("并发草稿CAS只提交一组内容与模型关系")
    void concurrentDraftCasCommitsOnlyWinningContentAndReferences() throws Exception {
        Fixture fixture = fixture(null);
        ModelFact winnerModel = model(fixture, 1);
        ModelFact loserModel = model(fixture, 2);
        UUID dashboardId = UUID.randomUUID();
        Instant at = Instant.parse("2026-09-06T10:15:00.123456Z");
        inProject(fixture, () -> {
            repository.create(catalog(fixture, dashboardId, "并发草稿", at),
                    draft(fixture, dashboardId, 1, at, List.of()));
            return null;
        });
        ConnectionScope first = connectionScope();
        ConnectionScope second = connectionScope();
        List<DashboardModelReference> winnerReferences =
                List.of(reference(0, "winner_model", winnerModel));
        List<DashboardModelReference> loserReferences =
                List.of(reference(0, "loser_model", loserModel));
        CountDownLatch firstSaveReturned = new CountDownLatch(1);
        CountDownLatch releaseFirstCommit = new CountDownLatch(1);
        CountDownLatch secondSaveEntered = new CountDownLatch(1);
        AtomicInteger secondBackendPid = new AtomicInteger();

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<DashboardDraftSaveResult> firstResult = executor.submit(() ->
                    first.transactions().execute(status -> {
                        setProjectContext(first.jdbc(), fixture);
                        DashboardDraftSaveResult result = first.repository().saveDraft(
                                fixture.projectId(), dashboardId, 0, content(2, winnerReferences),
                                winnerReferences,
                                fixture.accountId(), at.plusSeconds(1));
                        firstSaveReturned.countDown();
                        await(releaseFirstCommit);
                        return result;
                    }));
            assertThat(firstSaveReturned.await(5, TimeUnit.SECONDS)).isTrue();
            Future<DashboardDraftSaveResult> secondResult = executor.submit(() ->
                    inProject(second, fixture, () -> {
                        secondBackendPid.set(second.jdbc().queryForObject(
                                "SELECT pg_backend_pid()", Integer.class));
                        secondSaveEntered.countDown();
                        return second.repository().saveDraft(
                                fixture.projectId(), dashboardId, 0, content(3, loserReferences),
                                loserReferences,
                                fixture.accountId(), at.plusSeconds(2));
                    }));
            try {
                assertThat(secondSaveEntered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(awaitDatabaseLock(secondBackendPid.get())).isTrue();
            } finally {
                // 无论锁等待断言是否通过都释放赢家提交，避免第二连接滞留至外部超时。
                releaseFirstCommit.countDown();
            }

            assertThat(firstResult.get().status()).isEqualTo(DashboardDraftSaveResult.Status.SAVED);
            assertThat(secondResult.get().status())
                    .isEqualTo(DashboardDraftSaveResult.Status.REVISION_CONFLICT);
        }
        DashboardDraft persisted = inProject(fixture,
                () -> repository.findDraft(fixture.projectId(), dashboardId)).orElseThrow();
        assertThat(persisted.revision()).isEqualTo(1);
        assertThat(persisted.content()).isEqualTo(content(2, winnerReferences));
        assertThat(persisted.modelReferences())
                .containsExactly(reference(0, "winner_model", winnerModel));
        assertThat(owner.queryForObject("""
                SELECT count(*) FROM dash_dashboard_draft_model_ref
                 WHERE dashboard_id = ?
                """, Long.class, dashboardId)).isOne();
    }

    /**
     * 模型关系插入违反同项目外键时，已经执行的草稿UPDATE与关系删除必须随事务一起回滚。
     */
    @Test
    @DisplayName("关系插入失败回滚草稿内容与revision")
    void failedModelReplacementRollsBackDraftCas() {
        Fixture fixture = fixture(null);
        Fixture neighbor = fixture(fixture.tenantId());
        ModelFact currentModel = model(fixture, 1);
        ModelFact foreignModel = model(neighbor, 2);
        UUID dashboardId = UUID.randomUUID();
        Instant at = Instant.parse("2026-09-06T10:20:00.123456Z");
        inProject(fixture, () -> {
            repository.create(catalog(fixture, dashboardId, "回滚边界", at),
                    draft(fixture, dashboardId, 1, at,
                            List.of(reference(0, "current_model", currentModel))));
            return null;
        });
        String before = draftSnapshot(dashboardId);
        List<DashboardModelReference> foreignReferences =
                List.of(reference(0, "foreign_model", foreignModel));

        assertThatThrownBy(() -> inProject(fixture, () -> repository.saveDraft(
                fixture.projectId(), dashboardId, 0, content(2, foreignReferences),
                foreignReferences,
                fixture.accountId(), at.plusSeconds(1))))
                .isInstanceOf(DataAccessException.class);

        assertThat(draftSnapshot(dashboardId)).isEqualTo(before);
        DashboardDraft persisted = inProject(fixture,
                () -> repository.findDraft(fixture.projectId(), dashboardId)).orElseThrow();
        assertThat(persisted.revision()).isZero();
        assertThat(persisted.content()).isEqualTo(content(1,
                List.of(reference(0, "current_model", currentModel))));
        assertThat(persisted.modelReferences()).containsExactly(reference(0, "current_model", currentModel));
    }

    /**
     * Spring生产事务代理必须独立撤销create和saveDraft尾段外键失败前已经写入的聚合事实。
     */
    @Test
    @DisplayName("生产事务代理回滚创建与草稿保存的尾段失败")
    void transactionalProxyRollsBackCreateAndDraftSaveTailFailures() {
        Fixture fixture = fixture(null);
        Fixture neighbor = fixture(fixture.tenantId());
        ModelFact localModel = model(fixture, 1);
        ModelFact foreignModel = model(neighbor, 1);
        UUID failedCreateId = UUID.randomUUID();
        UUID failedSaveId = UUID.randomUUID();
        Instant at = Instant.parse("2026-09-06T10:25:00.123456Z");
        List<DashboardModelReference> foreignReferences =
                List.of(reference(0, "foreign_model", foreignModel));
        List<DashboardModelReference> localReferences =
                List.of(reference(0, "local_model", localModel));

        try (AnnotationConfigApplicationContext context = runtimeRepositoryContext()) {
            DashboardRepository proxiedRepository = context.getBean(DashboardRepository.class);
            assertThat(AopUtils.isAopProxy(proxiedRepository)).isTrue();

            assertThatThrownBy(() -> inRlsScope(fixture, () -> proxiedRepository.create(
                    catalog(fixture, failedCreateId, "创建回滚", at),
                    draft(fixture, failedCreateId, 1, at, foreignReferences))))
                    .isInstanceOf(DataAccessException.class);
            assertThat(owner.queryForObject(
                    "SELECT count(*) FROM dash_dashboard WHERE id = ?",
                    Long.class, failedCreateId)).isZero();
            assertThat(owner.queryForObject(
                    "SELECT count(*) FROM dash_dashboard_draft WHERE dashboard_id = ?",
                    Long.class, failedCreateId)).isZero();
            assertThat(owner.queryForObject(
                    "SELECT count(*) FROM dash_dashboard_draft_model_ref WHERE dashboard_id = ?",
                    Long.class, failedCreateId)).isZero();

            inRlsScope(fixture, () -> proxiedRepository.create(
                    catalog(fixture, failedSaveId, "保存回滚", at),
                    draft(fixture, failedSaveId, 1, at, localReferences)));
            String beforeSave = draftSnapshot(failedSaveId);
            assertThatThrownBy(() -> inRlsScope(fixture, () -> proxiedRepository.saveDraft(
                    fixture.projectId(), failedSaveId, 0, content(2, foreignReferences),
                    foreignReferences, fixture.accountId(), at.plusSeconds(1))))
                    .isInstanceOf(DataAccessException.class);
            assertThat(draftSnapshot(failedSaveId)).isEqualTo(beforeSave);
        }

        assertThat(RlsScopeContext.current()).isEmpty();
    }

    /**
     * 精确版本、最大版本号、当前发布状态和版本模型关系都必须按项目只读映射且返回防御副本。
     */
    @Test
    @DisplayName("版本关系与发布状态按项目只读映射")
    void versionsAndPublicationStateAreReadOnlyProjectScopedProjections() {
        Fixture fixture = fixture(null);
        Fixture neighbor = fixture(fixture.tenantId());
        ModelFact firstModel = model(fixture, 1);
        ModelFact secondModel = model(fixture, 2);
        UUID dashboardId = UUID.randomUUID();
        Instant at = Instant.parse("2026-09-06T10:30:00.123456Z");
        inProject(fixture, () -> {
            repository.create(catalog(fixture, dashboardId, "版本读取", at),
                    draft(fixture, dashboardId, 1, at, List.of()));
            return null;
        });
        DashboardVersion first = version(fixture, dashboardId, UUID.randomUUID(), 1, 0,
                1, at.plusSeconds(20), List.of(reference(0, "first_model", firstModel)));
        DashboardVersion latest = version(fixture, dashboardId, UUID.randomUUID(), 2, 1,
                2, at.plusSeconds(10), List.of(reference(0, "second_model", secondModel)));
        appendVersion(first);
        appendVersion(latest);

        DashboardVersion readFirst = inProject(fixture, () -> repository.findVersion(
                fixture.projectId(), dashboardId, first.id())).orElseThrow();
        assertThat(readFirst).isEqualTo(first);
        ((ObjectNode) readFirst.schema()).put("title", "调用方改写Schema副本");
        ((ArrayNode) readFirst.requiredComponents()).add("调用方改写组件副本");
        assertThat(inProject(fixture, () -> repository.findVersion(
                fixture.projectId(), dashboardId, first.id()))).contains(first);
        assertThat(inProject(fixture, () -> repository.findLatestVersion(
                fixture.projectId(), dashboardId))).contains(latest);
        assertThat(inProject(neighbor, () -> repository.findVersion(
                fixture.projectId(), dashboardId, first.id()))).isEmpty();

        owner.update("""
                UPDATE dash_dashboard
                   SET current_version_id = ?, publication_revision = 3,
                       updated_by = ?, updated_at = ?
                 WHERE id = ?
                """, first.id(), fixture.accountId(), Timestamp.from(at.plusSeconds(30)), dashboardId);
        DashboardPublicationState state = inProject(fixture, () -> repository.findPublicationState(
                fixture.projectId(), dashboardId)).orElseThrow();
        assertThat(state.draftRevision()).isZero();
        assertThat(state.publicationRevision()).isEqualTo(3);
        assertThat(state.currentVersionId()).isEqualTo(first.id());
        assertThat(state.latestVersionNumber()).isEqualTo(2);
        assertThat(state.deletedAt()).isNull();
        assertThat(inProject(fixture, () -> repository.findLatestVersion(
                fixture.projectId(), dashboardId))).contains(latest);
    }

    /** 历史分页只读取固定大小摘要，并在一条查询中区分空历史与不可见看板。 */
    @Test
    @DisplayName("版本历史按版本号倒序分页并区分不可见看板")
    void versionHistoryPaginatesByDescendingNumberAndKeepsDashboardVisibility() {
        Fixture fixture = fixture(null);
        Fixture neighbor = fixture(fixture.tenantId());
        UUID dashboardId = UUID.randomUUID();
        UUID emptyDashboardId = UUID.randomUUID();
        Instant at = Instant.parse("2026-09-06T10:35:00.123456Z");
        inProject(fixture, () -> {
            repository.create(catalog(fixture, dashboardId, "历史分页", at),
                    draft(fixture, dashboardId, 1, at, List.of()));
            repository.create(catalog(fixture, emptyDashboardId, "空历史", at),
                    draft(fixture, emptyDashboardId, 2, at, List.of()));
            return null;
        });
        DashboardVersion oldest = version(
                fixture, dashboardId, UUID.randomUUID(), 1, 0, 1, at.plusSeconds(30), List.of());
        DashboardVersion middle = version(
                fixture, dashboardId, UUID.randomUUID(), 2, 1, 2, at.plusSeconds(20), List.of());
        DashboardVersion newest = version(
                fixture, dashboardId, UUID.randomUUID(), 9_007_199_254_740_993L,
                9_007_199_254_740_993L, 3, at.plusSeconds(10), List.of());
        appendVersion(oldest);
        appendVersion(middle);
        appendVersion(newest);

        CursorPage<DashboardVersionSummary> first = inProject(fixture,
                () -> repository.pageVersions(fixture.projectId(), dashboardId, null, 2))
                .orElseThrow();
        assertThat(first.items()).extracting(DashboardVersionSummary::id)
                .containsExactly(newest.id(), middle.id());
        assertThat(first.items().get(0).sourceDraftRevision())
                .isEqualTo(9_007_199_254_740_993L);
        assertThat(first.hasMore()).isTrue();
        CursorPage<DashboardVersionSummary> last = inProject(fixture,
                () -> repository.pageVersions(fixture.projectId(), dashboardId, first.nextCursor(), 2))
                .orElseThrow();
        assertThat(last.items()).extracting(DashboardVersionSummary::id).containsExactly(oldest.id());
        assertThat(last.hasMore()).isFalse();

        DashboardVersionLookupResult found = inProject(fixture,
                () -> repository.findVersionForManagement(fixture.projectId(), dashboardId, middle.id()));
        assertThat(found.status()).isEqualTo(DashboardVersionLookupResult.Status.FOUND);
        assertThat(found.foundVersion()).contains(middle);
        assertThat(inProject(fixture, () -> repository.findVersionForManagement(
                fixture.projectId(), dashboardId, UUID.randomUUID())).status())
                .isEqualTo(DashboardVersionLookupResult.Status.VERSION_NOT_FOUND);
        assertThat(inProject(fixture, () -> repository.findVersionForManagement(
                fixture.projectId(), emptyDashboardId, middle.id())).status())
                .isEqualTo(DashboardVersionLookupResult.Status.VERSION_NOT_FOUND);

        CursorPage<DashboardVersionSummary> empty = inProject(fixture,
                () -> repository.pageVersions(fixture.projectId(), emptyDashboardId, null, 50))
                .orElseThrow();
        assertThat(empty.items()).isEmpty();
        assertThat(empty.nextCursor()).isNull();
        assertThat(empty.hasMore()).isFalse();
        assertThat(inProject(neighbor, () -> repository.pageVersions(
                fixture.projectId(), dashboardId, null, 50))).isEmpty();
        assertThat(inProject(fixture, () -> repository.pageVersions(
                fixture.projectId(), UUID.randomUUID(), null, 50))).isEmpty();
        assertThat(inProject(neighbor, () -> repository.findVersionForManagement(
                fixture.projectId(), dashboardId, middle.id())).status())
                .isEqualTo(DashboardVersionLookupResult.Status.DASHBOARD_NOT_FOUND);
        assertThat(inProject(fixture, () -> repository.findVersionForManagement(
                fixture.projectId(), UUID.randomUUID(), middle.id())).status())
                .isEqualTo(DashboardVersionLookupResult.Status.DASHBOARD_NOT_FOUND);

        for (String invalidCursor : List.of("invalid", Cursor.encode("0"), Cursor.encode("01"))) {
            assertThatThrownBy(() -> inProject(fixture, () -> repository.pageVersions(
                    fixture.projectId(), dashboardId, invalidCursor, 50)))
                    .isInstanceOf(BusinessException.class);
        }
        for (int invalidLimit : List.of(0, 201)) {
            assertThatThrownBy(() -> inProject(fixture, () -> repository.pageVersions(
                    fixture.projectId(), dashboardId, null, invalidLimit)))
                    .isInstanceOf(BusinessException.class);
        }

        owner.update("UPDATE dash_dashboard SET deleted_at=? WHERE id=?", Timestamp.from(at), dashboardId);
        assertThat(inProject(fixture, () -> repository.pageVersions(
                fixture.projectId(), dashboardId, null, 50))).isEmpty();
        assertThat(inProject(fixture, () -> repository.findVersionForManagement(
                fixture.projectId(), dashboardId, middle.id())).status())
                .isEqualTo(DashboardVersionLookupResult.Status.DASHBOARD_NOT_FOUND);
    }

    /** 创建测试账号、租户和独立ACTIVE项目。 */
    private Fixture fixture(UUID existingTenant) {
        UUID tenantId = existingTenant == null ? UUID.randomUUID() : existingTenant;
        UUID projectId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        owner.update("""
                INSERT INTO sys_account(id, email, password_hash, display_name)
                VALUES (?, ?, '{noop}dashboard-repository', '看板仓储测试账号')
                """, accountId, accountId + "@dashboard-repository.test");
        if (existingTenant == null) {
            owner.update("INSERT INTO sys_tenant(id, name) VALUES (?, '看板仓储测试租户')", tenantId);
        }
        owner.update("""
                INSERT INTO sys_project(id, tenant_id, name, project_key)
                VALUES (?, ?, '看板仓储测试项目', ?)
                """, projectId, tenantId, "dashboard_repo_" + compact(projectId));
        return new Fixture(tenantId, projectId, accountId);
    }

    /** 构造尚未发布的看板目录事实。 */
    private DashboardCatalogEntry catalog(
            Fixture fixture, UUID dashboardId, String name, Instant at) {
        return new DashboardCatalogEntry(
                dashboardId, fixture.tenantId(), fixture.projectId(), name, 0, null,
                fixture.accountId(), fixture.accountId(), at, at, null);
    }

    /** 构造revision 0的看板草稿及其完整关系集合。 */
    private DashboardDraft draft(
            Fixture fixture, UUID dashboardId, int discriminator, Instant at,
            List<DashboardModelReference> references) {
        return new DashboardDraft(
                dashboardId, fixture.tenantId(), fixture.projectId(), content(discriminator, references), 0,
                fixture.accountId(), at, at, references);
    }

    /** 创建同项目不可变物模型版本，供草稿和版本关系外键使用。 */
    private ModelFact model(Fixture fixture, int discriminator) {
        UUID typeId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        owner.update("""
                INSERT INTO dev_type(
                    id, tenant_id, project_id, type_key, name, device_kind,
                    access_protocol, network_type, status)
                VALUES (?, ?, ?, ?, ?, 'DIRECT', 'STANDARD', 'WIFI', 'PUBLISHED')
                """, typeId, fixture.tenantId(), fixture.projectId(),
                "repo_model_" + discriminator + "_" + compact(typeId), "仓储模型" + discriminator);
        owner.update("""
                INSERT INTO dev_thing_model_version(
                    id, tenant_id, project_id, device_type_id, version_number,
                    version_major, version_minor, version_patch, change_level,
                    schema_profile, model_snapshot, schema_digest, digest_algorithm)
                VALUES (?, ?, ?, ?, '1.0.0', 1, 0, 0, 'MAJOR',
                        'TC_PROPERTY_COMPOSITE_V1',
                        '{"properties":{},"events":{},"commands":{}}'::jsonb,
                        ?, 'PG_JSONB_TEXT_V1_SHA256')
                """, versionId, fixture.tenantId(), fixture.projectId(), typeId,
                compact(versionId).repeat(2));
        return new ModelFact(typeId, versionId);
    }

    /** 构造一条有序精确模型关系。 */
    private DashboardModelReference reference(int position, String key, ModelFact model) {
        return new DashboardModelReference(position, key, model.versionId());
    }

    /** 构造只读不可变看板版本投影。 */
    private DashboardVersion version(
            Fixture fixture, UUID dashboardId, UUID versionId, long number,
            long sourceRevision, int discriminator, Instant publishedAt,
            List<DashboardModelReference> references) {
        JsonNode schema = content(discriminator, references);
        String digest = owner.queryForObject("""
                SELECT encode(digest(convert_to(?::jsonb::text, 'UTF8'), 'sha256'), 'hex')
                """, String.class, schema.toString());
        return new DashboardVersion(
                versionId, fixture.tenantId(), fixture.projectId(), dashboardId,
                number, sourceRevision, schema, DashboardDraft.SCHEMA_VERSION,
                DashboardVersion.SCHEMA_DIGEST_ALGORITHM, digest,
                JSON.createArrayNode().add("line-chart"), JSON.createArrayNode().add("builtin://background"),
                fixture.accountId(), publishedAt, references);
    }

    /** 以迁移owner追加已完整校验的版本及其精确模型关系。 */
    private void appendVersion(DashboardVersion version) {
        owner.update("""
                INSERT INTO dash_dashboard_version(
                    id, tenant_id, project_id, dashboard_id, version_number,
                    source_draft_revision, schema, schema_version, schema_digest_algorithm,
                    schema_digest, required_components, required_resources,
                    published_by_account_id, published_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
                """, version.id(), version.tenantId(), version.projectId(), version.dashboardId(),
                version.versionNumber(), version.sourceDraftRevision(), version.schema().toString(),
                version.schemaVersion(), version.schemaDigestAlgorithm(), version.schemaDigest(),
                version.requiredComponents().toString(), version.requiredResources().toString(),
                version.publishedByAccountId(), Timestamp.from(version.publishedAt()));
        for (DashboardModelReference reference : version.modelReferences()) {
            owner.update("""
                    INSERT INTO dash_dashboard_version_model_ref(
                        tenant_id, project_id, dashboard_id, dashboard_version_id,
                        position, model_key, thing_model_version_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, version.tenantId(), version.projectId(), version.dashboardId(), version.id(),
                    reference.position(), reference.modelKey(), reference.thingModelVersionId());
        }
    }

    /** 构造不引用物模型且可由0120迁移接受的最小规范看板Schema。 */
    private ObjectNode content(int discriminator) {
        return content(discriminator, List.of());
    }

    /**
     * 构造模型数组与关系候选逐项一致的完整看板Schema。
     *
     * <p>摘要由物模型版本ID确定并与model夹具入库值一致，使本仓储测试不伪造已经失配的上游校验结果。</p>
     */
    private ObjectNode content(int discriminator, List<DashboardModelReference> references) {
        ObjectNode content = JSON.createObjectNode()
                .put("schemaVersion", DashboardDraft.SCHEMA_VERSION)
                .put("title", "看板" + discriminator);
        ArrayNode models = content.putArray("models");
        for (DashboardModelReference reference : references) {
            models.addObject()
                    .put("key", reference.modelKey())
                    .put("versionId", reference.thingModelVersionId().toString())
                    .put("digestAlgorithm", DashboardVersion.SCHEMA_DIGEST_ALGORITHM)
                    .put("digest", compact(reference.thingModelVersionId()).repeat(2))
                    .put("profile", "TC_PROPERTY_COMPOSITE_V1");
        }
        return content;
    }

    /** 读取草稿及整组模型关系的稳定数据库快照。 */
    private String draftSnapshot(UUID dashboardId) {
        return owner.queryForObject("""
                SELECT jsonb_build_object(
                    'draft', to_jsonb(draft),
                    'models', coalesce((
                        SELECT jsonb_agg(to_jsonb(reference) ORDER BY position)
                          FROM dash_dashboard_draft_model_ref reference
                         WHERE reference.dashboard_id = draft.dashboard_id), '[]'::jsonb))::text
                  FROM dash_dashboard_draft draft
                 WHERE draft.dashboard_id = ?
                """, String.class, dashboardId);
    }

    /** 在普通角色真实事务内设置项目上下文并执行仓储动作。 */
    private <T> T inProject(Fixture fixture, Supplier<T> action) {
        return inProject(new ConnectionScope(application, transactions, repository), fixture, action);
    }

    /** 在指定独立连接的事务内设置项目RLS上下文并执行动作。 */
    private <T> T inProject(ConnectionScope scope, Fixture fixture, Supplier<T> action) {
        return scope.transactions().execute(status -> {
            setProjectContext(scope.jdbc(), fixture);
            return action.get();
        });
    }

    /** 为真实并发反例创建不共享物理连接状态的仓储和事务入口。 */
    private ConnectionScope connectionScope() {
        DriverManagerDataSource source = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "thingslink_app", "thingslink");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        return new ConnectionScope(jdbc, new TransactionTemplate(new DataSourceTransactionManager(source)),
                new JdbcDashboardRepository(jdbc));
    }

    /**
     * 仅装配生产仓储和真实Spring事务解释器，证明测试未借外层TransactionTemplate掩盖注解缺失。
     */
    private AnnotationConfigApplicationContext runtimeRepositoryContext() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(RuntimeTransactionConfiguration.class);
        context.registerBean(DataSource.class, () -> new TenantAwareDataSource(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "thingslink_app", "thingslink")));
        context.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(context.getBean(DataSource.class)));
        context.registerBean(PlatformTransactionManager.class,
                () -> new JdbcTransactionManager(context.getBean(DataSource.class)));
        context.registerBean(JdbcDashboardRepository.class);
        try {
            context.refresh();
            return context;
        } catch (RuntimeException exception) {
            context.close();
            throw exception;
        }
    }

    /** 在仓储代理开事务前绑定生产数据源读取的RLS范围，并在调用后无条件清理。 */
    private void inRlsScope(Fixture fixture, Runnable action) {
        RlsScopeContext.set(new RlsScope(fixture.tenantId(), fixture.projectId()));
        try {
            action.run();
        } finally {
            RlsScopeContext.clear();
        }
    }

    /** 在当前物理事务内写入RLS租户和项目双轴上下文。 */
    private static void setProjectContext(JdbcTemplate jdbc, Fixture fixture) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)",
                String.class, fixture.tenantId().toString());
        jdbc.queryForObject("SELECT set_config('app.project_id', ?, true)",
                String.class, fixture.projectId().toString());
    }

    /** 等待并发屏障，超时或中断均立即使反例失败。 */
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

    /** 轮询真实PostgreSQL会话，确认竞争连接正在等待数据库锁。 */
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

    /** 将UUID压缩为数据库业务键安全片段。 */
    private String compact(UUID value) {
        return value.toString().replace("-", "");
    }

    /** @param tenantId 租户ID @param projectId 项目ID @param accountId 审计账号ID */
    private record Fixture(UUID tenantId, UUID projectId, UUID accountId) {
    }

    /** @param typeId 设备类型ID @param versionId 不可变物模型版本ID */
    private record ModelFact(UUID typeId, UUID versionId) {
    }

    /** @param jdbc 独立JDBC连接 @param transactions 独立事务入口 @param repository 独立仓储 */
    private record ConnectionScope(
            JdbcTemplate jdbc, TransactionTemplate transactions, DashboardRepository repository) {
    }

    /** 限定最小测试上下文，只解释生产仓储上的Spring事务注解。 */
    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class RuntimeTransactionConfiguration {
    }
}
