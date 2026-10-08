package com.things.link.bootstrap.export;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.bootstrap.fixture.ProjectExportMinioFixture;

import com.things.link.device.application.DeviceExportSource;
import com.things.link.export.application.ProjectExportCleanupWorker;
import com.things.link.export.application.ProjectExportService;
import com.things.link.export.application.ProjectExportWorker;
import com.things.link.export.domain.ProjectExportClaim;
import com.things.link.export.domain.ProjectExportJob;
import com.things.link.export.domain.ProjectExportJobRepository;
import com.things.link.export.domain.ProjectExportStatus;
import com.things.link.iam.application.AuthenticatedPrincipal;
import com.things.link.iam.infrastructure.security.JwtTokenIssuer;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.storage.MinioPrivateObjectStorage;
import com.things.link.support.storage.ObjectStorageException;
import com.things.link.support.storage.PrivateObjectStorage;
import com.things.link.support.audit.AuditLogService;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * ADR0075 的5g1永久验收：真实TimescaleDB快照、项目代次、异步围栏和真实MinIO共同参与。
 *
 * <p>本类不经下载签发接口；5g2负责短链、失权和24小时保留合同。</p>
 */
@Import({ProjectExportLifecycleTests.IsolatedDatabaseConfiguration.class,
        ProjectExportLifecycleTests.RealObjectStorageConfiguration.class})
@AutoConfigureMockMvc
@OwnedTestContainers({"EXPORT_POSTGRES"})
class ProjectExportLifecycleTests extends AbstractIntegrationTest {

    /** 独占数据库隔离全局导出领取与故障回拨。 */
    private static final String DATABASE_NAME = "project_export_lifecycle_"
            + UUID.randomUUID().toString().replace("-", "");

    /** 与全量套件一致的真实TimescaleDB/PostgreSQL。 */
    private static final PostgreSQLContainer<?> EXPORT_POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName(DATABASE_NAME)
            .withUsername(POSTGRES.getUsername())
            .withPassword(POSTGRES.getPassword());

    /** Flyway、APP和owner观察固定到同一专库。 */
    private static final String DATABASE_URL = startDatabase();

    /** 生成器独占临时根，逐例断言无残留。 */
    private static final Path TEMPORARY_ROOT = Path.of(System.getProperty("java.io.tmpdir"),
            "things-link-export-lifecycle-" + UUID.randomUUID());

    /** 基类配额启动器指向共享库，本类以mock阻止跨库写入。 */
    @MockitoBean(enforceOverride = true, name = "relaxRestQuota")
    private ApplicationRunner unusedRestQuotaRelaxation;

    /** 真实请求事务与资格复核。 */
    @Autowired
    private ProjectExportService exports;

    /** 真实SECURITY DEFINER队列和token CAS。 */
    @Autowired
    private ProjectExportJobRepository jobs;

    /** 真实生成、上传和完成编排。 */
    @Autowired
    private ProjectExportWorker worker;

    /** 真实孤儿清理编排。 */
    @Autowired
    private ProjectExportCleanupWorker cleanupWorker;

    /** 测试适配器仅在显式要求时失败一次，其余操作委托真实MinIO。 */
    @Autowired
    private FailOnceObjectStorage objectStorage;

    /** 在设备文件生成后建立确定快照并暂停，允许独立事务写入后续表。 */
    @MockitoSpyBean
    private DeviceExportSource deviceSource;

    /** 验证APP角色和专库身份。 */
    @Autowired
    private JdbcTemplate jdbc;

    /** ZIP及manifest语义解析。 */
    @Autowired
    private ObjectMapper json;

    /** 真实过滤器、JWT与Controller入口。 */
    @Autowired
    private MockMvc mockMvc;

    /** 测试只自行签发平台真实JWT，不绕开验签与项目代次过滤器。 */
    @Autowired
    private JwtTokenIssuer tokens;

    /** 在原审计INSERT之后注入故障，验证请求和完成事务的原子边界。 */
    @MockitoSpyBean
    private AuditLogService audits;

    /** 每例清空全局队列并确认测试基础设施没有退化。 */
    @BeforeEach
    void prepare() throws Exception {
        assertThat(mockingDetails(unusedRestQuotaRelaxation).isMock()).isTrue();
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo(DATABASE_NAME);
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        executeOwner("DELETE FROM sys_project_export_upload_cleanup");
        executeOwner("DELETE FROM sys_project_export_job");
        objectStorage.reset();
        deleteTree(TEMPORARY_ROOT);
    }

    /** 线程范围、测试对象与临时目录只清理本例独占身份。 */
    @AfterEach
    void cleanup() throws Exception {
        TenantContext.clear();
        for (String prefix : new ArrayList<>(OBJECT_PREFIXES)) {
            ProjectExportMinioFixture.deletePrefix(prefix);
            OBJECT_PREFIXES.remove(prefix);
        }
        deleteTree(TEMPORARY_ROOT);
    }

    /** 只有窗口内DELETING当前OWNER建立任务；重复请求吸收，其他事实统一50001且零新增。 */
    @Test
    void requestRequiresRetainedOwnerDeletingWindowAndIsIdempotentPerGeneration() throws Exception {
        Fixture valid = fixture("DELETING", Instant.now().minus(Duration.ofDays(1)), 4, true);
        ProjectExportJob first = request(valid);
        configureStorageQuota(valid);
        ProjectExportJob repeated = request(valid);
        assertThat(repeated.id()).isEqualTo(first.id());
        assertThat(number("SELECT count(*) FROM sys_project_export_job WHERE project_id=?", valid.projectId()))
                .isEqualTo(1);
        assertThat(number("SELECT count(*) FROM sys_audit_log WHERE project_id=? AND action='project.export.requested'",
                valid.projectId())).isEqualTo(1);

        Fixture active = fixture("ACTIVE", null, 0, true);
        Fixture expired = fixture("DELETING", Instant.now().minus(Duration.ofDays(31)), 1, true);
        Fixture nonOwner = fixture("DELETING", Instant.now().minus(Duration.ofDays(1)), 1, false);
        Fixture quotaWithoutExisting = fixture("DELETING", Instant.now().minus(Duration.ofDays(1)), 2, true);
        configureStorageQuota(quotaWithoutExisting);
        long jobsBefore = number("SELECT count(*) FROM sys_project_export_job");
        long auditsBefore = number("SELECT count(*) FROM sys_audit_log WHERE action='project.export.requested'");
        for (Fixture rejected : List.of(active, expired, nonOwner)) {
            Throwable failure = catchThrowable(() -> request(rejected));
            assertThat(failure).isExactlyInstanceOf(BusinessException.class);
            assertThat(((BusinessException) failure).errorCode().code()).isEqualTo(50001);
        }
        Throwable unavailable = catchThrowable(() -> request(quotaWithoutExisting));
        assertThat(unavailable).isExactlyInstanceOf(BusinessException.class);
        assertThat(((BusinessException) unavailable).errorCode().code()).isEqualTo(50019);
        assertThat(number("SELECT count(*) FROM sys_project_export_job")).isEqualTo(jobsBefore);
        assertThat(number("SELECT count(*) FROM sys_audit_log WHERE action='project.export.requested'"))
                .isEqualTo(auditsBefore);
    }

    /** 同账号项目组合一分钟最多五次；换项目使用独立计数且不受前一组合耗尽影响。 */
    @Test
    void requestRateLimitIsIsolatedByAccountAndProject() throws Exception {
        Fixture firstProject = fixture("DELETING", Instant.now().minus(Duration.ofDays(1)), 1, true);
        ProjectExportJob first = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            ProjectExportJob current = request(firstProject);
            if (first == null) first = current;
            assertThat(current.id()).isEqualTo(first.id());
        }
        Throwable limited = catchThrowable(() -> request(firstProject));
        assertThat(limited).isExactlyInstanceOf(BusinessException.class);
        assertThat(((BusinessException) limited).errorCode().code()).isEqualTo(10029);
        assertThat(number("SELECT count(*) FROM sys_project_export_job WHERE project_id=?", firstProject.projectId()))
                .isEqualTo(1);

        Fixture secondProject = projectFor(firstProject);
        assertThat(request(secondProject).projectId()).isEqualTo(secondProject.projectId());
    }

    /** 单RR快照冻结七类数据，随后提交的设备、Timescale点和告警事件不得混入同一ZIP。 */
    @Test
    void realSnapshotUploadsSevenConsistentDatasetsToPrivateMinio() throws Exception {
        Fixture fixture = fixture("DELETING", Instant.now().minus(Duration.ofDays(1)), 7, true);
        seedExportFacts(fixture);
        ProjectExportJob requested = request(fixture);
        ProjectExportClaim claim = jobs.claimReady("snapshot-worker").orElseThrow();
        CountDownLatch devicesRead = new CountDownLatch(1);
        CountDownLatch releaseSnapshot = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            devicesRead.countDown();
            if (!releaseSnapshot.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("快照写入未释放");
            }
            return result;
        }).when(deviceSource).streamDevices(any(), any(), any());

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> processing = executor.submit(() -> worker.process(claim));
            assertThat(devicesRead.await(10, TimeUnit.SECONDS)).isTrue();
            appendFactsAfterSnapshot(fixture);
            releaseSnapshot.countDown();
            processing.get(30, TimeUnit.SECONDS);
        } finally {
            releaseSnapshot.countDown();
        }

        ProjectExportJob succeeded = jobs.findByIdentity(
                fixture.tenantId(), fixture.projectId(), requested.id()).orElseThrow();
        assertThat(succeeded.status()).isEqualTo(ProjectExportStatus.SUCCEEDED);
        assertThat(succeeded.objectSize()).isPositive();
        byte[] archive = ProjectExportMinioFixture.readObject(succeeded.objectKey());
        assertThat((long) archive.length).isEqualTo(succeeded.objectSize());
        HttpResponse<Void> anonymous = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(ProjectExportMinioFixture.endpoint() + "/"
                        + ProjectExportMinioFixture.BUCKET + "/" + succeeded.objectKey())).build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(anonymous.statusCode()).isEqualTo(403);
        assertThat(directoryEmpty(TEMPORARY_ROOT)).isTrue();

        Path copy = Files.createTempFile("project-export-result-", ".zip");
        try {
            Files.write(copy, archive);
            try (ZipFile zip = new ZipFile(copy.toFile(), StandardCharsets.UTF_8)) {
                assertThat(zip.stream().map(ZipEntry::getName).toList()).containsExactly(
                        "manifest.json", "project.json", "members.jsonl", "devices.jsonl",
                        "property-points.jsonl", "alarm-instances.jsonl", "alarm-events.jsonl",
                        "audit-logs.jsonl");
                assertThat(lines(zip, "members.jsonl")).hasSize(1);
                assertThat(lines(zip, "devices.jsonl")).singleElement()
                        .satisfies(row -> assertThat(row.get("name").asString()).isEqualTo("快照前设备"));
                assertThat(lines(zip, "property-points.jsonl")).singleElement().satisfies(row ->
                        assertThat(Instant.parse(row.get("ts").asString())).isBefore(Instant.now().minus(Duration.ofDays(7))));
                assertThat(lines(zip, "alarm-instances.jsonl")).hasSize(1);
                assertThat(lines(zip, "alarm-events.jsonl")).hasSize(1);
                assertThat(lines(zip, "audit-logs.jsonl")).hasSize(2);
                JsonNode manifest = json.readTree(read(zip, "manifest.json"));
                assertThat(manifest.get("formatVersion").asInt()).isEqualTo(1);
                assertThat(manifest.get("projectGeneration").asLong()).isEqualTo(7);
                assertThat(manifest.get("files").size()).isEqualTo(7);
                String all = allEntriesText(zip);
                assertThat(all).doesNotContain(fixture.projectKey(), "test-password-hash", "tenantId");
            }
        } finally {
            Files.deleteIfExists(copy);
        }
        assertThat(number("SELECT count(*) FROM ts_property_point_internal WHERE project_id=?", fixture.projectId()))
                .isEqualTo(2);
        assertThat(number("SELECT count(*) FROM alarm_event WHERE project_id=?", fixture.projectId())).isEqualTo(2);
    }

    /** 真实对象服务首轮失败保留任务与清理事实；回拨退避后新token以新upload成功。 */
    @Test
    void objectFailureRetriesWithNewUploadAndLeavesNoTemporaryFiles() throws Exception {
        Fixture fixture = fixture("DELETING", Instant.now().minus(Duration.ofDays(1)), 3, true);
        ProjectExportJob requested = request(fixture);
        objectStorage.failNextUpload();
        worker.process(jobs.claimReady("retry-worker").orElseThrow());
        ProjectExportJob retrying = jobs.findByIdentity(
                fixture.tenantId(), fixture.projectId(), requested.id()).orElseThrow();
        assertThat(retrying.status()).isEqualTo(ProjectExportStatus.QUEUED);
        assertThat(retrying.attemptCount()).isEqualTo(1);
        assertThat(retrying.failureCode()).isEqualTo("STORAGE_UPLOAD");
        assertThat(ProjectExportMinioFixture.listObjectKeys(prefix(fixture))).isEmpty();

        executeOwner("UPDATE sys_project_export_job SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE id=?",
                requested.id());
        ProjectExportClaim second = jobs.claimReady("retry-worker").orElseThrow();
        assertThat(second.attemptCount()).isEqualTo(2);
        worker.process(second);

        ProjectExportJob succeeded = jobs.findByIdentity(
                fixture.tenantId(), fixture.projectId(), requested.id()).orElseThrow();
        assertThat(succeeded.status()).isEqualTo(ProjectExportStatus.SUCCEEDED);
        assertThat(ProjectExportMinioFixture.listObjectKeys(prefix(fixture)))
                .containsExactly(succeeded.objectKey());
        assertThat(number("SELECT count(*) FROM sys_project_export_upload_cleanup WHERE export_id=?", requested.id()))
                .isEqualTo(2);
        assertThat(rows("SELECT status FROM sys_project_export_upload_cleanup WHERE export_id=?",
                requested.id())).containsExactlyInAnyOrder("PENDING", "ADOPTED");
        assertThat(directoryEmpty(TEMPORARY_ROOT)).isTrue();

        executeOwner("UPDATE sys_project_export_upload_cleanup SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE export_id=? AND status='PENDING'",
                requested.id());
        cleanupWorker.cleanupReadyObjects();
        assertThat(rows("SELECT status FROM sys_project_export_upload_cleanup WHERE export_id=?",
                requested.id())).containsExactlyInAnyOrder("DELETED", "ADOPTED");
    }

    /** generator的900秒事务预算必须覆盖全局5秒JDBC预算，并让贡献查询加入同一事务。 */
    @Test
    void snapshotTransactionAllowsContributionQueryBeyondGlobalFiveSeconds() throws Exception {
        Fixture fixture = fixture("DELETING", Instant.now().minus(Duration.ofDays(1)), 5, true);
        ProjectExportJob requested = request(fixture);
        AtomicBoolean delayed = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (delayed.compareAndSet(true, false)) {
                assertThat(jdbc.queryForObject("SELECT 1 FROM pg_sleep(6)", Integer.class)).isEqualTo(1);
            }
            return invocation.callRealMethod();
        }).when(deviceSource).streamDevices(any(), any(), any());

        worker.process(jobs.claimReady("long-query-worker").orElseThrow());

        assertThat(jobs.findByIdentity(fixture.tenantId(), fixture.projectId(), requested.id()).orElseThrow().status())
                .isEqualTo(ProjectExportStatus.SUCCEEDED);
    }

    /** 恢复先提交时旧代次worker永久失败；再次删除产生的新代次可以建立独立任务。 */
    @Test
    void recoveryBeforeSnapshotRejectsOldGenerationAndNewDeletionUsesNewTask() throws Exception {
        Fixture fixture = fixture("DELETING", Instant.now().minus(Duration.ofDays(1)), 1, true);
        ProjectExportJob old = request(fixture);
        executeOwner("UPDATE sys_project SET status='ACTIVE',deleted_at=NULL WHERE id=?", fixture.projectId());
        worker.process(jobs.claimReady("generation-worker").orElseThrow());
        ProjectExportJob failed = jobs.findByIdentity(
                fixture.tenantId(), fixture.projectId(), old.id()).orElseThrow();
        assertThat(failed.status()).isEqualTo(ProjectExportStatus.FAILED);
        assertThat(failed.failureCode()).isEqualTo("PROJECT_NOT_EXPORTABLE");
        assertThat(ProjectExportMinioFixture.listObjectKeys(prefix(fixture))).isEmpty();

        executeOwner("""
                UPDATE sys_project SET status='DELETING', deleted_at=clock_timestamp(),
                       lifecycle_generation=lifecycle_generation+1 WHERE id=?
                """, fixture.projectId());
        ProjectExportJob current = request(fixture);
        assertThat(current.projectGeneration()).isEqualTo(2);
        assertThat(current.id()).isNotEqualTo(old.id());
    }

    /** worker先持项目SHARE时恢复必须真实等待；快照提交后恢复及下一删除代次都可顺序完成。 */
    @Test
    void snapshotShareLockSerializesRecoveryAndNextDeletionGeneration() throws Exception {
        Fixture fixture = fixture("DELETING", Instant.now().minus(Duration.ofDays(1)), 1, true);
        ProjectExportJob old = request(fixture);
        ProjectExportClaim claim = jobs.claimReady("recovery-race-worker").orElseThrow();
        CountDownLatch snapshotLocked = new CountDownLatch(1);
        CountDownLatch releaseSnapshot = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            snapshotLocked.countDown();
            if (!releaseSnapshot.await(10, TimeUnit.SECONDS)) throw new AssertionError("未释放恢复竞争快照");
            return result;
        }).when(deviceSource).streamDevices(any(), any(), any());

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> processing = executor.submit(() -> worker.process(claim));
            assertThat(snapshotLocked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> recovery = executor.submit(() -> recoverWithNamedConnection(fixture.projectId()));
            awaitNamedConnectionBlocked("export-recovery-race", recovery);
            releaseSnapshot.countDown();
            processing.get(30, TimeUnit.SECONDS);
            recovery.get(30, TimeUnit.SECONDS);
        } finally {
            releaseSnapshot.countDown();
        }

        assertThat(jobs.findByIdentity(fixture.tenantId(), fixture.projectId(), old.id()).orElseThrow().status())
                .isEqualTo(ProjectExportStatus.SUCCEEDED);
        assertThat(rows("SELECT status||':'||lifecycle_generation FROM sys_project WHERE id=?", fixture.projectId()))
                .containsExactly("ACTIVE:1");
        executeOwner("""
                UPDATE sys_project SET status='DELETING', deleted_at=clock_timestamp(),
                       lifecycle_generation=lifecycle_generation+1 WHERE id=?
                """, fixture.projectId());
        assertThat(request(fixture).projectGeneration()).isEqualTo(2);
    }

    /** HTTP入口必须走真实JWT过滤器，隐藏跨项目任务且永不暴露内部对象键。 */
    @Test
    void httpApiAuthenticatesOwnerRejectsStaleGenerationAndDoesNotRateLimitGet() throws Exception {
        Fixture fixture = fixture("DELETING", Instant.now().minus(Duration.ofDays(1)), 4, true);
        String projectless = token(fixture, null, 0);
        assertThat(mockMvc.perform(post("/api/v1/projects/" + fixture.projectId() + "/exports"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);

        MvcResult created = mockMvc.perform(post("/api/v1/projects/" + fixture.projectId() + "/exports")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + projectless)).andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(202);
        JsonNode body = json.readTree(created.getResponse().getContentAsString());
        UUID exportId = UUID.fromString(body.get("id").asString());
        assertThat(body.has("objectKey")).isFalse();

        // 首次POST已经计数；再回读四次后第六次必须限流，但GET仍不消费或要求请求额度。
        for (int attempt = 1; attempt < 5; attempt++) {
            assertThat(mockMvc.perform(post("/api/v1/projects/" + fixture.projectId() + "/exports")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + projectless))
                    .andReturn().getResponse().getStatus()).isEqualTo(202);
        }
        assertThat(mockMvc.perform(post("/api/v1/projects/" + fixture.projectId() + "/exports")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + projectless))
                .andReturn().getResponse().getStatus()).isEqualTo(429);
        MvcResult readable = mockMvc.perform(get("/api/v1/projects/" + fixture.projectId()
                        + "/exports/" + exportId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + projectless)).andReturn();
        assertThat(readable.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(readable.getResponse().getContentAsString()).has("objectKey")).isFalse();

        Fixture otherProject = projectFor(fixture);
        MvcResult hidden = mockMvc.perform(get("/api/v1/projects/" + otherProject.projectId()
                        + "/exports/" + exportId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + projectless)).andReturn();
        assertThat(hidden.getResponse().getStatus()).isEqualTo(404);
        assertThat(json.readTree(hidden.getResponse().getContentAsString()).get("code").asInt()).isEqualTo(50001);

        Fixture activeNewGeneration = fixture("ACTIVE", null, 1, true);
        String stale = token(activeNewGeneration, activeNewGeneration.projectId(), 0);
        MvcResult staleRejected = mockMvc.perform(post("/api/v1/projects/"
                        + activeNewGeneration.projectId() + "/exports")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + stale)).andReturn();
        assertThat(staleRejected.getResponse().getStatus()).isEqualTo(401);
        assertThat(json.readTree(staleRejected.getResponse().getContentAsString()).get("code").asInt())
                .isEqualTo(20020);
        assertThat(number("SELECT count(*) FROM sys_project_export_job WHERE project_id=?",
                activeNewGeneration.projectId())).isZero();
    }

    /** 只读最新任务可跨刷新找回终态，保持原请求者与当前代次过滤，不创建或计数。 */
    @Test
    void latestHttpFindsTerminalJobWithoutCreationAndHidesOtherRequesterOrGeneration() throws Exception {
        Fixture fixture = fixture("DELETING", Instant.now().minusSeconds(60), 3, true);
        String token = token(fixture, null, 0);
        String path = "/api/v1/projects/" + fixture.projectId() + "/exports/latest";
        assertThat(mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn().getResponse().getStatus()).isEqualTo(204);
        ProjectExportJob job = request(fixture);
        executeOwner("UPDATE sys_project_export_job SET status='FAILED',failure_code='TEST' WHERE id=?", job.id());
        MvcResult found = mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
        assertThat(found.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json.readTree(found.getResponse().getContentAsString());
        assertThat(body.get("id").asString()).isEqualTo(job.id().toString());
        assertThat(body.get("status").asString()).isEqualTo("FAILED");
        assertThat(body.has("objectKey")).isFalse();
        assertThat(body.has("url")).isFalse();
        // 同一项目也不能找回另一请求者或历史代次的任务。
        executeOwner("UPDATE sys_project_export_job SET requester_account_id=? WHERE id=?", fixture("DELETING", Instant.now().minusSeconds(60), 3, true).accountId(), job.id());
        assertThat(mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn().getResponse().getStatus()).isEqualTo(204);
        executeOwner("UPDATE sys_project_export_job SET requester_account_id=?,project_generation=2 WHERE id=?", fixture.accountId(), job.id());
        assertThat(mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn().getResponse().getStatus()).isEqualTo(204);
        assertThat(number("SELECT count(*) FROM sys_project_export_job WHERE project_id=?", fixture.projectId())).isEqualTo(1);
    }

    /** 请求审计在真实INSERT之后失败时，新任务与审计必须随同一事务全部回滚。 */
    @Test
    void requestAuditFailureRollsBackCreatedJob() throws Exception {
        Fixture fixture = fixture("DELETING", Instant.now().minus(Duration.ofDays(1)), 8, true);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if ("project.export.requested".equals(invocation.getArgument(0,
                    com.things.link.support.audit.AuditLogEntry.class).action())) {
                throw new IllegalStateException("request audit failed after insert");
            }
            return result;
        }).when(audits).record(any());

        Throwable failure = catchThrowable(() -> request(fixture));

        assertThat(failure).isInstanceOf(IllegalStateException.class);
        assertThat(number("SELECT count(*) FROM sys_project_export_job WHERE project_id=?", fixture.projectId()))
                .isZero();
        assertThat(number("SELECT count(*) FROM sys_audit_log WHERE project_id=? AND action='project.export.requested'",
                fixture.projectId())).isZero();
    }

    /** 两个真实事务并发请求同一项目代次只能收敛为一个任务和一条请求审计。 */
    @Test
    void concurrentRequestsCreateOneJobAndOneAudit() throws Exception {
        Fixture fixture = fixture("DELETING", Instant.now().minus(Duration.ofDays(1)), 9, true);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<ProjectExportJob> first = executor.submit(() -> requestAfter(start, fixture));
            Future<ProjectExportJob> second = executor.submit(() -> requestAfter(start, fixture));
            start.countDown();
            assertThat(first.get(30, TimeUnit.SECONDS).id()).isEqualTo(second.get(30, TimeUnit.SECONDS).id());
        }
        assertThat(number("SELECT count(*) FROM sys_project_export_job WHERE project_id=? AND project_generation=9",
                fixture.projectId())).isEqualTo(1);
        assertThat(number("SELECT count(*) FROM sys_audit_log WHERE project_id=? AND action='project.export.requested'",
                fixture.projectId())).isEqualTo(1);
    }

    /** 成功审计失败必须回滚采用CAS；孤儿对象由持久清理收束，移除故障后新attempt可成功。 */
    @Test
    void completionAuditFailureRollsBackAdoptionAndAllowsSafeRetry() throws Exception {
        Fixture fixture = fixture("DELETING", Instant.now().minus(Duration.ofDays(1)), 10, true);
        ProjectExportJob requested = request(fixture);
        AtomicBoolean failSuccessAudit = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if ("project.export.succeeded".equals(invocation.getArgument(0,
                    com.things.link.support.audit.AuditLogEntry.class).action())
                    && failSuccessAudit.compareAndSet(true, false)) {
                throw new IllegalStateException("success audit failed after insert");
            }
            return result;
        }).when(audits).record(any());

        worker.process(jobs.claimReady("completion-rollback-worker").orElseThrow());

        ProjectExportJob retrying = jobs.findByIdentity(
                fixture.tenantId(), fixture.projectId(), requested.id()).orElseThrow();
        assertThat(retrying.status()).isEqualTo(ProjectExportStatus.QUEUED);
        assertThat(retrying.objectKey()).isNull();
        assertThat(rows("SELECT status FROM sys_project_export_upload_cleanup WHERE export_id=?", requested.id()))
                .containsExactly("PENDING");
        assertThat(number("SELECT count(*) FROM sys_audit_log WHERE project_id=? AND action='project.export.succeeded'",
                fixture.projectId())).isZero();

        // 无论即时删除是否成功，持久清理都必须能幂等收掉第一attempt的对象。
        executeOwner("UPDATE sys_project_export_upload_cleanup SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE export_id=? AND status='PENDING'",
                requested.id());
        cleanupWorker.cleanupReadyObjects();
        assertThat(rows("SELECT status FROM sys_project_export_upload_cleanup WHERE export_id=?", requested.id()))
                .containsExactly("DELETED");
        assertThat(ProjectExportMinioFixture.listObjectKeys(prefix(fixture))).isEmpty();

        executeOwner("UPDATE sys_project_export_job SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE id=?",
                requested.id());
        worker.process(jobs.claimReady("completion-retry-worker").orElseThrow());
        ProjectExportJob succeeded = jobs.findByIdentity(
                fixture.tenantId(), fixture.projectId(), requested.id()).orElseThrow();
        assertThat(succeeded.status()).isEqualTo(ProjectExportStatus.SUCCEEDED);
        assertThat(number("SELECT count(*) FROM sys_audit_log WHERE project_id=? AND action='project.export.succeeded'",
                fixture.projectId())).isEqualTo(1);
        assertThat(ProjectExportMinioFixture.listObjectKeys(prefix(fixture)))
                .containsExactly(succeeded.objectKey());
    }

    /** generator事务在方法返回后的提交阶段失败，也必须由事务兜底清理已完成的本地ZIP。 */
    @Test
    void snapshotCommitFailureRemovesReturnedLocalArchive() throws Exception {
        Fixture fixture = fixture("DELETING", Instant.now().minus(Duration.ofDays(1)), 11, true);
        ProjectExportJob requested = request(fixture);
        String table = "project_export_commit_probe";
        String function = "project_export_commit_probe_fail";
        try {
            executeOwner("CREATE TABLE " + table + "(id uuid PRIMARY KEY)");
            executeOwner("GRANT INSERT ON " + table + " TO " + APP_ROLE);
            executeOwner("CREATE FUNCTION " + function + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'export snapshot commit failure' USING ERRCODE='23514'; END $$");
            executeOwner("CREATE CONSTRAINT TRIGGER " + table + "_deferred AFTER INSERT ON " + table
                    + " DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION " + function + "()");
            AtomicBoolean inserted = new AtomicBoolean();
            doAnswer(invocation -> {
                Object result = invocation.callRealMethod();
                if (inserted.compareAndSet(false, true)) {
                    jdbc.update("INSERT INTO " + table + "(id) VALUES (?)", Uuid7.generate());
                }
                return result;
            }).when(deviceSource).streamDevices(any(), any(), any());

            worker.process(jobs.claimReady("snapshot-commit-failure-worker").orElseThrow());

            assertThat(directoryEmpty(TEMPORARY_ROOT)).isTrue();
            assertThat(jobs.findByIdentity(fixture.tenantId(), fixture.projectId(), requested.id())
                    .orElseThrow().status()).isEqualTo(ProjectExportStatus.QUEUED);
            assertThat(ProjectExportMinioFixture.listObjectKeys(prefix(fixture))).isEmpty();
        } finally {
            executeOwner("DROP TABLE IF EXISTS " + table + " CASCADE");
            executeOwner("DROP FUNCTION IF EXISTS " + function + "() CASCADE");
        }
    }

    /** 通过真实用例事务建立任务，并始终恢复调用线程范围。 */
    private ProjectExportJob request(Fixture fixture) {
        TenantContext.set(new TenantScope(fixture.tenantId(), null, fixture.accountId()));
        try {
            return exports.request(fixture.projectId());
        } finally {
            TenantContext.clear();
        }
    }

    /** 两个并发事务各自建立和恢复线程范围。 */
    private ProjectExportJob requestAfter(CountDownLatch start, Fixture fixture) throws Exception {
        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
        return request(fixture);
    }

    /** 以生产签发器产生真实HS256控制台JWT；project可为空。 */
    private String token(Fixture fixture, UUID projectId, long generation) {
        return tokens.issue(new AuthenticatedPrincipal(
                fixture.accountId(), fixture.tenantId(), projectId, generation)).value();
    }

    /** 建立指定生命周期和OWNER资格的项目外键事实。 */
    private Fixture fixture(String status, Instant deletedAt, long generation, boolean ownerMember) throws Exception {
        UUID tenantId = Uuid7.generate();
        UUID accountId = Uuid7.generate();
        UUID projectId = Uuid7.generate();
        String projectKey = "export_secret_" + projectId.toString().replace("-", "");
        try (Connection owner = ownerConnection()) {
            owner.setAutoCommit(false);
            execute(owner, "INSERT INTO sys_tenant(id,name) VALUES (?,?)", tenantId, "导出租户");
            execute(owner, """
                    INSERT INTO sys_account(id,email,password_hash,display_name,email_verified_at)
                    VALUES (?,?,'test-password-hash','导出账号',clock_timestamp())
                    """, accountId, accountId + "@export.example");
            execute(owner, """
                    INSERT INTO sys_project(id,tenant_id,name,region,timezone,project_key,status,
                                            lifecycle_generation,created_at,updated_at,deleted_at)
                    VALUES (?,?,'项目导出','sh-1','Asia/Shanghai',?,?,?,
                            clock_timestamp(),clock_timestamp(),?)
                    """, projectId, tenantId, projectKey, status, generation, deletedAt);
            if (ownerMember) {
                execute(owner, """
                        INSERT INTO sys_project_member(id,project_id,account_id,role,status)
                        VALUES (?,?,?,'OWNER','ACTIVE')
                        """, Uuid7.generate(), projectId, accountId);
            }
            owner.commit();
        }
        Fixture fixture = new Fixture(tenantId, accountId, projectId, projectKey);
        OBJECT_PREFIXES.add(prefix(fixture));
        return fixture;
    }

    /** 在同账号与同tenant下创建第二个删除项目，专门证明限流project维度独立。 */
    private Fixture projectFor(Fixture identity) throws Exception {
        UUID projectId = Uuid7.generate();
        String projectKey = "export_secret_" + projectId.toString().replace("-", "");
        try (Connection owner = ownerConnection()) {
            owner.setAutoCommit(false);
            execute(owner, """
                    INSERT INTO sys_project(id,tenant_id,name,region,timezone,project_key,status,
                                            lifecycle_generation,created_at,updated_at,deleted_at)
                    VALUES (?,?,'第二导出项目','sh-1','Asia/Shanghai',?,'DELETING',1,
                            clock_timestamp(),clock_timestamp(),clock_timestamp()-interval '1 day')
                    """, projectId, identity.tenantId(), projectKey);
            execute(owner, """
                    INSERT INTO sys_project_member(id,project_id,account_id,role,status)
                    VALUES (?,?,?,'OWNER','ACTIVE')
                    """, Uuid7.generate(), projectId, identity.accountId());
            owner.commit();
        }
        Fixture fixture = new Fixture(identity.tenantId(), identity.accountId(), projectId, projectKey);
        OBJECT_PREFIXES.add(prefix(fixture));
        return fixture;
    }

    /** 为单个租户创建独立已启用存储额度策略，不修改共享FREE目录项。 */
    private void configureStorageQuota(Fixture fixture) throws Exception {
        UUID policyId = Uuid7.generate();
        executeOwner("INSERT INTO sys_quota_policy(id,code,storage_bytes_limit) VALUES (?,?,1024)",
                policyId, "EXPORT_" + policyId.toString().replace("-", "").substring(0, 20));
        executeOwner("UPDATE sys_tenant SET quota_policy_id=? WHERE id=?", policyId, fixture.tenantId());
    }

    /** 建立设备、真实Timescale点、告警实例/事件和已有审计各一条。 */
    private void seedExportFacts(Fixture fixture) throws Exception {
        UUID deviceId = Uuid7.generate();
        UUID ruleId = Uuid7.generate();
        UUID instanceId = Uuid7.generate();
        UUID messageId = Uuid7.generate();
        try (Connection owner = ownerConnection()) {
            owner.setAutoCommit(false);
            execute(owner, """
                    INSERT INTO dev_device(id,tenant_id,project_id,device_key,name,description,status,location)
                    VALUES (?,?,?,'snapshot-device','快照前设备','白名单描述','ONLINE','机房A')
                    """, deviceId, fixture.tenantId(), fixture.projectId());
            execute(owner, """
                    INSERT INTO ts_property_point_internal(project_id,device_id,property_key,ts,message_id,
                                                           value_double,quality)
                    VALUES (?,?, 'temperature',clock_timestamp()-interval '8 days',?,21.5,0)
                    """, fixture.projectId(), deviceId, messageId);
            execute(owner, """
                    INSERT INTO alarm_rule(id,tenant_id,project_id,name,alarm_type,originator_type,originator_id,
                                           property_key,trigger_operator,trigger_threshold,clear_operator,
                                           clear_threshold,severity)
                    VALUES (?,?,?,'导出规则','temperature-high','DEVICE',?,'temperature','GT',20,'LTE',18,'WARNING')
                    """, ruleId, fixture.tenantId(), fixture.projectId(), deviceId);
            execute(owner, """
                    INSERT INTO alarm_instance(id,tenant_id,project_id,rule_id,originator_type,originator_id,
                                               alarm_type,severity,condition_state,ack_state,first_condition_at,
                                               activated_at,last_received_at,last_occurred_at,last_value)
                    VALUES (?,?,?,?, 'DEVICE',?,'temperature-high','WARNING','ACTIVE','UNACKNOWLEDGED',
                            clock_timestamp()-interval '1 minute',clock_timestamp()-interval '50 seconds',
                            clock_timestamp()-interval '40 seconds',clock_timestamp()-interval '1 minute',21.5)
                    """, instanceId, fixture.tenantId(), fixture.projectId(), ruleId, deviceId);
            execute(owner, """
                    INSERT INTO alarm_event(id,tenant_id,project_id,instance_id,event_type,source_message_id,
                                            trace_id,value,occurred_at,received_at,condition_state,ack_state)
                    VALUES (?,?,?,?, 'ACTIVATED',?,'trace-before',21.5,clock_timestamp()-interval '1 minute',
                            clock_timestamp()-interval '50 seconds','ACTIVE','UNACKNOWLEDGED')
                    """, Uuid7.generate(), fixture.tenantId(), fixture.projectId(), instanceId, messageId);
            execute(owner, """
                    INSERT INTO sys_audit_log(id,tenant_id,project_id,actor_account_id,target_type,target_id,
                                              action,trace_id,details)
                    VALUES (?,?,?,?, 'project',?,'project.deleted','trace-before','{\"safe\":true}'::jsonb)
                    """, Uuid7.generate(), fixture.tenantId(), fixture.projectId(), fixture.accountId(),
                    fixture.projectId());
            owner.commit();
        }
        TEST_FACTS.put(fixture.projectId(), new ExportFacts(deviceId, instanceId));
    }

    /** 快照建立后提交第二批跨域事实，最终ZIP必须全部看不见。 */
    private void appendFactsAfterSnapshot(Fixture fixture) throws Exception {
        ExportFacts facts = TEST_FACTS.get(fixture.projectId());
        UUID messageId = Uuid7.generate();
        try (Connection owner = ownerConnection()) {
            owner.setAutoCommit(false);
            execute(owner, "UPDATE dev_device SET name='快照后设备',updated_at=clock_timestamp() WHERE id=?",
                    facts.deviceId());
            execute(owner, """
                    INSERT INTO ts_property_point_internal(project_id,device_id,property_key,ts,message_id,
                                                           value_double,quality)
                    VALUES (?,?, 'temperature',clock_timestamp(),?,99.5,0)
                    """, fixture.projectId(), facts.deviceId(), messageId);
            execute(owner, """
                    INSERT INTO alarm_event(id,tenant_id,project_id,instance_id,event_type,source_message_id,
                                            trace_id,value,occurred_at,received_at,condition_state,ack_state)
                    VALUES (?,?,?,?, 'ACTIVATED',?,'trace-after',99.5,clock_timestamp(),clock_timestamp(),
                            'ACTIVE','UNACKNOWLEDGED')
                    """, Uuid7.generate(), fixture.tenantId(), fixture.projectId(), facts.instanceId(), messageId);
            owner.commit();
        }
    }

    /** 使用可定位application_name执行恢复排他写，供pg_blocking_pids证明真实锁序。 */
    private static void recoverWithNamedConnection(UUID projectId) {
        try (Connection owner = ownerConnection()) {
            owner.setAutoCommit(false);
            execute(owner, "SET application_name='export-recovery-race'");
            execute(owner, "UPDATE sys_project SET status='ACTIVE',deleted_at=NULL WHERE id=?", projectId);
            owner.commit();
        } catch (SQLException exception) {
            throw new IllegalStateException("恢复竞争事务失败", exception);
        }
    }

    /** 以服务端阻塞图确认恢复已经等待快照SHARE锁，不以sleep猜线程调度。 */
    private static void awaitNamedConnectionBlocked(String applicationName, Future<?> recovery) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try (Connection observer = ownerConnection(); PreparedStatement statement = observer.prepareStatement("""
                SELECT cardinality(pg_blocking_pids(pid))
                  FROM pg_stat_activity
                 WHERE datname=current_database() AND application_name=?
                """)) {
            statement.setString(1, applicationName);
            while (System.nanoTime() < deadline) {
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next() && result.getInt(1) > 0) return;
                }
                if (recovery.isDone()) throw new AssertionError("恢复未等待项目SHARE锁");
                Thread.sleep(5);
            }
        }
        throw new AssertionError("未在预算内观察到恢复锁等待");
    }

    /** 按第一列读取owner事实列表。 */
    private List<String> rows(String sql, Object... values) throws Exception {
        try (Connection owner = ownerConnection(); PreparedStatement statement = owner.prepareStatement(sql)) {
            bind(statement, values);
            List<String> result = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(rows.getString(1));
            }
            return result;
        }
    }

    /** 读取owner单值数字。 */
    private long number(String sql, Object... values) throws Exception {
        return Long.parseLong(rows(sql, values).getFirst());
    }

    /** 解析JSONL非空行。 */
    private List<JsonNode> lines(ZipFile zip, String name) throws Exception {
        String content = new String(read(zip, name), StandardCharsets.UTF_8);
        List<JsonNode> result = new ArrayList<>();
        for (String line : content.lines().filter(value -> !value.isBlank()).toList()) {
            result.add(json.readTree(line));
        }
        return result;
    }

    /** 读取ZIP条目。 */
    private static byte[] read(ZipFile zip, String name) throws Exception {
        ZipEntry entry = zip.getEntry(name);
        assertThat(entry).isNotNull();
        try (InputStream input = zip.getInputStream(entry); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            input.transferTo(output);
            return output.toByteArray();
        }
    }

    /** 拼接解压后的JSON正文，敏感字段排除不能对压缩字节做无意义字符串检索。 */
    private static String allEntriesText(ZipFile zip) throws Exception {
        StringBuilder value = new StringBuilder();
        for (ZipEntry entry : zip.stream().toList()) {
            value.append(new String(read(zip, entry.getName()), StandardCharsets.UTF_8));
        }
        return value.toString();
    }

    /** 有界关闭Files.list后判断临时根是否为空或不存在。 */
    private static boolean directoryEmpty(Path directory) throws Exception {
        if (!Files.exists(directory)) return true;
        try (var entries = Files.list(directory)) {
            return entries.findAny().isEmpty();
        }
    }

    /** 当前项目所有upload共享的独占MinIO前缀。 */
    private static String prefix(Fixture fixture) {
        return "projects/" + fixture.tenantId() + "/" + fixture.projectId() + "/";
    }

    /** owner执行夹具或时钟回拨SQL。 */
    private void executeOwner(String sql, Object... values) throws Exception {
        try (Connection owner = ownerConnection()) {
            execute(owner, sql, values);
        }
    }

    /** owner连接绕过RLS，仅用于准备和事实观察。 */
    private static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(DATABASE_URL, EXPORT_POSTGRES.getUsername(), EXPORT_POSTGRES.getPassword());
    }

    /** 执行参数SQL。 */
    private static void execute(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            statement.executeUpdate();
        }
    }

    /** Instant明确转Timestamp，其他类型交给PG驱动。 */
    private static void bind(PreparedStatement statement, Object... values) throws SQLException {
        for (int index = 0; index < values.length; index++) {
            Object value = values[index];
            statement.setObject(index + 1, value instanceof Instant instant ? Timestamp.from(instant) : value);
        }
    }

    /** 删除测试独占临时目录。 */
    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /** 独占PG必须先于Spring/Flyway属性解析启动。 */
    private static String startDatabase() {
        EXPORT_POSTGRES.start();
        return EXPORT_POSTGRES.getJdbcUrl();
    }

    /** 各项目的跨快照追加只由本类线程访问。 */
    private static final Map<UUID, ExportFacts> TEST_FACTS = new java.util.concurrent.ConcurrentHashMap<>();

    /** 只清理本类已建立项目的MinIO前缀，不能扫掉并行类的对象。 */
    private static final java.util.Set<String> OBJECT_PREFIXES = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** APP与Flyway共同切到专库，后台worker首轮及后续扫描均推迟一小时。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class IsolatedDatabaseConfiguration {
        /** 注册独占数据库、临时目录和调度间隔。 */
        @Bean
        DynamicPropertyRegistrar isolatedDatabaseProperties() {
            return registry -> {
                registry.add("spring.datasource.url", () -> DATABASE_URL);
                registry.add("spring.flyway.url", () -> DATABASE_URL);
                registry.add("things-link.export.temporary-directory", TEMPORARY_ROOT::toString);
                registry.add("things-link.export.worker-fixed-delay-millis", () -> "3600000");
                registry.add("things-link.export.worker-initial-delay-millis", () -> "3600000");
                registry.add("things-link.export.cleanup-fixed-delay-millis", () -> "3600000");
                registry.add("things-link.export.cleanup-initial-delay-millis", () -> "3600000");
                registry.add("things-link.export.expiry-fixed-delay-millis", () -> "3600000");
                registry.add("things-link.export.expiry-initial-delay-millis", () -> "3600000");
                registry.add("things-link.storage.internal-endpoint", ProjectExportMinioFixture::endpoint);
                registry.add("things-link.storage.external-endpoint", ProjectExportMinioFixture::endpoint);
                registry.add("things-link.storage.access-key", ProjectExportMinioFixture::accessKey);
                registry.add("things-link.storage.secret-key", ProjectExportMinioFixture::secretKey);
                registry.add("things-link.outbox.publisher.enabled", () -> "false");
                registry.add("things-link.notification.retry.enabled", () -> "false");
                registry.add("spring.kafka.listener.auto-startup", () -> "false");
            };
        }
    }

    /** 将worker连接到真实固定版本MinIO，同时保留一次可控上传故障。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class RealObjectStorageConfiguration {
        /** @return 本类优先使用的真实MinIO包装器 */
        @Bean
        @Primary
        FailOnceObjectStorage projectExportTestObjectStorage(MinioPrivateObjectStorage delegate) {
            return new FailOnceObjectStorage(delegate);
        }
    }

    /** 只在测试显式开启时让一次上传失败，其余操作保持真实MinIO语义。 */
    static final class FailOnceObjectStorage implements PrivateObjectStorage {
        /** 真实适配器。 */ private final PrivateObjectStorage delegate;
        /** 单次故障开关。 */ private final AtomicBoolean failUpload = new AtomicBoolean();

        /** @param delegate 真实MinIO适配器 */
        private FailOnceObjectStorage(PrivateObjectStorage delegate) {
            this.delegate = delegate;
        }

        /** 下一次上传在访问服务端前失败。 */
        void failNextUpload() { failUpload.set(true); }

        /** 清除未消费故障，防止前例失败污染后例。 */
        void reset() { failUpload.set(false); }

        /** {@inheritDoc} */
        @Override
        public void upload(String bucket, String objectKey, Path source, String contentType,
                           Map<String, String> metadata) {
            if (failUpload.compareAndSet(true, false)) {
                throw new ObjectStorageException("测试注入上传失败", new IllegalStateException("once"));
            }
            delegate.upload(bucket, objectKey, source, contentType, metadata);
        }

        /** {@inheritDoc} */
        @Override
        public void delete(String bucket, String objectKey) {
            delegate.delete(bucket, objectKey);
        }

        /** 5g1不签发下载地址。 */
        @Override
        public java.net.URI presignGet(String bucket, String objectKey, Duration ttl) {
            return delegate.presignGet(bucket, objectKey, ttl);
        }
    }

    /** 项目请求身份。 */
    private record Fixture(UUID tenantId, UUID accountId, UUID projectId, String projectKey) {
    }

    /** 快照后追加所需设备与告警实例身份。 */
    private record ExportFacts(UUID deviceId, UUID instanceId) {
    }
}
