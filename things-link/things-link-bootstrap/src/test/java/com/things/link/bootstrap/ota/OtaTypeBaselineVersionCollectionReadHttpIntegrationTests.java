package com.things.link.bootstrap.ota;

import com.things.link.iam.application.AuthenticatedPrincipal;
import com.things.link.iam.application.TokenIssuer;
import com.things.link.ota.api.OtaTypeBaselineVersionResponse;
import com.things.link.ota.application.OtaCanonicalJson;
import com.things.link.ota.application.OtaTypeBaselineService;
import com.things.link.ota.application.OtaTypeBaselineSource;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.page.Cursor;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.tenant.ScopedTenantWork;
import com.things.link.testing.AbstractIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * S13-4e-7：OTA类型基线版本历史只读集合端点（D-152的④）。
 *
 * <p>真实PG、真实JWT与完整HTTP往返，不触达对象存储与Broker。历史行<b>经真实登记路径</b>产生：
 * 首版走真实HTTP登记入口{@code POST .../baseline/registrations}（正文只有{@code expectedRevision}，
 * 能力只从启动配置接受）；更高版本由真实{@link OtaTypeBaselineService#register}搭配本测试提供的
 * 受控配置源写入——这与既有{@code OtaTypeBaselineHttpIntegrationTests}的「新进程换新配置」做法
 * 完全一致，覆盖了真实编码器、真实仓储、真实数据库守卫与真实审计，而不是测试拼行。
 *
 * <p>本片的核心安全断言：历史集合走{@code ota:read}（任意项目成员），因此响应必须<b>只</b>暴露
 * 版本、规范摘要与登记时刻，绝不能把只由启动配置接受的能力配置（rootFingerprint、productKey、
 * trustDomain、签名Profile、RAM/Flash上限、AB槽、压缩/差分、属性Profile、evidenceReference）
 * 或规范字节{@code canonical}带给成员。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OtaTypeBaselineVersionCollectionReadHttpIntegrationTests extends AbstractIntegrationTest {
    /** 启动前固定独占范围，能力只能来自配置而不是请求正文。 */
    private static final Fixture CONFIGURED = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
            Uuid7.generate(), Uuid7.generate());
    /** 真正随机监听端口。 */ @Value("${local.server.port}") private int port;
    /** JWT仍经过真实验签与数据库角色读取。 */ @Autowired private TokenIssuer tokens;
    /** 注册表与守卫都走真实业务事务。 */ @Autowired private com.things.link.ota.domain.OtaTypeBaselineRepository repository;
    /** 设备域公开投影端口，OTA不直读dev_type。 */
    @Autowired private com.things.link.device.application.OtaDeviceTypeIdentityPort types;
    /** 正式项目授权与租户解析。 */ @Autowired private com.things.link.project.application.ProjectService projects;
    /** 登记要求项目持续许可。 */ @Autowired private com.things.link.project.application.ProjectLifecycleAccessService lifecycle;
    /** 真实审计INSERT，与登记同事务。 */ @Autowired private com.things.link.support.audit.AuditLogService audit;
    /** 真实业务事务边界。 */ @Autowired private PlatformTransactionManager transactions;
    /** 受限规范JSON仅用于构建公开测试配置。 */ private static final OtaCanonicalJson CANONICAL = new OtaCanonicalJson();
    /** 真实响应解码器。 */ private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 能力配置字段名闭集：成员可读的历史响应里出现任何一个都算泄露。 */
    private static final List<String> CAPABILITY_AND_IDENTITY_KEYS = List.of("canonical", "rootFingerprint",
            "productKey", "trustDomain", "hardware", "signatureProfiles", "maximumArtifactBytes",
            "availableRamBytes", "availableFlashBytes", "supportsAbSlots", "supportsRangeDownload",
            "supportsResumeDownload", "protectedSecurityCounterBits", "compressionAlgorithms", "deltaModes",
            "propertyProfile", "evidenceReference", "tenantId", "projectId", "deviceTypeId", "revision",
            "bootloader");
    /** 每例自有项目图。 */ private final List<Fixture> fixtures = new ArrayList<>();

    /** 启动前把受控配置固定到测试范围，请求正文无法替换能力。 */
    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("things-link.ota.type-baselines-json",
                () -> sourceJson(CONFIGURED, CONFIGURED.type(), 1, 1024));
    }

    /** 同类型多条历史按登记时刻与版本倒序分页，跨页不重不漏，摘要与真实规范字节一致。 */
    @Test void listsVersionsNewestFirstAndPaginatesAcrossMoreThanOnePage() throws Exception {
        Fixture f = seed(CONFIGURED);
        assertThat(ok(send(f, "GET", versions(f, f.type())), 200).path("items").size()).isZero();
        registerFirstThroughHttp(f);
        register(f, f.type(), 2);
        register(f, f.type(), 3);

        JsonNode first = ok(send(f, "GET", versions(f, f.type()) + "?limit=2"), 200);
        assertThat(first.path("hasMore").asBoolean()).isTrue();
        assertThat(first.path("nextCursor").asText()).isNotBlank();
        assertThat(versions(first)).containsExactly(3L, 2L);
        assertThat(first.path("items").get(0).path("baselineHash").asText()).isEqualTo(hashOf(f, 3, 1024 + 3));
        assertThat(first.path("items").get(1).path("baselineHash").asText()).isEqualTo(hashOf(f, 2, 1024 + 2));

        JsonNode second = ok(send(f, "GET",
                versions(f, f.type()) + "?limit=2&cursor=" + first.path("nextCursor").asText()), 200);
        assertThat(versions(second)).containsExactly(1L);
        assertThat(second.path("hasMore").asBoolean()).isFalse();
        assertThat(second.path("nextCursor").isNull()).isTrue();
        assertThat(second.path("items").get(0).path("baselineHash").asText()).isEqualTo(hashOf(f, 1, 1024));

        // 登记时刻按倒序单调：时间并列时由版本号给出确定性顺序（两者都不允许回退）。
        // 这里比较解析后的Instant而不是原文字符串：ISO文本的秒/毫秒后缀在字典序下会错序。
        List<java.time.Instant> times = new ArrayList<>();
        times.add(java.time.Instant.parse(first.path("items").get(0).path("registeredAt").asText()));
        times.add(java.time.Instant.parse(first.path("items").get(1).path("registeredAt").asText()));
        times.add(java.time.Instant.parse(second.path("items").get(0).path("registeredAt").asText()));
        assertThat(times).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    /** 字段白名单只有三项，显式断言能力配置与规范字节都没有随成员可读的历史集合泄露。 */
    @Test void exposesOnlyVersionHashAndTimeAndNeverCapabilityConfiguration() throws Exception {
        Fixture f = seed(CONFIGURED);
        registerFirstThroughHttp(f);
        register(f, f.type(), 2);
        String body = send(f, "GET", versions(f, f.type())).body();
        JsonNode page = JSON.readTree(body);
        assertThat(page.path("items").get(0).propertyNames())
                .containsExactlyInAnyOrder("baselineVersion", "baselineHash", "registeredAt");
        for (String key : CAPABILITY_AND_IDENTITY_KEYS) {
            assertThat(body).as("历史响应不得含受控配置字段 %s", key).doesNotContain(key);
        }
        // 白名单同时钉在DTO上：将来新增字段必须先改这条断言，不能悄悄放宽成员可见面。
        assertThat(OtaTypeBaselineVersionResponse.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("baselineVersion", "baselineHash", "registeredAt");
        // 同一路径族的管理读取仍能看到完整受控声明，本片只收窄历史集合，不改变管理读取边界。
        JsonNode managed = ok(send(f, "GET",
                "/api/v1/projects/" + f.project() + "/ota/device-types/" + f.type() + "/baseline"), 200);
        assertThat(managed.path("baseline").path("rootFingerprint").asText()).isEqualTo("a".repeat(64));
    }

    /** 类型真实存在但从未登记过基线时是200空页，而不是404。 */
    @Test void returnsEmptyPageForPublishedTypeWithoutRegisteredBaseline() throws Exception {
        Fixture f = seed(CONFIGURED);
        JsonNode page = ok(send(f, "GET", versions(f, f.type())), 200);
        assertThat(page.path("items").isArray()).isTrue();
        assertThat(page.path("items").size()).isZero();
        assertThat(page.path("hasMore").asBoolean()).isFalse();
        assertThat(page.path("nextCursor").isNull()).isTrue();
    }

    /** 基线经真实登记产生，独占项目归档后仍能读取公开历史，不授予管理写资格。 */
    @Test void archivedProjectRetainsPublicBaselineHistoryRead() throws Exception {
        Fixture own = seed(CONFIGURED);
        registerFirstThroughHttp(own);
        owner().update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?", own.project());
        assertThat(versions(ok(send(own, "GET", versions(own, own.type())), 200))).containsExactly(1L);
    }

    /** 不存在与本项目无关的设备类型都按OTA父级不可见统一404/70031隐藏，不泄露跨项目存在性。 */
    @Test void hidesUnknownAndForeignDeviceTypeAsNotFound() throws Exception {
        Fixture own = seed(CONFIGURED);
        Fixture other = seed(random());
        registerFirstThroughHttp(own);
        register(other, other.type(), 1);
        error(send(own, "GET", versions(own, Uuid7.generate())), 404, 70031);
        error(send(own, "GET", versions(own, other.type())), 404, 70031);
        // 类型被软删除后与不存在同权，避免历史读取成为已删除类型的探测面。
        owner().update("UPDATE dev_type SET deleted_at=now() WHERE id=?", own.type());
        error(send(own, "GET", versions(own, own.type())), 404, 70031);
    }

    /** 未认证401；不属于该项目的调用方（因而没有ota:read）按项目不存在404拒绝；成员与相邻读端点同权。 */
    @Test void enforcesAuthenticationAndReadScope() throws Exception {
        Fixture own = seed(CONFIGURED);
        Fixture other = seed(random());
        registerFirstThroughHttp(own);
        assertThat(anonymous("GET", versions(own, own.type())).statusCode()).isEqualTo(401);
        error(send(other, "GET", versions(own, own.type())), 404, 50001);
        // ota:read是成员制：VIEWER与设备作业、固件历史等相邻读端点一样可读，本片不改变该边界（见D-164）。
        owner().update("UPDATE sys_project_member SET role='VIEWER' WHERE project_id=? AND account_id=?",
                own.project(), own.account());
        assertThat(versions(ok(send(own, "GET", versions(own, own.type())), 200))).containsExactly(1L);
    }

    /** 非法游标、跨类型/跨项目游标与越界limit都是参数错误，不落成500或泄露其他类型的位置。 */
    @Test void rejectsMalformedCrossParentCursorAndOutOfRangeLimit() throws Exception {
        Fixture own = seed(CONFIGURED);
        Fixture other = seed(random());
        registerFirstThroughHttp(own);
        register(own, own.type(), 2);
        // 同项目内的第二个已发布类型：即便父类型存在，绑定到其他类型的游标也必须被拒。
        UUID sibling = type(own, "sibling");
        error(send(own, "GET", versions(own, own.type())
                + "?cursor=%E8%BF%99%E4%B8%8D%E6%98%AF%E6%B8%B8%E6%A0%87"), 400, 10001);
        error(send(own, "GET", versions(own, own.type()) + "?cursor=" + Cursor.encode("a|b|c")), 400, 10001);
        error(send(own, "GET", versions(own, sibling) + "?cursor="
                + Cursor.encode(own.project() + "|" + own.type() + "|2026-01-01T00:00:00Z|1")), 400, 10001);
        error(send(own, "GET", versions(own, own.type()) + "?cursor="
                + Cursor.encode(other.project() + "|" + own.type() + "|2026-01-01T00:00:00Z|1")), 400, 10001);
        error(send(own, "GET", versions(own, own.type()) + "?limit=0"), 400, 10001);
        error(send(own, "GET", versions(own, own.type()) + "?limit=101"), 400, 10001);
    }

    /** 历史集合路径。 */
    private static String versions(Fixture f, UUID type) {
        return "/api/v1/projects/" + f.project() + "/ota/device-types/" + type + "/baseline/versions";
    }

    /** 响应条目版本号按响应顺序。 */
    private static List<Long> versions(JsonNode page) {
        return StreamSupport.stream(page.path("items").spliterator(), false)
                .map(item -> item.path("baselineVersion").asLong()).toList();
    }

    /** 真实HTTP登记入口写首版：正文只有expectedRevision，能力无法注入。 */
    private void registerFirstThroughHttp(Fixture f) throws Exception {
        byte[] body = CANONICAL.writeObject(Map.of("expectedRevision", "0"));
        HttpResponse<String> response = exchange(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port
                                + "/api/v1/projects/" + f.project() + "/ota/device-types/" + f.type() + "/baseline/registrations"))
                .timeout(Duration.ofSeconds(45))
                .header("Authorization", "Bearer " + tokens.issue(
                        new AuthenticatedPrincipal(f.account(), f.tenant(), f.project())).value())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }

    /**
     * 真实{@link OtaTypeBaselineService#register}写入更高版本。
     *
     * <p>这里<b>没有</b>原始SQL：登记要求精确租户、管理角色、ACTIVE项目许可、权威已发布类型
     * （经设备域端口）、范围锁、修订CAS与同事务审计，任何一步缺失都会让测试失败而不是留半条历史。
     * 更高版本必须换一份受控配置（{@code baselineVersion}严格递增），等价于新进程启动时的配置，
     * 与{@code OtaTypeBaselineHttpIntegrationTests}已验证的做法一致。
     */
    private void register(Fixture f, UUID type, long version) {
        var service = new OtaTypeBaselineService(repository,
                new OtaTypeBaselineSource(sourceJson(f, type, version, 1024 + version)),
                types, projects, lifecycle, audit);
        transactional(f, () -> service.register(f.project(), type, UUID.randomUUID().toString(),
                Long.toString(version - 1)));
        assertThat(owner().queryForObject("SELECT count(*) FROM ota_type_baseline_version"
                + " WHERE project_id=? AND device_type_id=? AND baseline_version=?", Long.class,
                f.project(), type, version)).isEqualTo(1);
    }

    /** 真实scope内执行,登记内部端口要求MANDATORY事务与当前租户上下文。 */
    private <T> T transactional(Fixture f, java.util.function.Supplier<T> work) {
        return ScopedTenantWork.call(new TenantScope(f.tenant(), f.project(), f.account()),
                () -> new TransactionTemplate(transactions).execute(status -> work.get()));
    }

    /** 完整公开能力声明；只有从配置进入，绝不从HTTP正文。 */
    private static Map<String, Object> baseline(Fixture f, UUID type, long version, long ram) {
        var b = new LinkedHashMap<String, Object>();
        b.put("contractVersion", "tc-ota-type-baseline/v1");
        b.put("tenantId", f.tenant().toString()); b.put("projectId", f.project().toString());
        b.put("deviceTypeId", type.toString()); b.put("productKey", "product_" + type);
        b.put("baselineVersion", version); b.put("trustDomain", "baseline.test"); b.put("rootFingerprint", "a".repeat(64));
        b.put("hardware", Map.of("model", "board-v1", "boardRevisionMin", 0L, "boardRevisionMax", 1L));
        b.put("bootloader", Map.of("minimumVersion", "1.0.0", "maximumVersion", "2.0.0"));
        b.put("signatureProfiles", List.of("TC_OTA_ED25519_V1")); b.put("maximumArtifactBytes", 67108864L);
        b.put("availableRamBytes", ram); b.put("availableFlashBytes", 67108864L);
        b.put("supportsAbSlots", true); b.put("supportsRangeDownload", true); b.put("supportsResumeDownload", true);
        b.put("protectedSecurityCounterBits", 4L); b.put("compressionAlgorithms", List.of("NONE"));
        b.put("deltaModes", List.of("NONE")); b.put("propertyProfile", "TC_PROPERTY_COMPOSITE_V1");
        b.put("evidenceReference", "test-fixture-only");
        return b;
    }

    /** 每个登记服务实例获得独立不可变配置字符串。 */
    private static String sourceJson(Fixture f, UUID type, long version, long ram) {
        return new String(CANONICAL.writeObject(Map.of("baselines", List.of(baseline(f, type, version, ram)))),
                StandardCharsets.UTF_8);
    }

    /** 与配置源同源的规范摘要，用于核对响应没有替换或截断哈希。 */
    private static String hashOf(Fixture f, long version, long ram) {
        byte[] canonical = CANONICAL.writeObject(baseline(f, f.type(), version, ram));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    /** 每例自有身份图。 */
    private record Fixture(UUID tenant, UUID project, UUID account, UUID type, UUID model) { }

    /** 固定已知配置范围的一例。 */
    private static Fixture random() {
        return new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate());
    }

    /** 在既有项目内追加一个已发布类型，用于跨类型游标。 */
    private UUID type(Fixture f, String label) {
        UUID id = Uuid7.generate();
        owner().update("""
                INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,device_kind,access_protocol,network_type,status,product_key,product_secret_hash)
                VALUES (?,?,?,?,'OTA基线历史同级类型','DIRECT','STANDARD','WIFI','PUBLISHED',?,repeat('a',64))
                """, id, f.tenant(), f.project(), label + "_" + id, "product_" + id);
        return id;
    }

    /** 身份骨架与已发布类型/不可变模型；owner只准备夹具，基线事实全部经登记路径产生。 */
    private Fixture seed(Fixture fixture) {
        fixtures.add(fixture);
        JdbcTemplate jdbc = owner();
        jdbc.update("INSERT INTO sys_account(id,email,password_hash,display_name,email_verified_at)"
                + " VALUES (?,?,'{noop}unused','OTA基线历史测试',now())", fixture.account(), fixture.account() + "@example.invalid");
        jdbc.update("INSERT INTO sys_tenant(id,name) VALUES (?,'OTA基线历史租户')", fixture.tenant());
        jdbc.update("INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?,?,?)",
                Uuid7.generate(), fixture.tenant(), fixture.account());
        jdbc.update("INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?,?,'OTA基线历史项目','sh-1',?)",
                fixture.project(), fixture.tenant(), "otabasever_" + fixture.project().toString().replace("-", ""));
        jdbc.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'OWNER')",
                Uuid7.generate(), fixture.project(), fixture.account());
        jdbc.update("""
                INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,device_kind,access_protocol,network_type,status,product_key,product_secret_hash)
                VALUES (?,?,?,?,'OTA基线历史类型','DIRECT','STANDARD','WIFI','PUBLISHED',?,repeat('a',64))
                """, fixture.type(), fixture.tenant(), fixture.project(), "type_" + fixture.type(), "product_" + fixture.type());
        jdbc.update("""
                INSERT INTO dev_thing_model_version(id,tenant_id,project_id,device_type_id,version_number,
                    version_major,version_minor,version_patch,change_level,schema_profile,model_snapshot,schema_digest,digest_algorithm)
                VALUES (?,?,?,?,'1.0.0',1,0,0,'MAJOR','TC_PROPERTY_COMPOSITE_V1',
                    '{"properties":{},"events":{},"commands":{}}'::jsonb,?,'PG_JSONB_TEXT_V1_SHA256')
                """, fixture.model(), fixture.tenant(), fixture.project(), fixture.type(), "a".repeat(64));
        return fixture;
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

    /** owner连接只用于夹具准备、最终观察与清理，不走生产读取断言。 */
    private static JdbcTemplate owner() {
        return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
    }

    /** 子先父后清理本例全部事实；基线历史必须在头之前删除。 */
    @AfterEach void clearOwnedFacts() {
        JdbcTemplate owner = owner();
        for (Fixture fixture : fixtures) {
            new TransactionTemplate(new DataSourceTransactionManager(owner.getDataSource())).executeWithoutResult(status -> {
                owner.update("DELETE FROM ota_type_baseline_version WHERE project_id = ?", fixture.project());
                owner.update("DELETE FROM ota_type_baseline WHERE project_id = ?", fixture.project());
            });
            owner.update("DELETE FROM dev_thing_model_version WHERE project_id = ?", fixture.project());
            owner.update("DELETE FROM dev_type WHERE project_id = ?", fixture.project());
            owner.update("DELETE FROM sys_project_member WHERE project_id = ?", fixture.project());
            owner.update("DELETE FROM sys_project WHERE id = ?", fixture.project());
            owner.update("DELETE FROM sys_tenant_member WHERE tenant_id = ?", fixture.tenant());
            owner.update("DELETE FROM sys_tenant WHERE id = ?", fixture.tenant());
            owner.update("DELETE FROM sys_account WHERE id = ?", fixture.account());
        }
        fixtures.clear();
    }
}
