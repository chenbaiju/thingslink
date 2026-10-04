package com.things.link.bootstrap.export;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.bootstrap.fixture.ProjectExportMinioFixture;

import com.things.link.export.application.ProjectExportCleanupWorker;
import com.things.link.export.application.ProjectExportDownload;
import com.things.link.export.application.ProjectExportService;
import com.things.link.export.domain.ProjectExportExpiryClaim;
import com.things.link.export.domain.ProjectExportJobRepository;
import com.things.link.iam.application.AuthenticatedPrincipal;
import com.things.link.iam.infrastructure.security.JwtTokenIssuer;
import com.things.link.project.application.ProjectExportSource;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import com.things.link.support.storage.MinioPrivateObjectStorage;
import com.things.link.support.storage.ObjectStorageException;
import com.things.link.support.storage.PrivateObjectStorage;
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
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** ADR0075 决策5：下载能力与二十四小时对象清理必须服从同一持久任务身份和锁序。 */
@AutoConfigureMockMvc
@Import({ProjectExportDownloadLifecycleTests.IsolatedDatabaseConfiguration.class,
        ProjectExportDownloadLifecycleTests.ControlledObjectStorageConfiguration.class})
@OwnedTestContainers({"EXPORT_POSTGRES"})
class ProjectExportDownloadLifecycleTests extends AbstractIntegrationTest {

    /** 独占数据库隔离到期全局领取、时钟回拨和故障状态。 */
    private static final String DATABASE_NAME = "project_export_download_"
            + UUID.randomUUID().toString().replace("-", "");
    /** 与全量测试一致的真实TimescaleDB/PostgreSQL。 */
    private static final PostgreSQLContainer<?> EXPORT_POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName(DATABASE_NAME)
            .withUsername(POSTGRES.getUsername())
            .withPassword(POSTGRES.getPassword())
            .withConnectTimeoutSeconds(30);
    /** APP、Flyway与owner观察固定到专库。 */
    private static final String DATABASE_URL = startDatabase();
    /** MinIO对象使用独占前缀清理。 */
    private static final java.util.Set<String> OBJECT_PREFIXES =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 基类配额启动器指向共享库，本类禁止其跨库改写。 */
    @MockitoBean(enforceOverride = true, name = "relaxRestQuota")
    private ApplicationRunner unusedRestQuotaRelaxation;
    /** 真实HTTP过滤器、Controller与错误翻译。 */
    @Autowired private MockMvc mockMvc;
    /** 直接故障与并发驱动仍调用真实事务代理。 */
    @Autowired private ProjectExportService exports;
    /** 到期领取和token CAS使用真实SECURITY DEFINER函数。 */
    @Autowired private ProjectExportJobRepository jobs;
    /** 到期删除编排使用真实MinIO包装器。 */
    @Autowired private ProjectExportCleanupWorker cleanupWorker;
    /** 生产JWT签发器用于真实过滤器验签。 */
    @Autowired private JwtTokenIssuer tokens;
    /** APP连接验证专库及数据库实际时钟。 */
    @Autowired private JdbcTemplate jdbc;
    /** HTTP JSON语义解析。 */
    @Autowired private ObjectMapper json;
    /** 可控签名/删除故障仍委托真实MinIO。 */
    @Autowired private ControlledObjectStorage storage;
    /** 审计真实写入后注入故障，验证事务回滚且不泄露URL。 */
    @MockitoSpyBean private AuditLogService audits;
    /** 项目许可spy只记录真实APP事务PID，不替换任何授权或锁查询。 */
    @MockitoSpyBean private ProjectExportSource projectSource;

    /** 每例清空全局队列并确认测试没有退化到共享数据库或真实调度。 */
    @BeforeEach
    void prepare() throws Exception {
        assertThat(mockingDetails(unusedRestQuotaRelaxation).isMock()).isTrue();
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo(DATABASE_NAME);
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        executeOwner("DELETE FROM sys_project_export_upload_cleanup");
        executeOwner("DELETE FROM sys_project_export_job");
        storage.reset();
    }

    /** 清除调用线程范围和本类真实MinIO对象。 */
    @AfterEach
    void cleanup() {
        TenantContext.clear();
        for (String prefix : new ArrayList<>(OBJECT_PREFIXES)) {
            ProjectExportMinioFixture.deletePrefix(prefix);
            OBJECT_PREFIXES.remove(prefix);
        }
    }

    /** 成功HTTP响应只含外部端点五分钟URL和截止，独立匿名客户端可以读取真实私有对象。 */
    @Test
    void requesterOwnerGetsFiveMinuteExternalUrlWithoutStorageIdentity() throws Exception {
        Fixture fixture = succeededFixture("ACTIVE", 3, Instant.now().plus(Duration.ofHours(2)));
        Instant before = Instant.now();
        assertThat(mockMvc.perform(post("/api/v1/projects/" + fixture.projectId() + "/exports/"
                        + fixture.exportId() + "/download-url"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);

        MvcResult response = downloadUrl(token(fixture.requesterId(), fixture.callerTenantId()),
                fixture.projectId(), fixture.exportId());

        assertThat(response.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json.readTree(response.getResponse().getContentAsString());
        assertThat(fieldNames(body)).containsExactlyInAnyOrder("url", "expiresAt");
        URI url = URI.create(body.get("url").asString());
        assertThat(url.getAuthority()).isEqualTo(URI.create(externalEndpoint()).getAuthority());
        assertThat(url.getQuery()).contains("X-Amz-Expires=300");
        assertThat(Instant.parse(body.get("expiresAt").asString()))
                .isBetween(before.plusSeconds(295), Instant.now().plusSeconds(305));
        assertThat(readUrl(url)).isEqualTo(fixture.content());
        assertThat(number("SELECT count(*) FROM sys_audit_log WHERE project_id=? AND action='project.export.downloaded'",
                fixture.projectId())).isEqualTo(1);
        String details = row("SELECT details::text FROM sys_audit_log WHERE project_id=? AND action='project.export.downloaded'",
                fixture.projectId());
        assertThat(details).contains(fixture.exportId().toString(), fixture.sha256())
                .doesNotContain("http", "X-Amz", "Credential");
    }

    /** 只有原requester仍为当前有效OWNER时可签名；新OWNER、失权、停用和不可签状态统一隐藏。 */
    @Test
    void nonRequesterOrIneligibleFactsNeverReceiveUrl() throws Exception {
        Fixture fixture = succeededFixture("ACTIVE", 5, Instant.now().plus(Duration.ofHours(2)));
        UUID outsiderTenant = Uuid7.generate();
        UUID outsider = account(outsiderTenant, "cross-tenant-outsider");
        assertInvisible(outsider, outsiderTenant, fixture);
        executeOwner("UPDATE sys_project_member SET role='VIEWER' WHERE project_id=? AND account_id=?",
                fixture.projectId(), fixture.requesterId());
        UUID anotherOwner = account(fixture.projectTenantId(), "other-owner");
        member(fixture.projectId(), anotherOwner, "OWNER", "ACTIVE");
        assertInvisible(anotherOwner, fixture.callerTenantId(), fixture);
        assertInvisible(fixture.requesterId(), fixture.callerTenantId(), fixture);
        executeOwner("UPDATE sys_project_member SET role='VIEWER' WHERE project_id=? AND account_id=?",
                fixture.projectId(), anotherOwner);
        executeOwner("UPDATE sys_project_member SET role='OWNER' WHERE project_id=? AND account_id=?",
                fixture.projectId(), fixture.requesterId());
        executeOwner("UPDATE sys_account SET status='DISABLED' WHERE id=?", fixture.requesterId());
        assertInvisible(fixture.requesterId(), fixture.callerTenantId(), fixture);
        executeOwner("UPDATE sys_account SET status='ACTIVE' WHERE id=?", fixture.requesterId());

        for (String status : List.of("QUEUED", "FAILED", "EXPIRED")) {
            executeOwner("UPDATE sys_project_export_job SET status=? WHERE id=?", status, fixture.exportId());
            assertInvisible(fixture.requesterId(), fixture.callerTenantId(), fixture);
        }
        executeOwner("UPDATE sys_project_export_job SET status='SUCCEEDED', expires_at=clock_timestamp() WHERE id=?",
                fixture.exportId());
        assertInvisible(fixture.requesterId(), fixture.callerTenantId(), fixture);
        assertThat(number("SELECT count(*) FROM sys_audit_log WHERE project_id=? AND action='project.export.downloaded'",
                fixture.projectId())).isZero();
    }

    /** 恢复不改变代次时原requester仍可下载；再次删除递增代次后旧对象永久失去新签名资格。 */
    @Test
    void recoverySameGenerationAllowsRequesterButNextDeletionRevokesOldObject() throws Exception {
        Fixture fixture = succeededFixture("DELETING", 7, Instant.now().plus(Duration.ofHours(2)));
        assertThat(download(fixture.requesterId(), fixture.callerTenantId(), fixture).url()).isNotNull();
        executeOwner("UPDATE sys_project SET status='ACTIVE',deleted_at=NULL WHERE id=?", fixture.projectId());
        assertThat(download(fixture.requesterId(), fixture.callerTenantId(), fixture).url()).isNotNull();

        executeOwner("UPDATE sys_project SET status='ARCHIVED' WHERE id=?", fixture.projectId());
        assertThat(download(fixture.requesterId(), fixture.callerTenantId(), fixture).url()).isNotNull();

        executeOwner("UPDATE sys_project SET status='DELETING',deleted_at=clock_timestamp(),lifecycle_generation=8 WHERE id=?",
                fixture.projectId());
        assertInvisible(fixture.requesterId(), fixture.callerTenantId(), fixture);
        assertThat(number("SELECT count(*) FROM sys_audit_log WHERE project_id=? AND action='project.export.downloaded'",
                fixture.projectId())).isEqualTo(3);
    }

    /** 存储签名或审计提交失败都不得向调用者返回能力，也不得留下下载成功审计。 */
    @Test
    void signingOrAuditFailureReturnsNoCapabilityAndRollsBackAudit() throws Exception {
        Fixture signingFailure = succeededFixture("ACTIVE", 9, Instant.now().plus(Duration.ofHours(2)));
        Instant signingScheduleBefore = instant(
                "SELECT next_attempt_at FROM sys_project_export_upload_cleanup WHERE id=?",
                signingFailure.cleanupId());
        storage.failNextPresign();
        Throwable storageFailure = catchThrowable(() -> download(
                signingFailure.requesterId(), signingFailure.callerTenantId(), signingFailure));
        assertThat(storageFailure).isInstanceOf(ObjectStorageException.class);
        assertThat(number("SELECT count(*) FROM sys_audit_log WHERE project_id=? AND action='project.export.downloaded'",
                signingFailure.projectId())).isZero();
        assertThat(instant("SELECT next_attempt_at FROM sys_project_export_upload_cleanup WHERE id=?",
                signingFailure.cleanupId())).isEqualTo(signingScheduleBefore);

        Fixture auditFailure = succeededFixture("ACTIVE", 10, Instant.now().plus(Duration.ofHours(2)));
        Instant auditScheduleBefore = instant(
                "SELECT next_attempt_at FROM sys_project_export_upload_cleanup WHERE id=?",
                auditFailure.cleanupId());
        AtomicBoolean failOnce = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            AuditLogEntry entry = invocation.getArgument(0);
            if ("project.export.downloaded".equals(entry.action()) && failOnce.compareAndSet(true, false)) {
                throw new IllegalStateException("download audit commit failure");
            }
            return result;
        }).when(audits).record(any());
        int presignsBefore = storage.presignCount();
        Throwable auditError = catchThrowable(() -> download(
                auditFailure.requesterId(), auditFailure.callerTenantId(), auditFailure));
        assertThat(auditError).isInstanceOf(IllegalStateException.class);
        assertThat(storage.presignCount()).isEqualTo(presignsBefore);
        assertThat(number("SELECT count(*) FROM sys_audit_log WHERE project_id=? AND action='project.export.downloaded'",
                auditFailure.projectId())).isZero();
        assertThat(instant("SELECT next_attempt_at FROM sys_project_export_upload_cleanup WHERE id=?",
                auditFailure.cleanupId())).isEqualTo(auditScheduleBefore);
    }

    /** 审计延迟到事务COMMIT才失败时，Controller也不得先把已经生成的bearer URL写入响应。 */
    @Test
    void deferredAuditCommitFailureReturnsSystemErrorWithoutUrl() throws Exception {
        Fixture fixture = succeededFixture("ACTIVE", 11, Instant.now().plus(Duration.ofHours(2)));
        Instant scheduleBefore = instant(
                "SELECT next_attempt_at FROM sys_project_export_upload_cleanup WHERE id=?", fixture.cleanupId());
        String function = "project_export_download_commit_failure";
        String trigger = function + "_trigger";
        try {
            executeOwner("CREATE FUNCTION " + function + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.project_id='"
                    + fixture.projectId() + "'::uuid AND NEW.action='project.export.downloaded' THEN RAISE EXCEPTION 'download audit commit failure' USING ERRCODE='23514'; END IF; RETURN NEW; END $$");
            executeOwner("CREATE CONSTRAINT TRIGGER " + trigger + " AFTER INSERT ON sys_audit_log DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION "
                    + function + "()");

            MvcResult response = downloadUrl(token(fixture.requesterId(), fixture.callerTenantId()),
                    fixture.projectId(), fixture.exportId());

            assertThat(response.getResponse().getStatus()).isEqualTo(500);
            assertThat(response.getResponse().getContentAsString()).doesNotContain("\"url\"", "X-Amz");
            assertThat(number("SELECT count(*) FROM sys_audit_log WHERE project_id=? AND action='project.export.downloaded'",
                    fixture.projectId())).isZero();
            assertThat(instant("SELECT next_attempt_at FROM sys_project_export_upload_cleanup WHERE id=?",
                    fixture.cleanupId())).isEqualTo(scheduleBefore);
        } finally {
            executeOwner("DROP TRIGGER IF EXISTS " + trigger + " ON sys_audit_log");
            executeOwner("DROP FUNCTION IF EXISTS " + function + "() CASCADE");
        }
    }

    /** 临近保留截止签出的URL把采用对象清理推迟五分钟；签名事务持锁期间到期worker不能抢走对象。 */
    @Test
    void downloadAndExpiryCleanupSerializeAndPreserveSignedUrlGrace() throws Exception {
        Fixture fixture = succeededFixture("ACTIVE", 12, Instant.now().plusSeconds(2));
        storage.blockNextPresign();
        CountDownLatch entered = storage.presignEntered();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<ProjectExportDownload> signing = executor.submit(() -> {
                TenantContext.set(new TenantScope(fixture.callerTenantId(), null, fixture.requesterId()));
                try {
                    return exports.download(fixture.projectId(), fixture.exportId());
                } finally {
                    TenantContext.clear();
                }
            });
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            awaitDatabaseTime(fixture.expiresAt());
            // SKIP LOCKED应立即看不到正被下载事务锁定的任务，而不是等待后误删。
            assertThat(jobs.claimExpired()).isEmpty();
            storage.releasePresign();
            ProjectExportDownload signed = signing.get(30, TimeUnit.SECONDS);
            assertThat(readUrl(signed.url())).isEqualTo(fixture.content());
        } finally {
            storage.releasePresign();
        }

        Instant cleanupAfter = instant("SELECT next_attempt_at FROM sys_project_export_upload_cleanup WHERE id=?",
                fixture.cleanupId());
        assertThat(cleanupAfter).isAfterOrEqualTo(Instant.now().plusSeconds(285));
        assertThat(jobs.claimExpired()).isEmpty();
        executeOwner("UPDATE sys_project_export_upload_cleanup SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE id=?",
                fixture.cleanupId());
        assertThat(jobs.claimExpired()).isPresent();
    }

    /** 项目锁等待跨过数据库截止后必须在job锁内二次拒绝，不能沿用事务开始前的资格。 */
    @Test
    void projectLockWaitAcrossExpiryRejectsWithoutSigning() throws Exception {
        Fixture fixture = succeededFixture("ACTIVE", 15, Instant.now().plusSeconds(2));
        CompletableFuture<Integer> waiterPid = new CompletableFuture<>();
        doAnswer(invocation -> {
            waiterPid.complete(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
            return invocation.callRealMethod();
        }).when(projectSource).authorizeDownload(eq(fixture.requesterId()), eq(fixture.projectId()));

        try (Connection holder = ownerConnection(); ExecutorService executor = Executors.newSingleThreadExecutor()) {
            holder.setAutoCommit(false);
            int holderPid = backendPid(holder);
            execute(holder, "SELECT id FROM sys_project WHERE id=? FOR UPDATE", fixture.projectId());
            Future<Throwable> waiting = executor.submit(() -> catchThrowable(() -> download(
                    fixture.requesterId(), fixture.callerTenantId(), fixture)));
            awaitBlocked(waiterPid.get(10, TimeUnit.SECONDS), holderPid, waiting);
            awaitDatabaseTime(fixture.expiresAt());
            holder.commit();

            Throwable failure = waiting.get(30, TimeUnit.SECONDS);
            assertThat(failure).isExactlyInstanceOf(BusinessException.class);
            assertThat(((BusinessException) failure).errorCode().code()).isEqualTo(50001);
        }
        assertThat(storage.presignCount()).isZero();
        assertThat(number("SELECT count(*) FROM sys_audit_log WHERE project_id=? AND action='project.export.downloaded'",
                fixture.projectId())).isZero();
    }

    /** 真实MinIO到期删除在失败与删后崩溃时均可重领，最终cleanup和任务必须原子收束。 */
    @Test
    void expiredObjectDeletionRetriesStorageFailureAndCrashAfterDelete() throws Exception {
        Fixture failedDelete = succeededFixture("ACTIVE", 13, Instant.now().minusSeconds(1));
        ProjectExportExpiryClaim expiryWon = jobs.claimExpired().orElseThrow();
        assertInvisible(failedDelete.requesterId(), failedDelete.callerTenantId(), failedDelete);
        storage.failNextDelete();
        cleanupWorker.cleanupExpired(expiryWon);
        assertExpiryPending(failedDelete);
        assertThat(ProjectExportMinioFixture.listObjectKeys(prefix(failedDelete)))
                .containsExactly(failedDelete.objectKey());
        makeExpiryDue(failedDelete);
        cleanupWorker.cleanupExpiredObjects();
        assertExpiryComplete(failedDelete);

        Fixture crashedAfterDelete = succeededFixture("ACTIVE", 14, Instant.now().minusSeconds(1));
        storage.crashAfterNextDelete();
        cleanupWorker.cleanupExpiredObjects();
        assertExpiryPending(crashedAfterDelete);
        assertThat(ProjectExportMinioFixture.listObjectKeys(prefix(crashedAfterDelete))).isEmpty();
        makeExpiryDue(crashedAfterDelete);
        cleanupWorker.cleanupExpiredObjects();
        assertExpiryComplete(crashedAfterDelete);
    }

    /** 对象已删但完成事务在COMMIT失败时，job与cleanup必须共同回滚并可按同一事实幂等重试。 */
    @Test
    void expiryCompletionCommitFailureRollsBackBothStatesAndRecovers() throws Exception {
        Fixture fixture = succeededFixture("ACTIVE", 16, Instant.now().minusSeconds(1));
        String function = "project_export_expiry_commit_failure";
        String trigger = function + "_trigger";
        try {
            executeOwner("CREATE FUNCTION " + function + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.id='"
                    + fixture.exportId() + "'::uuid AND NEW.status='EXPIRED' THEN RAISE EXCEPTION 'expiry commit failure' USING ERRCODE='23514'; END IF; RETURN NEW; END $$");
            executeOwner("CREATE CONSTRAINT TRIGGER " + trigger
                    + " AFTER UPDATE ON sys_project_export_job DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION "
                    + function + "()");

            cleanupWorker.cleanupExpiredObjects();

            assertExpiryPending(fixture);
            assertThat(ProjectExportMinioFixture.listObjectKeys(prefix(fixture))).isEmpty();
        } finally {
            executeOwner("DROP TRIGGER IF EXISTS " + trigger + " ON sys_project_export_job");
            executeOwner("DROP FUNCTION IF EXISTS " + function + "() CASCADE");
        }

        makeExpiryDue(fixture);
        cleanupWorker.cleanupExpiredObjects();
        assertExpiryComplete(fixture);
    }

    /** 直接调用真实事务代理签发，不绕过项目、账号或任务锁。 */
    private ProjectExportDownload download(UUID accountId, UUID callerTenantId, Fixture fixture) {
        TenantContext.set(new TenantScope(callerTenantId, null, accountId));
        try {
            return exports.download(fixture.projectId(), fixture.exportId());
        } finally {
            TenantContext.clear();
        }
    }

    /** 所有不可见事实沿统一50001，不能用不同状态泄露任务或项目。 */
    private void assertInvisible(UUID accountId, UUID callerTenantId, Fixture fixture) {
        Throwable failure = catchThrowable(() -> download(accountId, callerTenantId, fixture));
        assertThat(failure).isExactlyInstanceOf(BusinessException.class);
        assertThat(((BusinessException) failure).errorCode().code()).isEqualTo(50001);
    }

    /** 真实HTTP签发入口。 */
    private MvcResult downloadUrl(String token, UUID projectId, UUID exportId) throws Exception {
        return mockMvc.perform(post("/api/v1/projects/" + projectId + "/exports/" + exportId + "/download-url")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
    }

    /** 生产JWT只带账号/调用租户，不预选项目，防止过滤器提前替代下载用例的权威复核。 */
    private String token(UUID accountId, UUID callerTenantId) {
        return tokens.issue(new AuthenticatedPrincipal(accountId, callerTenantId, null, 0)).value();
    }

    /** 建立真实项目、原requester成功任务、ADOPTED cleanup与MinIO对象。 */
    private Fixture succeededFixture(String projectStatus, long generation, Instant expiresAt) throws Exception {
        UUID projectTenantId = Uuid7.generate();
        UUID callerTenantId = Uuid7.generate();
        UUID requesterId = account(callerTenantId, "requester");
        executeOwner("INSERT INTO sys_tenant(id,name) VALUES (?,?)", projectTenantId, "项目真实租户");
        UUID projectId = Uuid7.generate();
        Instant deletedAt = "DELETING".equals(projectStatus) ? Instant.now().minusSeconds(60) : null;
        executeOwner("INSERT INTO sys_project(id,tenant_id,name,region,timezone,project_key,status,lifecycle_generation,deleted_at) VALUES (?,?,?,'sh-1','Asia/Shanghai',?,?,?,?)",
                projectId, projectTenantId, "下载项目", "export_" + projectId, projectStatus, generation, deletedAt);
        member(projectId, requesterId, "OWNER", "ACTIVE");

        UUID exportId = Uuid7.generate();
        UUID cleanupId = Uuid7.generate();
        UUID uploadId = Uuid7.generate();
        String objectKey = "projects/" + projectTenantId + "/" + projectId + "/generations/"
                + generation + "/exports/" + exportId + "/" + uploadId + "/project-export-v1.zip";
        byte[] content = ("download-" + exportId).getBytes(StandardCharsets.UTF_8);
        String sha256 = sha256(content);
        Path artifact = Files.createTempFile("project-export-download-", ".zip");
        try {
            Files.write(artifact, content);
            storage.upload(ProjectExportMinioFixture.BUCKET, objectKey, artifact,
                    "application/zip", Map.of("sha256", sha256));
        } finally {
            Files.deleteIfExists(artifact);
        }
        executeOwner("INSERT INTO sys_project_export_job(id,tenant_id,project_id,project_generation,requester_account_id,status,attempt_count,snapshot_at,current_upload_id,object_key,object_size,object_sha256,requested_at,started_at,succeeded_at,expires_at) VALUES (?,?,?,?,?,'SUCCEEDED',1,?,?,?,?,?,clock_timestamp()-interval '1 hour',clock_timestamp()-interval '50 minutes',?,?)",
                exportId, projectTenantId, projectId, generation, requesterId, Instant.now().minusSeconds(300),
                uploadId, objectKey, content.length, sha256, expiresAt.minus(Duration.ofHours(24)), expiresAt);
        executeOwner("INSERT INTO sys_project_export_upload_cleanup(id,export_id,tenant_id,project_id,upload_id,object_key,status,next_attempt_at,adopted_at) VALUES (?,?,?,?,?,?,'ADOPTED',?,?)",
                cleanupId, exportId, projectTenantId, projectId, uploadId, objectKey, expiresAt, expiresAt.minus(Duration.ofHours(24)));
        Fixture fixture = new Fixture(projectTenantId, callerTenantId, requesterId, projectId, exportId,
                cleanupId, objectKey, sha256, content, generation, expiresAt);
        OBJECT_PREFIXES.add(prefix(fixture));
        return fixture;
    }

    /** 建立调用账号及其独立租户成员事实。 */
    private UUID account(UUID tenantId, String label) throws Exception {
        executeOwner("INSERT INTO sys_tenant(id,name) VALUES (?,?) ON CONFLICT (id) DO NOTHING", tenantId, label + "租户");
        UUID accountId = Uuid7.generate();
        executeOwner("INSERT INTO sys_account(id,email,password_hash,display_name,email_verified_at) VALUES (?,?,'test-hash',?,clock_timestamp())",
                accountId, label + "+" + accountId + "@example.test", label);
        executeOwner("INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?,?,?)",
                Uuid7.generate(), tenantId, accountId);
        return accountId;
    }

    /** 建立项目成员事实。 */
    private void member(UUID projectId, UUID accountId, String role, String status) throws Exception {
        executeOwner("INSERT INTO sys_project_member(id,project_id,account_id,role,status) VALUES (?,?,?,?,?)",
                Uuid7.generate(), projectId, accountId, role, status);
    }

    /** 删除失败必须释放token并保留SUCCEEDED/ADOPTED以便重试。 */
    private void assertExpiryPending(Fixture fixture) throws Exception {
        assertThat(row("SELECT status FROM sys_project_export_job WHERE id=?", fixture.exportId()))
                .isEqualTo("SUCCEEDED");
        assertThat(row("SELECT status FROM sys_project_export_upload_cleanup WHERE id=?", fixture.cleanupId()))
                .isEqualTo("ADOPTED");
        assertThat(row("SELECT lease_token IS NULL FROM sys_project_export_upload_cleanup WHERE id=?",
                fixture.cleanupId())).isEqualTo("t");
    }

    /** 重试前仅回拨测试独占cleanup调度时刻。 */
    private void makeExpiryDue(Fixture fixture) throws Exception {
        executeOwner("UPDATE sys_project_export_upload_cleanup SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE id=?",
                fixture.cleanupId());
    }

    /** 成功清理必须同时原子收束两个持久状态并删除真实对象。 */
    private void assertExpiryComplete(Fixture fixture) throws Exception {
        assertThat(row("SELECT status FROM sys_project_export_job WHERE id=?", fixture.exportId()))
                .isEqualTo("EXPIRED");
        assertThat(row("SELECT status FROM sys_project_export_upload_cleanup WHERE id=?", fixture.cleanupId()))
                .isEqualTo("DELETED");
        assertThat(ProjectExportMinioFixture.listObjectKeys(prefix(fixture))).isEmpty();
    }

    /** 等待数据库实际时钟到达保留截止，不信任JVM墙钟判断边界。 */
    private void awaitDatabaseTime(Instant deadline) throws Exception {
        long timeout = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < timeout) {
            if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT clock_timestamp() >= ?", Boolean.class,
                    Timestamp.from(deadline)))) return;
            Thread.sleep(5);
        }
        throw new AssertionError("数据库时钟未到导出保留截止");
    }

    /** 以服务端阻塞图证明下载事务确实等待指定项目锁。 */
    private void awaitBlocked(int waiterPid, int holderPid, Future<?> waiting) throws Exception {
        long timeout = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try (Connection observer = ownerConnection(); PreparedStatement statement = observer.prepareStatement(
                "SELECT ? = ANY(pg_blocking_pids(?))")) {
            statement.setInt(1, holderPid);
            statement.setInt(2, waiterPid);
            while (System.nanoTime() < timeout) {
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next() && result.getBoolean(1)) return;
                }
                if (waiting.isDone()) throw new AssertionError("下载事务未等待项目锁");
                Thread.sleep(5);
            }
        }
        throw new AssertionError("未观察到下载事务项目锁等待");
    }

    /** 通过匿名HTTP读取预签名URL正文；不记录完整bearer地址。 */
    private static byte[] readUrl(URI uri) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(uri.toString()).openConnection();
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(10_000);
        try (var input = connection.getInputStream()) {
            return input.readAllBytes();
        } finally {
            connection.disconnect();
        }
    }

    /** JSON字段集合。 */
    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    /** 读取owner单行字符串。 */
    private String row(String sql, Object... values) throws Exception {
        try (Connection connection = ownerConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new AssertionError("预期数据库行不存在");
                return result.getString(1);
            }
        }
    }

    /** 读取owner单值数字。 */
    private long number(String sql, Object... values) throws Exception {
        return Long.parseLong(row(sql, values));
    }

    /** 读取owner时刻。 */
    private Instant instant(String sql, Object... values) throws Exception {
        try (Connection connection = ownerConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new AssertionError("预期数据库时刻不存在");
                return result.getTimestamp(1).toInstant();
            }
        }
    }

    /** owner执行夹具与测试独占状态回拨。 */
    private void executeOwner(String sql, Object... values) throws Exception {
        try (Connection connection = ownerConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, values);
                statement.execute();
            }
        }
    }

    /** 在已有连接事务中执行SQL，供真实锁竞争持有行锁。 */
    private static void execute(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            statement.execute();
        }
    }

    /** 读取连接服务端PID，阻塞证明不能用线程sleep替代。 */
    private static int backendPid(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_backend_pid()");
             ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getInt(1);
        }
    }

    /** owner连接绕过RLS，仅准备和观察测试事实。 */
    private static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(DATABASE_URL, EXPORT_POSTGRES.getUsername(), EXPORT_POSTGRES.getPassword());
    }

    /** Instant显式绑定timestamptz，其他类型交给PG驱动。 */
    private static void bind(PreparedStatement statement, Object... values) throws SQLException {
        for (int index = 0; index < values.length; index++) {
            Object value = values[index];
            statement.setObject(index + 1, value instanceof Instant instant ? Timestamp.from(instant) : value);
        }
    }

    /** 对象摘要按持久合同使用小写SHA-256。 */
    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    /** 当前夹具的MinIO独占前缀。 */
    private static String prefix(Fixture fixture) {
        return "projects/" + fixture.projectTenantId() + "/" + fixture.projectId() + "/";
    }

    /** 为签名结果使用与internal不同但等价可达的宿主名。 */
    private static String externalEndpoint() {
        URI internal = URI.create(ProjectExportMinioFixture.endpoint());
        String host = "localhost".equals(internal.getHost()) ? "127.0.0.1" : "localhost";
        return internal.getScheme() + "://" + host + ":" + internal.getPort();
    }

    /** 日志就绪不等于宿主JDBC可用；发布属性前验证映射端口、认证和专库身份。 */
    private static String startDatabase() {
        EXPORT_POSTGRES.start();
        // 仅启动探活使用短连接/读超时；不改变Flyway、业务连接或SSL的默认行为。
        try (Connection connection = EXPORT_POSTGRES.createConnection("?connectTimeout=2&socketTimeout=5");
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT current_database()")) {
            if (!result.next() || !DATABASE_NAME.equals(result.getString(1))) {
                throw new IllegalStateException("导出下载专库探活返回了错误的数据库身份");
            }
            return EXPORT_POSTGRES.getJdbcUrl();
        } catch (SQLException failure) {
            // 保留驱动原因链；容器日志用于区分数据库退出与宿主映射端口握手失败。
            try {
                failure.addSuppressed(new IllegalStateException("导出下载专库启动日志：\n" + EXPORT_POSTGRES.getLogs()));
            } catch (RuntimeException diagnosticFailure) {
                failure.addSuppressed(diagnosticFailure);
            }
            throw new IllegalStateException("导出下载专库JDBC探活失败，未启动Flyway", failure);
        }
    }

    /** 下载任务及真实对象身份。 */
    private record Fixture(UUID projectTenantId, UUID callerTenantId, UUID requesterId, UUID projectId,
                           UUID exportId, UUID cleanupId, String objectKey, String sha256, byte[] content,
                           long generation, Instant expiresAt) {
    }

    /** APP/Flyway专库、双端点和后台延迟配置。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class IsolatedDatabaseConfiguration {
        /** 注册独占基础设施属性。 */
        @Bean
        DynamicPropertyRegistrar projectExportDownloadProperties() {
            return registry -> {
                registry.add("spring.datasource.url", () -> DATABASE_URL);
                registry.add("spring.flyway.url", () -> DATABASE_URL);
                registry.add("things-link.storage.internal-endpoint", ProjectExportMinioFixture::endpoint);
                registry.add("things-link.storage.external-endpoint", ProjectExportDownloadLifecycleTests::externalEndpoint);
                registry.add("things-link.storage.access-key", ProjectExportMinioFixture::accessKey);
                registry.add("things-link.storage.secret-key", ProjectExportMinioFixture::secretKey);
                registry.add("things-link.export.worker-fixed-delay-millis", () -> "3600000");
                registry.add("things-link.export.worker-initial-delay-millis", () -> "3600000");
                registry.add("things-link.export.cleanup-fixed-delay-millis", () -> "3600000");
                registry.add("things-link.export.cleanup-initial-delay-millis", () -> "3600000");
                registry.add("things-link.export.expiry-fixed-delay-millis", () -> "3600000");
                registry.add("things-link.export.expiry-initial-delay-millis", () -> "3600000");
                registry.add("things-link.outbox.publisher.enabled", () -> "false");
                registry.add("things-link.notification.retry.enabled", () -> "false");
                registry.add("spring.kafka.listener.auto-startup", () -> "false");
            };
        }
    }

    /** 包装真实MinIO并提供单次签名/删除/删后崩溃故障。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class ControlledObjectStorageConfiguration {
        /** @return 本类优先真实存储包装器 */
        @Bean
        @Primary
        ControlledObjectStorage projectExportDownloadStorage(MinioPrivateObjectStorage delegate) {
            return new ControlledObjectStorage(delegate);
        }
    }

    /** 所有正常路径委托真实适配器，故障只由单次原子开关触发。 */
    static final class ControlledObjectStorage implements PrivateObjectStorage {
        /** 真实MinIO适配器。 */ private final PrivateObjectStorage delegate;
        /** 下一次签名失败。 */ private final AtomicBoolean failPresign = new AtomicBoolean();
        /** 下一次删除前失败。 */ private final AtomicBoolean failDelete = new AtomicBoolean();
        /** 下一次真实删除后模拟进程崩溃。 */ private final AtomicBoolean crashAfterDelete = new AtomicBoolean();
        /** 下一次签名阻塞开关。 */ private final AtomicBoolean blockPresign = new AtomicBoolean();
        /** 已进入签名调用。 */ private volatile CountDownLatch entered = new CountDownLatch(0);
        /** 释放签名调用。 */ private volatile CountDownLatch release = new CountDownLatch(0);
        /** 签名调用次数，不保存URL。 */ private final AtomicInteger presigns = new AtomicInteger();

        /** @param delegate 真实MinIO适配器 */
        ControlledObjectStorage(PrivateObjectStorage delegate) { this.delegate = delegate; }

        /** 恢复每例开关与latch。 */
        void reset() {
            failPresign.set(false);
            failDelete.set(false);
            crashAfterDelete.set(false);
            blockPresign.set(false);
            entered = new CountDownLatch(0);
            release = new CountDownLatch(0);
            presigns.set(0);
        }

        /** 下一次签名失败。 */ void failNextPresign() { failPresign.set(true); }
        /** 下一次删除前失败。 */ void failNextDelete() { failDelete.set(true); }
        /** 下一次删除完成后抛错。 */ void crashAfterNextDelete() { crashAfterDelete.set(true); }
        /** 下一次签名在持久事务内阻塞。 */
        void blockNextPresign() {
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
            blockPresign.set(true);
        }
        /** @return 签名已进入证据 */ CountDownLatch presignEntered() { return entered; }
        /** 释放阻塞签名。 */ void releasePresign() { release.countDown(); }
        /** @return 签名调用次数 */ int presignCount() { return presigns.get(); }

        /** {@inheritDoc} */
        @Override
        public void upload(String bucket, String objectKey, Path source, String contentType,
                           Map<String, String> metadata) {
            delegate.upload(bucket, objectKey, source, contentType, metadata);
        }

        /** {@inheritDoc} */
        @Override
        public void delete(String bucket, String objectKey) {
            if (failDelete.compareAndSet(true, false)) {
                throw new ObjectStorageException("测试删除失败", new IllegalStateException("before delete"));
            }
            delegate.delete(bucket, objectKey);
            if (crashAfterDelete.compareAndSet(true, false)) {
                throw new ObjectStorageException("测试删后崩溃", new IllegalStateException("after delete"));
            }
        }

        /** {@inheritDoc} */
        @Override
        public URI presignGet(String bucket, String objectKey, Duration ttl) {
            presigns.incrementAndGet();
            if (failPresign.compareAndSet(true, false)) {
                throw new ObjectStorageException("测试签名失败", new IllegalStateException("presign"));
            }
            if (blockPresign.compareAndSet(true, false)) {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("签名未释放");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("签名等待被中断", exception);
                }
            }
            return delegate.presignGet(bucket, objectKey, ttl);
        }
    }
}
