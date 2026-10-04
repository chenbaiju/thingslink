package com.things.link.bootstrap.ota;

import com.things.link.iam.application.AuthenticatedPrincipal;
import com.things.link.iam.application.TokenIssuer;
import com.things.link.ota.api.OtaDeviceJobSummaryResponse;
import com.things.link.ota.application.OtaCanonicalJson;
import com.things.link.ota.domain.OtaCampaign;
import com.things.link.ota.domain.OtaCampaignRepository;
import com.things.link.ota.domain.OtaPublication;
import com.things.link.ota.domain.OtaRelease;
import com.things.link.ota.domain.OtaUploadSession;
import com.things.link.ota.infrastructure.persistence.JdbcOtaCampaignRepository;
import com.things.link.ota.infrastructure.persistence.JdbcOtaPublicationRepository;
import com.things.link.ota.infrastructure.persistence.JdbcOtaUploadRepository;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.page.Cursor;
import com.things.link.testing.AbstractIntegrationTest;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * S13-4e-6：设备维度OTA作业只读集合端点（D-152的⑤）。
 *
 * <p>真实PG、真实JWT与完整HTTP往返，不触达对象存储与Broker：作业事实由真实
 * 「活动创建→排程→未派发取消」状态机产生，取消释放设备预占位后同一设备才能留下多条历史，
 * 这样断言的是生产状态机事实而不是测试拼出来的行。
 *
 * <p>这里显式覆盖「空页不是404」「父设备不存在/跨项目一律设备域404」与「游标跨设备复用400」
 * 三条容易混淆的边界，并与活动维度作业端点保持同一摘要白名单。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OtaDeviceJobCollectionReadHttpIntegrationTests extends AbstractIntegrationTest {
    /** 真正随机监听端口。 */ @Value("${local.server.port}") private int port;
    /** JWT仍经过真实验签与数据库角色读取。 */ @Autowired private TokenIssuer tokens;
    /** 响应只作断言。 */ private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 规范计划字节只用于构造真实活动父图，不进入本端点响应。 */
    private static final OtaCanonicalJson CANONICAL = new OtaCanonicalJson();
    /** 每例自有项目图。 */ private final List<Fixture> fixtures = new ArrayList<>();

    /** 同一设备多条作业按UUIDv7身份倒序分页，跨页不重不漏，且只返回本项目本设备的白名单字段。 */
    @Test void listsDeviceJobsNewestFirstAndPaginatesAcrossMoreThanOnePage() throws Exception {
        Fixture own = ready(), other = ready();
        UUID device = devices(own, 1).get(0);
        UUID foreignDevice = devices(other, 1).get(0);
        Instant base = Instant.now().truncatedTo(ChronoUnit.MICROS).minusSeconds(300);
        List<UUID> created = new ArrayList<>();
        // 前两条必须经未派发取消释放设备预占位，第三条保留PENDING，覆盖同一设备的真实状态混合。
        created.add(jobForDevice(own, device, base, 1, true));
        created.add(jobForDevice(own, device, base, 2, true));
        created.add(jobForDevice(own, device, base, 3, false));
        jobForDevice(other, foreignDevice, base, 4, false);

        JsonNode first = ok(send(own, "GET", jobs(own, device) + "?limit=2"), 200);
        assertThat(first.path("hasMore").asBoolean()).isTrue();
        assertThat(first.path("nextCursor").asText()).isNotBlank();
        assertThat(ids(first)).containsExactly(created.get(2), created.get(1));
        assertThat(first.path("items").get(0).propertyNames()).containsExactlyInAnyOrder("id", "batchNumber",
                "deviceId", "deviceTypeId", "thingModelVersionId", "credentialVersion", "status", "attemptNo",
                "stateVersion", "failureCode", "firstDispatchedAt", "dispatchedAt", "deadlineAt", "nextAttemptAt");
        assertThat(first.path("items").get(0).path("deviceId").asText()).isEqualTo(device.toString());
        assertThat(first.path("items").get(0).path("status").asText()).isEqualTo("PENDING");
        assertThat(first.path("items").get(1).path("status").asText()).isEqualTo("CANCELLED");
        assertThat(first.path("items").get(0).path("credentialVersion").asText()).isEqualTo("1");

        JsonNode second = ok(send(own, "GET",
                jobs(own, device) + "?limit=2&cursor=" + first.path("nextCursor").asText()), 200);
        assertThat(ids(second)).containsExactly(created.get(0)).doesNotContainAnyElementsOf(ids(first));
        assertThat(second.path("hasMore").asBoolean()).isFalse();
        assertThat(second.path("nextCursor").isNull()).isTrue();

        String body = send(own, "GET", jobs(own, device)).body();
        assertThat(body).doesNotContain("tenantId").doesNotContain("leaseToken").doesNotContain("leaseUntil")
                .doesNotContain("canonical").doesNotContain("reportHash").doesNotContain("planSha256")
                .doesNotContain("manifestSha256").doesNotContain("createdBy");
    }

    /** 设备真实存在但没有作业时是200空页，而不是404。 */
    @Test void returnsEmptyPageForDeviceWithoutJobs() throws Exception {
        Fixture own = ready();
        UUID device = devices(own, 1).get(0);
        JsonNode page = ok(send(own, "GET", jobs(own, device)), 200);
        assertThat(page.path("items").isArray()).isTrue();
        assertThat(page.path("items").size()).isZero();
        assertThat(page.path("hasMore").asBoolean()).isFalse();
        assertThat(page.path("nextCursor").isNull()).isTrue();
    }

    /** 不存在与本项目无关的设备都按设备域统一404隐藏，不泄露跨项目存在性。 */
    @Test void hidesNonexistentAndForeignDeviceAsNotFound() throws Exception {
        Fixture own = ready(), other = ready();
        UUID foreignDevice = devices(other, 1).get(0);
        jobForDevice(other, foreignDevice, Instant.now().truncatedTo(ChronoUnit.MICROS), 1, false);
        error(send(own, "GET", jobs(own, Uuid7.generate())), 404, 30020);
        error(send(own, "GET", jobs(own, foreignDevice)), 404, 30020);
    }

    /** 未认证401；不属于该项目的调用方（因而没有ota:read）按项目不存在404拒绝；成员与相邻读端点同权。 */
    @Test void enforcesAuthenticationAndReadScope() throws Exception {
        Fixture own = ready(), other = ready();
        UUID device = devices(own, 1).get(0);
        jobForDevice(own, device, Instant.now().truncatedTo(ChronoUnit.MICROS), 1, false);
        assertThat(anonymous("GET", jobs(own, device)).statusCode()).isEqualTo(401);
        error(send(other, "GET", jobs(own, device)), 404, 50001);
        // 现有OtaAuthorization.requireRead把读取绑定到项目成员资格；VIEWER成员与设备资格、
        // 固件历史等相邻读端点一样可读，本片不改变该边界（见D-164）。
        owner().update("UPDATE sys_project_member SET role='VIEWER' WHERE project_id=? AND account_id=?",
                own.project(), own.account());
        ok(send(own, "GET", jobs(own, device)), 200);
    }

    /** 非法游标、跨设备游标与越界limit都是参数错误，不落成500或泄露其他设备位置。 */
    @Test void rejectsMalformedCrossDeviceCursorAndOutOfRangeLimit() throws Exception {
        Fixture own = ready();
        List<UUID> two = devices(own, 2);
        UUID first = jobForDevice(own, two.get(0), Instant.now().truncatedTo(ChronoUnit.MICROS), 1, false);
        jobForDevice(own, two.get(1), Instant.now().truncatedTo(ChronoUnit.MICROS), 1, false);
        error(send(own, "GET", jobs(own, two.get(0)) + "?cursor=%E8%BF%99%E4%B8%8D%E6%98%AF%E6%B8%B8%E6%A0%87"),
                400, 10001);
        error(send(own, "GET", jobs(own, two.get(0)) + "?limit=0"), 400, 10001);
        error(send(own, "GET", jobs(own, two.get(1)) + "?cursor=" + Cursor.encode(own.project() + "|"
                + two.get(0) + "|" + first)), 400, 10001);
    }

    /** 作业摘要白名单与活动维度作业端点复用同一DTO，状态闭集仍与数据库约束实时一致。 */
    @Test void reusesExistingSummaryWhitelistAndStateClosedSet() throws Exception {
        Set<String> database = databaseJobStates();
        Schema contract = OtaDeviceJobSummaryResponse.class.getDeclaredField("status").getAnnotation(Schema.class);
        assertThat(Set.of(contract.allowableValues())).as("设备作业状态枚举").isEqualTo(database);
        assertThat(database).contains("RETRY_WAIT", "FAILED", "TIMED_OUT", "PENDING", "CANCELLED");
    }

    /** 每例自有项目图身份。 */
    private record Fixture(UUID tenant, UUID project, UUID account, UUID type, UUID model, UUID firmware) { }

    /** 设备维度作业集合路径。 */
    private static String jobs(Fixture f, UUID device) {
        return "/api/v1/projects/" + f.project() + "/ota/devices/" + device + "/jobs";
    }

    /** 响应条目身份按响应顺序。 */
    private static List<UUID> ids(JsonNode page) {
        return java.util.stream.StreamSupport.stream(page.path("items").spliterator(), false)
                .map(item -> UUID.fromString(item.path("id").asText())).toList();
    }

    /**
     * 为同一设备建立一条真实作业：活动创建→排程，需要释放预占位时走未派发取消。
     *
     * <p>设备行按 {@code OtaDeviceJobReadHttpIntegrationTests} 既有夹具口径由owner直接登记：
     * 设备注册要求已发布类型、凭据配额与真实设备API，与本片只读端点无关；作业状态本身仍
     * 完全由生产活动/排程/取消状态机产生。
     *
     * @param f 本例项目图
     * @param device 目标设备
     * @param base 本例时间基准
     * @param index 序号，用于错开活动创建时间
     * @param cancel 是否随后未派发取消以释放设备预占位
     * @return 真实持久作业身份
     */
    private UUID jobForDevice(Fixture f, UUID device, Instant base, int index, boolean cancel) {
        Instant at = base.plusSeconds(index * 60L);
        OtaCampaign campaign = campaign(f, List.of(device), 1, at);
        assertThat(OtaDeviceJobCollectionReadHttpIntegrationTests.<Boolean>campaignRun(f, repository ->
                repository.schedule(0, campaign,
                List.of(new OtaCampaignRepository.Target(device, f.type(), null, 1)), 1, f.account(),
                campaign.createdAt().plusSeconds(1)))).isTrue();
        UUID job = owner().queryForObject("SELECT id FROM ota_device_job WHERE project_id=? AND campaign_id=?"
                + " AND device_id=?", UUID.class, f.project(), campaign.id(), device);
        if (cancel) {
            assertThat(OtaDeviceJobCollectionReadHttpIntegrationTests.<Boolean>campaignRun(f, repository ->
                    repository.cancelUndispatched(1, campaign, f.account(),
                    campaign.createdAt().plusSeconds(2), "设备作业集合读取测试释放预占位"))).isTrue();
            assertThat(owner().queryForObject("SELECT status FROM ota_device_job WHERE id=?", String.class, job))
                    .isEqualTo("CANCELLED");
        }
        return job;
    }

    /** 目标设备真实写入，未绑定模型允许为空，与排程冻结口径一致。 */
    private List<UUID> devices(Fixture f, int count) {
        List<UUID> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            UUID id = Uuid7.generate();
            owner().update("INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name)"
                    + " VALUES(?,?,?,?,?,'设备作业集合读取目标')", id, f.tenant(), f.project(), f.type(), "dev_" + id);
            result.add(id);
        }
        return result;
    }

    /** 冻结全部目标到单一批次；计划带真实执行策略，快照口径与活动维度读取测试一致。 */
    private OtaCampaign campaign(Fixture f, List<UUID> targets, int batchSize, Instant createdAt) {
        OtaRelease release = run(f, r -> r.findRelease(f.project(), f.firmware()).orElseThrow());
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
        run(f, r -> r.recordSigned(prepared, prepared.leaseToken(), new byte[32], new byte[64], "device-jobs-read"));
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
                + " VALUES (?,?,'{noop}unused','OTA设备作业集合测试',now())", f.account(), f.account() + "@example.invalid");
        jdbc.update("INSERT INTO sys_tenant(id,name) VALUES (?,'OTA设备作业集合租户')", f.tenant());
        jdbc.update("INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?,?,?)",
                Uuid7.generate(), f.tenant(), f.account());
        jdbc.update("INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?,?,'OTA设备作业集合项目','sh-1',?)",
                f.project(), f.tenant(), "otadevjob_" + f.project().toString().replace("-", ""));
        jdbc.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'OWNER')",
                Uuid7.generate(), f.project(), f.account());
        jdbc.update("""
                INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,device_kind,access_protocol,network_type,status,product_key,product_secret_hash)
                VALUES (?,?,?,?,'OTA设备作业集合类型','DIRECT','STANDARD','WIFI','PUBLISHED',?,repeat('a',64))
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
                VALUES (?,?,?,?,?,?,'device_jobs_product','1','PG_JSONB_TEXT_V1_SHA256',repeat('a',64),
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

    /** 真实HTTP/1.1往返，JWT经过生产验签与数据库角色读取。 */
    private HttpResponse<String> send(Fixture f, String method, String path) throws Exception {
        return exchange(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(45))
                .header("Authorization", "Bearer " + tokens.issue(
                        new AuthenticatedPrincipal(f.account(), f.tenant(), f.project())).value())
                .method(method, HttpRequest.BodyPublishers.noBody()));
    }

    /** 无凭据往返。 */
    private HttpResponse<String> anonymous(String method, String path) throws Exception {
        return exchange(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(45))
                .method(method, HttpRequest.BodyPublishers.noBody()));
    }

    /** 复用真实HTTP/1.1客户端。 */
    private static HttpResponse<String> exchange(HttpRequest.Builder builder) throws Exception {
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
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
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), APP_ROLE, APP_ROLE_PASSWORD);
        var jdbc = new JdbcTemplate(source);
        return new TransactionTemplate(new DataSourceTransactionManager(source)).execute(status -> work.apply(jdbc));
    }

    /** owner限于夹具与独立最终观察。 */
    private static JdbcTemplate owner() {
        return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
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
}
