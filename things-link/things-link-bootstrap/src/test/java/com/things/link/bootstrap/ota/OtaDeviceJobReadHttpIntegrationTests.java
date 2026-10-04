package com.things.link.bootstrap.ota;

import com.things.link.iam.application.AuthenticatedPrincipal;
import com.things.link.iam.application.TokenIssuer;
import com.things.link.ota.api.OtaCampaignResponse;
import com.things.link.ota.api.OtaDeviceJobDetailResponse;
import com.things.link.ota.api.OtaDeviceJobSummaryResponse;
import com.things.link.ota.application.OtaCanonicalJson;
import com.things.link.ota.domain.OtaCampaign;
import com.things.link.ota.domain.OtaCampaignRepository;
import com.things.link.ota.domain.OtaCampaignRuntimeRepository;
import com.things.link.ota.domain.OtaPublication;
import com.things.link.ota.domain.OtaRelease;
import com.things.link.ota.domain.OtaUploadSession;
import com.things.link.ota.infrastructure.persistence.JdbcOtaCampaignRepository;
import com.things.link.ota.infrastructure.persistence.JdbcOtaCampaignRuntimeRepository;
import com.things.link.ota.infrastructure.persistence.JdbcOtaPublicationRepository;
import com.things.link.ota.infrastructure.persistence.JdbcOtaUploadRepository;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.page.Cursor;
import com.things.link.testing.AbstractIntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实PG与真实JWT验证S13-4c-2独立作业读取：游标列表与转移时间线。
 *
 * <p>作业状态不经夹具直接改列产生：先走数据库十五秒租约领取与
 * {@code ota_job_admission}真实准入，再由受限函数{@code ota_job_begin_retry}
 * 在真实"通知投递已耗尽"观察下进入重试等待。这样断言的是生产状态机事实，
 * 而不是测试拼出来的字符串。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = "things-link.ota.retry.runtime-enabled=false")
class OtaDeviceJobReadHttpIntegrationTests extends AbstractIntegrationTest {
    /** 真正随机监听端口。 */ @Value("${local.server.port}") private int port;
    /** JWT仍经过真实验签与数据库角色读取。 */ @Autowired private TokenIssuer tokens;
    /** 响应只作断言。 */ private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 规范计划字节与HTTP创建路径同一口径，活动详情端点才能解码。 */
    private static final OtaCanonicalJson CANONICAL = new OtaCanonicalJson();
    /** 夹具角色各用有界连接池，避免全量运行中每次事务新建宿主 TCP 连接。 */
    private static final HikariDataSource APP_SOURCE = fixtureSource("ota-job-read-app", APP_ROLE, APP_ROLE_PASSWORD);
    private static final HikariDataSource OWNER_SOURCE = fixtureSource("ota-job-read-owner", POSTGRES.getUsername(),
            POSTGRES.getPassword());
    /** 每例自有项目图。 */ private final List<Fixture> fixtures = new ArrayList<>();

    /** 已派发作业按UUIDv7身份倒序分页，只暴露运行白名单字段，不泄露租户、租约或规范字节。 */
    @Test void listsDispatchedJobsWithRealRunFactsAndWithoutInternalColumns() throws Exception {
        Fixture own = ready();
        List<UUID> targets = devices(own, 2);
        OtaCampaign campaign = campaign(own, targets, 2);
        schedule(own, campaign, targets, 2);
        startAndDispatch(own, campaign, 2);

        JsonNode page = ok(send(own, "GET", jobs(own, campaign.id()) + "?limit=100"), 200);
        assertThat(page.path("hasMore").asBoolean()).isFalse();
        assertThat(page.path("nextCursor").isNull()).isTrue();
        JsonNode items = page.path("items");
        assertThat(items.size()).isEqualTo(2);
        List<String> ids = new ArrayList<>();
        for (JsonNode job : items) {
            ids.add(job.path("id").asText());
            assertThat(job.path("batchNumber").asInt()).isEqualTo(1);
            assertThat(job.path("status").asText()).isEqualTo("DISPATCHED");
            assertThat(job.path("attemptNo").asInt()).isGreaterThanOrEqualTo(1);
            assertThat(job.path("credentialVersion").asText()).isEqualTo("1");
            assertThat(job.path("stateVersion").asText()).isEqualTo("1");
            assertThat(job.path("deviceTypeId").asText()).isEqualTo(own.type().toString());
            assertThat(targets).contains(UUID.fromString(job.path("deviceId").asText()));
            assertThat(job.path("thingModelVersionId").isNull()).isTrue();
            assertThat(job.path("failureCode").isNull()).isTrue();
            assertThat(job.path("firstDispatchedAt").isNull()).isFalse();
            assertThat(job.path("dispatchedAt").isNull()).isFalse();
            assertThat(job.path("deadlineAt").isNull()).isFalse();
            assertThat(job.path("nextAttemptAt").isNull()).isFalse();
            assertThat(job.propertyNames()).containsExactlyInAnyOrder("id", "batchNumber", "deviceId", "deviceTypeId",
                    "thingModelVersionId", "credentialVersion", "status", "attemptNo", "stateVersion", "failureCode",
                    "firstDispatchedAt", "dispatchedAt", "deadlineAt", "nextAttemptAt");
        }
        assertThat(ids).isSortedAccordingTo(Comparator.reverseOrder());

        String body = send(own, "GET", jobs(own, campaign.id())).body();
        assertThat(body).doesNotContain("tenantId").doesNotContain("leaseToken").doesNotContain("leaseUntil")
                .doesNotContain("canonical").doesNotContain("reportHash").doesNotContain("reportRevision")
                .doesNotContain("qualifiedCredentialVersion").doesNotContain("planSha256")
                .doesNotContain("manifestSha256").doesNotContain("createdBy").doesNotContain("pauseActorId");
    }

    /** limit=1时两页不重不漏，hasMore与nextCursor与真实位置一致。 */
    @Test void pagesJobsByDescendingIdentityWithoutDuplicates() throws Exception {
        Fixture own = ready();
        List<UUID> targets = devices(own, 2);
        OtaCampaign campaign = campaign(own, targets, 2);
        schedule(own, campaign, targets, 2);
        startAndDispatch(own, campaign, 2);
        List<UUID> persisted = jobIds(own, campaign.id());
        assertThat(persisted).hasSize(2);

        JsonNode first = ok(send(own, "GET", jobs(own, campaign.id()) + "?limit=1"), 200);
        assertThat(first.path("hasMore").asBoolean()).isTrue();
        assertThat(first.path("nextCursor").asText()).isNotBlank();
        assertThat(first.path("items").size()).isEqualTo(1);
        assertThat(UUID.fromString(first.path("items").get(0).path("id").asText())).isEqualTo(persisted.get(0));

        JsonNode second = ok(send(own, "GET",
                jobs(own, campaign.id()) + "?limit=1&cursor=" + first.path("nextCursor").asText()), 200);
        assertThat(second.path("hasMore").asBoolean()).isFalse();
        assertThat(second.path("nextCursor").isNull()).isTrue();
        assertThat(second.path("items").size()).isEqualTo(1);
        assertThat(UUID.fromString(second.path("items").get(0).path("id").asText())).isEqualTo(persisted.get(1));

        List<String> ids = new ArrayList<>();
        StreamSupport.stream(first.path("items").spliterator(), false).forEach(item -> ids.add(item.path("id").asText()));
        StreamSupport.stream(second.path("items").spliterator(), false).forEach(item -> ids.add(item.path("id").asText()));
        assertThat(ids).doesNotHaveDuplicates().hasSize(2);
    }

    /** 详情按toRevision升序返回真实转移，并证明数据库允许的重试状态已被契约枚举收录。 */
    @Test void returnsAscendingTransitionTimelineAndContractCoversRetryStates() throws Exception {
        Fixture own = ready();
        List<UUID> targets = devices(own, 2);
        OtaCampaign campaign = campaign(own, targets, 2);
        schedule(own, campaign, targets, 2);
        assertThat(OtaDeviceJobReadHttpIntegrationTests.<Boolean>runtime(own, r -> r.start(own.project(), campaign.id(), 1, own.account()))).isTrue();
        OtaCampaignRuntimeRepository.Claim retried = claimRuntime();
        assertThat(OtaDeviceJobReadHttpIntegrationTests.<Boolean>runtime(own, r -> r.admit(retried, 1, 1, OtaExecutionReportFixture.HASH, Instant.now()))).isTrue();
        OtaCampaignRuntimeRepository.Claim untouched = claimRuntime();
        assertThat(OtaDeviceJobReadHttpIntegrationTests.<Boolean>runtime(own, r -> r.admit(untouched, 1, 1, OtaExecutionReportFixture.HASH, Instant.now()))).isTrue();

        seedExhaustedNotification(retried.jobId());
        assertThat(OtaDeviceJobReadHttpIntegrationTests.<Boolean>retryRun(own, r -> r.beginRetry(own.tenant(), own.project(), retried.jobId(),
                "DISPATCH_TRANSIENT_FAILURE", "DISPATCH_NOT_ACKNOWLEDGED"))).isTrue();

        JsonNode detail = ok(send(own, "GET", job(own, campaign.id(), retried.jobId())), 200);
        assertThat(detail.path("status").asText()).isEqualTo("RETRY_WAIT");
        assertThat(detail.path("attemptNo").asInt()).isEqualTo(1);
        assertThat(detail.path("failureCode").asText()).isEqualTo("DISPATCH_TRANSIENT_FAILURE");
        assertThat(detail.path("deadlineAt").isNull()).isTrue();
        assertThat(detail.path("dispatchedAt").isNull()).isFalse();
        assertThat(detail.path("firstDispatchedAt").isNull()).isFalse();
        assertThat(detail.path("nextAttemptAt").isNull()).isFalse();
        assertThat(detail.propertyNames()).containsExactlyInAnyOrder("id", "batchNumber", "deviceId", "deviceTypeId",
                "thingModelVersionId", "credentialVersion", "status", "attemptNo", "stateVersion", "failureCode",
                "firstDispatchedAt", "dispatchedAt", "deadlineAt", "nextAttemptAt", "transitions");

        JsonNode transitions = detail.path("transitions");
        assertThat(transitions.size()).isEqualTo(2);
        assertThat(transitions.get(0).path("fromStatus").asText()).isEqualTo("PENDING");
        assertThat(transitions.get(0).path("toStatus").asText()).isEqualTo("DISPATCHED");
        assertThat(transitions.get(0).path("fromRevision").asText()).isEqualTo("0");
        assertThat(transitions.get(0).path("toRevision").asText()).isEqualTo("1");
        assertThat(transitions.get(1).path("fromStatus").asText()).isEqualTo("DISPATCHED");
        assertThat(transitions.get(1).path("toStatus").asText()).isEqualTo("RETRY_WAIT");
        assertThat(transitions.get(1).path("fromRevision").asText()).isEqualTo("1");
        assertThat(transitions.get(1).path("toRevision").asText()).isEqualTo("2");
        assertThat(transitions.get(0).path("reason").isNull()).isTrue();
        assertThat(transitions.get(1).path("reason").asText()).isEqualTo("DISPATCH_NOT_ACKNOWLEDGED");
        for (JsonNode transition : transitions) {
            assertThat(transition.path("actorKind").asText()).isEqualTo("SYSTEM");
            assertThat(transition.path("actorId").isNull()).isTrue();
            assertThat(transition.path("occurredAt").isNull()).isFalse();
            assertThat(transition.propertyNames()).containsExactlyInAnyOrder("fromStatus", "toStatus", "fromRevision",
                    "toRevision", "actorKind", "actorId", "occurredAt", "reason");
        }

        String body = send(own, "GET", job(own, campaign.id(), retried.jobId())).body();
        assertThat(body).doesNotContain("tenantId").doesNotContain("leaseToken").doesNotContain("leaseUntil")
                .doesNotContain("canonical").doesNotContain("reportHash").doesNotContain("qualifiedCredentialVersion");

        // D-151：RETRY_WAIT是数据库允许且真实出现的状态，活动详情必须同样返回它。
        JsonNode embedded = embeddedJob(ok(send(own, "GET", campaigns(own) + "/" + campaign.id()), 200), retried.jobId());
        assertThat(embedded.path("status").asText()).isEqualTo("RETRY_WAIT");

        // D-151契约断言：三处作业状态闭集都必须与数据库约束实时一致，不能比数据库事实更窄。
        Set<String> database = databaseJobStates();
        for (Class<?> type : List.of(OtaCampaignResponse.Job.class, OtaDeviceJobSummaryResponse.class,
                OtaDeviceJobDetailResponse.class)) {
            Schema contract = type.getDeclaredField("status").getAnnotation(Schema.class);
            assertThat(Set.of(contract.allowableValues())).as(type.getSimpleName() + " 作业状态枚举")
                    .isEqualTo(database);
        }
        assertThat(database).contains("RETRY_WAIT", "FAILED", "TIMED_OUT");
    }

    /** OPERATOR与VIEWER都是70035，OWNER两个端点都放行。 */
    @Test void enforcesCampaignForbiddenCodeForNonManagingMembers() throws Exception {
        Fixture own = ready();
        List<UUID> targets = devices(own, 2);
        OtaCampaign campaign = campaign(own, targets, 2);
        schedule(own, campaign, targets, 2);
        startAndDispatch(own, campaign, 2);
        String list = jobs(own, campaign.id());
        String detail = job(own, campaign.id(), jobIds(own, campaign.id()).get(0));
        ok(send(own, "GET", list), 200);
        ok(send(own, "GET", detail), 200);

        for (String role : List.of("OPERATOR", "VIEWER")) {
            owner().update("UPDATE sys_project_member SET role=? WHERE project_id=? AND account_id=?",
                    role, own.project(), own.account());
            error(send(own, "GET", list), 403, 70035);
            error(send(own, "GET", detail), 403, 70035);
        }
        owner().update("UPDATE sys_project_member SET role='OWNER' WHERE project_id=? AND account_id=?",
                own.project(), own.account());
        ok(send(own, "GET", list), 200);
        ok(send(own, "GET", detail), 200);
    }

    /** 未知活动、跨项目活动、未知作业、跨活动作业统一70034，非法游标是10001。 */
    @Test void hidesUnknownCrossProjectAndCrossCampaignIdentity() throws Exception {
        Fixture own = ready(), other = ready();
        List<UUID> firstTargets = devices(own, 2);
        OtaCampaign first = campaign(own, firstTargets, 2);
        schedule(own, first, firstTargets, 2);
        startAndDispatch(own, first, 2);
        List<UUID> secondTargets = devices(own, 1);
        OtaCampaign second = campaign(own, secondTargets, 1);
        schedule(own, second, secondTargets, 1);
        startAndDispatch(own, second, 1);

        UUID foreignCampaign = campaign(other, devices(other, 1), 1, Instant.now().truncatedTo(ChronoUnit.MICROS)).id();
        UUID ownJob = jobIds(own, first.id()).get(0);
        UUID otherCampaignJob = jobIds(own, second.id()).get(0);

        error(send(own, "GET", jobs(own, Uuid7.generate())), 404, 70034);
        error(send(own, "GET", job(own, Uuid7.generate(), ownJob)), 404, 70034);
        error(send(own, "GET", jobs(own, foreignCampaign)), 404, 70034);
        error(send(own, "GET", job(own, foreignCampaign, ownJob)), 404, 70034);
        error(send(own, "GET", job(own, first.id(), Uuid7.generate())), 404, 70034);
        error(send(own, "GET", job(own, first.id(), otherCampaignJob)), 404, 70034);
        error(send(own, "GET", jobs(own, first.id()) + "?cursor=" + Cursor.encode(other.project() + "|" + ownJob)),
                400, 10001);
        error(send(own, "GET", jobs(own, first.id()) + "?cursor=not-a-cursor"), 400, 10001);
        ok(send(own, "GET", jobs(own, first.id())), 200);
        ok(send(own, "GET", job(own, first.id(), ownJob)), 200);
    }

    /** 目标设备真实写入，未绑定模型允许为空，与排程冻结口径一致。 */
    private List<UUID> devices(Fixture f, int count) {
        List<UUID> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            UUID id = Uuid7.generate();
            owner().update("INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name)"
                    + " VALUES(?,?,?,?,?,'作业读取目标')", id, f.tenant(), f.project(), f.type(), "job_" + id);
            result.add(id);
        }
        return result.stream().sorted(Comparator.comparing(UUID::toString)).toList();
    }

    /** 冻结全部目标到单一批次；计划带真实执行策略，重试与阶段期限都由冻结快照决定。 */
    private OtaCampaign campaign(Fixture f, List<UUID> targets, int batchSize) {
        return campaign(f, targets, batchSize, Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    /** 允许显式创建时间，跨项目夹具不需要排程。 */
    private OtaCampaign campaign(Fixture f, List<UUID> targets, int batchSize, Instant createdAt) {
        OtaRelease release = run(f, r -> r.findRelease(f.project(), f.firmware()).orElseThrow());
        // 完整闭集执行策略：活动详情端点会用生产编解码器复核规范计划，缺字段会判为持久快照损坏。
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("maxConcurrentDownloads", 1L);
        policy.put("maxDownloadBytesPerSecond", 1048576L);
        policy.put("downloadRetryLimit", 1L);
        policy.put("retryBackoffSeconds", 1L);
        policy.put("healthWindowSeconds", 60L);
        policy.put("pauseMinEvaluated", 1L);
        policy.put("pauseFailureCount", 1L);
        policy.put("pauseFailureRateBps", 5000L);
        policy.put("batchMinSuccessRateBps", 10000L);
        policy.put("requireManualBatchApproval", false);
        Map<String, Object> stages = new LinkedHashMap<>();
        for (String stage : List.of("DISPATCHED", "DOWNLOADING", "VERIFYING", "INSTALLING", "REBOOTING",
                "HEALTH_CHECKING", "CONFIRMING", "ROLLBACK_PENDING", "ROLLING_BACK")) {
            stages.put(stage, 300L);
        }
        policy.put("stageTimeoutSeconds", stages);
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("contractVersion", "tc-ota-campaign-plan/v1");
        plan.put("firmwareId", f.firmware().toString());
        plan.put("deviceIds", targets.stream().map(UUID::toString).toList());
        plan.put("batchSize", (long) batchSize);
        plan.put("notBefore", "2020-01-01T00:00:00Z");
        plan.put("executionPolicy", policy);
        byte[] canonical = CANONICAL.writeObject(plan);
        OtaCampaign value = new OtaCampaign(Uuid7.generate(), f.tenant(), f.project(), f.firmware(), release.id(),
                f.account(), canonical, hash(canonical), release.canonicalManifest(), hash(release.canonicalManifest()),
                "DRAFT", 0, 0, 0, createdAt, createdAt, null, null, null);
        campaignRun(f, repository -> {
            repository.create(value, hash(value.id().toString().getBytes(StandardCharsets.UTF_8)), value.planSha256());
            return true;
        });
        return value;
    }

    /** 冻结稳定批次与PENDING占位，读取断言的是同一批真实事实。 */
    private void schedule(Fixture f, OtaCampaign campaign, List<UUID> targets, int batchSize) {
        List<OtaCampaignRepository.Target> frozen = targets.stream()
                .map(id -> new OtaCampaignRepository.Target(id, f.type(), null, 1)).toList();
        boolean changed = campaignRun(f, repository -> repository.schedule(0, campaign, frozen, batchSize,
                f.account(), campaign.createdAt().plusSeconds(1)));
        assertThat(changed).isTrue();
    }

    /** 真实启动与逐条领取准入，作业状态完全由数据库租约与准入函数产生。 */
    private void startAndDispatch(Fixture f, OtaCampaign campaign, int count) {
        assertThat(OtaDeviceJobReadHttpIntegrationTests.<Boolean>runtime(f, r -> r.start(f.project(), campaign.id(), 1, f.account()))).isTrue();
        for (int index = 0; index < count; index++) {
            OtaCampaignRuntimeRepository.Claim claim = claimRuntime();
            assertThat(OtaDeviceJobReadHttpIntegrationTests.<Boolean>runtime(f, r -> r.admit(claim, 1, 1, OtaExecutionReportFixture.HASH, Instant.now()))).isTrue();
        }
    }

    /** 夹具准备真实"通知投递已耗尽"观察，复用OtaBusinessRetryIntegrationTests既有夹具口径。 */
    private void seedExhaustedNotification(UUID job) {
        new TransactionTemplate(new DataSourceTransactionManager(OWNER_SOURCE)).execute(status -> {
            JdbcTemplate jdbc = new JdbcTemplate(OWNER_SOURCE);
            jdbc.execute("SET LOCAL session_replication_role=replica");
            int changed = jdbc.update("""
                    UPDATE ota_notification_delivery SET status='EXHAUSTED',revision=revision+1,
                        exhausted_at=clock_timestamp(),updated_at=clock_timestamp(),
                        reason='NOTIFICATION_DELIVERY_EXHAUSTED'
                    WHERE job_id=? AND status='WAITING' AND job_attempt_no=(SELECT attempt_no FROM ota_device_job WHERE id=?)
                    """, job, job);
            if (changed != 1) throw new IllegalStateException("通知交付事实不在WAITING，无法准备耗尽观察");
            return true;
        });
    }

    /** 重试入口仍是生产受限函数，夹具只提供已观察到的暂时失败。 */
    private static <T> T retryRun(Fixture f, Function<JdbcOtaCampaignRuntimeRepository, T> work) {
        return runtime(f, work);
    }

    /** 已持久作业身份，按UUIDv7倒序，与列表接口排序口径一致。 */
    private List<UUID> jobIds(Fixture f, UUID campaign) {
        return owner().queryForList("SELECT id FROM ota_device_job WHERE project_id=? AND campaign_id=?"
                + " ORDER BY id DESC", UUID.class, f.project(), campaign);
    }

    /** 从活动详情响应中按身份取出内嵌作业。 */
    private static JsonNode embeddedJob(JsonNode campaignDetail, UUID job) {
        for (JsonNode item : campaignDetail.path("jobs")) {
            if (job.toString().equals(item.path("id").asText())) { return item; }
        }
        throw new AssertionError("活动详情响应缺少作业 " + job);
    }

    /** 数据库约束里的真实状态闭集，避免测试复制一份会漂移的常量。 */
    private Set<String> databaseJobStates() {
        String definition = owner().queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint"
                + " WHERE conrelid='public.ota_device_job'::regclass"
                + " AND conname='ota_device_job_runtime_values_ck'", String.class);
        Matcher matcher = Pattern.compile("'([A-Z_]+)'").matcher(definition);
        Set<String> states = new TreeSet<>();
        while (matcher.find()) { states.add(matcher.group(1)); }
        assertThat(states).as("数据库作业状态闭集").isNotEmpty();
        return states;
    }

    /** 构造真实完整已采用发布父图，读取路径只消费其持久身份。 */
    private Fixture ready() {
        Fixture f = seed();
        OtaPublication prepared = prepared(f);
        run(f, r -> r.recordSigned(prepared, prepared.leaseToken(), new byte[32], new byte[64], "campaign-read"));
        OtaPublication signed = publication(f, prepared);
        run(f, r -> r.commitRelease(signed, signed.leaseToken(), release(signed)));
        return f;
    }

    /** 正常上传状态机建立固定版本DB证据，不伪造生产验签成功。 */
    private OtaPublication prepared(Fixture f) {
        OtaUploadSession session = create(f);
        UUID token = Uuid7.generate();
        upload(f, r -> r.claimReceive(session, token));
        upload(f, r -> r.markWriting(session, token));
        upload(f, r -> r.recordVersion(session, token, "version-one"));
        upload(f, r -> r.finishVerified(session, token));
        OtaUploadSession verified = current(f, session);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        OtaPublication value = new OtaPublication(Uuid7.generate(), f.tenant(), f.project(), f.firmware(),
                session.id(), f.account(), Uuid7.generate(), 0, 0, verified.revision(),
                ("{\"deviceTypeId\":\"" + f.type() + "\"}").getBytes(StandardCharsets.UTF_8), new byte[]{2},
                "PREPARED", 0, null, null, null, null, null, null, now, now);
        run(f, r -> {
            r.create(value);
            return true;
        });
        OtaPublication claimed = claim();
        assertThat(claimed.id()).isEqualTo(value.id());
        return claimed;
    }

    /** 单条可信后台领取。 */
    private OtaPublication claim() {
        return plain(j -> new JdbcOtaPublicationRepository(j).claimPreparedOrSigned().orElseThrow());
    }

    /** 精确当前回执。 */
    private OtaPublication publication(Fixture f, OtaPublication value) {
        return run(f, r -> r.find(f.project(), f.firmware(), value.id(), false).orElseThrow());
    }

    /** 同一规范证据构造不可变发布。 */
    private OtaRelease release(OtaPublication value) {
        return new OtaRelease(value.id(), value.tenantId(), value.projectId(), value.firmwareId(),
                value.uploadSessionId(), value.id(), value.canonicalManifest(), value.trustSnapshot(),
                value.spki(), value.signature(), value.receipt(), Instant.now());
    }

    /** owner只提供合法项目和固件头，不绕过上传会话业务写入。 */
    private Fixture seed() {
        Fixture f = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                Uuid7.generate(), Uuid7.generate());
        fixtures.add(f);
        JdbcTemplate jdbc = owner();
        jdbc.update("INSERT INTO sys_account(id,email,password_hash,display_name,email_verified_at)"
                + " VALUES (?,?,'{noop}unused','OTA作业读取测试',now())", f.account(), f.account() + "@example.invalid");
        jdbc.update("INSERT INTO sys_tenant(id,name) VALUES (?,'OTA作业读取租户')", f.tenant());
        jdbc.update("INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?,?,?)",
                Uuid7.generate(), f.tenant(), f.account());
        jdbc.update("INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?,?,'OTA作业读取项目','sh-1',?)",
                f.project(), f.tenant(), "otajob_" + f.project().toString().replace("-", ""));
        jdbc.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'OWNER')",
                Uuid7.generate(), f.project(), f.account());
        jdbc.update("""
                INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,device_kind,access_protocol,network_type,status,product_key,product_secret_hash)
                VALUES (?,?,?,?,'OTA作业读取类型','DIRECT','STANDARD','WIFI','PUBLISHED',?,repeat('a',64))
                """, f.type(), f.tenant(), f.project(), "type_" + f.type(), "product_" + f.type());
        jdbc.update("""
                INSERT INTO dev_thing_model_version(id,tenant_id,project_id,device_type_id,version_number,
                    version_major,version_minor,version_patch,change_level,schema_profile,model_snapshot,schema_digest,digest_algorithm)
                VALUES (?,?,?,?,'1.0.0',1,0,0,'MAJOR','TC_PROPERTY_COMPOSITE_V1',
                    '{"properties":{},"events":{},"commands":{}}'::jsonb,?,'PG_JSONB_TEXT_V1_SHA256')
                """, f.model(), f.tenant(), f.project(), f.type(), "a".repeat(64));
        jdbc.update("""
                INSERT INTO ota_firmware(id,tenant_id,project_id,created_by,device_type_id,thing_model_version_id,
                    product_key,firmware_version,schema_digest_algorithm,schema_digest,schema_profile,status,revision,created_at)
                VALUES (?,?,?,?,?,?,'job_product','1','PG_JSONB_TEXT_V1_SHA256',repeat('a',64),
                    'TC_PROPERTY_COMPOSITE_V1','DRAFT',0,now())
                """, f.firmware(), f.tenant(), f.project(), f.account(), f.type(), f.model());
        return f;
    }

    /** 固定远期恢复时间避免本例未领取会话干扰其他测试。 */
    private OtaUploadSession create(Fixture f) {
        UUID id = Uuid7.generate();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        OtaUploadSession session = new OtaUploadSession(id, f.tenant(), f.project(), f.firmware(), f.account(),
                Uuid7.generate(), 0, 1, 0, "a".repeat(64), "ota-test-bucket", "attempt/" + id, "WAITING", null, null,
                id.toString().replace("-", "").repeat(2), "b".repeat(64), now, now.plusSeconds(3600), null, null,
                null, null, null, now.plusSeconds(3600), null);
        upload(f, repo -> {
            repo.create(session);
            return true;
        });
        return session;
    }

    /** 当前修订来自实际持久状态。 */
    private OtaUploadSession current(Fixture f, OtaUploadSession session) {
        return upload(f, repo -> repo.find(f.project(), f.firmware(), session.id(), false).orElseThrow());
    }

    /** 管理集合路径。 */
    private static String campaigns(Fixture f) {
        return "/api/v1/projects/" + f.project() + "/ota/campaigns";
    }

    /** 作业集合路径。 */
    private static String jobs(Fixture f, UUID campaign) {
        return campaigns(f) + "/" + campaign + "/jobs";
    }

    /** 单个作业路径。 */
    private static String job(Fixture f, UUID campaign, UUID job) {
        return jobs(f, campaign) + "/" + job;
    }

    /** 真实HTTP/1.1往返，JWT经过生产验签与数据库角色读取。 */
    private HttpResponse<String> send(Fixture f, String method, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(45))
                .header("Authorization", "Bearer " + tokens.issue(
                        new AuthenticatedPrincipal(f.account(), f.tenant(), f.project())).value())
                .method(method, HttpRequest.BodyPublishers.noBody()).build();
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    /** 明确HTTP状态并保留响应首因。 */
    private static JsonNode ok(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return JSON.readTree(response.body());
    }

    /** HTTP状态和领域码均须匹配。 */
    private static void error(HttpResponse<String> response, int status, int code) {
        assertThat(ok(response, status).path("code").asInt()).isEqualTo(code);
    }

    /** 按实际字节计算摘要。 */
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    /** 真实普通角色活动事务。 */
    private static <T> T campaignRun(Fixture f, Function<JdbcOtaCampaignRepository, T> work) {
        return app(f, jdbc -> work.apply(new JdbcOtaCampaignRepository(jdbc)));
    }

    /** 普通app事务持久发布。 */
    private static <T> T run(Fixture f, Function<JdbcOtaPublicationRepository, T> work) {
        return app(f, jdbc -> work.apply(new JdbcOtaPublicationRepository(jdbc)));
    }

    /** 仓储所有上传动作进入实际事务。 */
    private static <T> T upload(Fixture f, Function<JdbcOtaUploadRepository, T> work) {
        return app(f, jdbc -> work.apply(new JdbcOtaUploadRepository(jdbc)));
    }

    /** 真实普通角色运行事务，作业状态只能经数据库租约与准入函数推进。 */
    private static <T> T runtime(Fixture f, Function<JdbcOtaCampaignRuntimeRepository, T> work) {
        return app(f, jdbc -> {
            OtaExecutionReportFixture.seed(jdbc, f.tenant(), f.project());
            var runtime = new JdbcOtaCampaignRuntimeRepository(jdbc);
            runtime.controlLock(f.tenant(), f.project());
            return work.apply(runtime);
        });
    }

    /** 可信领取不用ThreadLocal账号。 */
    private static OtaCampaignRuntimeRepository.Claim claimRuntime() {
        return plain(jdbc -> new JdbcOtaCampaignRuntimeRepository(jdbc).claimOne().orElseThrow());
    }

    /** 在真实普通连接的原事务中建立项目RLS。 */
    private static <T> T app(Fixture f, Function<JdbcTemplate, T> work) {
        return plain(jdbc -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)", String.class, f.tenant().toString());
            jdbc.queryForObject("SELECT set_config('app.project_id',?,true)", String.class, f.project().toString());
            return work.apply(jdbc);
        });
    }

    /** 无默认scope普通连接。 */
    private static <T> T plain(Function<JdbcTemplate, T> work) {
        var jdbc = new JdbcTemplate(APP_SOURCE);
        return new TransactionTemplate(new DataSourceTransactionManager(APP_SOURCE)).execute(status -> work.apply(jdbc));
    }

    /** owner限于夹具与独立最终观察。 */
    private static JdbcTemplate owner() {
        return new JdbcTemplate(OWNER_SOURCE);
    }

    private static HikariDataSource fixtureSource(String name, String user, String password) {
        // Spring/Flyway 创建普通角色后才首次取连接；此处不得提前启动连接池。
        var source = new HikariDataSource();
        source.setPoolName(name);
        source.setJdbcUrl(POSTGRES.getJdbcUrl());
        source.setUsername(user);
        source.setPassword(password);
        source.setMaximumPoolSize(2);
        source.setMinimumIdle(0);
        return source;
    }

    @AfterAll static void closeFixtureSources() {
        try {
            APP_SOURCE.close();
        } finally {
            OWNER_SOURCE.close();
        }
    }

    /** 子先父后清理本例全部事实。 */
    @AfterEach void clearOwnedFacts() {
        JdbcTemplate owner = owner();
        for (Fixture fixture : fixtures) {
            new TransactionTemplate(new DataSourceTransactionManager(owner.getDataSource())).executeWithoutResult(status -> {
                for (String table : List.of("ota_install_stop_control", "ota_install_stop_report",
                        "ota_install_stop_operation", "ota_job_progress_outbox", "ota_job_progress", "ota_job_expiry",
                        "ota_job_execution_origin", "ota_notification_transport", "ota_notification_delivery",
                        "ota_job_dispatch_outbox", "ota_job_transition", "ota_batch_transition", "ota_campaign_outbox",
                        "ota_campaign_transition", "ota_device_job", "ota_campaign_batch",
                        "ota_campaign_creation_request", "ota_campaign_completion", "ota_campaign_advancement",
                        "ota_campaign")) {
                    owner.update("DELETE FROM " + table + " WHERE project_id=?", fixture.project());
                }
                for (String table : List.of("ota_firmware_release", "ota_firmware_publication",
                        "ota_firmware_upload_session", "ota_firmware_creation_request", "ota_firmware")) {
                    owner.update("DELETE FROM " + table + " WHERE project_id=?", fixture.project());
                }
                owner.update("DELETE FROM ota_device_report WHERE project_id = ?", fixture.project());
                owner.update("DELETE FROM dev_device WHERE project_id = ?", fixture.project());
                owner.update("DELETE FROM dev_thing_model_version WHERE project_id = ?", fixture.project());
                owner.update("DELETE FROM dev_type WHERE project_id = ?", fixture.project());
                owner.update("DELETE FROM sys_project_member WHERE project_id = ?", fixture.project());
                owner.update("DELETE FROM sys_project WHERE id = ?", fixture.project());
                owner.update("DELETE FROM sys_tenant_member WHERE tenant_id = ?", fixture.tenant());
                owner.update("DELETE FROM sys_tenant WHERE id = ?", fixture.tenant());
                owner.update("DELETE FROM sys_account WHERE id = ?", fixture.account());
            });
        }
        fixtures.clear();
    }

    /** 本例独立父事实身份。
     * @param tenant 本例租户
     * @param project 本例项目
     * @param account 被测请求账号
     * @param type 固件类型
     * @param model 不可变模型版本
     * @param firmware 固定固件身份
     */
    private record Fixture(UUID tenant, UUID project, UUID account, UUID type, UUID model, UUID firmware) { }
}
