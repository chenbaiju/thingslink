package com.things.link.bootstrap.ota;

import com.things.link.iam.application.AuthenticatedPrincipal;
import com.things.link.iam.application.TokenIssuer;
import com.things.link.ota.domain.OtaCampaign;
import com.things.link.ota.domain.OtaCampaignRepository;
import com.things.link.ota.domain.OtaPublication;
import com.things.link.ota.domain.OtaRelease;
import com.things.link.ota.domain.OtaUploadSession;
import com.things.link.ota.infrastructure.persistence.JdbcOtaCampaignRepository;
import com.things.link.ota.infrastructure.persistence.JdbcOtaPublicationRepository;
import com.things.link.ota.infrastructure.persistence.JdbcOtaUploadRepository;
import com.things.link.shared.id.Uuid7;
import com.things.link.testing.AbstractIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
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

/** 真实PG与真实JWT验证S13-4b只读活动列表和冻结批次，不触达对象存储。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OtaCampaignReadHttpIntegrationTests extends AbstractIntegrationTest {
    /** 真正随机监听端口。 */ @Value("${local.server.port}") private int port;
    /** JWT仍经过真实验签与数据库角色读取。 */ @Autowired private TokenIssuer tokens;
    /** 响应只作断言。 */ private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 每例自有项目图；两个夹具足以证明跨项目不可见。 */ private final List<Fixture> fixtures = new ArrayList<>();

    /** 列表只返回本项目活动，最新在前，游标续页不重不漏且不泄露其他项目事实。 */
    @Test void listsOnlyOwnProjectCampaignsNewestFirstAndContinuesWithOpaqueCursor() throws Exception {
        Fixture own = ready(), other = ready();
        Instant base = Instant.now().truncatedTo(ChronoUnit.MICROS).minusSeconds(300);
        List<UUID> ownDevices = devices(own, 1);
        UUID oldest = campaign(own, ownDevices, 1, base).id();
        UUID middle = campaign(own, ownDevices, 1, base.plusSeconds(60)).id();
        UUID newest = campaign(own, ownDevices, 1, base.plusSeconds(120)).id();
        UUID foreign = campaign(other, devices(other, 1), 1, base.plusSeconds(180)).id();

        JsonNode first = ok(send(own, "GET", campaigns(own) + "?limit=2"), 200);
        assertThat(first.path("hasMore").asBoolean()).isTrue();
        assertThat(first.path("nextCursor").asText()).isNotBlank();
        assertThat(campaignIds(first)).containsExactly(newest, middle).doesNotContain(foreign);
        assertThat(first.path("items").get(0).propertyNames()).containsExactlyInAnyOrder("id", "firmwareId",
                "status", "stateVersion", "targetCount", "batchCount", "createdAt", "updatedAt", "scheduledAt",
                "cancelledAt");

        JsonNode second = ok(send(own, "GET", campaigns(own) + "?limit=2&cursor=" + first.path("nextCursor").asText()), 200);
        assertThat(campaignIds(second)).containsExactly(oldest).doesNotContain(foreign).doesNotContainAnyElementsOf(campaignIds(first));
        assertThat(second.path("hasMore").asBoolean()).isFalse();
        assertThat(second.path("nextCursor").isNull()).isTrue();
    }

    /** 管理读取沿用OTA活动FORBIDDEN：OPERATOR与VIEWER都是70035，OWNER成功。 */
    @Test void enforcesCampaignForbiddenCodeForNonManagingMembers() throws Exception {
        Fixture own = ready();
        UUID id = campaign(own, devices(own, 1), 1, Instant.now().truncatedTo(ChronoUnit.MICROS)).id();
        ok(send(own, "GET", campaigns(own)), 200);
        ok(send(own, "GET", batches(own, id)), 200);
        for (String role : List.of("OPERATOR", "VIEWER")) {
            owner().update("UPDATE sys_project_member SET role=? WHERE project_id=? AND account_id=?",
                    role, own.project(), own.account());
            error(send(own, "GET", campaigns(own)), 403, 70035);
            error(send(own, "GET", batches(own, id)), 403, 70035);
        }
        owner().update("UPDATE sys_project_member SET role='OWNER' WHERE project_id=? AND account_id=?",
                own.project(), own.account());
        ok(send(own, "GET", campaigns(own)), 200);
        ok(send(own, "GET", batches(own, id)), 200);
    }

    /** 批次按批次号升序返回冻结事实，未知与跨项目身份都是70034。 */
    @Test void returnsFrozenBatchesInBatchOrderAndHidesCrossProjectIdentity() throws Exception {
        Fixture own = ready(), other = ready();
        List<UUID> targets = devices(own, 3);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        OtaCampaign scheduled = campaign(own, targets, 2, now);
        schedule(own, scheduled, targets, 2);

        JsonNode page = ok(send(own, "GET", batches(own, scheduled.id())), 200);
        assertThat(page.isArray()).isTrue();
        assertThat(page.size()).isEqualTo(2);
        assertThat(page.get(0).path("batchNumber").asInt()).isEqualTo(1);
        assertThat(page.get(0).path("status").asText()).isEqualTo("PENDING");
        assertThat(page.get(0).path("targetCount").asInt()).isEqualTo(2);
        assertThat(page.get(1).path("batchNumber").asInt()).isEqualTo(2);
        assertThat(page.get(1).path("targetCount").asInt()).isEqualTo(1);
        for (JsonNode batch : page) {
            assertThat(batch.path("succeededCount").asLong()).isZero();
            assertThat(batch.path("rolledBackCount").asLong()).isZero();
            assertThat(batch.path("skippedCount").asLong()).isZero();
            assertThat(batch.path("timedOutCount").asInt(-1)).isZero();
            assertThat(batch.path("cancelledCount").asLong()).isZero();
            assertThat(batch.path("completedAt").isNull()).isTrue();
            assertThat(batch.path("outcome").isNull()).isTrue();
            assertThat(batch.propertyNames()).containsExactlyInAnyOrder("batchNumber", "status", "targetCount",
                    "succeededCount", "rolledBackCount", "skippedCount", "timedOutCount", "cancelledCount", "completedAt", "outcome");
        }

        UUID foreign = campaign(other, devices(other, 1), 1, now).id();
        error(send(own, "GET", batches(own, Uuid7.generate())), 404, 70034);
        error(send(own, "GET", batches(own, foreign)), 404, 70034);
    }

    /** 新读取投影不序列化规范计划、清单摘要或发布身份。 */
    @Test void neverExposesCanonicalPlanManifestOrReleaseIdentity() throws Exception {
        Fixture own = ready();
        List<UUID> targets = devices(own, 2);
        OtaCampaign scheduled = campaign(own, targets, 1, Instant.now().truncatedTo(ChronoUnit.MICROS));
        schedule(own, scheduled, targets, 1);

        String listBody = send(own, "GET", campaigns(own)).body();
        assertThat(listBody).doesNotContain("canonicalPlan").doesNotContain("planSha256")
                .doesNotContain("canonicalManifest").doesNotContain("manifestSha256").doesNotContain("releaseId")
                .doesNotContain("createdBy").doesNotContain("tenantId").doesNotContain("cancellationReason");
        JsonNode summary = ok(send(own, "GET", campaigns(own)), 200).path("items").get(0);
        assertThat(summary.has("plan")).isFalse();

        String batchesBody = send(own, "GET", batches(own, scheduled.id())).body();
        assertThat(batchesBody).doesNotContain("canonicalPlan").doesNotContain("canonicalManifest")
                .doesNotContain("deviceId").doesNotContain("leaseToken");
    }

    /** 稳定规范文本排序目标，返回真实设备身份。 */
    private List<UUID> devices(Fixture f, int count) {
        List<UUID> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            UUID id = Uuid7.generate();
            owner().update("INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name)"
                    + " VALUES(?,?,?,?,?,'活动读取目标')", id, f.tenant(), f.project(), f.type(), "read_" + id);
            result.add(id);
        }
        return result.stream().sorted(java.util.Comparator.comparing(UUID::toString)).toList();
    }

    /** 直接写活动头以精确控制创建时间，读取投影不依赖创建路径。 */
    private OtaCampaign campaign(Fixture f, List<UUID> targets, int batchSize, Instant createdAt) {
        OtaRelease release = run(f, r -> r.findRelease(f.project(), f.firmware()).orElseThrow());
        String ids = targets.stream().map(id -> "\"" + id + "\"").collect(java.util.stream.Collectors.joining(","));
        byte[] plan = ("{\"batchSize\":" + batchSize + ",\"deviceIds\":[" + ids
                + "],\"firmwareId\":\"" + f.firmware() + "\"}").getBytes(StandardCharsets.UTF_8);
        OtaCampaign value = new OtaCampaign(Uuid7.generate(), f.tenant(), f.project(), f.firmware(), release.id(),
                f.account(), plan, hash(plan), release.canonicalManifest(), hash(release.canonicalManifest()),
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
        Instant occurredAt = campaign.createdAt().plusSeconds(1);
        boolean changed = campaignRun(f, repository -> repository.schedule(0, campaign, frozen, batchSize,
                f.account(), occurredAt));
        assertThat(changed).isTrue();
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
                + " VALUES (?,?,'{noop}unused','OTA读取测试',now())", f.account(), f.account() + "@example.invalid");
        jdbc.update("INSERT INTO sys_tenant(id,name) VALUES (?,'OTA读取租户')", f.tenant());
        jdbc.update("INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?,?,?)",
                Uuid7.generate(), f.tenant(), f.account());
        jdbc.update("INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?,?,'OTA读取项目','sh-1',?)",
                f.project(), f.tenant(), "otaread_" + f.project().toString().replace("-", ""));
        jdbc.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'OWNER')",
                Uuid7.generate(), f.project(), f.account());
        jdbc.update("""
                INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,device_kind,access_protocol,network_type,status,product_key,product_secret_hash)
                VALUES (?,?,?,?,'OTA读取类型','DIRECT','STANDARD','WIFI','PUBLISHED',?,repeat('a',64))
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
                VALUES (?,?,?,?,?,?,'read_product','1','PG_JSONB_TEXT_V1_SHA256',repeat('a',64),
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

    /** 摘要页身份按响应顺序。 */
    private static List<UUID> campaignIds(JsonNode page) {
        return java.util.stream.StreamSupport.stream(page.path("items").spliterator(), false)
                .map(item -> UUID.fromString(item.path("id").asText())).toList();
    }

    /** 管理集合路径。 */
    private static String campaigns(Fixture f) {
        return "/api/v1/projects/" + f.project() + "/ota/campaigns";
    }

    /** 批次集合路径。 */
    private static String batches(Fixture f, UUID campaign) {
        return campaigns(f) + "/" + campaign + "/batches";
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
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
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
                for (String table : List.of("ota_campaign_outbox", "ota_campaign_transition", "ota_job_dispatch_outbox",
                        "ota_job_transition", "ota_batch_transition", "ota_device_job", "ota_campaign_batch",
                        "ota_campaign_creation_request", "ota_campaign_completion", "ota_campaign_advancement",
                        "ota_campaign")) {
                    owner.update("DELETE FROM " + table + " WHERE project_id=?", fixture.project());
                }
                for (String table : List.of("ota_firmware_release", "ota_firmware_publication",
                        "ota_firmware_upload_session", "ota_firmware_creation_request", "ota_firmware")) {
                    owner.update("DELETE FROM " + table + " WHERE project_id=?", fixture.project());
                }
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
