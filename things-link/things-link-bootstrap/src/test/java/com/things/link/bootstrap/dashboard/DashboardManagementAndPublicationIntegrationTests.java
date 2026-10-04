package com.things.link.bootstrap.dashboard;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.dashboard.application.ApplicationManagementService;
import com.things.link.dashboard.application.DashboardManagementService;
import com.things.link.dashboard.application.publication.ApplicationPublicationCandidate;
import com.things.link.dashboard.application.publication.ApplicationPublicationCandidateFactory;
import com.things.link.dashboard.application.publication.ApplicationPublicationQualificationException;
import com.things.link.dashboard.application.publication.ApplicationPublicationService;
import com.things.link.dashboard.application.publication.DashboardPublicationCandidate;
import com.things.link.dashboard.application.publication.DashboardPublicationCandidateFactory;
import com.things.link.dashboard.application.publication.DashboardDataAdapterQualificationPort;
import com.things.link.dashboard.application.publication.DashboardHostQualificationDescriptor;
import com.things.link.dashboard.infrastructure.qualification.ManagedWebAppHostQualificationAdapter;
import com.things.link.dashboard.application.publication.DashboardPublicationEligibilityRequirement;
import com.things.link.dashboard.application.publication.DashboardPublicationService;
import com.things.link.dashboard.domain.ApplicationCatalogEntry;
import com.things.link.dashboard.domain.ApplicationDraft;
import com.things.link.dashboard.domain.ApplicationPublicationState;
import com.things.link.dashboard.domain.ApplicationRepository;
import com.things.link.dashboard.domain.ApplicationVersion;
import com.things.link.dashboard.domain.DashboardCatalogEntry;
import com.things.link.dashboard.domain.DashboardDraft;
import com.things.link.dashboard.domain.DashboardModelReference;
import com.things.link.dashboard.domain.DashboardPublicationState;
import com.things.link.dashboard.domain.DashboardRepository;
import com.things.link.dashboard.domain.DashboardVersion;
import com.things.link.device.application.ThingModelVersionDescriptor;
import com.things.link.device.application.ThingModelVersionDescriptorPort;
import com.things.link.device.application.DeviceModelBindingFacts;
import com.things.link.device.application.DeviceModelBindingFactsPort;
import com.things.link.device.application.ThingModelPropertyFacts;
import com.things.link.device.application.ThingModelPropertyFactsPort;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

/** 看板管理、应用发布生命周期、双revision事务及数据库受控入口的真实PostgreSQL验收。 */
@Import(DashboardManagementAndPublicationIntegrationTests.IsolatedDatabaseConfiguration.class)
@DisplayName("看板管理与发布真实集成")
@OwnedTestContainers({"DASHBOARD_POSTGRES"})
class DashboardManagementAndPublicationIntegrationTests extends AbstractIntegrationTest {

    /** 为本组服务并发与审计故障测试提供不共享候选的独占数据库。 */
    private static final String DATABASE_NAME = "dashboard_management_"
            + UUID.randomUUID().toString().replace("-", "");
    /** PostgreSQL/Timescale版本沿用全仓真实验收镜像。 */
    private static final PostgreSQLContainer<?> DASHBOARD_POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName(DATABASE_NAME)
            .withUsername(POSTGRES.getUsername())
            .withPassword(POSTGRES.getPassword());
    /** Spring、Flyway、运行角色和owner观察统一落在独占库。 */
    private static final String DATABASE_URL = startDatabase();
    /** 当前项目模型的真实摘要。 */
    private static final String LOCAL_DIGEST = "a".repeat(64);
    /** 邻居项目模型的真实摘要。 */
    private static final String FOREIGN_DIGEST = "b".repeat(64);
    /** 真实摘要反例构造草稿树使用的JSON映射器。 */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 独占库不运行共享配额初始化runner。 */
    @MockitoBean(enforceOverride = true, name = "relaxRestQuota")
    private ApplicationRunner unusedQuotaRunner;
    /** 看板管理不参与全表通知领取。 */
    @MockitoBean(enforceOverride = true)
    private NotificationWorkCoordinator unusedNotificationCoordinator;
    /** 任务调度与看板草稿正交。 */
    @MockitoBean(enforceOverride = true)
    private TaskSchedulingScanner unusedTaskScanner;
    /** 命令超时扫描不得污染专库候选。 */
    @MockitoBean(enforceOverride = true)
    private DeviceCommandTimeoutScanner unusedCommandScanner;
    /** 属性聚合回补不参与看板模型校验。 */
    @MockitoBean(enforceOverride = true)
    private PropertyAggregateBackfillScanner unusedBackfillScanner;
    /** 正向发布只替换尚未交付的生产宿主注册事实，不替换发布事务或数据库。 */
    @MockitoBean(enforceOverride = true)
    private ManagedWebAppHostQualificationAdapter hostQualifications;
    /** 空组件画布没有数据适配需求；替身仍保证未来误加需求时明确返回支持。 */
    @MockitoBean(enforceOverride = true)
    private DashboardDataAdapterQualificationPort dataAdapters;

    /** 被测真实事务服务。 */
    @Autowired
    private DashboardManagementService dashboards;
    /** 建立真实应用目录与指定revision草稿的管理服务。 */
    @Autowired
    private ApplicationManagementService applications;
    /** 被测真实双revision发布事务服务。 */
    @Autowired
    private DashboardPublicationService publications;
    /** 读取真实版本、关系与三轴状态的领域仓储。 */
    @Autowired
    private DashboardRepository dashboardRepository;
    /** 被测Device公开只读模型摘要端口。 */
    @Autowired
    private ThingModelVersionDescriptorPort descriptors;
    /** 被测规范发布候选工厂及其真实PostgreSQL摘要适配。 */
    @Autowired
    private DashboardPublicationCandidateFactory publicationCandidates;
    /** 被测应用规范候选工厂及其真实仓储、历史重验和PostgreSQL摘要链。 */
    @Autowired
    private ApplicationPublicationCandidateFactory applicationPublicationCandidates;
    /** 被测应用双revision原子发布事务。 */
    @Autowired
    private ApplicationPublicationService applicationPublications;
    /** 读取真实应用版本、指针和版本号三轴。 */
    @Autowired
    private ApplicationRepository applicationRepository;
    /** 被测Device公开单属性资格端口。 */
    @Autowired
    private ThingModelPropertyFactsPort propertyFacts;
    /** 被测Device公开默认设备当前绑定端口。 */
    @Autowired
    private DeviceModelBindingFactsPort deviceBindings;
    /** 对真实审计服务注入写后故障以证明同事务回滚。 */
    @MockitoSpyBean
    private AuditLogService audits;
    /** 普通运行角色连接，仅用于确认专库和连接身份。 */
    @Autowired
    private JdbcTemplate jdbc;

    /** 每例确认专库、运行角色和全部无关后台替身已生效。 */
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
        when(hostQualifications.current()).thenReturn(Optional.of(new DashboardHostQualificationDescriptor(
                "tc.webapp-host/v1", "1.0.0", Set.of("tc.application/v1"), Set.of("tc.dashboard/v1"),
                Map.of(DashboardPublicationEligibilityRequirement.ComponentKind.TEXT, "1.0.0"), Map.of())));
        when(dataAdapters.supports(any(), any())).thenReturn(true);
        when(dataAdapters.supportsHistory(any(), any())).thenReturn(true);
    }

    /** 两次发布分别封存锁内草稿，历史版本不变且版本、关系、指针、revision和审计同步推进。 */
    @Test
    @DisplayName("双revision发布严格递增并封存完整模型关系")
    void publicationAppendsImmutableVersionsAndCompleteRelations() throws Exception {
        Fixture fixture = seed();
        DashboardCatalogEntry dashboard = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.create(fixture.projectId(), "发布看板",
                        schema("发布一", fixture.localModelId(), LOCAL_DIGEST)));

        DashboardVersion first = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> publications.publish(fixture.projectId(), dashboard.id(), "0", "0"));
        as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.saveDraft(fixture.projectId(), dashboard.id(), "0",
                        schema("发布二", fixture.localModelId(), LOCAL_DIGEST)));
        DashboardVersion second = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> publications.publish(fixture.projectId(), dashboard.id(), "1", "1"));

        DashboardVersion persistedFirst = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboardRepository.findVersion(fixture.projectId(), dashboard.id(), first.id()).orElseThrow());
        DashboardVersion persistedSecond = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboardRepository.findVersion(fixture.projectId(), dashboard.id(), second.id()).orElseThrow());
        DashboardPublicationState state = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboardRepository.findPublicationState(fixture.projectId(), dashboard.id()).orElseThrow());
        List<String> actions = auditActions(fixture.projectId());

        assertSoftly(softly -> {
            softly.assertThat(first.versionNumber()).isOne();
            softly.assertThat(first.sourceDraftRevision()).isZero();
            softly.assertThat(first.publishedAt().getNano() % 1_000).isZero();
            softly.assertThat(second.versionNumber()).isEqualTo(2);
            softly.assertThat(second.sourceDraftRevision()).isOne();
            softly.assertThat(second.publishedAt().getNano() % 1_000).isZero();
            softly.assertThat(persistedFirst).isEqualTo(first);
            softly.assertThat(persistedSecond).isEqualTo(second);
            softly.assertThat(persistedFirst.schema().path("pages").get(0).path("title").asString())
                    .isEqualTo("发布一");
            softly.assertThat(persistedSecond.schema().path("pages").get(0).path("title").asString())
                    .isEqualTo("发布二");
            softly.assertThat(persistedFirst.modelReferences()).hasSize(1);
            softly.assertThat(persistedSecond.modelReferences()).hasSize(1);
            softly.assertThat(state.currentVersionId()).isEqualTo(second.id());
            softly.assertThat(state.publicationRevision()).isEqualTo(2);
            softly.assertThat(state.latestVersionNumber()).isEqualTo(2);
            softly.assertThat(actions).containsExactly(
                    "dashboard.created", "dashboard.published", "dashboard.draft_saved", "dashboard.published");
        });
    }

    /** 应用候选保持历史精确引用，随目录撤回、重发和软删变化资格，且全过程零发布写。 */
    @Test
    @DisplayName("应用候选以真实历史版本和当前目录状态形成零写资格")
    void applicationCandidateUsesExactHistoryAndCurrentRunnableStateWithoutWrites() throws Exception {
        Fixture fixture = seed();
        DashboardCatalogEntry dashboard = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.create(fixture.projectId(), "应用引用看板", schema("历史页面")));
        DashboardVersion historical = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> publications.publish(fixture.projectId(), dashboard.id(), "0", "0"));
        as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.saveDraft(
                        fixture.projectId(), dashboard.id(), "0", schema("当前页面")));
        DashboardVersion current = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> publications.publish(fixture.projectId(), dashboard.id(), "1", "1"));
        ApplicationCatalogEntry application = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> applications.create(fixture.projectId(), "精确引用应用",
                        applicationDraft(dashboard.id(), historical.id())));
        List<String> before = applicationPublicationFacts(fixture.projectId(), application.id());

        ApplicationPublicationCandidate candidate = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> applicationPublicationCandidates.prepare(
                        applications.getDraft(fixture.projectId(), application.id())));
        List<String> afterCandidate = applicationPublicationFacts(fixture.projectId(), application.id());

        assertSoftly(softly -> {
            softly.assertThat(current.id()).isNotEqualTo(historical.id());
            softly.assertThat(candidate.dashboardReferences()).singleElement()
                    .satisfies(reference -> {
                        softly.assertThat(reference.dashboardId()).isEqualTo(dashboard.id());
                        softly.assertThat(reference.dashboardVersionId()).isEqualTo(historical.id());
                    });
            softly.assertThat(candidate.snapshot().path("dashboardRefs").get(0)
                    .path("dashboardVersionId").asString()).isEqualTo(historical.id().toString());
            softly.assertThat(candidate.snapshot().path("dashboardRefs").get(0)
                    .path("pages").get(0).path("title").asString()).isEqualTo("历史页面");
            softly.assertThat(candidate.snapshotDigest()).isEqualTo(sha256(candidate.postgresqlCanonicalText()
                    .getBytes(StandardCharsets.UTF_8)));
            softly.assertThat(afterCandidate).isEqualTo(before);
        });

        as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> publications.withdraw(fixture.projectId(), dashboard.id(), "2"));
        assertApplicationCandidateFailure(fixture, application,
                ApplicationPublicationQualificationException.Reason.DASHBOARD_NOT_RUNNABLE);
        as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> publications.rollback(fixture.projectId(), dashboard.id(), historical.id(), "3"));
        as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> applicationPublicationCandidates.prepare(
                        applications.getDraft(fixture.projectId(), application.id())));
        as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> {
                    publications.softDelete(fixture.projectId(), dashboard.id(), "4");
                    return null;
                });
        assertApplicationCandidateFailure(fixture, application,
                ApplicationPublicationQualificationException.Reason.DASHBOARD_NOT_RUNNABLE);
        assertThat(applicationPublicationFacts(fixture.projectId(), application.id())).isEqualTo(before);
    }

    /** 两次应用发布必须封存同一精确历史看板版本，严格推进版本号、指针、revision、关系和唯一审计。 */
    @Test
    @DisplayName("应用发布原子封存精确历史看板版本并推进双轴")
    void applicationPublicationAtomicallyAppendsExactHistoricalReferences() throws Exception {
        Fixture fixture = seed();
        DashboardCatalogEntry dashboard = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.create(fixture.projectId(), "应用发布看板", schema("应用历史页面")));
        DashboardVersion historical = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> publications.publish(fixture.projectId(), dashboard.id(), "0", "0"));
        as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.saveDraft(fixture.projectId(), dashboard.id(), "0", schema("看板当前页面")));
        DashboardVersion current = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> publications.publish(fixture.projectId(), dashboard.id(), "1", "1"));
        ApplicationCatalogEntry application = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> applications.create(fixture.projectId(), "原子发布应用",
                        applicationDraft(dashboard.id(), historical.id())));

        ApplicationVersion first = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> applicationPublications.publish(fixture.projectId(), application.id(), "0", "0"));
        ApplicationVersion second = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> applicationPublications.publish(fixture.projectId(), application.id(), "0", "1"));
        ApplicationVersion persistedFirst = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> applicationRepository.findVersion(
                        fixture.projectId(), application.id(), first.id()).orElseThrow());
        ApplicationVersion persistedSecond = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> applicationRepository.findVersion(
                        fixture.projectId(), application.id(), second.id()).orElseThrow());
        ApplicationPublicationState state = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> applicationRepository.findPublicationState(
                        fixture.projectId(), application.id()).orElseThrow());
        List<String> relations = applicationVersionReferenceFacts(
                fixture.projectId(), application.id(), List.of(first.id(), second.id()));
        String publicationFacts = applicationPublicationFacts(
                fixture.projectId(), application.id()).getFirst();
        long publishedAuditCount = applicationAuditCount(
                fixture.projectId(), application.id(), "application.published");

        assertSoftly(softly -> {
            softly.assertThat(current.id()).isNotEqualTo(historical.id());
            softly.assertThat(first.versionNumber()).isOne();
            softly.assertThat(second.versionNumber()).isEqualTo(2);
            softly.assertThat(first.sourceDraftRevision()).isZero();
            softly.assertThat(second.sourceDraftRevision()).isZero();
            softly.assertThat(first.publishedAt().getNano() % 1_000).isZero();
            softly.assertThat(persistedFirst).isEqualTo(first);
            softly.assertThat(persistedSecond).isEqualTo(second);
            softly.assertThat(persistedFirst.snapshot().path("dashboardRefs").get(0)
                    .path("dashboardVersionId").asString()).isEqualTo(historical.id().toString());
            softly.assertThat(persistedFirst.snapshot().path("dashboardRefs").get(0)
                    .path("pages").get(0).path("title").asString()).isEqualTo("应用历史页面");
            softly.assertThat(state.currentVersionId()).isEqualTo(second.id());
            softly.assertThat(state.publicationRevision()).isEqualTo(2);
            softly.assertThat(state.latestVersionNumber()).isEqualTo(2);
            softly.assertThat(relations).containsExactly(
                    first.id() + ":0:" + dashboard.id() + ":" + historical.id(),
                    second.id() + ":0:" + dashboard.id() + ":" + historical.id());
            softly.assertThat(publicationFacts)
                    .contains("\"publicationRevision\": 2", "\"versionCount\": 2",
                            "\"referenceCount\": 2", "application.published");
            softly.assertThat(publishedAuditCount).isEqualTo(2);
        });
    }

    /** 两个相同应用发布命令必须在应用目录锁上一胜一冲突，只留下一个完整版本、关系和审计。 */
    @Test
    @DisplayName("并发应用发布相同双revision仅一笔成功")
    void concurrentApplicationPublicationAllowsExactlyOneWinner() throws Exception {
        Fixture fixture = seed();
        DashboardCatalogEntry dashboard = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.create(fixture.projectId(), "并发应用看板", schema("并发应用页面")));
        DashboardVersion dashboardVersion = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> publications.publish(fixture.projectId(), dashboard.id(), "0", "0"));
        ApplicationCatalogEntry application = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> applications.create(fixture.projectId(), "并发应用",
                        applicationDraft(dashboard.id(), dashboardVersion.id())));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<ApplicationPublishOutcome> first = executor.submit(
                    () -> publishApplicationConcurrently(fixture, application.id(), ready, start));
            Future<ApplicationPublishOutcome> second = executor.submit(
                    () -> publishApplicationConcurrently(fixture, application.id(), ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<ApplicationPublishOutcome> outcomes = List.of(
                    first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

            assertThat(outcomes).filteredOn(ApplicationPublishOutcome::published).hasSize(1);
            assertThat(outcomes).filteredOn(outcome -> Integer.valueOf(60044).equals(outcome.errorCode()))
                    .hasSize(1);
        }
        assertThat(applicationPublicationFacts(fixture.projectId(), application.id()).getFirst())
                .contains("\"publicationRevision\": 1", "\"versionCount\": 1",
                        "\"referenceCount\": 1", "application.published");
        assertThat(applicationAuditCount(
                fixture.projectId(), application.id(), "application.published")).isOne();
    }

    /** 发布真实等待应用目录锁后必须重读草稿revision，不能消费等待前观察形成版本。 */
    @Test
    @DisplayName("应用发布等待目录锁后按最新草稿revision拒绝")
    void applicationPublicationWaitsForLockAndRejectsChangedDraftRevision() throws Exception {
        Fixture fixture = seed();
        DashboardCatalogEntry dashboard = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.create(fixture.projectId(), "等待应用看板", schema("等待应用页面")));
        DashboardVersion dashboardVersion = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> publications.publish(fixture.projectId(), dashboard.id(), "0", "0"));
        ApplicationCatalogEntry application = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> applications.create(fixture.projectId(), "等待重建应用",
                        applicationDraft(dashboard.id(), dashboardVersion.id())));
        List<String> before = applicationPublicationFacts(fixture.projectId(), application.id());

        ApplicationPublishOutcome outcome;
        try (Connection blocker = application(); ExecutorService executor = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            setSessionProjectContext(blocker, fixture.ownerTenantId(), fixture.projectId());
            lockApplicationPublicationRows(blocker, fixture.projectId(), application.id());
            Future<ApplicationPublishOutcome> pending = executor.submit(() -> publishApplication(
                    fixture, application.id(), "0", "0"));
            try {
                assertThat(awaitApplicationPublicationDatabaseLock()).isTrue();
                execute(blocker, """
                        UPDATE app_application_draft
                           SET revision=revision+1, updated_by=?, updated_at=now()
                         WHERE project_id=? AND application_id=?
                        """, fixture.ownerId(), fixture.projectId(), application.id());
            } finally {
                blocker.commit();
            }
            outcome = pending.get(5, TimeUnit.SECONDS);
        }

        assertThat(outcome.published()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo(60044);
        assertThat(applicationPublicationFacts(fixture.projectId(), application.id())).isEqualTo(before);
        ApplicationPublicationState state = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> applicationRepository.findPublicationState(
                        fixture.projectId(), application.id()).orElseThrow());
        assertThat(state.draftRevision()).isOne();
        assertThat(applicationAuditCount(
                fixture.projectId(), application.id(), "application.published")).isZero();
    }

    /** 应用发布审计真实写入后的故障必须回滚版本、关系、指针、revision和审计本身，并允许原命令重试。 */
    @Test
    @DisplayName("应用发布审计失败回滚整笔事务并允许重试")
    void applicationPublicationAuditFailureRollsBackAndAllowsRetry() throws Exception {
        Fixture fixture = seed();
        DashboardCatalogEntry dashboard = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.create(fixture.projectId(), "应用审计看板", schema("应用审计页面")));
        DashboardVersion dashboardVersion = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> publications.publish(fixture.projectId(), dashboard.id(), "0", "0"));
        ApplicationCatalogEntry application = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> applications.create(fixture.projectId(), "审计回滚应用",
                        applicationDraft(dashboard.id(), dashboardVersion.id())));
        List<String> before = applicationPublicationFacts(fixture.projectId(), application.id());
        doAnswer(invocation -> {
            invocation.callRealMethod();
            AuditLogEntry entry = invocation.getArgument(0, AuditLogEntry.class);
            if ("application.published".equals(entry.action())) {
                throw new AuditFailureAfterInsertException();
            }
            return null;
        }).when(audits).record(any(AuditLogEntry.class));

        try {
            Throwable failure = catchThrowable(() -> as(
                    fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                    () -> applicationPublications.publish(
                            fixture.projectId(), application.id(), "0", "0")));
            assertThat(failure).isInstanceOf(AuditFailureAfterInsertException.class);
            assertThat(applicationPublicationFacts(fixture.projectId(), application.id())).isEqualTo(before);

            doCallRealMethod().when(audits).record(any(AuditLogEntry.class));
            ApplicationVersion retried = as(
                    fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                    () -> applicationPublications.publish(
                            fixture.projectId(), application.id(), "0", "0"));
            assertThat(retried.versionNumber()).isOne();
            assertThat(applicationPublicationFacts(fixture.projectId(), application.id()).getFirst())
                    .contains("\"publicationRevision\": 1", "\"versionCount\": 1",
                            "\"referenceCount\": 1", "application.published");
            assertThat(applicationAuditCount(
                    fixture.projectId(), application.id(), "application.published")).isOne();
        } finally {
            doCallRealMethod().when(audits).record(any(AuditLogEntry.class));
        }
    }

    /** 受控函数收到与PG快照不一致的摘要时必须在写版本前失败，且不留下任何发布事实。 */
    @Test
    @DisplayName("应用受控发布拒绝篡改摘要且保持零写")
    void controlledApplicationPublicationRejectsTamperedDigestWithoutWrites() throws Exception {
        Fixture fixture = seed();
        DashboardCatalogEntry dashboard = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.create(fixture.projectId(), "应用摘要围栏看板", schema("摘要围栏页面")));
        DashboardVersion dashboardVersion = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> publications.publish(fixture.projectId(), dashboard.id(), "0", "0"));
        ApplicationCatalogEntry application = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> applications.create(fixture.projectId(), "应用摘要围栏",
                        applicationDraft(dashboard.id(), dashboardVersion.id())));
        ApplicationDraft draft = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> applications.getDraft(fixture.projectId(), application.id()));
        ApplicationPublicationCandidate candidate = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> applicationPublicationCandidates.prepare(draft));
        List<String> before = applicationPublicationFacts(fixture.projectId(), application.id());

        assertPostgresConstraint(() -> {
            try (Connection app = application()) {
                setSessionProjectContext(app, fixture.ownerTenantId(), fixture.projectId());
                callApplicationPublicationFunction(
                        app, candidate, UUID.randomUUID(), fixture.ownerId(), "f".repeat(64), 0);
            }
        }, "app_application_publish_snapshot_digest");

        assertThat(applicationPublicationFacts(fixture.projectId(), application.id())).isEqualTo(before);
        assertThat(applicationAuditCount(
                fixture.projectId(), application.id(), "application.published")).isZero();
    }

    /** A→B→A只移动应用指针，历史版本和精确关系不变，后续发布仍必须分配版本3。 */
    @Test
    @DisplayName("应用受控回滚保留历史并维持后续版本号单调")
    void applicationRollbackPreservesHistoryAndKeepsFutureVersionNumbersMonotonic() throws Exception {
        Fixture fixture = seed();
        ApplicationRollbackFixture rollback = applicationRollbackFixture(fixture, "应用回滚主路径");
        List<String> immutableHistory = immutableApplicationVersionFacts(
                fixture.projectId(), rollback.application().id(),
                List.of(rollback.versionA().id(), rollback.versionB().id()));

        ApplicationVersion restored = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> applicationPublications.rollback(
                        fixture.projectId(), rollback.application().id(), rollback.versionA().id(), "2"));
        ApplicationPublicationState afterRollback = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> applicationRepository.findPublicationState(
                        fixture.projectId(), rollback.application().id()).orElseThrow());
        List<String> historyAfterRollback = immutableApplicationVersionFacts(
                fixture.projectId(), rollback.application().id(),
                List.of(rollback.versionA().id(), rollback.versionB().id()));
        long rollbackAuditCount = applicationAuditCount(
                fixture.projectId(), rollback.application().id(),
                "application.rolled_back");

        assertSoftly(softly -> {
            softly.assertThat(restored).isEqualTo(rollback.versionA());
            softly.assertThat(afterRollback.currentVersionId()).isEqualTo(rollback.versionA().id());
            softly.assertThat(afterRollback.publicationRevision()).isEqualTo(3);
            softly.assertThat(afterRollback.latestVersionNumber()).isEqualTo(2);
            softly.assertThat(historyAfterRollback).isEqualTo(immutableHistory);
            softly.assertThat(rollbackAuditCount).isOne();
        });

        as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> applications.saveDraft(
                        fixture.projectId(), rollback.application().id(), "1",
                        applicationDraft("版本C", rollback.dashboard().id(), rollback.dashboardVersion().id())));
        ApplicationVersion versionC = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> applicationPublications.publish(
                        fixture.projectId(), rollback.application().id(), "2", "3"));

        assertThat(versionC.versionNumber()).isEqualTo(3);
        assertThat(immutableApplicationVersionFacts(
                fixture.projectId(), rollback.application().id(),
                List.of(rollback.versionA().id(), rollback.versionB().id())))
                .isEqualTo(immutableHistory);
        assertThat(applicationAuditCount(
                fixture.projectId(), rollback.application().id(),
                "application.rolled_back")).isOne();
    }

    /** 两个同expected回滚在应用目录锁上一胜一冲突，且只提交一次指针变化和一次审计。 */
    @Test
    @DisplayName("并发应用回滚同一revision仅一笔成功")
    void concurrentApplicationRollbackAllowsExactlyOneWinner() throws Exception {
        Fixture fixture = seed();
        ApplicationRollbackFixture rollback = applicationRollbackFixture(fixture, "应用并发回滚");
        CountDownLatch winnerReachedAudit = new CountDownLatch(1);
        CountDownLatch releaseWinner = new CountDownLatch(1);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            AuditLogEntry entry = invocation.getArgument(0, AuditLogEntry.class);
            if ("application.rolled_back".equals(entry.action())) {
                winnerReachedAudit.countDown();
                if (!releaseWinner.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("应用并发回滚赢家未按时放行");
                }
            }
            return null;
        }).when(audits).record(any(AuditLogEntry.class));

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<ApplicationRollbackOutcome> first = executor.submit(
                    () -> rollbackApplication(
                            fixture, rollback.application().id(), rollback.versionA().id(), "2"));
            assertThat(winnerReachedAudit.await(5, TimeUnit.SECONDS)).isTrue();
            Future<ApplicationRollbackOutcome> second = executor.submit(
                    () -> rollbackApplication(
                            fixture, rollback.application().id(), UUID.randomUUID(), "2"));
            try {
                assertThat(awaitApplicationPublicationDatabaseLock()).isTrue();
            } finally {
                releaseWinner.countDown();
            }

            assertThat(first.get(5, TimeUnit.SECONDS).version()).isEqualTo(rollback.versionA());
            assertThat(second.get(5, TimeUnit.SECONDS).errorCode()).isEqualTo(60044);
        } finally {
            releaseWinner.countDown();
            doCallRealMethod().when(audits).record(any(AuditLogEntry.class));
        }
        ApplicationPublicationState state = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> applicationRepository.findPublicationState(
                        fixture.projectId(), rollback.application().id()).orElseThrow());
        assertThat(state.currentVersionId()).isEqualTo(rollback.versionA().id());
        assertThat(state.publicationRevision()).isEqualTo(3);
        assertThat(applicationAuditCount(
                fixture.projectId(), rollback.application().id(),
                "application.rolled_back")).isOne();
    }

    /** 回滚真实等待看板行锁后必须按提交后的撤回事实重验，不能消费等待前的可运行观察。 */
    @Test
    @DisplayName("应用回滚等待看板锁后按撤回事实拒绝")
    void applicationRollbackWaitsForDashboardLockAndRevalidatesRunnableState() throws Exception {
        Fixture fixture = seed();
        ApplicationRollbackFixture rollback = applicationRollbackFixture(fixture, "应用锁后重验");
        List<String> before = applicationPublicationFacts(
                fixture.projectId(), rollback.application().id());

        ApplicationRollbackOutcome outcome;
        try (Connection blocker = application(); ExecutorService executor = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            setSessionProjectContext(blocker, fixture.ownerTenantId(), fixture.projectId());
            lockPublicationRows(blocker, fixture.projectId(), rollback.dashboard().id());
            Future<ApplicationRollbackOutcome> pending = executor.submit(
                    () -> rollbackApplication(
                            fixture, rollback.application().id(), rollback.versionA().id(), "2"));
            try {
                assertThat(awaitApplicationDatabaseLock()).isTrue();
                WithdrawalFunctionResult withdrawal = callWithdrawalFunction(
                        blocker, fixture.projectId(), rollback.dashboard().id(),
                        1, fixture.ownerId());
                assertThat(withdrawal.status()).isEqualTo("WITHDRAWN");
            } finally {
                blocker.commit();
            }
            outcome = pending.get(5, TimeUnit.SECONDS);
        }

        assertThat(outcome.errorCode()).isEqualTo(60043);
        assertThat(applicationPublicationFacts(
                fixture.projectId(), rollback.application().id())).isEqualTo(before);
        assertThat(applicationAuditCount(
                fixture.projectId(), rollback.application().id(),
                "application.rolled_back")).isZero();
    }

    /** 目标、publicationRevision、宿主和看板生命周期拒绝必须使用稳定错误且保持应用事实不变。 */
    @Test
    @DisplayName("应用回滚失败边界按60043至60046分类且零写")
    void applicationRollbackFailureBoundariesLeaveFactsUntouched() throws Exception {
        Fixture fixture = seed();
        ApplicationRollbackFixture rollback = applicationRollbackFixture(fixture, "应用回滚失败边界");
        List<String> baseline = applicationPublicationFacts(
                fixture.projectId(), rollback.application().id());

        assertThat(rollbackApplication(
                fixture, rollback.application().id(), UUID.randomUUID(), "2").errorCode()).isEqualTo(60046);
        assertThat(rollbackApplication(
                fixture, rollback.application().id(), rollback.versionB().id(), "2").errorCode()).isEqualTo(60044);
        assertThat(rollbackApplication(
                fixture, rollback.application().id(), rollback.versionA().id(), "1").errorCode()).isEqualTo(60044);
        assertThat(applicationPublicationFacts(
                fixture.projectId(), rollback.application().id())).isEqualTo(baseline);

        when(hostQualifications.current()).thenReturn(Optional.empty());
        assertThat(rollbackApplication(
                fixture, rollback.application().id(), rollback.versionA().id(), "2").errorCode()).isEqualTo(60045);
        assertThat(applicationPublicationFacts(
                fixture.projectId(), rollback.application().id())).isEqualTo(baseline);
        when(hostQualifications.current()).thenReturn(Optional.of(new DashboardHostQualificationDescriptor(
                "tc.webapp-host/v1", "1.0.0", Set.of("tc.application/v1"), Set.of("tc.dashboard/v1"),
                Map.of(DashboardPublicationEligibilityRequirement.ComponentKind.TEXT, "1.0.0"), Map.of())));

        as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(), () -> {
            publications.softDelete(fixture.projectId(), rollback.dashboard().id(), "1");
            return null;
        });
        assertThat(rollbackApplication(
                fixture, rollback.application().id(), rollback.versionA().id(), "2").errorCode()).isEqualTo(60043);
        assertThat(applicationPublicationFacts(
                fixture.projectId(), rollback.application().id())).isEqualTo(baseline);

        try (Connection owner = owner()) {
            execute(owner, """
                    UPDATE app_application
                       SET publication_revision=9223372036854775807
                     WHERE project_id=? AND id=?
                    """, fixture.projectId(), rollback.application().id());
        }
        List<String> exhausted = applicationPublicationFacts(
                fixture.projectId(), rollback.application().id());
        assertThat(rollbackApplication(
                fixture, rollback.application().id(), rollback.versionA().id(),
                Long.toString(Long.MAX_VALUE)).errorCode()).isEqualTo(60044);
        assertThat(applicationPublicationFacts(
                fixture.projectId(), rollback.application().id())).isEqualTo(exhausted);
        assertThat(applicationAuditCount(
                fixture.projectId(), rollback.application().id(),
                "application.rolled_back")).isZero();
    }

    /** 回滚审计真实INSERT后的故障必须撤销指针与revision，保留历史并允许原revision重试。 */
    @Test
    @DisplayName("应用回滚审计失败撤销指针并允许重试")
    void applicationRollbackAuditFailureRestoresPointerAndAllowsRetry() throws Exception {
        Fixture fixture = seed();
        ApplicationRollbackFixture rollback = applicationRollbackFixture(fixture, "应用回滚审计");
        List<String> factsBefore = applicationPublicationFacts(
                fixture.projectId(), rollback.application().id());
        List<String> historyBefore = immutableApplicationVersionFacts(
                fixture.projectId(), rollback.application().id(),
                List.of(rollback.versionA().id(), rollback.versionB().id()));
        doAnswer(invocation -> {
            invocation.callRealMethod();
            AuditLogEntry entry = invocation.getArgument(0, AuditLogEntry.class);
            if ("application.rolled_back".equals(entry.action())) {
                throw new AuditFailureAfterInsertException();
            }
            return null;
        }).when(audits).record(any(AuditLogEntry.class));

        try {
            Throwable failure = catchThrowable(() -> as(
                    fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                    () -> applicationPublications.rollback(
                            fixture.projectId(), rollback.application().id(),
                            rollback.versionA().id(), "2")));
            assertThat(failure).isInstanceOf(AuditFailureAfterInsertException.class);
            assertThat(applicationPublicationFacts(
                    fixture.projectId(), rollback.application().id())).isEqualTo(factsBefore);
            assertThat(immutableApplicationVersionFacts(
                    fixture.projectId(), rollback.application().id(),
                    List.of(rollback.versionA().id(), rollback.versionB().id())))
                    .isEqualTo(historyBefore);
            assertThat(applicationAuditCount(
                    fixture.projectId(), rollback.application().id(),
                    "application.rolled_back")).isZero();

            doCallRealMethod().when(audits).record(any(AuditLogEntry.class));
            ApplicationVersion retried = as(
                    fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                    () -> applicationPublications.rollback(
                            fixture.projectId(), rollback.application().id(),
                            rollback.versionA().id(), "2"));
            assertThat(retried).isEqualTo(rollback.versionA());
            assertThat(applicationAuditCount(
                    fixture.projectId(), rollback.application().id(),
                    "application.rolled_back")).isOne();
        } finally {
            doCallRealMethod().when(audits).record(any(AuditLogEntry.class));
        }
    }

    /** ADMIN撤回只清当前指针并推进发布轴，历史版本、精确关系和独立草稿必须逐行不变。 */
    @Test
    @DisplayName("应用撤回保留全部内容事实并写入唯一审计")
    void applicationWithdrawalPreservesContentAndWritesSingleAudit() throws Exception {
        Fixture fixture = seed();
        ApplicationRollbackFixture withdrawal = applicationRollbackFixture(fixture, "应用撤回主路径");
        List<String> immutableContent = immutableApplicationContentFacts(
                fixture.projectId(), withdrawal.application().id(),
                List.of(withdrawal.versionA().id(), withdrawal.versionB().id()));

        ApplicationVersion previous = as(
                fixture.collaboratorTenantId(), fixture.projectId(), fixture.adminId(),
                () -> applicationPublications.withdraw(
                        fixture.projectId(), withdrawal.application().id(), "2"));
        ApplicationPublicationState state = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> applicationRepository.findPublicationState(
                        fixture.projectId(), withdrawal.application().id()).orElseThrow());
        List<String> contentAfter = immutableApplicationContentFacts(
                fixture.projectId(), withdrawal.application().id(),
                List.of(withdrawal.versionA().id(), withdrawal.versionB().id()));
        List<String> withdrawalAudits = applicationWithdrawalAuditFacts(
                fixture.projectId(), withdrawal.application().id());

        assertSoftly(softly -> {
            softly.assertThat(previous).isEqualTo(withdrawal.versionB());
            softly.assertThat(state.currentVersionId()).isNull();
            softly.assertThat(state.publicationRevision()).isEqualTo(3);
            softly.assertThat(state.latestVersionNumber()).isEqualTo(2);
            softly.assertThat(contentAfter).isEqualTo(immutableContent);
            softly.assertThat(withdrawalAudits)
                    .containsExactly(fixture.adminId() + ":" + withdrawal.versionB().id() + ":2:3");
        });

        List<String> withdrawnFacts = applicationPublicationFacts(
                fixture.projectId(), withdrawal.application().id());
        assertThat(withdrawApplication(
                fixture, withdrawal.application().id(), "3").errorCode()).isEqualTo(60044);
        assertThat(applicationPublicationFacts(
                fixture.projectId(), withdrawal.application().id())).isEqualTo(withdrawnFacts);
        assertThat(applicationAuditCount(
                fixture.projectId(), withdrawal.application().id(), "application.withdrawn")).isOne();
    }

    /** 同expected撤回必须真实争用应用目录行锁，只允许一笔指针变化和一条成功审计提交。 */
    @Test
    @DisplayName("并发应用撤回同一revision仅一笔成功")
    void concurrentApplicationWithdrawalAllowsExactlyOneWinner() throws Exception {
        Fixture fixture = seed();
        ApplicationRollbackFixture withdrawal = applicationRollbackFixture(fixture, "应用并发撤回");
        CountDownLatch winnerReachedAudit = new CountDownLatch(1);
        CountDownLatch releaseWinner = new CountDownLatch(1);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            AuditLogEntry entry = invocation.getArgument(0, AuditLogEntry.class);
            if ("application.withdrawn".equals(entry.action())) {
                winnerReachedAudit.countDown();
                if (!releaseWinner.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("应用并发撤回赢家未按时放行");
                }
            }
            return null;
        }).when(audits).record(any(AuditLogEntry.class));

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<ApplicationWithdrawalOutcome> first = executor.submit(
                    () -> withdrawApplication(fixture, withdrawal.application().id(), "2"));
            assertThat(winnerReachedAudit.await(5, TimeUnit.SECONDS)).isTrue();
            Future<ApplicationWithdrawalOutcome> second = executor.submit(
                    () -> withdrawApplication(fixture, withdrawal.application().id(), "2"));
            try {
                assertThat(awaitApplicationPublicationDatabaseLock()).isTrue();
            } finally {
                releaseWinner.countDown();
            }

            assertThat(first.get(5, TimeUnit.SECONDS).version()).isEqualTo(withdrawal.versionB());
            assertThat(second.get(5, TimeUnit.SECONDS).errorCode()).isEqualTo(60044);
        } finally {
            releaseWinner.countDown();
            doCallRealMethod().when(audits).record(any(AuditLogEntry.class));
        }
        ApplicationPublicationState state = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> applicationRepository.findPublicationState(
                        fixture.projectId(), withdrawal.application().id()).orElseThrow());
        assertThat(state.currentVersionId()).isNull();
        assertThat(state.publicationRevision()).isEqualTo(3);
        assertThat(applicationAuditCount(
                fixture.projectId(), withdrawal.application().id(), "application.withdrawn")).isOne();
    }

    /** 撤回审计真实INSERT后的故障必须回滚指针与revision，且原expected仍可完整重试。 */
    @Test
    @DisplayName("应用撤回审计失败回滚整笔事务并允许重试")
    void applicationWithdrawalAuditFailureRestoresPointerAndAllowsRetry() throws Exception {
        Fixture fixture = seed();
        ApplicationRollbackFixture withdrawal = applicationRollbackFixture(fixture, "应用撤回审计");
        List<String> factsBefore = applicationPublicationFacts(
                fixture.projectId(), withdrawal.application().id());
        List<String> contentBefore = immutableApplicationContentFacts(
                fixture.projectId(), withdrawal.application().id(),
                List.of(withdrawal.versionA().id(), withdrawal.versionB().id()));
        doAnswer(invocation -> {
            invocation.callRealMethod();
            AuditLogEntry entry = invocation.getArgument(0, AuditLogEntry.class);
            if ("application.withdrawn".equals(entry.action())) {
                throw new AuditFailureAfterInsertException();
            }
            return null;
        }).when(audits).record(any(AuditLogEntry.class));

        try {
            Throwable failure = catchThrowable(() -> as(
                    fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                    () -> applicationPublications.withdraw(
                            fixture.projectId(), withdrawal.application().id(), "2")));
            assertThat(failure).isInstanceOf(AuditFailureAfterInsertException.class);
            assertThat(applicationPublicationFacts(
                    fixture.projectId(), withdrawal.application().id())).isEqualTo(factsBefore);
            assertThat(immutableApplicationContentFacts(
                    fixture.projectId(), withdrawal.application().id(),
                    List.of(withdrawal.versionA().id(), withdrawal.versionB().id())))
                    .isEqualTo(contentBefore);
            assertThat(applicationAuditCount(
                    fixture.projectId(), withdrawal.application().id(), "application.withdrawn")).isZero();

            doCallRealMethod().when(audits).record(any(AuditLogEntry.class));
            ApplicationVersion retried = as(
                    fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                    () -> applicationPublications.withdraw(
                            fixture.projectId(), withdrawal.application().id(), "2"));
            assertThat(retried).isEqualTo(withdrawal.versionB());
            assertThat(applicationAuditCount(
                    fixture.projectId(), withdrawal.application().id(), "application.withdrawn")).isOne();
        } finally {
            doCallRealMethod().when(audits).record(any(AuditLogEntry.class));
        }
    }

    /** OWNER删除已发布应用、ADMIN删除已撤回应用，均只关闭目录并完整保留两个内容存储层。 */
    @Test
    @DisplayName("管理角色软删已发布与已撤回应用并保留全部内容事实")
    void managementRolesSoftDeletePublishedAndWithdrawnApplications() throws Exception {
        Fixture fixture = seed();
        ApplicationRollbackFixture published = applicationRollbackFixture(fixture, "OWNER应用删除");
        ApplicationRollbackFixture withdrawn = applicationRollbackFixture(fixture, "ADMIN应用删除");
        as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> applicationPublications.withdraw(
                        fixture.projectId(), withdrawn.application().id(), "2"));
        List<String> publishedContent = immutableApplicationContentFacts(
                fixture.projectId(), published.application().id(),
                List.of(published.versionA().id(), published.versionB().id()));
        List<String> withdrawnContent = immutableApplicationContentFacts(
                fixture.projectId(), withdrawn.application().id(),
                List.of(withdrawn.versionA().id(), withdrawn.versionB().id()));

        as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(), () -> {
            applicationPublications.softDelete(fixture.projectId(), published.application().id(), "2");
            return null;
        });
        as(fixture.collaboratorTenantId(), fixture.projectId(), fixture.adminId(), () -> {
            applicationPublications.softDelete(fixture.projectId(), withdrawn.application().id(), "3");
            return null;
        });

        ApplicationSoftDeletedCatalogFact publishedState = softDeletedApplicationCatalogFact(
                fixture.projectId(), published.application().id());
        ApplicationSoftDeletedCatalogFact withdrawnState = softDeletedApplicationCatalogFact(
                fixture.projectId(), withdrawn.application().id());
        ObjectNode publishedAudit = applicationDeletionAuditDetails(
                fixture.projectId(), published.application().id());
        ObjectNode withdrawnAudit = applicationDeletionAuditDetails(
                fixture.projectId(), withdrawn.application().id());
        List<String> publishedContentAfter = immutableApplicationContentFacts(
                fixture.projectId(), published.application().id(),
                List.of(published.versionA().id(), published.versionB().id()));
        List<String> withdrawnContentAfter = immutableApplicationContentFacts(
                fixture.projectId(), withdrawn.application().id(),
                List.of(withdrawn.versionA().id(), withdrawn.versionB().id()));
        assertSoftly(softly -> {
            softly.assertThat(publishedState.publicationRevision()).isEqualTo(3);
            softly.assertThat(publishedState.currentVersionId()).isNull();
            softly.assertThat(publishedState.deletedAt()).isNotNull();
            softly.assertThat(withdrawnState.publicationRevision()).isEqualTo(4);
            softly.assertThat(withdrawnState.currentVersionId()).isNull();
            softly.assertThat(withdrawnState.deletedAt()).isNotNull();
            softly.assertThat(publishedContentAfter).isEqualTo(publishedContent);
            softly.assertThat(withdrawnContentAfter).isEqualTo(withdrawnContent);
            softly.assertThat(publishedAudit.propertyNames()).containsExactlyInAnyOrder(
                    "previousVersionId", "previousVersionNumber", "publicationRevision", "deletedAt");
            softly.assertThat(publishedAudit.path("previousVersionId").asString())
                    .isEqualTo(published.versionB().id().toString());
            softly.assertThat(publishedAudit.path("previousVersionNumber").asString()).isEqualTo("2");
            softly.assertThat(publishedAudit.path("publicationRevision").asString()).isEqualTo("3");
            softly.assertThat(publishedAudit.path("deletedAt").asString())
                    .isEqualTo(publishedState.deletedAt().toString());
            softly.assertThat(withdrawnAudit.get("previousVersionId").isNull()).isTrue();
            softly.assertThat(withdrawnAudit.get("previousVersionNumber").isNull()).isTrue();
            softly.assertThat(withdrawnAudit.path("publicationRevision").asString()).isEqualTo("4");
            softly.assertThat(withdrawnAudit.path("deletedAt").asString())
                    .isEqualTo(withdrawnState.deletedAt().toString());
        });
        assertThat(applicationAuditCount(
                fixture.projectId(), published.application().id(), "application.deleted")).isOne();
        assertThat(applicationAuditCount(
                fixture.projectId(), withdrawn.application().id(), "application.deleted")).isOne();
    }

    /** 软删应用在管理、发布、回滚、撤回和重复删除入口均永久不可见，owner仍可核对历史。 */
    @Test
    @DisplayName("软删应用拒绝全部后续管理和发布状态命令")
    void softDeletedApplicationRejectsManagementAndPublicationCommands() throws Exception {
        Fixture fixture = seed();
        ApplicationRollbackFixture deletion = applicationRollbackFixture(fixture, "删除后不可见应用");
        List<String> retainedContent = immutableApplicationContentFacts(
                fixture.projectId(), deletion.application().id(),
                List.of(deletion.versionA().id(), deletion.versionB().id()));
        as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(), () -> {
            applicationPublications.softDelete(fixture.projectId(), deletion.application().id(), "2");
            return null;
        });

        List<Throwable> failures = List.of(
                catchThrowable(() -> as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                        () -> applications.find(fixture.projectId(), deletion.application().id()))),
                catchThrowable(() -> as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                        () -> applications.getDraft(fixture.projectId(), deletion.application().id()))),
                catchThrowable(() -> as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                        () -> applications.rename(
                                fixture.projectId(), deletion.application().id(), "删除后改名"))),
                catchThrowable(() -> as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                        () -> applications.saveDraft(
                                fixture.projectId(), deletion.application().id(), "1",
                                applicationDraft("删除后草稿", deletion.dashboard().id(),
                                        deletion.dashboardVersion().id())))),
                catchThrowable(() -> as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                        () -> applicationPublications.publish(
                                fixture.projectId(), deletion.application().id(), "1", "3"))),
                catchThrowable(() -> as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                        () -> applicationPublications.rollback(
                                fixture.projectId(), deletion.application().id(),
                                deletion.versionA().id(), "3"))),
                catchThrowable(() -> as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                        () -> applicationPublications.withdraw(
                                fixture.projectId(), deletion.application().id(), "3"))),
                catchThrowable(() -> as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(), () -> {
                    applicationPublications.softDelete(
                            fixture.projectId(), deletion.application().id(), "3");
                    return null;
                })));
        var visiblePage = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> applications.list(fixture.projectId(), null, 200));

        assertThat(failures).allSatisfy(failure -> assertThat(errorCode(failure)).isEqualTo(60030));
        assertThat(visiblePage.items()).extracting(ApplicationCatalogEntry::id)
                .doesNotContain(deletion.application().id());
        assertThat(immutableApplicationContentFacts(
                fixture.projectId(), deletion.application().id(),
                List.of(deletion.versionA().id(), deletion.versionB().id())))
                .isEqualTo(retainedContent);
        assertThat(applicationAuditCount(
                fixture.projectId(), deletion.application().id(), "application.deleted")).isOne();
    }

    /** 同expected软删必须真实争用应用目录锁，只允许一笔永久删除和一条成功审计提交。 */
    @Test
    @DisplayName("并发应用软删同一revision仅一笔成功")
    void concurrentApplicationSoftDeleteAllowsExactlyOneWinner() throws Exception {
        Fixture fixture = seed();
        ApplicationRollbackFixture deletion = applicationRollbackFixture(fixture, "应用并发软删");
        CountDownLatch winnerReachedAudit = new CountDownLatch(1);
        CountDownLatch releaseWinner = new CountDownLatch(1);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            AuditLogEntry entry = invocation.getArgument(0, AuditLogEntry.class);
            if ("application.deleted".equals(entry.action())) {
                winnerReachedAudit.countDown();
                if (!releaseWinner.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("应用并发软删赢家未按时放行");
                }
            }
            return null;
        }).when(audits).record(any(AuditLogEntry.class));

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<ApplicationSoftDeleteOutcome> first = executor.submit(
                    () -> softDeleteApplication(fixture, deletion.application().id(), "2"));
            assertThat(winnerReachedAudit.await(5, TimeUnit.SECONDS)).isTrue();
            Future<ApplicationSoftDeleteOutcome> second = executor.submit(
                    () -> softDeleteApplication(fixture, deletion.application().id(), "2"));
            try {
                assertThat(awaitApplicationPublicationDatabaseLock()).isTrue();
            } finally {
                releaseWinner.countDown();
            }

            assertThat(first.get(5, TimeUnit.SECONDS).deleted()).isTrue();
            assertThat(second.get(5, TimeUnit.SECONDS).errorCode()).isEqualTo(60030);
        } finally {
            releaseWinner.countDown();
            doCallRealMethod().when(audits).record(any(AuditLogEntry.class));
        }
        assertThat(softDeletedApplicationCatalogFact(
                fixture.projectId(), deletion.application().id())).satisfies(state -> {
                    assertThat(state.publicationRevision()).isEqualTo(3);
                    assertThat(state.currentVersionId()).isNull();
                    assertThat(state.deletedAt()).isNotNull();
                });
        assertThat(applicationAuditCount(
                fixture.projectId(), deletion.application().id(), "application.deleted")).isOne();
    }

    /** 软删审计真实INSERT后的故障必须恢复目录全状态，且同expected可完整重试。 */
    @Test
    @DisplayName("应用软删审计失败回滚整笔事务并允许重试")
    void applicationSoftDeleteAuditFailureRestoresCatalogAndAllowsRetry() throws Exception {
        Fixture fixture = seed();
        ApplicationRollbackFixture deletion = applicationRollbackFixture(fixture, "应用软删审计");
        ApplicationSoftDeletedCatalogFact stateBefore = softDeletedApplicationCatalogFact(
                fixture.projectId(), deletion.application().id());
        List<String> factsBefore = applicationPublicationFacts(
                fixture.projectId(), deletion.application().id());
        List<String> contentBefore = immutableApplicationContentFacts(
                fixture.projectId(), deletion.application().id(),
                List.of(deletion.versionA().id(), deletion.versionB().id()));
        doAnswer(invocation -> {
            invocation.callRealMethod();
            AuditLogEntry entry = invocation.getArgument(0, AuditLogEntry.class);
            if ("application.deleted".equals(entry.action())) {
                throw new AuditFailureAfterInsertException();
            }
            return null;
        }).when(audits).record(any(AuditLogEntry.class));

        try {
            Throwable failure = catchThrowable(() -> as(
                    fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(), () -> {
                        applicationPublications.softDelete(
                                fixture.projectId(), deletion.application().id(), "2");
                        return null;
                    }));
            assertThat(failure).isInstanceOf(AuditFailureAfterInsertException.class);
            assertThat(softDeletedApplicationCatalogFact(
                    fixture.projectId(), deletion.application().id())).isEqualTo(stateBefore);
            assertThat(applicationPublicationFacts(
                    fixture.projectId(), deletion.application().id())).isEqualTo(factsBefore);
            assertThat(immutableApplicationContentFacts(
                    fixture.projectId(), deletion.application().id(),
                    List.of(deletion.versionA().id(), deletion.versionB().id())))
                    .isEqualTo(contentBefore);
            assertThat(applicationAuditCount(
                    fixture.projectId(), deletion.application().id(), "application.deleted")).isZero();

            doCallRealMethod().when(audits).record(any(AuditLogEntry.class));
            as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(), () -> {
                applicationPublications.softDelete(
                        fixture.projectId(), deletion.application().id(), "2");
                return null;
            });
            assertThat(softDeletedApplicationCatalogFact(
                    fixture.projectId(), deletion.application().id())).satisfies(state -> {
                        assertThat(state.publicationRevision()).isEqualTo(3);
                        assertThat(state.currentVersionId()).isNull();
                        assertThat(state.deletedAt()).isNotNull();
                    });
            assertThat(applicationAuditCount(
                    fixture.projectId(), deletion.application().id(), "application.deleted")).isOne();
        } finally {
            doCallRealMethod().when(audits).record(any(AuditLogEntry.class));
        }
    }

    /** A→B→A只推进发布轴，历史A/B保持逐字节不变，后续发布继续分配版本3。 */
    @Test
    @DisplayName("受控回滚保持历史不变并继续递增版本号")
    void rollbackPreservesHistoryAndKeepsFutureVersionNumbersMonotonic() throws Exception {
        Fixture fixture = seed();
        DashboardCatalogEntry dashboard = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.create(fixture.projectId(), "版本回滚",
                        schema("版本A", fixture.localModelId(), LOCAL_DIGEST)));
        DashboardVersion versionA = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> publications.publish(fixture.projectId(), dashboard.id(), "0", "0"));
        as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.saveDraft(fixture.projectId(), dashboard.id(), "0",
                        schema("版本B", fixture.localModelId(), LOCAL_DIGEST)));
        DashboardVersion versionB = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> publications.publish(fixture.projectId(), dashboard.id(), "1", "1"));
        List<String> immutableHistory = immutableVersionFacts(
                fixture.projectId(), dashboard.id(), List.of(versionA.id(), versionB.id()));

        DashboardVersion rolledBack = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> publications.rollback(fixture.projectId(), dashboard.id(), versionA.id(), "2"));
        DashboardPublicationState afterRollback = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboardRepository.findPublicationState(
                        fixture.projectId(), dashboard.id()).orElseThrow());
        List<String> historyAfterRollback = immutableVersionFacts(
                fixture.projectId(), dashboard.id(), List.of(versionA.id(), versionB.id()));
        List<String> actionsAfterRollback = auditActions(fixture.projectId());

        assertSoftly(softly -> {
            softly.assertThat(rolledBack).isEqualTo(versionA);
            softly.assertThat(afterRollback.currentVersionId()).isEqualTo(versionA.id());
            softly.assertThat(afterRollback.publicationRevision()).isEqualTo(3);
            softly.assertThat(afterRollback.latestVersionNumber()).isEqualTo(2);
            softly.assertThat(historyAfterRollback).isEqualTo(immutableHistory);
            softly.assertThat(actionsAfterRollback).containsExactly(
                    "dashboard.created", "dashboard.published", "dashboard.draft_saved",
                    "dashboard.published", "dashboard.rolled_back");
        });

        as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.saveDraft(fixture.projectId(), dashboard.id(), "1",
                        schema("版本C", fixture.localModelId(), LOCAL_DIGEST)));
        DashboardVersion versionC = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> publications.publish(fixture.projectId(), dashboard.id(), "2", "3"));

        assertThat(versionC.versionNumber()).isEqualTo(3);
        assertThat(immutableVersionFacts(
                fixture.projectId(), dashboard.id(), List.of(versionA.id(), versionB.id())))
                .isEqualTo(immutableHistory);
    }

    /** 真实撤回只清空指针并保留历史，随后仍可把同一合格历史版本恢复为当前版本。 */
    @Test
    @DisplayName("撤回后可恢复合格历史版本且历史保持不变")
    void withdrawalPreservesHistoryAndRollbackRestoresIt() throws Exception {
        Fixture fixture = seed();
        DashboardCatalogEntry dashboard = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.create(fixture.projectId(), "撤回后恢复",
                        schema("恢复版本", fixture.localModelId(), LOCAL_DIGEST)));
        DashboardVersion version = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> publications.publish(fixture.projectId(), dashboard.id(), "0", "0"));
        List<String> immutableHistory = immutableVersionFacts(
                fixture.projectId(), dashboard.id(), List.of(version.id()));

        DashboardVersion withdrawn = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> publications.withdraw(fixture.projectId(), dashboard.id(), "1"));
        DashboardPublicationState afterWithdrawal = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboardRepository.findPublicationState(
                        fixture.projectId(), dashboard.id()).orElseThrow());

        assertThat(withdrawn).isEqualTo(version);
        assertThat(afterWithdrawal.currentVersionId()).isNull();
        assertThat(afterWithdrawal.publicationRevision()).isEqualTo(2);
        assertThat(afterWithdrawal.latestVersionNumber()).isOne();
        assertThat(immutableVersionFacts(
                fixture.projectId(), dashboard.id(), List.of(version.id())))
                .isEqualTo(immutableHistory);
        assertThat(auditActions(fixture.projectId())).containsExactly(
                "dashboard.created", "dashboard.published", "dashboard.withdrawn");

        DashboardVersion restored = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> publications.rollback(fixture.projectId(), dashboard.id(), version.id(), "2"));
        DashboardPublicationState state = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboardRepository.findPublicationState(
                        fixture.projectId(), dashboard.id()).orElseThrow());

        assertThat(restored).isEqualTo(version);
        assertThat(state.currentVersionId()).isEqualTo(version.id());
        assertThat(state.publicationRevision()).isEqualTo(3);
        assertThat(state.latestVersionNumber()).isOne();
        assertThat(immutableVersionFacts(
                fixture.projectId(), dashboard.id(), List.of(version.id())))
                .isEqualTo(immutableHistory);
        assertThat(auditActions(fixture.projectId())).containsExactly(
                "dashboard.created", "dashboard.published",
                "dashboard.withdrawn", "dashboard.rolled_back");
    }

    /** OWNER可删除已发布看板，ADMIN可在发布并撤回后删除，且revision单调、历史与关系均永久保留。 */
    @Test
    @DisplayName("管理角色软删有无当前版本的看板并保留全部子事实")
    void managementRolesSoftDeletePublishedAndWithdrawnDashboards() throws Exception {
        Fixture fixture = seed();
        DashboardCatalogEntry published = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.create(fixture.projectId(), "OWNER删除",
                        schema("OWNER历史", fixture.localModelId(), LOCAL_DIGEST)));
        DashboardVersion previousVersion = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> publications.publish(fixture.projectId(), published.id(), "0", "0"));
        DashboardCatalogEntry withdrawnDashboard = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.adminId(),
                () -> dashboards.create(fixture.projectId(), "ADMIN删除",
                        schema("ADMIN草稿", fixture.localModelId(), LOCAL_DIGEST)));
        DashboardVersion withdrawnVersion = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.adminId(),
                () -> publications.publish(fixture.projectId(), withdrawnDashboard.id(), "0", "0"));
        as(fixture.ownerTenantId(), fixture.projectId(), fixture.adminId(),
                () -> publications.withdraw(fixture.projectId(), withdrawnDashboard.id(), "1"));
        List<String> publishedFacts = retainedDashboardFacts(fixture.projectId(), published.id());
        List<String> withdrawnFacts = retainedDashboardFacts(
                fixture.projectId(), withdrawnDashboard.id());

        as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> {
                    publications.softDelete(fixture.projectId(), published.id(), "1");
                    return null;
                });
        as(fixture.ownerTenantId(), fixture.projectId(), fixture.adminId(),
                () -> {
                    publications.softDelete(fixture.projectId(), withdrawnDashboard.id(), "2");
                    return null;
                });

        SoftDeletedCatalogFact publishedState = softDeletedCatalogFact(
                fixture.projectId(), published.id());
        SoftDeletedCatalogFact withdrawnState = softDeletedCatalogFact(
                fixture.projectId(), withdrawnDashboard.id());
        List<String> publishedFactsAfter = retainedDashboardFacts(fixture.projectId(), published.id());
        List<String> withdrawnFactsAfter = retainedDashboardFacts(
                fixture.projectId(), withdrawnDashboard.id());
        ObjectNode publishedAudit = deletionAuditDetails(fixture.projectId(), published.id());
        ObjectNode withdrawnAudit = deletionAuditDetails(fixture.projectId(), withdrawnDashboard.id());
        assertSoftly(softly -> {
            softly.assertThat(publishedState.publicationRevision()).isEqualTo(2);
            softly.assertThat(publishedState.currentVersionId()).isNull();
            softly.assertThat(publishedState.deletedAt()).isNotNull();
            softly.assertThat(withdrawnState.publicationRevision()).isEqualTo(3);
            softly.assertThat(withdrawnState.currentVersionId()).isNull();
            softly.assertThat(withdrawnState.deletedAt()).isNotNull();
            softly.assertThat(publishedFactsAfter).isEqualTo(publishedFacts);
            softly.assertThat(withdrawnFactsAfter).isEqualTo(withdrawnFacts);
            softly.assertThat(withdrawnFacts).anyMatch(
                    fact -> fact.contains(withdrawnVersion.id().toString()));
            softly.assertThat(publishedAudit.propertyNames())
                    .containsExactlyInAnyOrder(
                            "previousVersionId", "previousVersionNumber",
                            "publicationRevision", "deletedAt");
            softly.assertThat(publishedAudit.path("previousVersionId").asString())
                    .isEqualTo(previousVersion.id().toString());
            softly.assertThat(publishedAudit.path("previousVersionNumber").asString()).isEqualTo("1");
            softly.assertThat(publishedAudit.path("publicationRevision").asString()).isEqualTo("2");
            softly.assertThat(publishedAudit.path("deletedAt").asString())
                    .isEqualTo(publishedState.deletedAt().toString());
            softly.assertThat(withdrawnAudit.get("previousVersionId").isNull()).isTrue();
            softly.assertThat(withdrawnAudit.get("previousVersionNumber").isNull()).isTrue();
            softly.assertThat(withdrawnAudit.path("publicationRevision").asString()).isEqualTo("3");
            softly.assertThat(withdrawnAudit.path("deletedAt").asString())
                    .isEqualTo(withdrawnState.deletedAt().toString());
        });
    }

    /** 目录软删后所有管理与发布状态命令都应把目标视为不存在，历史事实保持owner可审计。 */
    @Test
    @DisplayName("软删看板拒绝全部后续管理和发布状态命令")
    void softDeletedDashboardRejectsManagementAndPublicationCommands() throws Exception {
        Fixture fixture = seed();
        DashboardCatalogEntry dashboard = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.create(fixture.projectId(), "删除后不可见",
                        schema("删除历史", fixture.localModelId(), LOCAL_DIGEST)));
        DashboardVersion version = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> publications.publish(fixture.projectId(), dashboard.id(), "0", "0"));
        List<String> retainedFacts = retainedDashboardFacts(fixture.projectId(), dashboard.id());
        as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> {
                    publications.softDelete(fixture.projectId(), dashboard.id(), "1");
                    return null;
                });

        List<Throwable> failures = List.of(
                catchThrowable(() -> as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                        () -> dashboards.find(fixture.projectId(), dashboard.id()))),
                catchThrowable(() -> as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                        () -> dashboards.getDraft(fixture.projectId(), dashboard.id()))),
                catchThrowable(() -> as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                        () -> dashboards.rename(fixture.projectId(), dashboard.id(), "删除后改名"))),
                catchThrowable(() -> as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                        () -> dashboards.saveDraft(fixture.projectId(), dashboard.id(), "0",
                                schema("删除后保存", fixture.localModelId(), LOCAL_DIGEST)))),
                catchThrowable(() -> as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                        () -> publications.publish(fixture.projectId(), dashboard.id(), "0", "2"))),
                catchThrowable(() -> as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                        () -> publications.rollback(
                                fixture.projectId(), dashboard.id(), version.id(), "2"))),
                catchThrowable(() -> as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                        () -> publications.withdraw(fixture.projectId(), dashboard.id(), "2"))),
                catchThrowable(() -> as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                        () -> {
                            publications.softDelete(fixture.projectId(), dashboard.id(), "2");
                            return null;
                        })));
        var visiblePage = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.list(fixture.projectId(), null, 200));

        assertThat(failures).allSatisfy(failure -> assertThat(errorCode(failure)).isEqualTo(60034));
        assertThat(visiblePage.items()).extracting(DashboardCatalogEntry::id)
                .doesNotContain(dashboard.id());
        assertThat(retainedDashboardFacts(fixture.projectId(), dashboard.id())).isEqualTo(retainedFacts);
        assertThat(auditActions(fixture.projectId())).containsExactly(
                "dashboard.created", "dashboard.published", "dashboard.deleted");
    }

    /** 撤回审计真实INSERT后的异常必须恢复指针和revision，并允许原命令安全重试。 */
    @Test
    @DisplayName("撤回审计失败恢复发布状态且允许原revision重试")
    void withdrawalAuditFailureRestoresPointerAndAllowsRetry() throws Exception {
        Fixture fixture = seed();
        DashboardCatalogEntry dashboard = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.create(fixture.projectId(), "撤回审计",
                        schema("撤回审计版本", fixture.localModelId(), LOCAL_DIGEST)));
        DashboardVersion version = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> publications.publish(fixture.projectId(), dashboard.id(), "0", "0"));
        List<String> factsBefore = publicationFacts(fixture.projectId(), dashboard.id());
        List<String> historyBefore = immutableVersionFacts(
                fixture.projectId(), dashboard.id(), List.of(version.id()));
        doAnswer(invocation -> {
            invocation.callRealMethod();
            AuditLogEntry entry = invocation.getArgument(0, AuditLogEntry.class);
            if ("dashboard.withdrawn".equals(entry.action())) {
                throw new AuditFailureAfterInsertException();
            }
            return null;
        }).when(audits).record(any(AuditLogEntry.class));

        try {
            Throwable failure = catchThrowable(() -> as(
                    fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                    () -> publications.withdraw(fixture.projectId(), dashboard.id(), "1")));
            assertThat(failure).isInstanceOf(AuditFailureAfterInsertException.class);
            assertThat(publicationFacts(fixture.projectId(), dashboard.id())).isEqualTo(factsBefore);
            assertThat(immutableVersionFacts(
                    fixture.projectId(), dashboard.id(), List.of(version.id())))
                    .isEqualTo(historyBefore);

            doCallRealMethod().when(audits).record(any(AuditLogEntry.class));
            DashboardVersion retried = as(
                    fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                    () -> publications.withdraw(fixture.projectId(), dashboard.id(), "1"));
            assertThat(retried).isEqualTo(version);
            assertThat(publicationFacts(fixture.projectId(), dashboard.id())).containsExactly(
                    "catalog:2:null", "versions:1", "version_refs:1", "audits:3");
        } finally {
            doCallRealMethod().when(audits).record(any(AuditLogEntry.class));
        }
    }

    /** 软删审计真实INSERT后失败必须恢复目录指针、revision和deletedAt，并允许同命令重试。 */
    @Test
    @DisplayName("软删审计失败恢复目录状态且允许原revision重试")
    void softDeleteAuditFailureRollsBackCatalogAndAllowsRetry() throws Exception {
        Fixture fixture = seed();
        DashboardCatalogEntry dashboard = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.create(fixture.projectId(), "删除审计",
                        schema("删除审计历史", fixture.localModelId(), LOCAL_DIGEST)));
        DashboardVersion version = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> publications.publish(fixture.projectId(), dashboard.id(), "0", "0"));
        SoftDeletedCatalogFact catalogBefore = softDeletedCatalogFact(
                fixture.projectId(), dashboard.id());
        List<String> factsBefore = publicationFacts(fixture.projectId(), dashboard.id());
        List<String> retainedBefore = retainedDashboardFacts(fixture.projectId(), dashboard.id());
        doAnswer(invocation -> {
            invocation.callRealMethod();
            AuditLogEntry entry = invocation.getArgument(0, AuditLogEntry.class);
            if ("dashboard.deleted".equals(entry.action())) {
                throw new AuditFailureAfterInsertException();
            }
            return null;
        }).when(audits).record(any(AuditLogEntry.class));

        try {
            Throwable failure = catchThrowable(() -> as(
                    fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                    () -> {
                        publications.softDelete(fixture.projectId(), dashboard.id(), "1");
                        return null;
                    }));
            assertThat(failure).isInstanceOf(AuditFailureAfterInsertException.class);
            assertThat(softDeletedCatalogFact(fixture.projectId(), dashboard.id()))
                    .isEqualTo(catalogBefore);
            assertThat(publicationFacts(fixture.projectId(), dashboard.id())).isEqualTo(factsBefore);
            assertThat(retainedDashboardFacts(fixture.projectId(), dashboard.id()))
                    .isEqualTo(retainedBefore);

            doCallRealMethod().when(audits).record(any(AuditLogEntry.class));
            as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                    () -> {
                        publications.softDelete(fixture.projectId(), dashboard.id(), "1");
                        return null;
                    });
            assertThat(softDeletedCatalogFact(fixture.projectId(), dashboard.id()))
                    .satisfies(state -> {
                        assertThat(state.publicationRevision()).isEqualTo(2);
                        assertThat(state.currentVersionId()).isNull();
                        assertThat(state.deletedAt()).isNotNull();
                    });
            assertThat(retainedDashboardFacts(fixture.projectId(), dashboard.id()))
                    .isEqualTo(retainedBefore);
            assertThat(auditActions(fixture.projectId())).containsExactly(
                    "dashboard.created", "dashboard.published", "dashboard.deleted");
        } finally {
            doCallRealMethod().when(audits).record(any(AuditLogEntry.class));
        }
    }

    /** 同revision软删先持锁并暂停提交时，撤回必须真实等待且提交后只有软删成功。 */
    @Test
    @DisplayName("软删与撤回并发相同revision仅软删赢家提交")
    void concurrentSoftDeleteAndWithdrawalAllowOneWinnerAfterDatabaseLockWait() throws Exception {
        Fixture fixture = seed();
        DashboardCatalogEntry dashboard = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.create(fixture.projectId(), "删除撤回竞争",
                        schema("竞争历史", fixture.localModelId(), LOCAL_DIGEST)));
        DashboardVersion version = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> publications.publish(fixture.projectId(), dashboard.id(), "0", "0"));
        List<String> retainedBefore = retainedDashboardFacts(fixture.projectId(), dashboard.id());
        CountDownLatch deleteReachedAudit = new CountDownLatch(1);
        CountDownLatch releaseDelete = new CountDownLatch(1);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            AuditLogEntry entry = invocation.getArgument(0, AuditLogEntry.class);
            if ("dashboard.deleted".equals(entry.action())) {
                deleteReachedAudit.countDown();
                if (!releaseDelete.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("看板并发软删赢家未按时放行");
                }
            }
            return null;
        }).when(audits).record(any(AuditLogEntry.class));

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<SoftDeleteOutcome> deletion = executor.submit(
                    () -> softDeleteConcurrently(fixture, dashboard.id(), "1"));
            assertThat(deleteReachedAudit.await(5, TimeUnit.SECONDS)).isTrue();
            Future<WithdrawalOutcome> withdrawal = executor.submit(
                    () -> withdrawConcurrently(fixture, dashboard.id(), "1"));
            try {
                assertThat(awaitApplicationDatabaseLock()).isTrue();
            } finally {
                releaseDelete.countDown();
            }

            assertThat(deletion.get(5, TimeUnit.SECONDS).deleted()).isTrue();
            assertThat(withdrawal.get(5, TimeUnit.SECONDS).errorCode()).isEqualTo(60034);
        } finally {
            releaseDelete.countDown();
            doCallRealMethod().when(audits).record(any(AuditLogEntry.class));
        }
        assertThat(softDeletedCatalogFact(fixture.projectId(), dashboard.id()))
                .satisfies(state -> {
                    assertThat(state.publicationRevision()).isEqualTo(2);
                    assertThat(state.currentVersionId()).isNull();
                    assertThat(state.deletedAt()).isNotNull();
                });
        assertThat(retainedDashboardFacts(fixture.projectId(), dashboard.id()))
                .isEqualTo(retainedBefore);
        assertThat(auditActions(fixture.projectId())).containsExactly(
                "dashboard.created", "dashboard.published", "dashboard.deleted");
    }

    /** 两个同revision撤回共享目录行锁，先取得锁的命令成功，等待者读取新revision后稳定冲突。 */
    @Test
    @DisplayName("并发撤回相同发布revision仅一笔成功")
    void concurrentWithdrawalAllowsOneWinnerAfterRealDatabaseLockWait() throws Exception {
        Fixture fixture = seed();
        DashboardCatalogEntry dashboard = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.create(fixture.projectId(), "并发撤回",
                        schema("并发撤回版本", fixture.localModelId(), LOCAL_DIGEST)));
        DashboardVersion version = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> publications.publish(fixture.projectId(), dashboard.id(), "0", "0"));
        List<String> immutableHistory = immutableVersionFacts(
                fixture.projectId(), dashboard.id(), List.of(version.id()));
        CountDownLatch winnerReachedAudit = new CountDownLatch(1);
        CountDownLatch releaseWinner = new CountDownLatch(1);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            AuditLogEntry entry = invocation.getArgument(0, AuditLogEntry.class);
            if ("dashboard.withdrawn".equals(entry.action())) {
                winnerReachedAudit.countDown();
                if (!releaseWinner.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("看板并发撤回赢家未按时放行");
                }
            }
            return null;
        }).when(audits).record(any(AuditLogEntry.class));

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<WithdrawalOutcome> first = executor.submit(
                    () -> withdrawConcurrently(fixture, dashboard.id(), "1"));
            assertThat(winnerReachedAudit.await(5, TimeUnit.SECONDS)).isTrue();
            Future<WithdrawalOutcome> second = executor.submit(
                    () -> withdrawConcurrently(fixture, dashboard.id(), "1"));
            try {
                assertThat(awaitApplicationDatabaseLock()).isTrue();
            } finally {
                releaseWinner.countDown();
            }

            assertThat(first.get(5, TimeUnit.SECONDS).version()).isEqualTo(version);
            assertThat(second.get(5, TimeUnit.SECONDS).errorCode()).isEqualTo(60040);
        } finally {
            releaseWinner.countDown();
            doCallRealMethod().when(audits).record(any(AuditLogEntry.class));
        }
        assertThat(publicationFacts(fixture.projectId(), dashboard.id())).containsExactly(
                "catalog:2:null", "versions:1", "version_refs:1", "audits:3");
        assertThat(immutableVersionFacts(
                fixture.projectId(), dashboard.id(), List.of(version.id())))
                .isEqualTo(immutableHistory);
    }

    /** 另一看板的真实版本ID不能作为当前看板回滚目标，且两个看板都保持原事实。 */
    @Test
    @DisplayName("跨看板回滚目标统一不可见")
    void rollbackRejectsVersionOwnedByAnotherDashboard() throws Exception {
        Fixture fixture = seed();
        DashboardCatalogEntry first = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.create(fixture.projectId(), "目标看板一",
                        schema("目标一", fixture.localModelId(), LOCAL_DIGEST)));
        DashboardCatalogEntry second = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.create(fixture.projectId(), "目标看板二",
                        schema("目标二", fixture.localModelId(), LOCAL_DIGEST)));
        as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> publications.publish(fixture.projectId(), first.id(), "0", "0"));
        DashboardVersion foreignVersion = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> publications.publish(fixture.projectId(), second.id(), "0", "0"));
        List<String> firstBefore = publicationFacts(fixture.projectId(), first.id());
        List<String> secondBefore = publicationFacts(fixture.projectId(), second.id());

        Throwable failure = catchThrowable(() -> as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> publications.rollback(
                        fixture.projectId(), first.id(), foreignVersion.id(), "1")));

        assertThat(errorCode(failure)).isEqualTo(60042);
        assertThat(publicationFacts(fixture.projectId(), first.id())).isEqualTo(firstBefore);
        assertThat(publicationFacts(fixture.projectId(), second.id())).isEqualTo(secondBefore);
    }

    /** 回滚审计真实INSERT后的故障必须撤销指针和revision，且不触碰历史版本或关系。 */
    @Test
    @DisplayName("回滚审计失败撤销指针变化且允许原revision重试")
    void rollbackAuditFailureRestoresPointerAndAllowsRetry() throws Exception {
        Fixture fixture = seed();
        DashboardCatalogEntry dashboard = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.create(fixture.projectId(), "审计回滚",
                        schema("审计版本A", fixture.localModelId(), LOCAL_DIGEST)));
        DashboardVersion versionA = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> publications.publish(fixture.projectId(), dashboard.id(), "0", "0"));
        as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.saveDraft(fixture.projectId(), dashboard.id(), "0",
                        schema("审计版本B", fixture.localModelId(), LOCAL_DIGEST)));
        DashboardVersion versionB = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> publications.publish(fixture.projectId(), dashboard.id(), "1", "1"));
        List<String> factsBefore = publicationFacts(fixture.projectId(), dashboard.id());
        List<String> historyBefore = immutableVersionFacts(
                fixture.projectId(), dashboard.id(), List.of(versionA.id(), versionB.id()));
        doAnswer(invocation -> {
            invocation.callRealMethod();
            AuditLogEntry entry = invocation.getArgument(0, AuditLogEntry.class);
            if ("dashboard.rolled_back".equals(entry.action())) {
                throw new AuditFailureAfterInsertException();
            }
            return null;
        }).when(audits).record(any(AuditLogEntry.class));

        try {
            Throwable failure = catchThrowable(() -> as(
                    fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                    () -> publications.rollback(
                            fixture.projectId(), dashboard.id(), versionA.id(), "2")));
            assertThat(failure).isInstanceOf(AuditFailureAfterInsertException.class);
            assertThat(publicationFacts(fixture.projectId(), dashboard.id())).isEqualTo(factsBefore);
            assertThat(immutableVersionFacts(
                    fixture.projectId(), dashboard.id(), List.of(versionA.id(), versionB.id())))
                    .isEqualTo(historyBefore);

            doCallRealMethod().when(audits).record(any(AuditLogEntry.class));
            DashboardVersion retried = as(
                    fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                    () -> publications.rollback(
                            fixture.projectId(), dashboard.id(), versionA.id(), "2"));
            assertThat(retried).isEqualTo(versionA);
            assertThat(publicationFacts(fixture.projectId(), dashboard.id())).containsExactly(
                    "catalog:3:" + versionA.id(), "versions:2", "version_refs:2", "audits:5");
        } finally {
            doCallRealMethod().when(audits).record(any(AuditLogEntry.class));
        }
    }

    /** 两个同revision回滚在同一目录行锁上串行仲裁，先取得锁的A成功而B稳定冲突。 */
    @Test
    @DisplayName("并发回滚不同历史目标仅一笔成功")
    void concurrentRollbackAllowsOneDeterministicWinner() throws Exception {
        Fixture fixture = seed();
        DashboardCatalogEntry dashboard = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.create(fixture.projectId(), "并发回滚",
                        schema("并发版本A", fixture.localModelId(), LOCAL_DIGEST)));
        DashboardVersion versionA = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> publications.publish(fixture.projectId(), dashboard.id(), "0", "0"));
        as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.saveDraft(fixture.projectId(), dashboard.id(), "0",
                        schema("并发版本B", fixture.localModelId(), LOCAL_DIGEST)));
        DashboardVersion versionB = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> publications.publish(fixture.projectId(), dashboard.id(), "1", "1"));
        as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.saveDraft(fixture.projectId(), dashboard.id(), "1",
                        schema("并发版本C", fixture.localModelId(), LOCAL_DIGEST)));
        as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> publications.publish(fixture.projectId(), dashboard.id(), "2", "2"));
        List<String> immutableHistory = immutableVersionFacts(
                fixture.projectId(), dashboard.id(), List.of(versionA.id(), versionB.id()));
        CountDownLatch winnerReachedAudit = new CountDownLatch(1);
        CountDownLatch releaseWinner = new CountDownLatch(1);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            AuditLogEntry entry = invocation.getArgument(0, AuditLogEntry.class);
            if ("dashboard.rolled_back".equals(entry.action())) {
                winnerReachedAudit.countDown();
                if (!releaseWinner.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("看板并发回滚赢家未按时放行");
                }
            }
            return null;
        }).when(audits).record(any(AuditLogEntry.class));

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<RollbackOutcome> first = executor.submit(
                    () -> rollbackConcurrently(fixture, dashboard.id(), versionA.id(), "3"));
            assertThat(winnerReachedAudit.await(5, TimeUnit.SECONDS)).isTrue();
            Future<RollbackOutcome> second = executor.submit(
                    () -> rollbackConcurrently(fixture, dashboard.id(), versionB.id(), "3"));
            try {
                assertThat(awaitApplicationDatabaseLock()).isTrue();
            } finally {
                releaseWinner.countDown();
            }

            assertThat(first.get(5, TimeUnit.SECONDS).version()).isEqualTo(versionA);
            assertThat(second.get(5, TimeUnit.SECONDS).errorCode()).isEqualTo(60040);
        } finally {
            releaseWinner.countDown();
            doCallRealMethod().when(audits).record(any(AuditLogEntry.class));
        }
        assertThat(publicationFacts(fixture.projectId(), dashboard.id())).containsExactly(
                "catalog:4:" + versionA.id(), "versions:3", "version_refs:3", "audits:7");
        assertThat(immutableVersionFacts(
                fixture.projectId(), dashboard.id(), List.of(versionA.id(), versionB.id())))
                .isEqualTo(immutableHistory);
    }

    /** 草稿轴或发布轴任一陈旧都在候选重建和写入前拒绝，已发布事实不漂移。 */
    @Test
    @DisplayName("双revision任一冲突均不产生部分发布")
    void eitherRevisionConflictLeavesPublicationFactsUntouched() throws Exception {
        Fixture fixture = seed();
        DashboardCatalogEntry dashboard = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.create(fixture.projectId(), "冲突看板",
                        schema("冲突", fixture.localModelId(), LOCAL_DIGEST)));
        as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> publications.publish(fixture.projectId(), dashboard.id(), "0", "0"));
        List<String> beforePublicationConflict = publicationFacts(fixture.projectId(), dashboard.id());

        Throwable publicationConflict = catchThrowable(() -> as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> publications.publish(fixture.projectId(), dashboard.id(), "0", "0")));
        assertThat(errorCode(publicationConflict)).isEqualTo(60040);
        assertThat(publicationFacts(fixture.projectId(), dashboard.id())).isEqualTo(beforePublicationConflict);

        as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.saveDraft(fixture.projectId(), dashboard.id(), "0",
                        schema("新草稿", fixture.localModelId(), LOCAL_DIGEST)));
        List<String> beforeDraftConflict = publicationFacts(fixture.projectId(), dashboard.id());
        Throwable draftConflict = catchThrowable(() -> as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> publications.publish(fixture.projectId(), dashboard.id(), "0", "1")));

        assertThat(errorCode(draftConflict)).isEqualTo(60040);
        assertThat(publicationFacts(fixture.projectId(), dashboard.id())).isEqualTo(beforeDraftConflict);
    }

    /** 两个相同命令必须在目录锁上一胜一冲突，只留下一个完整版本和一次发布审计。 */
    @Test
    @DisplayName("并发发布相同双revision仅一笔成功")
    void concurrentPublicationAllowsExactlyOneWinner() throws Exception {
        Fixture fixture = seed();
        DashboardCatalogEntry dashboard = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.create(fixture.projectId(), "并发发布",
                        schema("并发", fixture.localModelId(), LOCAL_DIGEST)));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<PublishOutcome> first = executor.submit(
                    () -> publishConcurrently(fixture, dashboard.id(), ready, start));
            Future<PublishOutcome> second = executor.submit(
                    () -> publishConcurrently(fixture, dashboard.id(), ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<PublishOutcome> outcomes = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

            assertThat(outcomes).filteredOn(PublishOutcome::published).hasSize(1);
            assertThat(outcomes).filteredOn(outcome -> Integer.valueOf(60040).equals(outcome.errorCode()))
                    .hasSize(1);
        }
        assertThat(publicationFacts(fixture.projectId(), dashboard.id())).containsExactly(
                "catalog:1:" + currentVersionId(fixture.projectId(), dashboard.id()),
                "versions:1", "version_refs:1", "audits:2");
    }

    /** 最晚的审计写后故障必须回滚此前版本、关系、指针和发布revision，原命令可安全重试。 */
    @Test
    @DisplayName("审计失败回滚整笔发布且允许原revision重试")
    void auditFailureRollsBackPublicationAndAllowsRetry() throws Exception {
        Fixture fixture = seed();
        DashboardCatalogEntry dashboard = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.create(fixture.projectId(), "回滚发布",
                        schema("回滚", fixture.localModelId(), LOCAL_DIGEST)));
        List<String> before = publicationFacts(fixture.projectId(), dashboard.id());
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new AuditFailureAfterInsertException();
        }).when(audits).record(any(AuditLogEntry.class));

        Throwable failure = catchThrowable(() -> as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> publications.publish(fixture.projectId(), dashboard.id(), "0", "0")));
        assertThat(failure).isInstanceOf(AuditFailureAfterInsertException.class);
        assertThat(publicationFacts(fixture.projectId(), dashboard.id())).isEqualTo(before);

        doCallRealMethod().when(audits).record(any(AuditLogEntry.class));
        DashboardVersion retried = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> publications.publish(fixture.projectId(), dashboard.id(), "0", "0"));
        assertThat(retried.versionNumber()).isOne();
        assertThat(publicationFacts(fixture.projectId(), dashboard.id())).containsExactly(
                "catalog:1:" + retried.id(), "versions:1", "version_refs:1", "audits:2");
    }

    /** 最终迁移撤销两张版本事实直写，只给APP四个受控发布状态函数执行权且不给PUBLIC。 */
    @Test
    @DisplayName("普通运行身份只能执行受控发布状态入口")
    void runtimeRoleCannotWritePublicationTablesDirectly() {
        assertThat(jdbc.queryForObject("""
                SELECT NOT has_table_privilege(current_user, 'dash_dashboard_version', 'INSERT')
                   AND NOT has_table_privilege(current_user, 'dash_dashboard_version_model_ref', 'INSERT')
                   AND has_function_privilege(current_user,
                       'public.dashboard_publish_version(uuid,uuid,bigint,bigint,uuid,jsonb,varchar,varchar,varchar,jsonb,jsonb,uuid,timestamptz)',
                       'EXECUTE')
                   AND NOT EXISTS (
                       SELECT 1
                         FROM pg_proc procedure
                         CROSS JOIN LATERAL aclexplode(COALESCE(
                             procedure.proacl, acldefault('f', procedure.proowner))) privilege
                        WHERE procedure.oid =
                            'public.dashboard_publish_version(uuid,uuid,bigint,bigint,uuid,jsonb,varchar,varchar,varchar,jsonb,jsonb,uuid,timestamptz)'::regprocedure
                          AND privilege.grantee = 0
                          AND privilege.privilege_type = 'EXECUTE')
                   AND (SELECT procedure.prosecdef
                          FROM pg_proc procedure
                         WHERE procedure.oid =
                            'public.dashboard_publish_version(uuid,uuid,bigint,bigint,uuid,jsonb,varchar,varchar,varchar,jsonb,jsonb,uuid,timestamptz)'::regprocedure)
                   AND (SELECT 'search_path=pg_catalog, public' = ANY(procedure.proconfig)
                          FROM pg_proc procedure
                         WHERE procedure.oid =
                            'public.dashboard_publish_version(uuid,uuid,bigint,bigint,uuid,jsonb,varchar,varchar,varchar,jsonb,jsonb,uuid,timestamptz)'::regprocedure)
                   AND (SELECT pg_get_userbyid(procedure.proowner) <> current_user
                          FROM pg_proc procedure
                         WHERE procedure.oid =
                            'public.dashboard_publish_version(uuid,uuid,bigint,bigint,uuid,jsonb,varchar,varchar,varchar,jsonb,jsonb,uuid,timestamptz)'::regprocedure)
                   AND has_function_privilege(current_user,
                       'public.dashboard_rollback_version(uuid,uuid,uuid,bigint,uuid,timestamptz)',
                       'EXECUTE')
                   AND NOT EXISTS (
                       SELECT 1
                         FROM pg_proc procedure
                         CROSS JOIN LATERAL aclexplode(COALESCE(
                             procedure.proacl, acldefault('f', procedure.proowner))) privilege
                        WHERE procedure.oid =
                            'public.dashboard_rollback_version(uuid,uuid,uuid,bigint,uuid,timestamptz)'::regprocedure
                          AND privilege.grantee = 0
                          AND privilege.privilege_type = 'EXECUTE')
                   AND (SELECT procedure.prosecdef
                          FROM pg_proc procedure
                         WHERE procedure.oid =
                            'public.dashboard_rollback_version(uuid,uuid,uuid,bigint,uuid,timestamptz)'::regprocedure)
                   AND (SELECT 'search_path=pg_catalog, public' = ANY(procedure.proconfig)
                          FROM pg_proc procedure
                         WHERE procedure.oid =
                            'public.dashboard_rollback_version(uuid,uuid,uuid,bigint,uuid,timestamptz)'::regprocedure)
                   AND (SELECT pg_get_userbyid(procedure.proowner) <> current_user
                          FROM pg_proc procedure
                         WHERE procedure.oid =
                            'public.dashboard_rollback_version(uuid,uuid,uuid,bigint,uuid,timestamptz)'::regprocedure)
                   AND has_function_privilege(current_user,
                       'public.dashboard_withdraw_publication(uuid,uuid,bigint,uuid,timestamptz)',
                       'EXECUTE')
                   AND NOT EXISTS (
                       SELECT 1
                         FROM pg_proc procedure
                         CROSS JOIN LATERAL aclexplode(COALESCE(
                             procedure.proacl, acldefault('f', procedure.proowner))) privilege
                        WHERE procedure.oid =
                            'public.dashboard_withdraw_publication(uuid,uuid,bigint,uuid,timestamptz)'::regprocedure
                          AND privilege.grantee = 0
                          AND privilege.privilege_type = 'EXECUTE')
                   AND (SELECT procedure.prosecdef
                          FROM pg_proc procedure
                         WHERE procedure.oid =
                            'public.dashboard_withdraw_publication(uuid,uuid,bigint,uuid,timestamptz)'::regprocedure)
                   AND (SELECT 'search_path=pg_catalog, public' = ANY(procedure.proconfig)
                          FROM pg_proc procedure
                         WHERE procedure.oid =
                            'public.dashboard_withdraw_publication(uuid,uuid,bigint,uuid,timestamptz)'::regprocedure)
                   AND (SELECT pg_get_userbyid(procedure.proowner) <> current_user
                          FROM pg_proc procedure
                         WHERE procedure.oid =
                            'public.dashboard_withdraw_publication(uuid,uuid,bigint,uuid,timestamptz)'::regprocedure)
                   AND has_function_privilege(current_user,
                       'public.dashboard_soft_delete(uuid,uuid,bigint,uuid,timestamptz)',
                       'EXECUTE')
                   AND NOT EXISTS (
                       SELECT 1
                         FROM pg_proc procedure
                         CROSS JOIN LATERAL aclexplode(COALESCE(
                             procedure.proacl, acldefault('f', procedure.proowner))) privilege
                        WHERE procedure.oid =
                            'public.dashboard_soft_delete(uuid,uuid,bigint,uuid,timestamptz)'::regprocedure
                          AND privilege.grantee = 0
                          AND privilege.privilege_type = 'EXECUTE')
                   AND (SELECT procedure.prosecdef
                          FROM pg_proc procedure
                         WHERE procedure.oid =
                            'public.dashboard_soft_delete(uuid,uuid,bigint,uuid,timestamptz)'::regprocedure)
                   AND (SELECT 'search_path=pg_catalog, public' = ANY(procedure.proconfig)
                          FROM pg_proc procedure
                         WHERE procedure.oid =
                            'public.dashboard_soft_delete(uuid,uuid,bigint,uuid,timestamptz)'::regprocedure)
                   AND (SELECT pg_get_userbyid(procedure.proowner) <> current_user
                          FROM pg_proc procedure
                         WHERE procedure.oid =
                            'public.dashboard_soft_delete(uuid,uuid,bigint,uuid,timestamptz)'::regprocedure)
                """, Boolean.class)).isTrue();
        assertPostgresState(() -> {
            try (Connection app = application()) {
                execute(app, "INSERT INTO dash_dashboard_version DEFAULT VALUES");
            }
        }, "42501");
        assertPostgresState(() -> {
            try (Connection app = application()) {
                execute(app, "INSERT INTO dash_dashboard_version_model_ref DEFAULT VALUES");
            }
        }, "42501");
    }

    /** APP不能直接推进发布指针，但迁移保留的管理名称列级更新必须让服务正常工作。 */
    @Test
    @DisplayName("普通运行身份不能直写发布指针但仍能修改管理名称")
    void runtimeRoleCannotUpdatePublicationPointerButCanRename() throws Exception {
        Fixture fixture = seed();
        DashboardCatalogEntry dashboard = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.create(fixture.projectId(), "列级权限",
                        schema("列级权限", fixture.localModelId(), LOCAL_DIGEST)));

        assertPostgresState(() -> {
            try (Connection app = application()) {
                setSessionProjectContext(app, fixture.ownerTenantId(), fixture.projectId());
                execute(app, """
                        UPDATE dash_dashboard
                           SET current_version_id=NULL,
                               publication_revision=publication_revision+1
                         WHERE project_id=? AND id=?
                        """, fixture.projectId(), dashboard.id());
            }
        }, "42501");
        assertPostgresState(() -> {
            try (Connection app = application()) {
                setSessionProjectContext(app, fixture.ownerTenantId(), fixture.projectId());
                execute(app, """
                        UPDATE dash_dashboard
                           SET deleted_at=now()
                         WHERE project_id=? AND id=?
                        """, fixture.projectId(), dashboard.id());
            }
        }, "42501");

        DashboardCatalogEntry renamed = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.rename(fixture.projectId(), dashboard.id(), "允许改名"));
        assertThat(renamed.managementName()).isEqualTo("允许改名");
        assertThat(publicationFacts(fixture.projectId(), dashboard.id())).containsExactly(
                "catalog:0:null", "versions:0", "version_refs:0", "audits:2");
    }

    /** 应用版本与发布字段只能由固定SECURITY DEFINER发布生命周期入口写入，PUBLIC不得继承执行权。 */
    @Test
    @DisplayName("应用运行身份只能通过受控发布生命周期函数改变发布状态")
    void applicationRuntimeRoleCannotBypassControlledPublication() throws Exception {
        Fixture fixture = seed();
        ApplicationCatalogEntry application = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> applications.create(fixture.projectId(), "发布权限应用", applicationDraft(
                        UUID.randomUUID(), UUID.randomUUID())));

        assertThat(jdbc.queryForObject("""
                SELECT NOT has_table_privilege(current_user, 'app_application_version', 'INSERT')
                   AND NOT has_table_privilege(
                       current_user, 'app_application_version_dashboard_ref', 'INSERT')
                   AND has_function_privilege(current_user,
                       'public.application_publish_version(uuid,uuid,bigint,bigint,uuid,jsonb,varchar,varchar,jsonb,uuid,timestamptz)',
                       'EXECUTE')
                   AND has_function_privilege(current_user,
                       'public.application_rollback_version(uuid,uuid,uuid,bigint,uuid,timestamptz)',
                       'EXECUTE')
                   AND NOT EXISTS (
                       SELECT 1
                         FROM pg_proc procedure
                         CROSS JOIN LATERAL aclexplode(COALESCE(
                             procedure.proacl, acldefault('f', procedure.proowner))) privilege
                        WHERE procedure.oid =
                            'public.application_publish_version(uuid,uuid,bigint,bigint,uuid,jsonb,varchar,varchar,jsonb,uuid,timestamptz)'::regprocedure
                          AND privilege.grantee = 0
                          AND privilege.privilege_type = 'EXECUTE')
                   AND (SELECT procedure.prosecdef
                          FROM pg_proc procedure
                         WHERE procedure.oid =
                            'public.application_publish_version(uuid,uuid,bigint,bigint,uuid,jsonb,varchar,varchar,jsonb,uuid,timestamptz)'::regprocedure)
                   AND (SELECT 'search_path=pg_catalog, public' = ANY(procedure.proconfig)
                          FROM pg_proc procedure
                         WHERE procedure.oid =
                            'public.application_publish_version(uuid,uuid,bigint,bigint,uuid,jsonb,varchar,varchar,jsonb,uuid,timestamptz)'::regprocedure)
                   AND NOT EXISTS (
                       SELECT 1
                         FROM pg_proc procedure
                         CROSS JOIN LATERAL aclexplode(COALESCE(
                             procedure.proacl, acldefault('f', procedure.proowner))) privilege
                        WHERE procedure.oid =
                            'public.application_rollback_version(uuid,uuid,uuid,bigint,uuid,timestamptz)'::regprocedure
                          AND privilege.grantee = 0
                          AND privilege.privilege_type = 'EXECUTE')
                   AND (SELECT procedure.prosecdef
                          FROM pg_proc procedure
                         WHERE procedure.oid =
                            'public.application_rollback_version(uuid,uuid,uuid,bigint,uuid,timestamptz)'::regprocedure)
                   AND (SELECT 'search_path=pg_catalog, public' = ANY(procedure.proconfig)
                          FROM pg_proc procedure
                         WHERE procedure.oid =
                            'public.application_rollback_version(uuid,uuid,uuid,bigint,uuid,timestamptz)'::regprocedure)
                   AND has_function_privilege(current_user,
                       'public.application_withdraw_publication(uuid,uuid,bigint,uuid,timestamptz)',
                       'EXECUTE')
                   AND NOT EXISTS (
                       SELECT 1
                         FROM pg_proc procedure
                         CROSS JOIN LATERAL aclexplode(COALESCE(
                             procedure.proacl, acldefault('f', procedure.proowner))) privilege
                        WHERE procedure.oid =
                            'public.application_withdraw_publication(uuid,uuid,bigint,uuid,timestamptz)'::regprocedure
                          AND privilege.grantee = 0
                          AND privilege.privilege_type = 'EXECUTE')
                   AND (SELECT procedure.prosecdef
                          FROM pg_proc procedure
                         WHERE procedure.oid =
                            'public.application_withdraw_publication(uuid,uuid,bigint,uuid,timestamptz)'::regprocedure)
                   AND (SELECT 'search_path=pg_catalog, public' = ANY(procedure.proconfig)
                          FROM pg_proc procedure
                         WHERE procedure.oid =
                            'public.application_withdraw_publication(uuid,uuid,bigint,uuid,timestamptz)'::regprocedure)
                   AND has_function_privilege(current_user,
                       'public.application_soft_delete(uuid,uuid,bigint,uuid,timestamptz)',
                       'EXECUTE')
                   AND NOT EXISTS (
                       SELECT 1
                         FROM pg_proc procedure
                         CROSS JOIN LATERAL aclexplode(COALESCE(
                             procedure.proacl, acldefault('f', procedure.proowner))) privilege
                        WHERE procedure.oid =
                            'public.application_soft_delete(uuid,uuid,bigint,uuid,timestamptz)'::regprocedure
                          AND privilege.grantee = 0
                          AND privilege.privilege_type = 'EXECUTE')
                   AND (SELECT procedure.prosecdef
                          FROM pg_proc procedure
                         WHERE procedure.oid =
                            'public.application_soft_delete(uuid,uuid,bigint,uuid,timestamptz)'::regprocedure)
                   AND (SELECT 'search_path=pg_catalog, public' = ANY(procedure.proconfig)
                          FROM pg_proc procedure
                         WHERE procedure.oid =
                            'public.application_soft_delete(uuid,uuid,bigint,uuid,timestamptz)'::regprocedure)
                """, Boolean.class)).isTrue();
        assertPostgresState(() -> {
            try (Connection app = application()) {
                setSessionProjectContext(app, fixture.ownerTenantId(), fixture.projectId());
                execute(app, """
                        UPDATE app_application
                           SET publication_revision=publication_revision+1
                         WHERE project_id=? AND id=?
                        """, fixture.projectId(), application.id());
            }
        }, "42501");
        assertPostgresState(() -> {
            try (Connection app = application()) {
                execute(app, "INSERT INTO app_application_version DEFAULT VALUES");
            }
        }, "42501");
        assertPostgresState(() -> {
            try (Connection app = application()) {
                execute(app, "INSERT INTO app_application_version_dashboard_ref DEFAULT VALUES");
            }
        }, "42501");
        assertThat(applicationPublicationFacts(fixture.projectId(), application.id()).getFirst())
                .contains("\"publicationRevision\": 0", "\"versionCount\": 0",
                        "\"referenceCount\": 0");
    }

    /** 发布行锁与受控追加不得各自开启短事务，否则锁会提前释放且版本可能脱离审计提交。 */
    @Test
    @DisplayName("发布仓储写操作必须加入外层事务")
    void publicationRepositoryWritesRequireAnOuterTransaction() {
        assertThatThrownBy(() -> dashboardRepository.lockPublicationState(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> dashboardRepository.appendPublication(null, 0))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> dashboardRepository.rollbackPublication(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0,
                UUID.randomUUID(), java.time.Instant.now()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> dashboardRepository.withdrawPublication(
                UUID.randomUUID(), UUID.randomUUID(), 0,
                UUID.randomUUID(), java.time.Instant.now()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> dashboardRepository.softDelete(
                UUID.randomUUID(), UUID.randomUUID(), 0,
                UUID.randomUUID(), java.time.Instant.now()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> applicationRepository.lockPublicationState(
                UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> applicationRepository.appendPublication(null, 0))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> applicationRepository.rollbackPublication(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0,
                UUID.randomUUID(), java.time.Instant.now()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> applicationRepository.withdrawPublication(
                UUID.randomUUID(), UUID.randomUUID(), 0,
                UUID.randomUUID(), java.time.Instant.now()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> applicationRepository.softDelete(
                UUID.randomUUID(), UUID.randomUUID(), 0,
                UUID.randomUUID(), java.time.Instant.now()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    /** 缺失或错误项目上下文直调回滚函数必须统一不可见，目标和邻居事实均不得漂移。 */
    @Test
    @DisplayName("受控回滚入口按连接项目上下文失败关闭")
    void controlledRollbackFailsClosedWithoutMatchingProjectContext() throws Exception {
        Fixture fixture = seed();
        DashboardCatalogEntry dashboard = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.create(fixture.projectId(), "范围回滚",
                        schema("范围版本A", fixture.localModelId(), LOCAL_DIGEST)));
        DashboardVersion versionA = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> publications.publish(fixture.projectId(), dashboard.id(), "0", "0"));
        as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.saveDraft(fixture.projectId(), dashboard.id(), "0",
                        schema("范围版本B", fixture.localModelId(), LOCAL_DIGEST)));
        as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> publications.publish(fixture.projectId(), dashboard.id(), "1", "1"));
        List<String> targetBefore = publicationFacts(fixture.projectId(), dashboard.id());
        List<String> neighborBefore = facts(fixture.neighborProjectId());

        try (Connection missing = application(); Connection wrongProject = application()) {
            RollbackFunctionResult missingResult = callRollbackFunction(
                    missing, fixture.projectId(), dashboard.id(), versionA.id(), 2, fixture.ownerId());
            setSessionProjectContext(wrongProject, fixture.ownerTenantId(), fixture.neighborProjectId());
            RollbackFunctionResult wrongResult = callRollbackFunction(
                    wrongProject, fixture.projectId(), dashboard.id(), versionA.id(), 2, fixture.ownerId());

            assertThat(missingResult.status()).isEqualTo("DASHBOARD_NOT_FOUND");
            assertThat(missingResult.publicationRevision()).isNull();
            assertThat(wrongResult.status()).isEqualTo("DASHBOARD_NOT_FOUND");
            assertThat(wrongResult.publicationRevision()).isNull();
        }
        assertThat(publicationFacts(fixture.projectId(), dashboard.id())).isEqualTo(targetBefore);
        assertThat(facts(fixture.neighborProjectId())).isEqualTo(neighborBefore);
    }

    /** 缺失或错误项目上下文直调撤回函数必须统一不可见，目标与邻居事实均不得漂移。 */
    @Test
    @DisplayName("受控撤回入口按连接项目上下文失败关闭")
    void controlledWithdrawalFailsClosedWithoutMatchingProjectContext() throws Exception {
        Fixture fixture = seed();
        DashboardCatalogEntry dashboard = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.create(fixture.projectId(), "范围撤回",
                        schema("范围撤回版本", fixture.localModelId(), LOCAL_DIGEST)));
        as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> publications.publish(fixture.projectId(), dashboard.id(), "0", "0"));
        List<String> targetBefore = publicationFacts(fixture.projectId(), dashboard.id());
        List<String> neighborBefore = facts(fixture.neighborProjectId());

        try (Connection missing = application(); Connection wrongProject = application()) {
            WithdrawalFunctionResult missingResult = callWithdrawalFunction(
                    missing, fixture.projectId(), dashboard.id(), 1, fixture.ownerId());
            setSessionProjectContext(wrongProject, fixture.ownerTenantId(), fixture.neighborProjectId());
            WithdrawalFunctionResult wrongResult = callWithdrawalFunction(
                    wrongProject, fixture.projectId(), dashboard.id(), 1, fixture.ownerId());

            assertThat(missingResult.status()).isEqualTo("DASHBOARD_NOT_FOUND");
            assertThat(missingResult.previousVersionId()).isNull();
            assertThat(missingResult.publicationRevision()).isNull();
            assertThat(wrongResult.status()).isEqualTo("DASHBOARD_NOT_FOUND");
            assertThat(wrongResult.previousVersionId()).isNull();
            assertThat(wrongResult.publicationRevision()).isNull();
        }
        assertThat(publicationFacts(fixture.projectId(), dashboard.id())).isEqualTo(targetBefore);
        assertThat(facts(fixture.neighborProjectId())).isEqualTo(neighborBefore);
    }

    /** 缺失或错误项目上下文直调软删函数必须统一不可见，目标与邻居事实均不得漂移。 */
    @Test
    @DisplayName("受控软删入口按连接项目上下文失败关闭")
    void controlledSoftDeleteFailsClosedWithoutMatchingProjectContext() throws Exception {
        Fixture fixture = seed();
        DashboardCatalogEntry dashboard = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.create(fixture.projectId(), "范围删除",
                        schema("范围删除历史", fixture.localModelId(), LOCAL_DIGEST)));
        as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> publications.publish(fixture.projectId(), dashboard.id(), "0", "0"));
        List<String> targetBefore = publicationFacts(fixture.projectId(), dashboard.id());
        SoftDeletedCatalogFact targetCatalogBefore = softDeletedCatalogFact(
                fixture.projectId(), dashboard.id());
        List<String> neighborBefore = facts(fixture.neighborProjectId());

        try (Connection missing = application(); Connection wrongProject = application()) {
            SoftDeleteFunctionResult missingResult = callSoftDeleteFunction(
                    missing, fixture.projectId(), dashboard.id(), 1, fixture.ownerId());
            setSessionProjectContext(wrongProject, fixture.ownerTenantId(), fixture.neighborProjectId());
            SoftDeleteFunctionResult wrongResult = callSoftDeleteFunction(
                    wrongProject, fixture.projectId(), dashboard.id(), 1, fixture.ownerId());

            assertThat(missingResult.status()).isEqualTo("DASHBOARD_NOT_FOUND");
            assertThat(missingResult.previousVersionId()).isNull();
            assertThat(missingResult.publicationRevision()).isNull();
            assertThat(missingResult.deletedAt()).isNull();
            assertThat(wrongResult.status()).isEqualTo("DASHBOARD_NOT_FOUND");
            assertThat(wrongResult.previousVersionId()).isNull();
            assertThat(wrongResult.publicationRevision()).isNull();
            assertThat(wrongResult.deletedAt()).isNull();
        }
        assertThat(publicationFacts(fixture.projectId(), dashboard.id())).isEqualTo(targetBefore);
        assertThat(softDeletedCatalogFact(fixture.projectId(), dashboard.id()))
                .isEqualTo(targetCatalogBefore);
        assertThat(facts(fixture.neighborProjectId())).isEqualTo(neighborBefore);
    }

    /** 缺失或错误项目上下文调用SECURITY DEFINER入口必须统一不可见且不能触碰目标或邻居事实。 */
    @Test
    @DisplayName("受控发布入口按连接项目上下文失败关闭")
    void controlledPublicationFailsClosedWithoutMatchingProjectContext() throws Exception {
        PublicationFixture publication = publicationFixture("范围发布");
        List<String> targetBefore = publicationFacts(
                publication.fixture().projectId(), publication.dashboard().id());
        List<String> neighborBefore = facts(publication.fixture().neighborProjectId());

        try (Connection missing = application(); Connection wrongProject = application()) {
            PublicationFunctionResult missingResult = callPublicationFunction(
                    missing, publication.candidate(), UUID.randomUUID(),
                    publication.fixture().ownerId(),
                    publication.candidate().normalizedSchema().toString(),
                    publication.candidate().schemaDigest(), 0);
            setSessionProjectContext(
                    wrongProject, publication.fixture().ownerTenantId(),
                    publication.fixture().neighborProjectId());
            PublicationFunctionResult wrongResult = callPublicationFunction(
                    wrongProject, publication.candidate(), UUID.randomUUID(),
                    publication.fixture().ownerId(),
                    publication.candidate().normalizedSchema().toString(),
                    publication.candidate().schemaDigest(), 0);

            assertThat(missingResult.status()).isEqualTo("NOT_FOUND");
            assertThat(wrongResult.status()).isEqualTo("NOT_FOUND");
        }
        assertThat(publicationFacts(
                publication.fixture().projectId(), publication.dashboard().id())).isEqualTo(targetBefore);
        assertThat(facts(publication.fixture().neighborProjectId())).isEqualTo(neighborBefore);
    }

    /** 错误摘要必须由函数自身拒绝，不能依赖Java候选值对象守住数据库直接调用。 */
    @Test
    @DisplayName("受控发布入口拒绝错误Schema摘要")
    void controlledPublicationRejectsTamperedSchemaDigest() throws Exception {
        PublicationFixture publication = publicationFixture("摘要围栏");
        List<String> before = publicationFacts(
                publication.fixture().projectId(), publication.dashboard().id());

        assertPostgresConstraint(() -> {
            try (Connection app = application()) {
                setSessionProjectContext(
                        app, publication.fixture().ownerTenantId(), publication.fixture().projectId());
                callPublicationFunction(
                        app, publication.candidate(), UUID.randomUUID(),
                        publication.fixture().ownerId(),
                        publication.candidate().normalizedSchema().toString(), "f".repeat(64), 0);
            }
        }, "dash_dashboard_publish_schema_digest");

        assertThat(publicationFacts(
                publication.fixture().projectId(), publication.dashboard().id())).isEqualTo(before);
    }

    /** 自洽新摘要也不能把锁内草稿之外的Schema冒充同一revision发布。 */
    @Test
    @DisplayName("受控发布入口拒绝与锁内草稿不同的自洽Schema")
    void controlledPublicationRejectsSelfConsistentButDifferentDraftSchema() throws Exception {
        PublicationFixture publication = publicationFixture("草稿身份围栏");
        ObjectNode changed = (ObjectNode) publication.candidate().normalizedSchema();
        ((ObjectNode) changed.path("pages").get(0)).put("title", "被篡改的标题");
        String changedJson = changed.toString();
        String changedDigest = sha256(postgreSqlCanonicalText(changedJson).getBytes(StandardCharsets.UTF_8));
        List<String> before = publicationFacts(
                publication.fixture().projectId(), publication.dashboard().id());

        assertPostgresConstraint(() -> {
            try (Connection app = application()) {
                setSessionProjectContext(
                        app, publication.fixture().ownerTenantId(), publication.fixture().projectId());
                callPublicationFunction(
                        app, publication.candidate(), UUID.randomUUID(), publication.fixture().ownerId(),
                        changedJson, changedDigest, 0);
            }
        }, "dash_dashboard_publish_draft_schema");

        assertThat(publicationFacts(
                publication.fixture().projectId(), publication.dashboard().id())).isEqualTo(before);
    }

    /** 草稿关系表若与同revision内容损坏，函数必须在复制关系前拒绝精确模型投影。 */
    @Test
    @DisplayName("受控发布入口拒绝损坏的草稿模型关系投影")
    void controlledPublicationRejectsCorruptedDraftModelProjection() throws Exception {
        PublicationFixture publication = publicationFixture("模型投影围栏");
        try (Connection owner = owner()) {
            execute(owner, """
                    UPDATE dash_dashboard_draft_model_ref
                       SET model_key = 'corrupted_model'
                     WHERE project_id = ? AND dashboard_id = ?
                    """, publication.fixture().projectId(), publication.dashboard().id());
        }
        List<String> managementBefore = facts(publication.fixture().projectId());
        List<String> publicationBefore = publicationFacts(
                publication.fixture().projectId(), publication.dashboard().id());

        assertPostgresConstraint(() -> {
            try (Connection app = application()) {
                setSessionProjectContext(
                        app, publication.fixture().ownerTenantId(), publication.fixture().projectId());
                callPublicationFunction(
                        app, publication.candidate(), UUID.randomUUID(),
                        publication.fixture().ownerId(),
                        publication.candidate().normalizedSchema().toString(),
                        publication.candidate().schemaDigest(), 0);
            }
        }, "dash_dashboard_publish_model_projection");

        assertThat(facts(publication.fixture().projectId())).isEqualTo(managementBefore);
        assertThat(publicationFacts(
                publication.fixture().projectId(), publication.dashboard().id())).isEqualTo(publicationBefore);
    }

    /** 独立APP连接持有相同目录与草稿锁时，受控发布必须出现真实数据库锁等待。 */
    @Test
    @DisplayName("受控发布入口真实等待目录发布锁")
    void controlledPublicationWaitsForTheDatabasePublicationLock() throws Exception {
        PublicationFixture publication = publicationFixture("真实锁等待");
        try (Connection blocker = application(); Connection publisher = application();
                ExecutorService executor = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            setSessionProjectContext(
                    blocker, publication.fixture().ownerTenantId(), publication.fixture().projectId());
            setSessionProjectContext(
                    publisher, publication.fixture().ownerTenantId(), publication.fixture().projectId());
            lockPublicationRows(
                    blocker, publication.fixture().projectId(), publication.dashboard().id());
            int publisherPid = backendPid(publisher);
            Future<PublicationFunctionResult> pending = executor.submit(() -> callPublicationFunction(
                    publisher, publication.candidate(), UUID.randomUUID(),
                    publication.fixture().ownerId(),
                    publication.candidate().normalizedSchema().toString(),
                    publication.candidate().schemaDigest(), 0));
            try {
                assertThat(awaitDatabaseLock(publisherPid)).isTrue();
            } finally {
                blocker.commit();
            }

            PublicationFunctionResult result = pending.get(5, TimeUnit.SECONDS);
            assertThat(result.status()).isEqualTo("PUBLISHED");
            assertThat(result.versionNumber()).isOne();
            assertThat(result.publicationRevision()).isOne();
        }
        assertThat(publicationFacts(
                publication.fixture().projectId(), publication.dashboard().id()))
                .containsExactly(
                        "catalog:1:" + currentVersionId(
                                publication.fixture().projectId(), publication.dashboard().id()),
                        "versions:1", "version_refs:1", "audits:1");
    }

    /** OWNER与跨租户ADMIN可写，四角色均可读取，成功操作各只追加一条精确审计。 */
    @Test
    @DisplayName("管理角色写入且四角色读取同一项目事实")
    void managementRolesWriteAndAllRolesReadProjectFacts() throws Exception {
        Fixture fixture = seed();
        DashboardCatalogEntry created = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.create(fixture.projectId(), "设备总览",
                        schema("初始画布", fixture.localModelId(), LOCAL_DIGEST)));

        List<UUID> readers = List.of(
                fixture.ownerId(), fixture.adminId(), fixture.operatorId(), fixture.viewerId());
        for (UUID reader : readers) {
            UUID callerTenant = reader.equals(fixture.ownerId())
                    ? fixture.ownerTenantId() : fixture.collaboratorTenantId();
            DashboardCatalogEntry visible = as(callerTenant, fixture.projectId(), reader,
                    () -> dashboards.find(fixture.projectId(), created.id()));
            DashboardDraft readable = as(callerTenant, fixture.projectId(), reader,
                    () -> dashboards.getDraft(fixture.projectId(), created.id()));
            assertThat(visible.id()).isEqualTo(created.id());
            assertThat(readable.modelReferences()).singleElement().satisfies(reference ->
                    assertThat(reference.thingModelVersionId()).isEqualTo(fixture.localModelId()));
        }

        DashboardCatalogEntry renamed = as(
                fixture.collaboratorTenantId(), fixture.projectId(), fixture.adminId(),
                () -> dashboards.rename(fixture.projectId(), created.id(), "生产总览"));
        DashboardDraft saved = as(
                fixture.collaboratorTenantId(), fixture.projectId(), fixture.adminId(),
                () -> dashboards.saveDraft(fixture.projectId(), created.id(), "0",
                        schema("已保存画布", fixture.localModelId(), LOCAL_DIGEST)));
        List<String> actions = auditActions(fixture.projectId());
        List<UUID> actors = auditActors(fixture.projectId());
        List<UUID> tenants = persistedTenants(fixture.projectId());

        assertSoftly(softly -> {
            softly.assertThat(created.tenantId()).isEqualTo(fixture.ownerTenantId());
            softly.assertThat(renamed.managementName()).isEqualTo("生产总览");
            softly.assertThat(saved.tenantId()).isEqualTo(fixture.ownerTenantId());
            softly.assertThat(saved.revision()).isOne();
            softly.assertThat(saved.content().path("pages").get(0).path("title").asString())
                    .isEqualTo("已保存画布");
            softly.assertThat(actions).containsExactly(
                    "dashboard.created", "dashboard.renamed", "dashboard.draft_saved");
            softly.assertThat(actors).containsExactly(
                    fixture.ownerId(), fixture.adminId(), fixture.adminId());
            softly.assertThat(tenants).containsOnly(fixture.ownerTenantId());
        });
    }

    /** 同一账号同键同请求必须恢复首次身份；异请求和软删结果分别稳定映射10009与10014。 */
    @Test
    @DisplayName("看板创建幂等映射稳定恢复并拒绝冲突")
    void idempotentDashboardCreationRestoresIdentityAndRejectsConflicts() throws Exception {
        Fixture fixture = seed();
        byte[] source = schema("幂等画布", fixture.localModelId(), LOCAL_DIGEST);

        DashboardCatalogEntry first = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.createIdempotent(
                        fixture.projectId(), "stable-dashboard-key", "幂等看板", source));
        DashboardCatalogEntry replay = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.createIdempotent(
                        fixture.projectId(), "stable-dashboard-key", "幂等看板", source));
        Throwable conflict = catchThrowable(() -> as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.createIdempotent(
                        fixture.projectId(), "stable-dashboard-key", "不同看板", source)));

        try (Connection owner = owner()) {
            execute(owner, "UPDATE dash_dashboard SET deleted_at=now() WHERE id=?", first.id());
        }
        Throwable deleted = catchThrowable(() -> as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.createIdempotent(
                        fixture.projectId(), "stable-dashboard-key", "幂等看板", source)));
        List<String> actions = auditActions(fixture.projectId());

        try (Connection owner = owner()) {
            assertSoftly(softly -> {
                softly.assertThat(replay).isEqualTo(first);
                softly.assertThat(errorCode(conflict)).isEqualTo(10009);
                softly.assertThat(errorCode(deleted)).isEqualTo(10014);
                softly.assertThat(queryLong(owner,
                        "SELECT count(*) FROM dash_dashboard_creation_result WHERE dashboard_id=?",
                        first.id())).isOne();
                softly.assertThat(actions).containsExactly("dashboard.created");
            });
        }
    }

    /** 审计在真实INSERT后失败时，目录、草稿、模型关系和恢复映射必须随事务整体回滚。 */
    @Test
    @DisplayName("看板幂等创建审计失败回滚全部事实")
    void idempotentDashboardCreationAuditFailureRollsBackAggregateAndMapping() throws Exception {
        Fixture fixture = seed();
        byte[] source = schema("审计候选", fixture.localModelId(), LOCAL_DIGEST);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            AuditLogEntry entry = invocation.getArgument(0, AuditLogEntry.class);
            if ("dashboard.created".equals(entry.action())) {
                throw new AuditFailureAfterInsertException();
            }
            return result;
        }).when(audits).record(any(AuditLogEntry.class));

        try {
            Throwable failure = catchThrowable(() -> as(
                    fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                    () -> dashboards.createIdempotent(
                            fixture.projectId(), "audit-dashboard-key", "审计看板", source)));
            assertThat(failure).isInstanceOf(AuditFailureAfterInsertException.class);
            try (Connection owner = owner()) {
                assertThat(queryLong(owner,
                        "SELECT count(*) FROM dash_dashboard_creation_result WHERE project_id=?",
                        fixture.projectId())).isZero();
                assertThat(queryLong(owner,
                        "SELECT count(*) FROM dash_dashboard WHERE project_id=?",
                        fixture.projectId())).isZero();
            }

            doCallRealMethod().when(audits).record(any(AuditLogEntry.class));
            DashboardCatalogEntry retried = as(
                    fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                    () -> dashboards.createIdempotent(
                            fixture.projectId(), "audit-dashboard-key", "审计看板", source));
            assertThat(retried.deletedAt()).isNull();
            assertThat(auditActions(fixture.projectId())).containsExactly("dashboard.created");
        } finally {
            doCallRealMethod().when(audits).record(any(AuditLogEntry.class));
        }
    }

    /** 两个空缺映射请求必须由事务级锁串行，并共同观察唯一目录、恢复映射与创建审计。 */
    @Test
    @DisplayName("并发同键看板创建只生成一个身份")
    void concurrentSameKeyDashboardCreationProducesOneIdentity() throws Exception {
        Fixture fixture = seed();
        byte[] source = schema("并发画布", fixture.localModelId(), LOCAL_DIGEST);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<DashboardCatalogEntry> creation = () -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                        () -> dashboards.createIdempotent(
                                fixture.projectId(), "concurrent-dashboard-key", "并发看板", source));
            };
            Future<DashboardCatalogEntry> first = executor.submit(creation);
            Future<DashboardCatalogEntry> second = executor.submit(creation);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            DashboardCatalogEntry firstResult = first.get(10, TimeUnit.SECONDS);
            DashboardCatalogEntry secondResult = second.get(10, TimeUnit.SECONDS);

            assertThat(secondResult).isEqualTo(firstResult);
            try (Connection owner = owner()) {
                assertThat(queryLong(owner,
                        "SELECT count(*) FROM dash_dashboard_creation_result WHERE project_id=?",
                        fixture.projectId())).isOne();
                assertThat(queryLong(owner,
                        "SELECT count(*) FROM dash_dashboard WHERE project_id=?",
                        fixture.projectId())).isOne();
            }
            assertThat(auditActions(fixture.projectId())).containsExactly("dashboard.created");
        } finally {
            start.countDown();
        }
    }

    /** 原生连接持有同一摘要锁时服务事务必须真实等待，释放后才能创建唯一恢复身份。 */
    @Test
    @DisplayName("看板创建事务真实等待同键advisory锁")
    void dashboardCreationWaitsForMatchingAdvisoryLock() throws Exception {
        Fixture fixture = seed();
        String key = "blocked-dashboard-key";
        byte[] source = schema("锁等待画布");
        String keyDigest = dashboardCreationKeyDigest(key);
        try (Connection blocker = application(); ExecutorService executor = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            setSessionProjectContext(blocker, fixture.ownerTenantId(), fixture.projectId());
            try (PreparedStatement statement = blocker.prepareStatement("""
                    SELECT pg_advisory_xact_lock(hashtextextended(
                        concat_ws(':','dashboard-create-v1',?::text,?::text,?::text,?),12013::bigint))
                    """)) {
                statement.setObject(1, fixture.ownerTenantId());
                statement.setObject(2, fixture.projectId());
                statement.setObject(3, fixture.ownerId());
                statement.setString(4, keyDigest);
                try (ResultSet rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                }
            }
            int blockerPid = backendPid(blocker);
            Future<DashboardCatalogEntry> pending = executor.submit(() -> as(
                    fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                    () -> dashboards.createIdempotent(
                            fixture.projectId(), key, "锁等待看板", source)));
            try {
                assertThat(awaitAdvisoryLockWait(blockerPid)).isTrue();
            } finally {
                blocker.commit();
            }
            assertThat(pending.get(10, TimeUnit.SECONDS).managementName()).isEqualTo("锁等待看板");
        }
    }

    /** 受控软删在未提交事务内持有目录锁时，重放必须等待并在提交后稳定观察10014。 */
    @Test
    @DisplayName("看板创建重放与受控软删按目录锁线性化")
    void dashboardCreationReplayWaitsForSoftDeleteThenReturnsNotReplayable() throws Exception {
        Fixture fixture = seed();
        byte[] source = schema("软删竞态画布");
        DashboardCatalogEntry dashboard = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.createIdempotent(
                        fixture.projectId(), "delete-race-key", "软删竞态看板", source));

        try (Connection deleter = application(); ExecutorService executor = Executors.newSingleThreadExecutor()) {
            deleter.setAutoCommit(false);
            setSessionProjectContext(deleter, fixture.ownerTenantId(), fixture.projectId());
            assertThat(callSoftDeleteFunction(
                    deleter, fixture.projectId(), dashboard.id(), 0, fixture.ownerId()).status())
                    .isEqualTo("DELETED");
            int deleterPid = backendPid(deleter);
            Future<Integer> replay = executor.submit(() -> errorCode(catchThrowable(() -> as(
                    fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                    () -> dashboards.createIdempotent(
                            fixture.projectId(), "delete-race-key", "软删竞态看板", source)))));
            try {
                assertThat(awaitBlockedBy(deleterPid)).isTrue();
            } finally {
                deleter.commit();
            }
            assertThat(replay.get(10, TimeUnit.SECONDS)).isEqualTo(10014);
        }
        assertThat(auditActions(fixture.projectId())).containsExactly("dashboard.created");
    }

    /** 幂等键只在tenant/project/account身份内复用；同文本跨账号或项目必须生成独立目录。 */
    @Test
    @DisplayName("看板创建幂等键按账号和项目隔离")
    void dashboardCreationKeyIsIsolatedAcrossAccountsAndProjectsAndRlsScopes() throws Exception {
        Fixture fixture = seed();
        try (Connection owner = owner()) {
            execute(owner, "INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'OWNER')",
                    Uuid7.generate(), fixture.neighborProjectId(), fixture.ownerId());
        }
        byte[] source = schema("作用域画布");
        DashboardCatalogEntry ownerDashboard = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.createIdempotent(fixture.projectId(), "shared-key", "作用域看板", source));
        DashboardCatalogEntry adminDashboard = as(
                fixture.collaboratorTenantId(), fixture.projectId(), fixture.adminId(),
                () -> dashboards.createIdempotent(fixture.projectId(), "shared-key", "作用域看板", source));
        DashboardCatalogEntry neighborDashboard = as(
                fixture.ownerTenantId(), fixture.neighborProjectId(), fixture.ownerId(),
                () -> dashboards.createIdempotent(
                        fixture.neighborProjectId(), "shared-key", "作用域看板", source));

        assertThat(Set.of(ownerDashboard.id(), adminDashboard.id(), neighborDashboard.id())).hasSize(3);
        try (Connection application = application()) {
            setSessionProjectContext(application, fixture.ownerTenantId(), fixture.projectId());
            assertThat(queryLong(application,
                    "SELECT count(*) FROM dash_dashboard_creation_result WHERE project_id=?",
                    fixture.projectId())).isEqualTo(2);
            setSessionProjectContext(application, fixture.ownerTenantId(), fixture.neighborProjectId());
            assertThat(queryLong(application,
                    "SELECT count(*) FROM dash_dashboard_creation_result WHERE project_id=?",
                    fixture.neighborProjectId())).isOne();
            UUID wrongProjectId = Uuid7.generate();
            setSessionProjectContext(application, fixture.ownerTenantId(), wrongProjectId);
            assertThat(queryLong(application,
                    "SELECT count(*) FROM dash_dashboard_creation_result WHERE project_id=?",
                    fixture.projectId())).isZero();
        }
    }

    /** 501条恢复映射须先分500与1两批删除，随后才能触碰看板父事实，且邻居项目完整保留。 */
    @Test
    @DisplayName("项目清理有界删除看板创建恢复映射")
    void projectCleanupDeletesDashboardCreationMappingsBeforeParentsInBoundedBatches() throws Exception {
        Fixture fixture = seed();
        UUID cleanupToken = Uuid7.generate();
        try (Connection owner = owner()) {
            execute(owner, """
                    INSERT INTO dash_dashboard(
                        id,tenant_id,project_id,management_name,created_by,updated_by)
                    SELECT gen_random_uuid(), ?, ?, '清理映射' || ordinal, ?, ?
                      FROM generate_series(1,501) ordinal
                    """, fixture.ownerTenantId(), fixture.projectId(),
                    fixture.ownerId(), fixture.ownerId());
            execute(owner, """
                    INSERT INTO dash_dashboard_draft(
                        dashboard_id,tenant_id,project_id,content,revision,updated_by)
                    SELECT id,tenant_id,project_id,'{"schemaVersion":"tc.dashboard/v1"}'::jsonb,0,?
                      FROM dash_dashboard WHERE project_id=?
                    """, fixture.ownerId(), fixture.projectId());
            execute(owner, """
                    INSERT INTO dash_dashboard_creation_result(
                        tenant_id,project_id,account_id,idempotency_key_digest,
                        request_digest,dashboard_id,created_at)
                    SELECT tenant_id,project_id,?,encode(digest(id::text,'sha256'),'hex'),
                           repeat('a',64),id,created_at
                      FROM dash_dashboard WHERE project_id=?
                    """, fixture.ownerId(), fixture.projectId());
            execute(owner, """
                    INSERT INTO dash_dashboard(
                        id,tenant_id,project_id,management_name,created_by,updated_by)
                    VALUES (?,?,?,?,?,?)
                    """, Uuid7.generate(), fixture.ownerTenantId(), fixture.neighborProjectId(),
                    "邻居映射", fixture.ownerId(), fixture.ownerId());
            execute(owner, """
                    INSERT INTO dash_dashboard_creation_result(
                        tenant_id,project_id,account_id,idempotency_key_digest,
                        request_digest,dashboard_id,created_at)
                    SELECT tenant_id,project_id,?,repeat('b',64),repeat('c',64),id,created_at
                      FROM dash_dashboard WHERE project_id=?
                    """, fixture.ownerId(), fixture.neighborProjectId());
            execute(owner, """
                    UPDATE sys_project
                       SET status='PURGING',lifecycle_generation=1,
                           deleted_at=now()-interval '31 days',cleanup_stage='DASHBOARD',
                           cleanup_started_at=now()-interval '1 minute',
                           cleanup_next_attempt_at=now()-interval '1 second',
                           cleanup_lease_token=?,cleanup_lease_until=now()+interval '10 minutes'
                     WHERE id=?
                    """, cleanupToken, fixture.projectId());
        }

        try (Connection application = application()) {
            setSessionProjectContext(application, fixture.ownerTenantId(), fixture.projectId());
            assertThat(callDashboardCleanup(application, fixture, cleanupToken))
                    .isEqualTo(new CleanupFunctionResult(500, false, null));
            try (Connection owner = owner()) {
                assertThat(queryLong(owner,
                        "SELECT count(*) FROM dash_dashboard_creation_result WHERE project_id=?",
                        fixture.projectId())).isOne();
                assertThat(queryLong(owner,
                        "SELECT count(*) FROM dash_dashboard WHERE project_id=?",
                        fixture.projectId())).isEqualTo(501);
            }
            assertThat(callDashboardCleanup(application, fixture, cleanupToken))
                    .isEqualTo(new CleanupFunctionResult(1, false, null));
            CleanupFunctionResult result = null;
            for (int invocation = 0; invocation < 10 && (result == null || !result.complete()); invocation++) {
                result = callDashboardCleanup(application, fixture, cleanupToken);
                assertThat(result.deletedRows()).isBetween(0, 500);
                assertThat(result.blockedReason()).isNull();
            }
            assertThat(result).isEqualTo(new CleanupFunctionResult(0, true, null));
        }

        try (Connection owner = owner()) {
            assertSoftly(softly -> {
                softly.assertThat(queryLong(owner,
                        "SELECT count(*) FROM dash_dashboard_creation_result WHERE project_id=?",
                        fixture.projectId())).isZero();
                softly.assertThat(queryLong(owner,
                        "SELECT count(*) FROM dash_dashboard WHERE project_id=?",
                        fixture.projectId())).isZero();
                softly.assertThat(queryLong(owner,
                        "SELECT count(*) FROM dash_dashboard_creation_result WHERE project_id=?",
                        fixture.neighborProjectId())).isOne();
                softly.assertThat(queryLong(owner,
                        "SELECT count(*) FROM dash_dashboard WHERE project_id=?",
                        fixture.neighborProjectId())).isOne();
            });
        }
    }

    /** 新映射表必须完整注释、启用项目RLS、保留NO ACTION父约束，并只开放APP查询与插入。 */
    @Test
    @DisplayName("看板创建恢复映射具有完整数据库防线")
    void dashboardCreationMappingHasDocumentedRlsNoActionForeignKeyAndMinimalAcl() throws Exception {
        try (Connection owner = owner(); PreparedStatement statement = owner.prepareStatement("""
                SELECT
                    obj_description('dash_dashboard_creation_result'::regclass,'pg_class') IS NOT NULL
                        AND (SELECT count(*)=7 FROM information_schema.columns column_info
                              WHERE column_info.table_schema='public'
                                AND column_info.table_name='dash_dashboard_creation_result'
                                AND col_description('dash_dashboard_creation_result'::regclass,
                                        column_info.ordinal_position) IS NOT NULL) AS documented,
                    (SELECT relrowsecurity FROM pg_class
                      WHERE oid='dash_dashboard_creation_result'::regclass) AS rls_enabled,
                    EXISTS (SELECT 1 FROM pg_policies
                             WHERE tablename='dash_dashboard_creation_result'
                               AND policyname='project_isolation') AS project_policy,
                    EXISTS (SELECT 1 FROM pg_constraint
                             WHERE conname='dash_dashboard_creation_result_dashboard_fk'
                               AND confdeltype='a') AS no_action_fk,
                    EXISTS (SELECT 1 FROM pg_constraint
                             WHERE conname='dash_dashboard_creation_result_pk'
                               AND pg_get_constraintdef(oid) =
                                   'PRIMARY KEY (tenant_id, project_id, account_id, idempotency_key_digest)')
                        AS exact_primary_key,
                    EXISTS (SELECT 1 FROM pg_constraint
                             WHERE conname='dash_dashboard_creation_result_dashboard_uk'
                               AND pg_get_constraintdef(oid) =
                                   'UNIQUE (tenant_id, project_id, dashboard_id)')
                        AS exact_dashboard_unique,
                    EXISTS (SELECT 1 FROM pg_indexes
                             WHERE indexname='dash_dashboard_creation_result_purge_scope_idx'
                               AND indexdef LIKE
                                   '%(tenant_id, project_id, dashboard_id, account_id, idempotency_key_digest)%')
                        AS exact_cleanup_index,
                    has_table_privilege('thingslink_app','dash_dashboard_creation_result','SELECT,INSERT')
                        AND NOT has_table_privilege(
                            'thingslink_app','dash_dashboard_creation_result','UPDATE')
                        AND NOT has_table_privilege(
                            'thingslink_app','dash_dashboard_creation_result','DELETE')
                        AND NOT has_table_privilege(
                            'thingslink_app','dash_dashboard_creation_result','TRUNCATE') AS minimal_acl
                """)) {
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                boolean documented = rows.getBoolean("documented");
                boolean rlsEnabled = rows.getBoolean("rls_enabled");
                boolean projectPolicy = rows.getBoolean("project_policy");
                boolean noActionForeignKey = rows.getBoolean("no_action_fk");
                boolean exactPrimaryKey = rows.getBoolean("exact_primary_key");
                boolean exactDashboardUnique = rows.getBoolean("exact_dashboard_unique");
                boolean exactCleanupIndex = rows.getBoolean("exact_cleanup_index");
                boolean minimalAcl = rows.getBoolean("minimal_acl");
                assertSoftly(softly -> {
                    softly.assertThat(documented).isTrue();
                    softly.assertThat(rlsEnabled).isTrue();
                    softly.assertThat(projectPolicy).isTrue();
                    softly.assertThat(noActionForeignKey).isTrue();
                    softly.assertThat(exactPrimaryKey).isTrue();
                    softly.assertThat(exactDashboardUnique).isTrue();
                    softly.assertThat(exactCleanupIndex).isTrue();
                    softly.assertThat(minimalAcl).isTrue();
                });
                assertThat(rows.next()).isFalse();
            }
        }
        try (Connection application = application()) {
            assertPostgresState(() -> execute(application,
                    "UPDATE dash_dashboard_creation_result SET request_digest=repeat('a',64)"), "42501");
            assertPostgresState(() -> execute(application,
                    "DELETE FROM dash_dashboard_creation_result"), "42501");
        }
    }

    /** OPERATOR与VIEWER读取成功，但保存必须在Schema、模型和数据库写入前稳定60035。 */
    @ParameterizedTest
    @EnumSource(ReadOnlyMember.class)
    @DisplayName("只读角色可读草稿但不能保存")
    void readOnlyMembersCanReadButCannotSave(ReadOnlyMember member) throws Exception {
        Fixture fixture = seed();
        DashboardCatalogEntry dashboard = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.create(fixture.projectId(), "只读角色看板", schema("只读前")));
        List<String> before = facts(fixture.projectId());
        long auditsBefore = auditCount(fixture.projectId());

        DashboardDraft readable = as(
                fixture.collaboratorTenantId(), fixture.projectId(), member.accountId(fixture),
                () -> dashboards.getDraft(fixture.projectId(), dashboard.id()));
        Throwable failure = catchThrowable(() -> as(
                fixture.collaboratorTenantId(), fixture.projectId(), member.accountId(fixture),
                () -> dashboards.saveDraft(
                        fixture.projectId(), dashboard.id(), "0", schema("越权保存"))));
        List<String> after = facts(fixture.projectId());
        long auditsAfter = auditCount(fixture.projectId());

        assertSoftly(softly -> {
            softly.assertThat(readable.revision()).isZero();
            softly.assertThat(errorCode(failure)).isEqualTo(60035);
            softly.assertThat(after).isEqualTo(before);
            softly.assertThat(auditsAfter).isEqualTo(auditsBefore);
        });
    }

    /** ARCHIVED保留四角色读，但OWNER写经持续生命周期许可拒绝且事实零漂移。 */
    @Test
    @DisplayName("归档项目保留读取并拒绝草稿写入")
    void archivedProjectRemainsReadableAndRejectsWrites() throws Exception {
        Fixture fixture = seed();
        DashboardCatalogEntry dashboard = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.create(fixture.projectId(), "归档前看板", schema("归档前")));
        archive(fixture.projectId());
        List<String> before = facts(fixture.projectId());
        long auditsBefore = auditCount(fixture.projectId());

        DashboardDraft readable = as(
                fixture.collaboratorTenantId(), fixture.projectId(), fixture.viewerId(),
                () -> dashboards.getDraft(fixture.projectId(), dashboard.id()));
        Throwable failure = catchThrowable(() -> as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.saveDraft(
                        fixture.projectId(), dashboard.id(), "0", schema("归档后"))));
        List<String> after = facts(fixture.projectId());
        long auditsAfter = auditCount(fixture.projectId());

        assertSoftly(softly -> {
            softly.assertThat(readable.dashboardId()).isEqualTo(dashboard.id());
            softly.assertThat(errorCode(failure)).isEqualTo(50017);
            softly.assertThat(after).isEqualTo(before);
            softly.assertThat(auditsAfter).isEqualTo(auditsBefore);
        });
    }

    /** 缺失、跨项目和摘要不匹配均返回同一60038，不能借错误差异枚举模型事实。 */
    @Test
    @DisplayName("外部模型失败统一拒绝且草稿零漂移")
    void missingForeignAndDigestMismatchedModelsUseOneSafeFailure() throws Exception {
        Fixture fixture = seed();
        DashboardCatalogEntry dashboard = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.create(fixture.projectId(), "模型校验看板", schema("无模型初始")));
        List<String> before = facts(fixture.projectId());
        long auditsBefore = auditCount(fixture.projectId());
        List<byte[]> rejected = List.of(
                schema("缺失模型", UUID.randomUUID(), LOCAL_DIGEST),
                schema("跨项目模型", fixture.foreignModelId(), FOREIGN_DIGEST),
                schema("摘要不匹配", fixture.localModelId(), "c".repeat(64)));

        for (byte[] candidate : rejected) {
            Throwable failure = catchThrowable(() -> as(
                    fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                    () -> dashboards.saveDraft(fixture.projectId(), dashboard.id(), "0", candidate)));
            assertThat(failure).isInstanceOfSatisfying(BusinessException.class, business -> {
                assertThat(business.errorCode().code()).isEqualTo(60038);
                assertThat(business.details()).isEmpty();
                assertThat(business.getMessage()).isEqualTo("看板外部模型引用不合法");
            });
            assertThat(facts(fixture.projectId())).isEqualTo(before);
            assertThat(auditCount(fixture.projectId())).isEqualTo(auditsBefore);
        }
    }

    /** Device公开端口精确返回有限描述，缺失和跨项目在真实RLS下统一为空。 */
    @Test
    @DisplayName("模型描述端口按项目精确查询并失败关闭")
    void modelDescriptorPortReturnsExactMetadataAndFailsClosed() throws Exception {
        Fixture fixture = seed();

        assertThat(as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> descriptors.find(fixture.projectId(), fixture.localModelId())))
                .contains(new ThingModelVersionDescriptor(
                        fixture.localModelId(), fixture.projectId(),
                        "PG_JSONB_TEXT_V1_SHA256", LOCAL_DIGEST, "TC_PROPERTY_COMPOSITE_V1"));
        assertThat(as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> descriptors.find(fixture.projectId(), UUID.randomUUID()))).isEmpty();
        assertThat(as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> descriptors.find(fixture.projectId(), fixture.foreignModelId()))).isEmpty();
        // 公开端口自身不创建可信范围；没有调用链RLS上下文时必须失败关闭。
        assertThat(descriptors.find(fixture.projectId(), fixture.localModelId())).isEmpty();
        assertThat(as(fixture.ownerTenantId(), fixture.neighborProjectId(), fixture.ownerId(),
                () -> descriptors.find(fixture.neighborProjectId(), fixture.foreignModelId())))
                .contains(new ThingModelVersionDescriptor(
                        fixture.foreignModelId(), fixture.neighborProjectId(),
                        "PG_JSONB_TEXT_V1_SHA256", FOREIGN_DIGEST, "TC_PROPERTY_COMPOSITE_V1"));
    }

    /** Device资格公开端口在真实RLS下只返回同项目顶层属性和未删除设备当前模型。 */
    @Test
    @DisplayName("模型属性与默认设备端口按项目精确查询并失败关闭")
    void propertyAndDeviceQualificationPortsReturnOnlyProjectScopedFacts() throws Exception {
        Fixture fixture = seed();

        List<ThingModelPropertyFacts> expectedProperties = List.of(
                new ThingModelPropertyFacts("temperature", ThingModelPropertyFacts.DataType.NUMBER,
                        new java.math.BigDecimal("-40"), new java.math.BigDecimal("125")),
                new ThingModelPropertyFacts("label", ThingModelPropertyFacts.DataType.TEXT, null, null),
                new ThingModelPropertyFacts("enabled", ThingModelPropertyFacts.DataType.SWITCH, null, null),
                new ThingModelPropertyFacts("mode", ThingModelPropertyFacts.DataType.ENUM, null, null),
                new ThingModelPropertyFacts("payload", ThingModelPropertyFacts.DataType.OBJECT, null, null),
                new ThingModelPropertyFacts("samples", ThingModelPropertyFacts.DataType.LIST, null, null));
        for (ThingModelPropertyFacts expected : expectedProperties) {
            assertThat(as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                    () -> propertyFacts.find(
                            fixture.projectId(), fixture.localModelId(), expected.propertyKey())))
                    .contains(expected);
        }
        assertThat(as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> propertyFacts.find(
                        fixture.projectId(), fixture.localModelId(), "missing"))).isEmpty();
        assertThat(as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> propertyFacts.find(
                        fixture.projectId(), fixture.foreignModelId(), "temperature"))).isEmpty();
        assertThat(propertyFacts.find(
                fixture.projectId(), fixture.localModelId(), "temperature")).isEmpty();

        assertThat(as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> deviceBindings.find(fixture.projectId(), fixture.localDeviceId())))
                .contains(new DeviceModelBindingFacts(
                        fixture.localDeviceId(), fixture.projectId(), fixture.localModelId()));
        assertThat(as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> deviceBindings.find(fixture.projectId(), fixture.foreignDeviceId()))).isEmpty();
        assertThat(as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> deviceBindings.find(fixture.projectId(), UUID.randomUUID()))).isEmpty();
        assertThat(as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> deviceBindings.find(fixture.projectId(), fixture.unboundDeviceId()))).isEmpty();
        assertThat(as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> deviceBindings.find(fixture.projectId(), fixture.deletedDeviceId()))).isEmpty();
        assertThat(deviceBindings.find(fixture.projectId(), fixture.localDeviceId())).isEmpty();
    }

    /** 审计真实INSERT后失败必须回滚草稿revision、内容、模型关系和审计自身，并允许重试。 */
    @Test
    @DisplayName("草稿审计失败回滚全部业务与关系事实")
    void draftAuditFailureRollsBackContentReferencesAndAudit() throws Exception {
        Fixture fixture = seed();
        DashboardCatalogEntry dashboard = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.create(fixture.projectId(), "审计回滚看板", schema("审计前")));
        List<String> before = facts(fixture.projectId());
        long auditsBefore = auditCount(fixture.projectId());
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            AuditLogEntry entry = invocation.getArgument(0, AuditLogEntry.class);
            if ("dashboard.draft_saved".equals(entry.action())) {
                throw new AuditFailureAfterInsertException();
            }
            return result;
        }).when(audits).record(any(AuditLogEntry.class));

        try {
            Throwable failure = catchThrowable(() -> as(
                    fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                    () -> dashboards.saveDraft(fixture.projectId(), dashboard.id(), "0",
                            schema("审计失败候选", fixture.localModelId(), LOCAL_DIGEST))));
            assertThat(failure).isInstanceOf(AuditFailureAfterInsertException.class);
            assertThat(facts(fixture.projectId())).isEqualTo(before);
            assertThat(auditCount(fixture.projectId())).isEqualTo(auditsBefore);

            doCallRealMethod().when(audits).record(any(AuditLogEntry.class));
            DashboardDraft retried = as(
                    fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                    () -> dashboards.saveDraft(fixture.projectId(), dashboard.id(), "0",
                            schema("审计恢复", fixture.localModelId(), LOCAL_DIGEST)));
            assertThat(retried.revision()).isOne();
            assertThat(retried.modelReferences()).singleElement().satisfies(reference ->
                    assertThat(reference.thingModelVersionId()).isEqualTo(fixture.localModelId()));
            assertThat(auditCount(fixture.projectId())).isEqualTo(auditsBefore + 1);
        } finally {
            doCallRealMethod().when(audits).record(any(AuditLogEntry.class));
        }
    }

    /** 两个相同旧revision的服务事务竞争时只提交一份草稿和一条成功审计。 */
    @Test
    @DisplayName("并发草稿保存只有一个赢家")
    void concurrentDraftSavesCommitOneWinnerAndOneConflict() throws Exception {
        Fixture fixture = seed();
        DashboardCatalogEntry dashboard = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.create(fixture.projectId(), "并发看板", schema("并发前")));
        long auditsBefore = auditCount(fixture.projectId());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<SaveOutcome> first = executor.submit(() -> saveConcurrently(
                    fixture, dashboard.id(), "候选甲", ready, start));
            Future<SaveOutcome> second = executor.submit(() -> saveConcurrently(
                    fixture, dashboard.id(), "候选乙", ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<SaveOutcome> outcomes = List.of(
                    first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            DashboardDraft persisted = as(
                    fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                    () -> dashboards.getDraft(fixture.projectId(), dashboard.id()));
            long auditsAfter = auditCount(fixture.projectId());

            assertSoftly(softly -> {
                softly.assertThat(outcomes).filteredOn(SaveOutcome::success).hasSize(1);
                softly.assertThat(outcomes).filteredOn(outcome -> !outcome.success())
                        .extracting(SaveOutcome::errorCode).containsExactly(60037);
                softly.assertThat(persisted.revision()).isOne();
                softly.assertThat(auditsAfter).isEqualTo(auditsBefore + 1);
            });
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** 键顺序由jsonb消除，数字尾零由jsonb保留；摘要必须来自同一PG规范文本而非Java字符串。 */
    @Test
    @DisplayName("PostgreSQL规范文本裁决键顺序和数字表示摘要")
    void postgreSqlCanonicalTextControlsKeyOrderAndNumericDigestSemantics() throws Exception {
        Fixture fixture = seed();
        DashboardPublicationCandidate ordered = prepare(
                fixture, publicationDraft(fixture, publicationSchema(fixture.localModelId(), false, "123")));
        DashboardPublicationCandidate reordered = prepare(
                fixture, publicationDraft(fixture, publicationSchema(fixture.localModelId(), true, "123")));
        DashboardPublicationCandidate decimal = prepare(
                fixture, publicationDraft(fixture, publicationSchema(fixture.localModelId(), false, "123.0")));

        String independentPgText = jdbc.queryForObject(
                "SELECT (?::jsonb)::text", String.class, ordered.normalizedSchema().toString());
        assertSoftly(softly -> {
            softly.assertThat(ordered.postgresqlCanonicalText()).isEqualTo(independentPgText);
            softly.assertThat(ordered.schemaDigest()).isEqualTo(
                    sha256(ordered.postgresqlCanonicalUtf8()));
            softly.assertThat(ordered.postgresqlCanonicalText())
                    .isNotEqualTo(ordered.normalizedSchema().toString());
            softly.assertThat(reordered.postgresqlCanonicalText())
                    .isEqualTo(ordered.postgresqlCanonicalText());
            softly.assertThat(reordered.schemaDigest()).isEqualTo(ordered.schemaDigest());
            softly.assertThat(decimal.postgresqlCanonicalText()).contains("123.0");
            softly.assertThat(decimal.schemaDigest()).isNotEqualTo(ordered.schemaDigest());
        });
    }

    /** 准备候选只能读取草稿并计算派生事实，不得创建版本、模型关系、切指针或追加审计。 */
    @Test
    @DisplayName("规范候选准备不写版本指针或审计")
    void candidatePreparationLeavesPublicationAndAuditFactsUntouched() throws Exception {
        Fixture fixture = seed();
        DashboardCatalogEntry dashboard = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.create(fixture.projectId(), "候选只读看板",
                        schema("候选前", fixture.localModelId(), LOCAL_DIGEST)));
        DashboardDraft draft = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.getDraft(fixture.projectId(), dashboard.id()));
        List<String> before = publicationFacts(fixture.projectId(), dashboard.id());

        DashboardPublicationCandidate candidate = prepare(fixture, draft);
        List<String> after = publicationFacts(fixture.projectId(), dashboard.id());

        assertSoftly(softly -> {
            softly.assertThat(candidate.dashboardId()).isEqualTo(dashboard.id());
            softly.assertThat(candidate.sourceDraftRevision()).isZero();
            softly.assertThat(candidate.schemaDigestAlgorithm())
                    .isEqualTo("PG_JSONB_TEXT_V1_SHA256");
            softly.assertThat(before).containsExactly(
                    "catalog:0:null", "versions:0", "version_refs:0", "audits:1");
            softly.assertThat(after).isEqualTo(before);
        });
    }

    /** 同步起跑一个真实服务保存事务，并压缩为可比较的成功或错误结果。 */
    private SaveOutcome saveConcurrently(
            Fixture fixture, UUID dashboardId, String title,
            CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("看板并发保存未按时放行");
            }
            DashboardDraft saved = as(
                    fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                    () -> dashboards.saveDraft(fixture.projectId(), dashboardId, "0",
                            schema(title, fixture.localModelId(), LOCAL_DIGEST)));
            return SaveOutcome.saved(saved);
        } catch (Throwable failure) {
            return SaveOutcome.failed(errorCode(failure));
        }
    }

    /** 同步起跑一个真实发布事务，并压缩为成功或稳定业务错误。 */
    private PublishOutcome publishConcurrently(
            Fixture fixture, UUID dashboardId,
            CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("看板并发发布未按时放行");
            }
            DashboardVersion version = as(
                    fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                    () -> publications.publish(fixture.projectId(), dashboardId, "0", "0"));
            return PublishOutcome.published(version);
        } catch (Throwable failure) {
            return PublishOutcome.failed(errorCode(failure));
        }
    }

    /** 同步起跑一个真实应用发布事务，并压缩为成功或稳定业务错误。 */
    private ApplicationPublishOutcome publishApplicationConcurrently(
            Fixture fixture, UUID applicationId,
            CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("应用并发发布未按时放行");
            }
            ApplicationVersion version = as(
                    fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                    () -> applicationPublications.publish(
                            fixture.projectId(), applicationId, "0", "0"));
            return ApplicationPublishOutcome.published(version);
        } catch (Throwable failure) {
            return ApplicationPublishOutcome.failed(errorCode(failure));
        }
    }

    /** 执行一次指定双revision的真实应用发布，并压缩为成功或稳定业务错误。 */
    private ApplicationPublishOutcome publishApplication(
            Fixture fixture,
            UUID applicationId,
            String expectedDraftRevision,
            String expectedPublicationRevision) {
        try {
            ApplicationVersion version = as(
                    fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                    () -> applicationPublications.publish(
                            fixture.projectId(), applicationId,
                            expectedDraftRevision, expectedPublicationRevision));
            return ApplicationPublishOutcome.published(version);
        } catch (Throwable failure) {
            return ApplicationPublishOutcome.failed(errorCode(failure));
        }
    }

    /** 执行一次指定发布revision的真实应用回滚，并压缩为成功目标或稳定业务错误。 */
    private ApplicationRollbackOutcome rollbackApplication(
            Fixture fixture,
            UUID applicationId,
            UUID targetVersionId,
            String expectedPublicationRevision) {
        try {
            ApplicationVersion version = as(
                    fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                    () -> applicationPublications.rollback(
                            fixture.projectId(), applicationId,
                            targetVersionId, expectedPublicationRevision));
            return ApplicationRollbackOutcome.rolledBack(version);
        } catch (Throwable failure) {
            return ApplicationRollbackOutcome.failed(errorCode(failure));
        }
    }

    /** 在独立真实服务事务中撤回应用，并压缩为成功原版本或稳定业务错误。 */
    private ApplicationWithdrawalOutcome withdrawApplication(
            Fixture fixture, UUID applicationId, String expectedPublicationRevision) {
        try {
            ApplicationVersion version = as(
                    fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                    () -> applicationPublications.withdraw(
                            fixture.projectId(), applicationId, expectedPublicationRevision));
            return ApplicationWithdrawalOutcome.withdrawn(version);
        } catch (Throwable failure) {
            return ApplicationWithdrawalOutcome.failed(errorCode(failure));
        }
    }

    /** 在独立真实服务事务中软删应用，并压缩为成功标记或稳定业务错误。 */
    private ApplicationSoftDeleteOutcome softDeleteApplication(
            Fixture fixture, UUID applicationId, String expectedPublicationRevision) {
        try {
            as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(), () -> {
                applicationPublications.softDelete(
                        fixture.projectId(), applicationId, expectedPublicationRevision);
                return null;
            });
            return ApplicationSoftDeleteOutcome.success();
        } catch (Throwable failure) {
            return ApplicationSoftDeleteOutcome.failed(errorCode(failure));
        }
    }

    /** 建立已发布A/B两个不可变应用版本及其仍可运行的精确看板引用。 */
    private ApplicationRollbackFixture applicationRollbackFixture(
            Fixture fixture, String managementName) throws Exception {
        DashboardCatalogEntry dashboard = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.create(fixture.projectId(), managementName + "看板", schema("应用回滚页面")));
        DashboardVersion dashboardVersion = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> publications.publish(fixture.projectId(), dashboard.id(), "0", "0"));
        ApplicationCatalogEntry application = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> applications.create(
                        fixture.projectId(), managementName,
                        applicationDraft("版本A", dashboard.id(), dashboardVersion.id())));
        ApplicationVersion versionA = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> applicationPublications.publish(
                        fixture.projectId(), application.id(), "0", "0"));
        as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> applications.saveDraft(
                        fixture.projectId(), application.id(), "0",
                        applicationDraft("版本B", dashboard.id(), dashboardVersion.id())));
        ApplicationVersion versionB = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> applicationPublications.publish(
                        fixture.projectId(), application.id(), "1", "1"));
        return new ApplicationRollbackFixture(
                dashboard, dashboardVersion, application, versionA, versionB);
    }

    /** 在独立真实服务事务中执行回滚，并压缩为成功目标或稳定业务错误。 */
    private RollbackOutcome rollbackConcurrently(
            Fixture fixture, UUID dashboardId, UUID targetVersionId,
            String expectedPublicationRevision) {
        try {
            DashboardVersion version = as(
                    fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                    () -> publications.rollback(
                            fixture.projectId(), dashboardId, targetVersionId,
                            expectedPublicationRevision));
            return RollbackOutcome.rolledBack(version);
        } catch (Throwable failure) {
            return RollbackOutcome.failed(errorCode(failure));
        }
    }

    /** 在独立真实服务事务中执行撤回，并压缩为成功原版本或稳定业务错误。 */
    private WithdrawalOutcome withdrawConcurrently(
            Fixture fixture, UUID dashboardId, String expectedPublicationRevision) {
        try {
            DashboardVersion version = as(
                    fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                    () -> publications.withdraw(
                            fixture.projectId(), dashboardId, expectedPublicationRevision));
            return WithdrawalOutcome.withdrawn(version);
        } catch (Throwable failure) {
            return WithdrawalOutcome.failed(errorCode(failure));
        }
    }

    /** 在独立真实服务事务中执行软删，并压缩为成功标记或稳定业务错误。 */
    private SoftDeleteOutcome softDeleteConcurrently(
            Fixture fixture, UUID dashboardId, String expectedPublicationRevision) {
        try {
            as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                    () -> {
                        publications.softDelete(
                                fixture.projectId(), dashboardId, expectedPublicationRevision);
                        return null;
                    });
            return SoftDeleteOutcome.success();
        } catch (Throwable failure) {
            return SoftDeleteOutcome.failed(errorCode(failure));
        }
    }

    /** 在当前项目RLS身份下准备只读发布候选。 */
    private DashboardPublicationCandidate prepare(Fixture fixture, DashboardDraft draft) throws Exception {
        return as(fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> publicationCandidates.prepare(draft));
    }

    /** 构造仅用于PG摘要语义的持久草稿事实。 */
    private DashboardDraft publicationDraft(Fixture fixture, byte[] source) throws Exception {
        return new DashboardDraft(
                UUID.randomUUID(), fixture.ownerTenantId(), fixture.projectId(), JSON.readTree(source), 3,
                fixture.ownerId(), java.time.Instant.parse("2026-09-06T12:00:00Z"),
                java.time.Instant.parse("2026-09-06T12:00:00Z"),
                List.of(new DashboardModelReference(0, "pump_model", fixture.localModelId())));
    }

    /** 创建一份尚未发布、含单一真实模型关系且已生成PG候选的完整夹具。 */
    private PublicationFixture publicationFixture(String managementName) throws Exception {
        Fixture fixture = seed();
        DashboardCatalogEntry dashboard = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.create(fixture.projectId(), managementName,
                        schema(managementName, fixture.localModelId(), LOCAL_DIGEST)));
        DashboardDraft draft = as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> dashboards.getDraft(fixture.projectId(), dashboard.id()));
        return new PublicationFixture(fixture, dashboard, prepare(fixture, draft));
    }

    /** 通过普通APP连接直接调用唯一发布函数，避免Java仓储预校验遮蔽数据库围栏。 */
    private static PublicationFunctionResult callPublicationFunction(
            Connection connection,
            DashboardPublicationCandidate candidate,
            UUID versionId,
            UUID actorAccountId,
            String schema,
            String digest,
            long expectedPublicationRevision) throws SQLException {
        try (PreparedStatement call = connection.prepareStatement("""
                SELECT publication_status, version_number, publication_revision
                  FROM public.dashboard_publish_version(
                       ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
                """)) {
            call.setQueryTimeout(5);
            call.setObject(1, candidate.projectId());
            call.setObject(2, candidate.dashboardId());
            call.setLong(3, candidate.sourceDraftRevision());
            call.setLong(4, expectedPublicationRevision);
            call.setObject(5, versionId);
            call.setString(6, schema);
            call.setString(7, DashboardDraft.SCHEMA_VERSION);
            call.setString(8, candidate.schemaDigestAlgorithm());
            call.setString(9, digest);
            call.setString(10, JSON.valueToTree(candidate.requiredComponents()).toString());
            call.setString(11, JSON.valueToTree(candidate.requiredResources()).toString());
            call.setObject(12, actorAccountId);
            call.setTimestamp(13, Timestamp.from(java.time.Instant.now()));
            try (ResultSet rows = call.executeQuery()) {
                assertThat(rows.next()).isTrue();
                PublicationFunctionResult result = new PublicationFunctionResult(
                        rows.getString(1), rows.getObject(2, Long.class), rows.getObject(3, Long.class));
                assertThat(rows.next()).isFalse();
                return result;
            }
        }
    }

    /** 通过普通APP连接直调应用发布函数，使摘要或关系反例不被Java候选边界提前拦截。 */
    private static ApplicationPublicationFunctionResult callApplicationPublicationFunction(
            Connection connection,
            ApplicationPublicationCandidate candidate,
            UUID versionId,
            UUID actorAccountId,
            String digest,
            long expectedPublicationRevision) throws SQLException {
        try (PreparedStatement call = connection.prepareStatement("""
                SELECT publication_status, version_number, publication_revision
                  FROM public.application_publish_version(
                       ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?::jsonb, ?, ?)
                """)) {
            call.setQueryTimeout(5);
            call.setObject(1, candidate.projectId());
            call.setObject(2, candidate.applicationId());
            call.setLong(3, candidate.sourceDraftRevision());
            call.setLong(4, expectedPublicationRevision);
            call.setObject(5, versionId);
            call.setString(6, candidate.snapshot().toString());
            call.setString(7, candidate.snapshotDigestAlgorithm());
            call.setString(8, digest);
            call.setString(9, JSON.valueToTree(candidate.dashboardReferences()).toString());
            call.setObject(10, actorAccountId);
            call.setTimestamp(11, Timestamp.from(java.time.Instant.now()));
            try (ResultSet rows = call.executeQuery()) {
                assertThat(rows.next()).isTrue();
                ApplicationPublicationFunctionResult result = new ApplicationPublicationFunctionResult(
                        rows.getString(1), rows.getObject(2, Long.class), rows.getObject(3, Long.class));
                assertThat(rows.next()).isFalse();
                return result;
            }
        }
    }

    /** 通过普通APP连接直调唯一回滚函数，验证数据库项目上下文和封闭结果列。 */
    private static RollbackFunctionResult callRollbackFunction(
            Connection connection,
            UUID projectId,
            UUID dashboardId,
            UUID targetVersionId,
            long expectedPublicationRevision,
            UUID actorAccountId) throws SQLException {
        try (PreparedStatement call = connection.prepareStatement("""
                SELECT rollback_status, publication_revision
                  FROM public.dashboard_rollback_version(?, ?, ?, ?, ?, ?)
                """)) {
            call.setQueryTimeout(5);
            call.setObject(1, projectId);
            call.setObject(2, dashboardId);
            call.setObject(3, targetVersionId);
            call.setLong(4, expectedPublicationRevision);
            call.setObject(5, actorAccountId);
            call.setTimestamp(6, Timestamp.from(java.time.Instant.now()));
            try (ResultSet rows = call.executeQuery()) {
                assertThat(rows.next()).isTrue();
                RollbackFunctionResult result = new RollbackFunctionResult(
                        rows.getString("rollback_status"),
                        rows.getObject("publication_revision", Long.class));
                assertThat(rows.next()).isFalse();
                return result;
            }
        }
    }

    /** 通过普通APP连接直调唯一撤回函数，验证数据库项目上下文与三列封闭结果。 */
    private static WithdrawalFunctionResult callWithdrawalFunction(
            Connection connection,
            UUID projectId,
            UUID dashboardId,
            long expectedPublicationRevision,
            UUID actorAccountId) throws SQLException {
        try (PreparedStatement call = connection.prepareStatement("""
                SELECT withdrawal_status, previous_version_id, publication_revision
                  FROM public.dashboard_withdraw_publication(?, ?, ?, ?, ?)
                """)) {
            call.setQueryTimeout(5);
            call.setObject(1, projectId);
            call.setObject(2, dashboardId);
            call.setLong(3, expectedPublicationRevision);
            call.setObject(4, actorAccountId);
            call.setTimestamp(5, Timestamp.from(java.time.Instant.now()));
            try (ResultSet rows = call.executeQuery()) {
                assertThat(rows.next()).isTrue();
                WithdrawalFunctionResult result = new WithdrawalFunctionResult(
                        rows.getString("withdrawal_status"),
                        rows.getObject("previous_version_id", UUID.class),
                        rows.getObject("publication_revision", Long.class));
                assertThat(rows.next()).isFalse();
                return result;
            }
        }
    }

    /** 通过普通APP连接直调唯一软删函数，验证数据库项目上下文与四列封闭结果。 */
    private static SoftDeleteFunctionResult callSoftDeleteFunction(
            Connection connection,
            UUID projectId,
            UUID dashboardId,
            long expectedPublicationRevision,
            UUID actorAccountId) throws SQLException {
        try (PreparedStatement call = connection.prepareStatement("""
                SELECT deletion_status, previous_version_id, publication_revision, deleted_at
                  FROM public.dashboard_soft_delete(?, ?, ?, ?, ?)
                """)) {
            call.setQueryTimeout(5);
            call.setObject(1, projectId);
            call.setObject(2, dashboardId);
            call.setLong(3, expectedPublicationRevision);
            call.setObject(4, actorAccountId);
            call.setTimestamp(5, Timestamp.from(java.time.Instant.now()));
            try (ResultSet rows = call.executeQuery()) {
                assertThat(rows.next()).isTrue();
                Timestamp deletedAt = rows.getTimestamp("deleted_at");
                SoftDeleteFunctionResult result = new SoftDeleteFunctionResult(
                        rows.getString("deletion_status"),
                        rows.getObject("previous_version_id", UUID.class),
                        rows.getObject("publication_revision", Long.class),
                        deletedAt == null ? null : deletedAt.toInstant());
                assertThat(rows.next()).isFalse();
                return result;
            }
        }
    }

    /** 在原生连接会话上绑定RLS租户与项目，供跨线程函数和锁调用复用同一物理连接。 */
    private static void setSessionProjectContext(
            Connection connection, UUID tenantId, UUID projectId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT set_config('app.tenant_id', ?, false),
                       set_config('app.project_id', ?, false)
                """)) {
            statement.setQueryTimeout(5);
            statement.setString(1, tenantId.toString());
            statement.setString(2, projectId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
            }
        }
    }

    /** 取得当前APP物理连接的后端PID，供独立owner连接观察真实锁等待。 */
    private static int backendPid(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_backend_pid()")) {
            statement.setQueryTimeout(5);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getInt(1);
            }
        }
    }

    /** 显式锁住函数将以固定顺序取得的目录与草稿行。 */
    private static void lockPublicationRows(
            Connection connection, UUID projectId, UUID dashboardId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT dashboard.id
                  FROM dash_dashboard dashboard
                  JOIN dash_dashboard_draft draft
                    ON draft.tenant_id = dashboard.tenant_id
                   AND draft.project_id = dashboard.project_id
                   AND draft.dashboard_id = dashboard.id
                 WHERE dashboard.project_id = ? AND dashboard.id = ?
                 FOR UPDATE OF dashboard, draft
                """)) {
            statement.setQueryTimeout(5);
            statement.setObject(1, projectId);
            statement.setObject(2, dashboardId);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
            }
        }
    }

    /** 显式锁住应用发布服务首先取得的目录与草稿行。 */
    private static void lockApplicationPublicationRows(
            Connection connection, UUID projectId, UUID applicationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT application.id
                  FROM app_application application
                  JOIN app_application_draft draft
                    ON draft.tenant_id = application.tenant_id
                   AND draft.project_id = application.project_id
                   AND draft.application_id = application.id
                 WHERE application.project_id = ? AND application.id = ?
                 FOR UPDATE OF application, draft
                """)) {
            statement.setQueryTimeout(5);
            statement.setObject(1, projectId);
            statement.setObject(2, applicationId);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
            }
        }
    }

    /** 轮询真实PostgreSQL会话，确认指定发布连接正在等待数据库锁。 */
    private boolean awaitDatabaseLock(int backendPid) throws SQLException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            try (Connection observer = owner(); PreparedStatement statement = observer.prepareStatement("""
                    SELECT wait_event_type = 'Lock'
                      FROM pg_stat_activity
                     WHERE pid = ?
                    """)) {
                statement.setQueryTimeout(5);
                statement.setInt(1, backendPid);
                try (ResultSet rows = statement.executeQuery()) {
                    if (rows.next() && rows.getBoolean(1)) {
                        return true;
                    }
                }
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待看板发布数据库锁时被中断", exception);
            }
        }
        return false;
    }

    /** 轮询独占测试库锁图，确认指定会话持有的创建advisory锁已有真实等待者。 */
    private boolean awaitAdvisoryLockWait(int holderPid) throws SQLException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            try (Connection observer = owner(); PreparedStatement statement = observer.prepareStatement("""
                    SELECT EXISTS (
                        SELECT 1 FROM pg_locks holder
                         WHERE holder.locktype='advisory' AND holder.granted AND holder.pid=?)
                       AND EXISTS (
                        SELECT 1 FROM pg_locks waiting
                         WHERE waiting.locktype='advisory' AND NOT waiting.granted)
                    """)) {
                statement.setQueryTimeout(5);
                statement.setInt(1, holderPid);
                try (ResultSet rows = statement.executeQuery()) {
                    if (rows.next() && rows.getBoolean(1)) {
                        return true;
                    }
                }
            }
            pauseForLockObservation();
        }
        return false;
    }

    /** 轮询阻塞图，确认至少一个服务连接正在等待指定软删事务释放目录行。 */
    private boolean awaitBlockedBy(int blockerPid) throws SQLException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            try (Connection observer = owner(); PreparedStatement statement = observer.prepareStatement("""
                    SELECT EXISTS (
                        SELECT 1 FROM pg_stat_activity waiting
                         WHERE ? = ANY(pg_blocking_pids(waiting.pid)))
                    """)) {
                statement.setQueryTimeout(5);
                statement.setInt(1, blockerPid);
                try (ResultSet rows = statement.executeQuery()) {
                    if (rows.next() && rows.getBoolean(1)) {
                        return true;
                    }
                }
            }
            pauseForLockObservation();
        }
        return false;
    }

    /** 短暂让出CPU供真实并发连接进入PostgreSQL锁等待，线程中断时立即失败。 */
    private static void pauseForLockObservation() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待数据库锁时线程被中断", exception);
        }
    }

    /** 轮询专库APP会话，证明并发服务回滚真实阻塞在PostgreSQL目录行锁而非仅线程同步。 */
    private boolean awaitApplicationDatabaseLock() throws SQLException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            try (Connection observer = owner(); PreparedStatement statement = observer.prepareStatement("""
                    SELECT EXISTS (
                        SELECT 1
                          FROM pg_stat_activity
                         WHERE datname = current_database()
                           AND usename = 'thingslink_app'
                           AND state = 'active'
                           AND wait_event_type = 'Lock'
                           AND query LIKE '%dash_dashboard%')
                    """)) {
                statement.setQueryTimeout(5);
                try (ResultSet rows = statement.executeQuery()) {
                    if (rows.next() && rows.getBoolean(1)) {
                        return true;
                    }
                }
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待看板并发回滚数据库锁时被中断", exception);
            }
        }
        return false;
    }

    /** 轮询服务连接，证明应用发布真实等待应用目录或草稿行锁。 */
    private boolean awaitApplicationPublicationDatabaseLock() throws SQLException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            try (Connection observer = owner(); PreparedStatement statement = observer.prepareStatement("""
                    SELECT EXISTS (
                        SELECT 1
                          FROM pg_stat_activity
                         WHERE datname = current_database()
                           AND usename = 'thingslink_app'
                           AND state = 'active'
                           AND wait_event_type = 'Lock'
                           AND query LIKE '%app_application%')
                    """)) {
                statement.setQueryTimeout(5);
                try (ResultSet rows = statement.executeQuery()) {
                    if (rows.next() && rows.getBoolean(1)) {
                        return true;
                    }
                }
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待应用发布数据库锁时被中断", exception);
            }
        }
        return false;
    }

    /** 返回PostgreSQL实际用于发布摘要比较的jsonb规范文本。 */
    private String postgreSqlCanonicalText(String schema) {
        return jdbc.queryForObject("SELECT (?::jsonb)::text", String.class, schema);
    }

    /** 断言PostgreSQL约束拒绝的SQLSTATE和精确约束名。 */
    private static void assertPostgresConstraint(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String constraint) {
        Throwable failure = catchThrowable(action);
        assertThat(failure).isInstanceOfSatisfying(PSQLException.class, postgres -> {
            assertThat(postgres.getSQLState()).isEqualTo("23514");
            assertThat(postgres.getServerErrorMessage()).isNotNull();
            assertThat(postgres.getServerErrorMessage().getConstraint()).isEqualTo(constraint);
        });
    }

    /** 断言普通APP直接DML由数据库权限拒绝。 */
    private static void assertPostgresState(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String sqlState) {
        assertThat(catchThrowable(action)).isInstanceOfSatisfying(
                SQLException.class, postgres -> assertThat(postgres.getSQLState()).isEqualTo(sqlState));
    }

    /** 创建两租户、两个项目、四角色和各项目一个不可变模型版本。 */
    private Fixture seed() throws SQLException {
        Fixture fixture = new Fixture(
                Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                Uuid7.generate(), Uuid7.generate());
        try (Connection owner = owner()) {
            owner.setAutoCommit(false);
            execute(owner, "INSERT INTO sys_tenant(id,name) VALUES (?, '看板OWNER租户'), (?, '看板协作者租户')",
                    fixture.ownerTenantId(), fixture.collaboratorTenantId());
            // 多看板管理场景显式绑定合法容量；FREE边界由独立配额验收覆盖。
            execute(owner, "UPDATE sys_tenant SET quota_policy_id=(SELECT id FROM sys_quota_policy WHERE code='PLAN_R1_STANDARD') WHERE id=?", fixture.ownerTenantId());
            execute(owner, "INSERT INTO sys_account(id,email,password_hash,display_name) VALUES "
                            + "(?,?,'{noop}unused','看板OWNER'),(?,?,'{noop}unused','看板ADMIN'),"
                            + "(?,?,'{noop}unused','看板OPERATOR'),(?,?,'{noop}unused','看板VIEWER')",
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
            execute(owner, "INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES "
                            + "(?,?,'看板管理项目','sh-1',?),(?,?,'看板邻居项目','sh-1',?)",
                    fixture.projectId(), fixture.ownerTenantId(), "dashboard_manage_" + compact(fixture.projectId()),
                    fixture.neighborProjectId(), fixture.ownerTenantId(),
                    "dashboard_neighbor_" + compact(fixture.neighborProjectId()));
            execute(owner, "INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES "
                            + "(?,?,?,'OWNER'),(?,?,?,'ADMIN'),(?,?,?,'OPERATOR'),(?,?,?,'VIEWER')",
                    Uuid7.generate(), fixture.projectId(), fixture.ownerId(),
                    Uuid7.generate(), fixture.projectId(), fixture.adminId(),
                    Uuid7.generate(), fixture.projectId(), fixture.operatorId(),
                    Uuid7.generate(), fixture.projectId(), fixture.viewerId());
            UUID localTypeId = insertModel(owner, fixture.ownerTenantId(), fixture.projectId(),
                    fixture.localModelId(), LOCAL_DIGEST, "local");
            UUID foreignTypeId = insertModel(owner, fixture.ownerTenantId(), fixture.neighborProjectId(),
                    fixture.foreignModelId(), FOREIGN_DIGEST, "foreign");
            insertDevice(owner, fixture.ownerTenantId(), fixture.projectId(),
                    localTypeId, fixture.localModelId(), fixture.localDeviceId(), "local_device");
            insertDevice(owner, fixture.ownerTenantId(), fixture.neighborProjectId(),
                    foreignTypeId, fixture.foreignModelId(), fixture.foreignDeviceId(), "foreign_device");
            insertDevice(owner, fixture.ownerTenantId(), fixture.projectId(),
                    localTypeId, null, fixture.unboundDeviceId(), "unbound_device");
            insertDevice(owner, fixture.ownerTenantId(), fixture.projectId(),
                    localTypeId, fixture.localModelId(), fixture.deletedDeviceId(), "deleted_device");
            execute(owner, "UPDATE dev_device SET deleted_at=now() WHERE id=?", fixture.deletedDeviceId());
            owner.commit();
        }
        return fixture;
    }

    /** 写入可由公开Device端口读取的最小不可变模型版本。 */
    private UUID insertModel(
            Connection owner, UUID tenantId, UUID projectId, UUID versionId,
            String digest, String suffix) throws SQLException {
        UUID typeId = Uuid7.generate();
        execute(owner, "INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,device_kind,"
                        + "access_protocol,network_type,status) VALUES (?,?,?,?,'看板模型',"
                        + "'DIRECT','STANDARD','WIFI','PUBLISHED')",
                typeId, tenantId, projectId, "dashboard_" + suffix + '_' + compact(typeId));
        execute(owner, """
                INSERT INTO dev_thing_model_version(
                    id,tenant_id,project_id,device_type_id,version_number,
                    version_major,version_minor,version_patch,change_level,schema_profile,
                    model_snapshot,schema_digest,digest_algorithm)
                VALUES (?,?,?,?,'1.0.0',1,0,0,'MAJOR','TC_PROPERTY_COMPOSITE_V1',
                        '{"properties":{"temperature":{"dataType":"NUMBER",\
                         "minimum":-40,"maximum":125},\
                         "label":{"dataType":"TEXT"},"enabled":{"dataType":"SWITCH"},\
                         "mode":{"dataType":"ENUM"},"payload":{"dataType":"OBJECT"},\
                         "samples":{"dataType":"LIST"}},\
                         "events":{},"commands":{}}'::jsonb,?,
                        'PG_JSONB_TEXT_V1_SHA256')
                """, versionId, tenantId, projectId, typeId, digest);
        return typeId;
    }

    /** 写入绑定精确不可变模型版本且可由公开端口读取的有效设备。 */
    private void insertDevice(
            Connection owner, UUID tenantId, UUID projectId, UUID typeId,
            UUID versionId, UUID deviceId, String deviceKey) throws SQLException {
        execute(owner, """
                INSERT INTO dev_device(
                    id,tenant_id,project_id,device_type_id,thing_model_version_id,
                    device_key,name,status)
                VALUES (?,?,?,?,?,?,'看板资格设备','ONLINE')
                """, deviceId, tenantId, projectId, typeId, versionId, deviceKey);
    }

    /** 将项目独立提交为ARCHIVED，成员及看板事实保持。 */
    private void archive(UUID projectId) throws SQLException {
        try (Connection owner = owner()) {
            execute(owner, "UPDATE sys_project SET status='ARCHIVED' WHERE id=?", projectId);
        }
    }

    /** 返回目录、草稿及草稿模型关系完整稳定快照。 */
    private List<String> facts(UUID projectId) throws SQLException {
        List<String> facts = new ArrayList<>();
        try (Connection owner = owner()) {
            for (String table : List.of(
                    "dash_dashboard", "dash_dashboard_draft", "dash_dashboard_draft_model_ref")) {
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

    /** 读取候选绝不能改变的目录发布状态、版本、版本关系和审计事实。 */
    private List<String> publicationFacts(UUID projectId, UUID dashboardId) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection owner = owner()) {
            try (PreparedStatement query = owner.prepareStatement("""
                    SELECT publication_revision, current_version_id
                    FROM dash_dashboard WHERE project_id=? AND id=?
                    """)) {
                query.setQueryTimeout(5);
                query.setObject(1, projectId);
                query.setObject(2, dashboardId);
                try (ResultSet rows = query.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    values.add("catalog:" + rows.getLong(1) + ':' + rows.getString(2));
                }
            }
            for (String table : List.of("dash_dashboard_version", "dash_dashboard_version_model_ref")) {
                try (PreparedStatement query = owner.prepareStatement(
                        "SELECT count(*) FROM " + table + " WHERE project_id=? AND dashboard_id=?")) {
                    query.setQueryTimeout(5);
                    query.setObject(1, projectId);
                    query.setObject(2, dashboardId);
                    try (ResultSet rows = query.executeQuery()) {
                        assertThat(rows.next()).isTrue();
                        values.add((table.equals("dash_dashboard_version")
                                ? "versions:" : "version_refs:") + rows.getLong(1));
                    }
                }
            }
            try (PreparedStatement query = owner.prepareStatement(
                    "SELECT count(*) FROM sys_audit_log WHERE project_id=? AND target_id=?")) {
                query.setQueryTimeout(5);
                query.setObject(1, projectId);
                query.setObject(2, dashboardId);
                try (ResultSet rows = query.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    values.add("audits:" + rows.getLong(1));
                }
            }
        }
        return List.copyOf(values);
    }

    /** 返回软删必须永久保留的草稿、版本及两类模型关系完整行快照。 */
    private List<String> retainedDashboardFacts(UUID projectId, UUID dashboardId) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection owner = owner()) {
            for (String table : List.of(
                    "dash_dashboard_draft",
                    "dash_dashboard_draft_model_ref",
                    "dash_dashboard_version",
                    "dash_dashboard_version_model_ref")) {
                try (PreparedStatement query = owner.prepareStatement(
                        "SELECT row_to_json(row_data)::text FROM (SELECT * FROM " + table
                                + " WHERE project_id=? AND dashboard_id=? ORDER BY 1) row_data")) {
                    query.setQueryTimeout(5);
                    query.setObject(1, projectId);
                    query.setObject(2, dashboardId);
                    try (ResultSet rows = query.executeQuery()) {
                        while (rows.next()) {
                            values.add(table + ':' + rows.getString(1));
                        }
                    }
                }
            }
        }
        return List.copyOf(values);
    }

    /** 读取owner可见的目录软删状态，避免公开仓储正确隐藏后无法验证物理事实。 */
    private SoftDeletedCatalogFact softDeletedCatalogFact(
            UUID projectId, UUID dashboardId) throws SQLException {
        try (Connection owner = owner(); PreparedStatement query = owner.prepareStatement("""
                SELECT publication_revision, current_version_id, deleted_at
                  FROM dash_dashboard
                 WHERE project_id=? AND id=?
                """)) {
            query.setQueryTimeout(5);
            query.setObject(1, projectId);
            query.setObject(2, dashboardId);
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                Timestamp deletedAt = rows.getTimestamp("deleted_at");
                SoftDeletedCatalogFact result = new SoftDeletedCatalogFact(
                        rows.getLong("publication_revision"),
                        rows.getObject("current_version_id", UUID.class),
                        deletedAt == null ? null : deletedAt.toInstant());
                assertThat(rows.next()).isFalse();
                return result;
            }
        }
    }

    /** 读取一次删除审计详情，验证空旧身份仍保留键且提交时间与目录一致。 */
    private ObjectNode deletionAuditDetails(UUID projectId, UUID dashboardId) throws Exception {
        try (Connection owner = owner(); PreparedStatement query = owner.prepareStatement("""
                SELECT details::text
                  FROM sys_audit_log
                 WHERE project_id=? AND target_id=? AND action='dashboard.deleted'
                 ORDER BY created_at DESC, id DESC
                 LIMIT 1
                """)) {
            query.setQueryTimeout(5);
            query.setObject(1, projectId);
            query.setObject(2, dashboardId);
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                ObjectNode details = (ObjectNode) JSON.readTree(rows.getString(1));
                assertThat(rows.next()).isFalse();
                return details;
            }
        }
    }

    /**
     * 返回指定历史版本及其模型关系的完整数据库行快照。
     *
     * <p>S12-1b2d要求回滚只移动目录指针；逐行快照可同时捕获内容改写、审计列漂移和关系替换，
     * 避免仅比较行数造成误绿。</p>
     *
     * @param projectId 项目ID
     * @param dashboardId 看板ID
     * @param versionIds 需要比较的历史版本ID，返回顺序与输入一致
     * @return 版本及关系的稳定JSON快照
     * @throws SQLException 数据库读取失败
     */
    private List<String> immutableVersionFacts(
            UUID projectId, UUID dashboardId, List<UUID> versionIds) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection owner = owner()) {
            for (UUID versionId : versionIds) {
                try (PreparedStatement query = owner.prepareStatement("""
                        SELECT row_to_json(version_row)::text
                          FROM (SELECT *
                                  FROM dash_dashboard_version
                                 WHERE project_id=? AND dashboard_id=? AND id=?) version_row
                        """)) {
                    query.setQueryTimeout(5);
                    query.setObject(1, projectId);
                    query.setObject(2, dashboardId);
                    query.setObject(3, versionId);
                    try (ResultSet rows = query.executeQuery()) {
                        assertThat(rows.next()).isTrue();
                        values.add("version:" + rows.getString(1));
                        assertThat(rows.next()).isFalse();
                    }
                }
                try (PreparedStatement query = owner.prepareStatement("""
                        SELECT row_to_json(reference_row)::text
                          FROM (SELECT *
                                  FROM dash_dashboard_version_model_ref
                                 WHERE project_id=?
                                   AND dashboard_id=?
                                   AND dashboard_version_id=?
                                 ORDER BY position) reference_row
                        """)) {
                    query.setQueryTimeout(5);
                    query.setObject(1, projectId);
                    query.setObject(2, dashboardId);
                    query.setObject(3, versionId);
                    try (ResultSet rows = query.executeQuery()) {
                        while (rows.next()) {
                            values.add("reference:" + rows.getString(1));
                        }
                    }
                }
            }
        }
        return List.copyOf(values);
    }

    /** 读取应用版本、精确关系、发布指针/revision及应用审计，证明候选准备没有任何发布写。 */
    private List<String> applicationPublicationFacts(UUID projectId, UUID applicationId) throws SQLException {
        List<String> facts = new ArrayList<>();
        try (Connection owner = owner(); PreparedStatement query = owner.prepareStatement("""
                SELECT jsonb_build_object(
                    'publicationRevision', publication_revision,
                    'currentVersionId', current_version_id,
                    'versionCount', (SELECT count(*) FROM app_application_version
                                      WHERE project_id=? AND application_id=?),
                    'referenceCount', (SELECT count(*) FROM app_application_version_dashboard_ref
                                        WHERE project_id=? AND application_id=?),
                    'auditActions', (SELECT coalesce(jsonb_agg(action ORDER BY created_at,id), '[]'::jsonb)
                                       FROM sys_audit_log
                                      WHERE project_id=? AND target_type='application' AND target_id=?))::text
                  FROM app_application
                 WHERE project_id=? AND id=?
                """)) {
            query.setQueryTimeout(5);
            for (int index = 0; index < 4; index++) {
                query.setObject(index * 2 + 1, projectId);
                query.setObject(index * 2 + 2, applicationId);
            }
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                facts.add(rows.getString(1));
                assertThat(rows.next()).isFalse();
            }
        }
        return List.copyOf(facts);
    }

    /** 精确统计指定应用审计动作，防止文本包含断言漏掉重复审计。 */
    private long applicationAuditCount(UUID projectId, UUID applicationId, String action) throws SQLException {
        try (Connection owner = owner(); PreparedStatement query = owner.prepareStatement("""
                SELECT count(*)
                  FROM sys_audit_log
                 WHERE project_id=? AND target_type='application' AND target_id=? AND action=?
                """)) {
            query.setQueryTimeout(5);
            query.setObject(1, projectId);
            query.setObject(2, applicationId);
            query.setString(3, action);
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        }
    }

    /** 按应用版本号读取精确看板关系，证明导航position与历史版本ID被独立持久化。 */
    private List<String> applicationVersionReferenceFacts(
            UUID projectId, UUID applicationId, List<UUID> versionIds) throws SQLException {
        List<String> facts = new ArrayList<>();
        try (Connection owner = owner(); PreparedStatement query = owner.prepareStatement("""
                SELECT reference.application_version_id, reference.position,
                       reference.dashboard_id, reference.dashboard_version_id
                  FROM app_application_version_dashboard_ref reference
                  JOIN app_application_version version
                    ON version.tenant_id = reference.tenant_id
                   AND version.project_id = reference.project_id
                   AND version.application_id = reference.application_id
                   AND version.id = reference.application_version_id
                 WHERE reference.project_id=? AND reference.application_id=?
                   AND reference.application_version_id = ANY(?::uuid[])
                 ORDER BY version.version_number, reference.position
                """)) {
            query.setQueryTimeout(5);
            query.setObject(1, projectId);
            query.setObject(2, applicationId);
            query.setArray(3, owner.createArrayOf("uuid", versionIds.toArray()));
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    facts.add(rows.getObject(1, UUID.class) + ":" + rows.getInt(2) + ":"
                            + rows.getObject(3, UUID.class) + ":" + rows.getObject(4, UUID.class));
                }
            }
        }
        return List.copyOf(facts);
    }

    /** 逐行冻结应用版本及其精确看板关系，证明回滚没有复制或改写任何历史事实。 */
    private List<String> immutableApplicationVersionFacts(
            UUID projectId, UUID applicationId, List<UUID> versionIds) throws SQLException {
        List<String> facts = new ArrayList<>();
        try (Connection owner = owner()) {
            for (UUID versionId : versionIds) {
                try (PreparedStatement versionQuery = owner.prepareStatement("""
                        SELECT row_to_json(version_row)::text
                          FROM (SELECT *
                                  FROM app_application_version
                                 WHERE project_id=? AND application_id=? AND id=?) version_row
                        """)) {
                    versionQuery.setQueryTimeout(5);
                    versionQuery.setObject(1, projectId);
                    versionQuery.setObject(2, applicationId);
                    versionQuery.setObject(3, versionId);
                    try (ResultSet rows = versionQuery.executeQuery()) {
                        assertThat(rows.next()).isTrue();
                        facts.add("version:" + rows.getString(1));
                        assertThat(rows.next()).isFalse();
                    }
                }
                try (PreparedStatement relationQuery = owner.prepareStatement("""
                        SELECT row_to_json(reference_row)::text
                          FROM (SELECT *
                                  FROM app_application_version_dashboard_ref
                                 WHERE project_id=? AND application_id=?
                                   AND application_version_id=?
                                 ORDER BY position) reference_row
                        """)) {
                    relationQuery.setQueryTimeout(5);
                    relationQuery.setObject(1, projectId);
                    relationQuery.setObject(2, applicationId);
                    relationQuery.setObject(3, versionId);
                    try (ResultSet rows = relationQuery.executeQuery()) {
                        while (rows.next()) {
                            facts.add("reference:" + rows.getString(1));
                        }
                    }
                }
            }
        }
        return List.copyOf(facts);
    }

    /** 冻结应用历史版本、精确关系及独立草稿整行，证明撤回没有触碰内容轴。 */
    private List<String> immutableApplicationContentFacts(
            UUID projectId, UUID applicationId, List<UUID> versionIds) throws SQLException {
        List<String> facts = new ArrayList<>(
                immutableApplicationVersionFacts(projectId, applicationId, versionIds));
        try (Connection owner = owner(); PreparedStatement query = owner.prepareStatement("""
                SELECT row_to_json(draft_row)::text
                  FROM (SELECT *
                          FROM app_application_draft
                         WHERE project_id=? AND application_id=?) draft_row
                """)) {
            query.setQueryTimeout(5);
            query.setObject(1, projectId);
            query.setObject(2, applicationId);
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                facts.add("draft:" + rows.getString(1));
                assertThat(rows.next()).isFalse();
            }
        }
        return List.copyOf(facts);
    }

    /** 读取应用撤回审计的操作者和最小详情，证明ADMIN归属与成功次数均精确。 */
    private List<String> applicationWithdrawalAuditFacts(
            UUID projectId, UUID applicationId) throws SQLException {
        List<String> facts = new ArrayList<>();
        try (Connection owner = owner(); PreparedStatement query = owner.prepareStatement("""
                SELECT actor_account_id,
                       details->>'previousVersionId',
                       details->>'previousVersionNumber',
                       details->>'publicationRevision'
                  FROM sys_audit_log
                 WHERE project_id=? AND target_type='application' AND target_id=?
                   AND action='application.withdrawn'
                 ORDER BY created_at, id
                """)) {
            query.setQueryTimeout(5);
            query.setObject(1, projectId);
            query.setObject(2, applicationId);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    facts.add(rows.getObject(1, UUID.class) + ":" + rows.getString(2) + ":"
                            + rows.getString(3) + ":" + rows.getString(4));
                }
            }
        }
        return List.copyOf(facts);
    }

    /** owner直读应用软删后的发布轴、空指针和删除时刻，绕过正常仓储的永久不可见过滤。 */
    private ApplicationSoftDeletedCatalogFact softDeletedApplicationCatalogFact(
            UUID projectId, UUID applicationId) throws SQLException {
        try (Connection owner = owner(); PreparedStatement query = owner.prepareStatement("""
                SELECT publication_revision, current_version_id, deleted_at
                  FROM app_application
                 WHERE project_id=? AND id=?
                """)) {
            query.setQueryTimeout(5);
            query.setObject(1, projectId);
            query.setObject(2, applicationId);
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                Timestamp deletedAt = rows.getTimestamp("deleted_at");
                ApplicationSoftDeletedCatalogFact fact = new ApplicationSoftDeletedCatalogFact(
                        rows.getLong("publication_revision"),
                        rows.getObject("current_version_id", UUID.class),
                        deletedAt == null ? null : deletedAt.toInstant());
                assertThat(rows.next()).isFalse();
                return fact;
            }
        }
    }

    /** 精确读取应用唯一删除审计详情；重复审计或目标漂移必须直接使验收失败。 */
    private ObjectNode applicationDeletionAuditDetails(
            UUID projectId, UUID applicationId) throws Exception {
        try (Connection owner = owner(); PreparedStatement query = owner.prepareStatement("""
                SELECT details::text
                  FROM sys_audit_log
                 WHERE project_id=? AND target_type='application' AND target_id=?
                   AND action='application.deleted'
                """)) {
            query.setQueryTimeout(5);
            query.setObject(1, projectId);
            query.setObject(2, applicationId);
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                ObjectNode details = (ObjectNode) JSON.readTree(rows.getString(1));
                assertThat(rows.next()).isFalse();
                return details;
            }
        }
    }

    /** 读取目录当前版本ID，用于把并发最终指针绑定唯一成功版本。 */
    private UUID currentVersionId(UUID projectId, UUID dashboardId) throws SQLException {
        try (Connection owner = owner(); PreparedStatement query = owner.prepareStatement(
                "SELECT current_version_id FROM dash_dashboard WHERE project_id=? AND id=?")) {
            query.setQueryTimeout(5);
            query.setObject(1, projectId);
            query.setObject(2, dashboardId);
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getObject(1, UUID.class);
            }
        }
    }

    /** 查询三张看板管理事实表的owner tenant，跨租户协作者不能改写归属。 */
    private List<UUID> persistedTenants(UUID projectId) throws SQLException {
        List<UUID> tenants = new ArrayList<>();
        try (Connection owner = owner(); PreparedStatement query = owner.prepareStatement("""
                SELECT tenant_id FROM dash_dashboard WHERE project_id=?
                UNION ALL SELECT tenant_id FROM dash_dashboard_draft WHERE project_id=?
                UNION ALL SELECT tenant_id FROM dash_dashboard_draft_model_ref WHERE project_id=?
                """)) {
            query.setQueryTimeout(5);
            query.setObject(1, projectId);
            query.setObject(2, projectId);
            query.setObject(3, projectId);
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

    /** 查询按提交时刻排序的看板审计动作。 */
    private List<String> auditActions(UUID projectId) throws SQLException {
        return auditStrings(projectId, "action");
    }

    /** 查询按提交时刻排序的看板审计行为人。 */
    private List<UUID> auditActors(UUID projectId) throws SQLException {
        List<UUID> actors = new ArrayList<>();
        try (Connection owner = owner(); PreparedStatement query = owner.prepareStatement(
                "SELECT actor_account_id FROM sys_audit_log WHERE project_id=? ORDER BY created_at,id")) {
            query.setQueryTimeout(5);
            query.setObject(1, projectId);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    actors.add(rows.getObject(1, UUID.class));
                }
            }
        }
        return actors;
    }

    /** 从固定白名单审计文本列读取值。 */
    private List<String> auditStrings(UUID projectId, String column) throws SQLException {
        assertThat(column).isEqualTo("action");
        List<String> values = new ArrayList<>();
        try (Connection owner = owner(); PreparedStatement query = owner.prepareStatement(
                "SELECT " + column + " FROM sys_audit_log WHERE project_id=? ORDER BY created_at,id")) {
            query.setQueryTimeout(5);
            query.setObject(1, projectId);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    values.add(rows.getString(1));
                }
            }
        }
        return values;
    }

    /** 构造不含模型引用的最小合法看板Schema。 */
    private static byte[] schema(String title) {
        return schema(title, null, null);
    }

    /** 构造含一个精确模型版本、摘要算法、摘要和Profile的完整合法看板Schema。 */
    private static byte[] schema(String title, UUID versionId, String digest) {
        String models = versionId == null ? "[]" : ("""
                [{"key":"pump_model","versionId":"%s",\
                "digestAlgorithm":"PG_JSONB_TEXT_V1_SHA256","digest":"%s",\
                "profile":"TC_PROPERTY_COMPOSITE_V1"}]
                """).formatted(versionId, digest).strip();
        return ("""
                {"schemaVersion":"tc.dashboard/v1","presentation":{"mode":"RESPONSIVE_GRID"},\
                "models":%s,"variables":[],\
                "pages":[{"id":"overview","title":"%s","components":[]}]}
                """).formatted(models, title).getBytes(StandardCharsets.UTF_8);
    }

    /** 建立引用一个精确历史看板版本的完整应用草稿。 */
    private static byte[] applicationDraft(UUID dashboardId, UUID dashboardVersionId) {
        return applicationDraft("精确历史应用", dashboardId, dashboardVersionId);
    }

    /** 建立指定展示名且引用一个精确历史看板版本的完整应用草稿。 */
    private static byte[] applicationDraft(
            String displayName, UUID dashboardId, UUID dashboardVersionId) {
        return ("""
                {"formatVersion":"tc.application/v1","displayName":"%s",\
                "hostCompatibility":{"minInclusive":"1.0.0","maxExclusive":"2.0.0"},\
                "dashboardRefs":[{"dashboardId":"%s","dashboardVersionId":"%s","title":"历史入口"}],\
                "entryDashboardId":"%s"}
                """).formatted(displayName, dashboardId, dashboardVersionId, dashboardId)
                .getBytes(StandardCharsets.UTF_8);
    }

    /** 候选资格失败必须只返回内部安全原因，不写入任何应用发布事实。 */
    private void assertApplicationCandidateFailure(
            Fixture fixture,
            ApplicationCatalogEntry application,
            ApplicationPublicationQualificationException.Reason expectedReason) throws Exception {
        List<String> before = applicationPublicationFacts(fixture.projectId(), application.id());
        Throwable failure = catchThrowable(() -> as(
                fixture.ownerTenantId(), fixture.projectId(), fixture.ownerId(),
                () -> applicationPublicationCandidates.prepare(
                        applications.getDraft(fixture.projectId(), application.id()))));
        assertThat(failure).isInstanceOfSatisfying(
                ApplicationPublicationQualificationException.class,
                exception -> assertThat(exception.reason()).isEqualTo(expectedReason));
        assertThat(applicationPublicationFacts(fixture.projectId(), application.id())).isEqualTo(before);
    }

    /** 构造语义相同但可切换根键顺序和显式数值表示的GAUGE Schema。 */
    private static byte[] publicationSchema(
            UUID modelVersionId, boolean reverseRootOrder, String maximumLiteral) {
        String model = ("""
                [{"key":"pump_model","versionId":"%s",\
                "digestAlgorithm":"PG_JSONB_TEXT_V1_SHA256","digest":"%s",\
                "profile":"TC_PROPERTY_COMPOSITE_V1"}]
                """).formatted(modelVersionId, LOCAL_DIGEST).strip();
        String variables = """
                [{"key":"single","type":"DEVICE_SINGLE","title":"设备",\
                "modelKey":"pump_model"}]
                """.strip();
        String pages = ("""
                [{"id":"main","title":"主页","components":[{\
                "id":"gauge","kind":"GAUGE","componentVersion":"1.0.0",\
                "layout":{"x":0,"y":0,"w":1,"h":1},\
                "props":{"scaleMode":"EXPLICIT","min":0,"max":%s},\
                "bindings":{"value":{"source":"CURRENT_VALUE",\
                "device":{"variableKey":"single"},"propertyKey":"temperature"}}}]}]
                """).formatted(maximumLiteral).strip();
        String source = reverseRootOrder
                ? ("""
                  {"pages":%s,"variables":%s,"models":%s,\
                  "presentation":{"mode":"RESPONSIVE_GRID"},"schemaVersion":"tc.dashboard/v1"}
                  """).formatted(pages, variables, model)
                : ("""
                  {"schemaVersion":"tc.dashboard/v1","presentation":{"mode":"RESPONSIVE_GRID"},\
                  "models":%s,"variables":%s,"pages":%s}
                  """).formatted(model, variables, pages);
        return source.getBytes(StandardCharsets.UTF_8);
    }

    /** 计算候选返回PG规范字节的独立JDK SHA-256。 */
    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK缺少SHA-256", exception);
        }
    }

    /** 独立重建生产创建键摘要，使原生连接与首次服务请求竞争同一锁身份。 */
    private static String dashboardCreationKeyDigest(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("tc.dashboard-create-key/v1\0".getBytes(StandardCharsets.US_ASCII));
            byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(keyBytes.length).array());
            digest.update(keyBytes);
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK缺少SHA-256", exception);
        }
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

    /** 业务异常公开码；基础设施异常或无异常返回空。 */
    private static Integer errorCode(Throwable failure) {
        return failure instanceof BusinessException business ? business.errorCode().code() : null;
    }

    /** UUID去除连字符后用于合法数据库业务键。 */
    private static String compact(UUID value) {
        return value.toString().replace("-", "");
    }

    /** 以owner连接读取单个计数，参数只用于本类固定SQL中的UUID身份。 */
    private static long queryLong(Connection connection, String sql, UUID value) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, value);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("计数查询未返回结果");
                }
                return rows.getLong(1);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("计数查询失败", exception);
        }
    }

    /** 调用固定DASHBOARD项目清理函数并映射唯一批次结果。 */
    private static CleanupFunctionResult callDashboardCleanup(
            Connection connection, Fixture fixture, UUID cleanupToken) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT deleted_rows,complete,blocked_reason
                  FROM dashboard_project_cleanup_batch(?,?,1,?)
                """)) {
            statement.setQueryTimeout(5);
            statement.setObject(1, fixture.ownerTenantId());
            statement.setObject(2, fixture.projectId());
            statement.setObject(3, cleanupToken);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                CleanupFunctionResult result = new CleanupFunctionResult(
                        rows.getInt("deleted_rows"), rows.getBoolean("complete"),
                        rows.getString("blocked_reason"));
                assertThat(rows.next()).isFalse();
                return result;
            }
        }
    }

    /** owner只准备夹具、生命周期状态并观察已提交事实。 */
    private Connection owner() throws SQLException {
        return DriverManager.getConnection(
                DATABASE_URL, DASHBOARD_POSTGRES.getUsername(), DASHBOARD_POSTGRES.getPassword());
    }

    /** 普通运行身份原生连接，用于直接验证最终ACL、SECURITY DEFINER与数据库锁。 */
    private Connection application() throws SQLException {
        return DriverManager.getConnection(DATABASE_URL, APP_ROLE, APP_ROLE_PASSWORD);
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
        DASHBOARD_POSTGRES.start();
        return DASHBOARD_POSTGRES.getJdbcUrl();
    }

    /** 独占容器由OwnedTestContainers在本类上下文物理关闭后回收；测试只清理线程范围和可恢复spy。 */
    @AfterEach
    void clearContext() {
        doCallRealMethod().when(audits).record(any(AuditLogEntry.class));
        TenantContext.clear();
        RlsScopeContext.clear();
    }

    /** 专库覆盖数据源并关闭无关后台入口，保留真实看板、Device端口、项目许可与审计。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class IsolatedDatabaseConfiguration {
        /** 数据源和Flyway统一使用专库。 */
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

    /** 可读取但不可管理看板的项目角色。 */
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

    /** 两租户、两项目、四角色和两个项目模型版本夹具。 */
    private record Fixture(
            UUID ownerTenantId,
            UUID collaboratorTenantId,
            UUID projectId,
            UUID neighborProjectId,
            UUID ownerId,
            UUID adminId,
            UUID operatorId,
            UUID viewerId,
            UUID localModelId,
            UUID foreignModelId,
            UUID localDeviceId,
            UUID foreignDeviceId,
            UUID unboundDeviceId,
            UUID deletedDeviceId) {
    }

    /** @param deletedRows 本批删除数 @param complete DASHBOARD事实是否清空 @param blockedReason 阻断原因 */
    private record CleanupFunctionResult(int deletedRows, boolean complete, String blockedReason) {
    }

    /** 一份未发布目录、真实项目模型和同revision PG候选。 */
    private record PublicationFixture(
            Fixture fixture,
            DashboardCatalogEntry dashboard,
            DashboardPublicationCandidate candidate) {
    }

    /** 受控数据库发布函数返回的唯一封闭结果行。 */
    private record PublicationFunctionResult(
            String status,
            Long versionNumber,
            Long publicationRevision) {
    }

    /** 应用受控数据库发布函数返回的唯一封闭结果行。 */
    private record ApplicationPublicationFunctionResult(
            String status,
            Long versionNumber,
            Long publicationRevision) {
    }

    /** 受控数据库回滚函数返回的唯一封闭结果行。 */
    private record RollbackFunctionResult(String status, Long publicationRevision) {
    }

    /** 受控数据库撤回函数返回的唯一封闭结果行。 */
    private record WithdrawalFunctionResult(
            String status, UUID previousVersionId, Long publicationRevision) {
    }

    /** 受控数据库软删函数返回的唯一封闭结果行。 */
    private record SoftDeleteFunctionResult(
            String status,
            UUID previousVersionId,
            Long publicationRevision,
            java.time.Instant deletedAt) {
    }

    /** owner直读的目录删除轴事实。 */
    private record SoftDeletedCatalogFact(
            long publicationRevision,
            UUID currentVersionId,
            java.time.Instant deletedAt) {
    }

    /** 并发保存的成功草稿或稳定业务错误码。 */
    private record SaveOutcome(DashboardDraft draft, Integer errorCode) {
        /** @return 携带成功草稿的结果 */
        static SaveOutcome saved(DashboardDraft draft) {
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
    }

    /** @param version 成功发布版本 @param errorCode 失败时稳定业务码 */
    private record PublishOutcome(DashboardVersion version, Integer errorCode) {
        /** @return 是否取得唯一成功版本 */
        boolean published() {
            return version != null;
        }

        /** @return 成功发布结果 */
        static PublishOutcome published(DashboardVersion version) {
            return new PublishOutcome(Objects.requireNonNull(version, "version"), null);
        }

        /** @return 失败发布结果 */
        static PublishOutcome failed(Integer errorCode) {
            return new PublishOutcome(null, errorCode);
        }
    }

    /** @param version 成功发布的应用版本 @param errorCode 失败时稳定业务码 */
    private record ApplicationPublishOutcome(ApplicationVersion version, Integer errorCode) {
        /** @return 是否取得唯一成功版本 */
        boolean published() {
            return version != null;
        }

        /** @return 成功应用发布结果 */
        static ApplicationPublishOutcome published(ApplicationVersion version) {
            return new ApplicationPublishOutcome(Objects.requireNonNull(version, "version"), null);
        }

        /** @return 失败应用发布结果 */
        static ApplicationPublishOutcome failed(Integer errorCode) {
            return new ApplicationPublishOutcome(null, errorCode);
        }
    }

    /** @param version 成功回滚的应用目标版本 @param errorCode 失败时稳定业务码 */
    private record ApplicationRollbackOutcome(ApplicationVersion version, Integer errorCode) {
        /** @return 携带成功回滚目标的结果 */
        static ApplicationRollbackOutcome rolledBack(ApplicationVersion version) {
            return new ApplicationRollbackOutcome(Objects.requireNonNull(version, "version"), null);
        }

        /** @return 携带稳定业务错误的失败结果 */
        static ApplicationRollbackOutcome failed(Integer errorCode) {
            return new ApplicationRollbackOutcome(null, errorCode);
        }
    }

    /** @param version 成功撤回前的应用版本 @param errorCode 失败时稳定业务码 */
    private record ApplicationWithdrawalOutcome(ApplicationVersion version, Integer errorCode) {
        /** @return 携带成功撤回前应用版本的结果 */
        static ApplicationWithdrawalOutcome withdrawn(ApplicationVersion version) {
            return new ApplicationWithdrawalOutcome(Objects.requireNonNull(version, "version"), null);
        }

        /** @return 携带稳定业务错误的失败结果 */
        static ApplicationWithdrawalOutcome failed(Integer errorCode) {
            return new ApplicationWithdrawalOutcome(null, errorCode);
        }
    }

    /** @param deleted 是否成功提交应用软删 @param errorCode 失败时稳定业务码 */
    private record ApplicationSoftDeleteOutcome(boolean deleted, Integer errorCode) {
        /** @return 成功提交应用软删的结果 */
        static ApplicationSoftDeleteOutcome success() {
            return new ApplicationSoftDeleteOutcome(true, null);
        }

        /** @return 携带稳定业务错误的失败结果 */
        static ApplicationSoftDeleteOutcome failed(Integer errorCode) {
            return new ApplicationSoftDeleteOutcome(false, errorCode);
        }
    }

    /** 应用A/B历史及其唯一可运行看板的真实集成夹具。 */
    private record ApplicationRollbackFixture(
            DashboardCatalogEntry dashboard,
            DashboardVersion dashboardVersion,
            ApplicationCatalogEntry application,
            ApplicationVersion versionA,
            ApplicationVersion versionB) {
    }

    /** owner绕过正常不可见过滤读取的应用软删目录事实。 */
    private record ApplicationSoftDeletedCatalogFact(
            long publicationRevision,
            UUID currentVersionId,
            java.time.Instant deletedAt) {
    }

    /** @param version 成功回滚目标版本 @param errorCode 失败时稳定业务码 */
    private record RollbackOutcome(DashboardVersion version, Integer errorCode) {
        /** @return 携带成功回滚目标的结果 */
        static RollbackOutcome rolledBack(DashboardVersion version) {
            return new RollbackOutcome(Objects.requireNonNull(version, "version"), null);
        }

        /** @return 携带稳定业务错误的结果 */
        static RollbackOutcome failed(Integer errorCode) {
            return new RollbackOutcome(null, errorCode);
        }
    }

    /** @param version 成功撤回前的版本 @param errorCode 失败时稳定业务码 */
    private record WithdrawalOutcome(DashboardVersion version, Integer errorCode) {
        /** @return 携带成功撤回前版本的结果 */
        static WithdrawalOutcome withdrawn(DashboardVersion version) {
            return new WithdrawalOutcome(Objects.requireNonNull(version, "version"), null);
        }

        /** @return 携带稳定业务错误的结果 */
        static WithdrawalOutcome failed(Integer errorCode) {
            return new WithdrawalOutcome(null, errorCode);
        }
    }

    /** @param deleted 是否成功提交软删 @param errorCode 失败时稳定业务码 */
    private record SoftDeleteOutcome(boolean deleted, Integer errorCode) {
        /** @return 软删成功结果 */
        static SoftDeleteOutcome success() {
            return new SoftDeleteOutcome(true, null);
        }

        /** @return 携带稳定业务错误的失败结果 */
        static SoftDeleteOutcome failed(Integer errorCode) {
            return new SoftDeleteOutcome(false, errorCode);
        }
    }

    /** 专用于证明审计INSERT之后异常仍由看板服务事务统一回滚。 */
    private static final class AuditFailureAfterInsertException extends RuntimeException {
        /** 固定消息表明异常来自验收故障注入。 */
        private AuditFailureAfterInsertException() {
            super("看板草稿审计已写入后注入失败");
        }
    }
}
