package com.things.link.bootstrap.dashboard;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.bootstrap.fixture.WebAppDataRuntimeFixture;
import com.things.link.bootstrap.fixture.WebAppDataRuntimeFixture.DataFixture;
import com.things.link.bootstrap.fixture.WebAppRuntimeFixture.Fixture;
import com.things.link.testing.PausedSchedulerShutdownTestConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** S12-2c3五路数据HTTP真实PG/RLS/Redis联合验收；夹具不声明D-145生产Host资格。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PausedSchedulerShutdownTestConfiguration.class)
@OwnedTestContainers({"DATABASE", "REDIS"})
class DashboardShareDataApiTests {
    /** 专库保持匿名封存事实与其他全局清理测试隔离。 */
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("dashboard_share_data").withUsername("thingslink").withPassword("thingslink");
    /** 真实专用Redis验证固定来源桶与已知身份预算，不能接开发实例。 */
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    /** 仅本测试显式可写临时目录，不冒称生产持久挂载资格。 */
    private static final Path LOG_PATH = logDirectory();
    /** 请求间改变真实remoteAddr仅用于隔离不同反例的来源速率。 */
    private static final AtomicInteger SOURCE = new AtomicInteger();
    /** 原始HTTP结构逐字段核验，不能用DTO忽略额外凭据。 */
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 含最外层日志、独立Security链和生产Controller。 */
    @Autowired private MockMvc mvc;
    /** 普通应用角色，不以owner执行匿名业务。 */
    @Autowired private JdbcTemplate application;
    /** 检查真实Redis键，未知capability不能派生身份键。 */
    @Autowired private StringRedisTemplate redis;
    /** Owner仅播种/变更本测试事实。 */
    private JdbcTemplate owner;
    /** 当前精确版本与真实项目。 */
    private Fixture fixture;
    /** 五种源真实Schema及同模型的两台设备。 */
    private DataFixture data;
    /** 已封存独立token选择器。 */
    private UUID shareId;
    /** 明文仅内存用于实际请求，数据库只保存hash。 */
    private String secret;
    /** 精确定位SHA-256，不记录到日志或响应。 */
    private String hash;

    static { DATABASE.start(); REDIS.start(); }

    /** scope先完整写入再封存；所有运行读取均由普通APP数据源执行。 */
    @BeforeEach void seedShareData() throws Exception {
        owner = new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(), "thingslink", "thingslink"));
        assertThat(application.queryForObject("SELECT current_user", String.class)).isEqualTo("thingslink_app");
        assertThat(application.queryForObject("SELECT current_database()", String.class)).isEqualTo("dashboard_share_data");
        data = WebAppDataRuntimeFixture.seed(owner);
        fixture = data.runtime();
        owner.update("UPDATE sys_tenant SET quota_policy_id=(SELECT id FROM sys_quota_policy WHERE code='PLAN_R1_FREE') WHERE id=?", fixture.tenantId());
        newShare();
    }

    /** 五路均穿越真实安全链、独立capability、普通RLS及Redis计量，读到PG非空值/历史/告警。 */
    @Test void readsAllFiveDeclaredSourcesWithRealFactsAndNoAmbientScope() throws Exception {
        Instant at = owner.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant().minusSeconds(5);
        owner.update("""
                INSERT INTO dev_shadow(device_id,tenant_id,project_id,reported,reported_at,reported_model_version)
                VALUES (?,?,?,'{"temperature":23.5}'::jsonb,?::jsonb,?::jsonb)
                """, data.first(), fixture.tenantId(), fixture.projectId(),
                "{\"temperature\":\"" + at + "\"}", "{\"temperature\":\"" + data.model() + "\"}");
        owner.update("""
                INSERT INTO ts_property_point_internal(project_id,device_id,property_key,ts,message_id,
                    data_type,thing_model_version_id,model_version,value_double)
                VALUES (?,?,'temperature',?,?,'NUMBER',?,'1.0.0',23.5)
                """, fixture.projectId(), data.first(), Timestamp.from(at), UUID.randomUUID(), data.model());
        UUID alarmId = alarm(data.first(), at);
        String anchor = json(request(get(route("context"))), 200).path("historyAnchorAt").asString();
        JsonNode snapshot = json(request(post(route("devices/snapshots/query")).content(snapshotBody(data.first()))), 200);
        assertThat(snapshot.path("devices").get(0).path("status").asString()).isEqualTo("AVAILABLE");
        assertThat(snapshot.path("models").get(0).path("properties").get(0).path("propertyKey").asString()).isEqualTo("temperature");
        JsonNode current = json(request(post(route("devices/current-values/query")).content(currentBody(data.first()))), 200);
        assertThat(current.path("devices").get(0).path("values").get(0).path("state").asString()).isEqualTo("VALUE");
        assertThat(current.path("devices").get(0).path("values").get(0).path("value").doubleValue()).isEqualTo(23.5);
        JsonNode catalog = json(request(catalog()), 200);
        assertThat(catalog.path("items")).hasSize(2);
        JsonNode history = json(request(history(anchor)), 200);
        assertThat(history.path("points")).hasSize(1);
        assertThat(history.path("to").asString()).isEqualTo(anchor);
        JsonNode alarms = json(request(post(route("alarms/query")).content(alarmBody(data.first()))), 200);
        assertThat(alarms.path("items").get(0).path("id").asString()).isEqualTo(alarmId.toString());
        assertThat(application.queryForObject("SELECT count(*) FROM dash_share_token", Integer.class)).isZero();
        assertThat(application.queryForObject("SELECT count(*) FROM dev_device_runtime_catalog_v1", Integer.class)).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM sys_idempotency_record WHERE project_id=?",
                Integer.class, fixture.projectId())).isZero();
        assertThat(redis.opsForHash().get("things-link:{dashboard-share}:bytes:values:share:" + shareId, "_total"))
                .isNotNull();
    }

    /** scope之外整请求403；同模型设备属于目录/告警变量也不授予sensor的当前值/元信息绑定。 */
    @Test void refusesOutsideScopeAndSameModelVariableCrossProduct() throws Exception {
        UUID outside = WebAppDataRuntimeFixture.addDevice(owner, fixture, data.type(), data.model(), false, Instant.now());
        for (UUID denied : List.of(outside, data.second())) {
            error(request(post(route("devices/current-values/query")).content(currentBody(denied))), 403, 60054);
            error(request(post(route("devices/snapshots/query")).content(snapshotBody(denied))), 403, 60054);
        }
        error(request(post(route("alarms/query")).content(alarmBody(outside))), 403, 60054);
        error(request(post(route("devices/current-values/query"))
                .content(currentBody(data.first()).replace("temperature", "secret"))), 403, 60054);
    }

    /** 同一个公共幂等键不能保存或恢复capability数据；撤销立即使旧读取404。 */
    @Test void revocationCannotReplayPriorSuccessThroughIdempotencyKey() throws Exception {
        String body = currentBody(data.first());
        json(request(post(route("devices/current-values/query")).content(body).header("Idempotency-Key", "share-read")), 200);
        owner.update("UPDATE dash_share_token SET revoked_at=clock_timestamp(),revoked_by=? WHERE id=?", fixture.actorId(), shareId);
        error(request(post(route("devices/current-values/query")).content(body).header("Idempotency-Key", "share-read")), 404, 60053);
        assertThat(owner.queryForObject("SELECT count(*) FROM sys_idempotency_record WHERE project_id=?",
                Integer.class, fixture.projectId())).isZero();
    }

    /** 历史窗口只由近期DB锚点派生；旧/未来时刻即使设备及Schema均合法也拒绝。 */
    @Test void refusesStaleAndFutureHistoryAnchors() throws Exception {
        Instant now = owner.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
        error(request(history(now.minusSeconds(120).toString())), 400, 10001);
        error(request(history(now.plusSeconds(120).toString())), 400, 10001);
    }

    /** 游标绑定分享身份与变量过滤，完全相同设备范围的另一token也不能重用。 */
    @Test void catalogCursorIsBoundToShareAndExactPageShape() throws Exception {
        JsonNode first = json(request(catalog().queryParam("limit", "1")), 200);
        String cursor = first.path("nextCursor").asString();
        assertThat(first.path("items").get(0).path("deviceId").asString()).isEqualTo(data.first().toString());
        JsonNode second = json(request(catalog().queryParam("limit", "1").queryParam("cursor", cursor)), 200);
        assertThat(second.path("items").get(0).path("deviceId").asString()).isEqualTo(data.second().toString());
        assertThat(second.path("hasMore").asBoolean()).isFalse();
        newShare();
        error(request(catalog().queryParam("limit", "1").queryParam("cursor", cursor)), 400, 10001);
    }

    /** 当前模型改变时当前值返回显式失配，严格历史/告警整请求拒绝而非泄漏旧模型事实。 */
    @Test void modelDriftAndDeletedDevicesNeverLeakStrictReadFacts() throws Exception {
        UUID nextModel = UUID.randomUUID();
        owner.update("""
                INSERT INTO dev_thing_model_version(id,tenant_id,project_id,device_type_id,version_number,
                    version_major,version_minor,version_patch,change_level,schema_profile,model_snapshot,schema_digest,digest_algorithm)
                SELECT ?,tenant_id,project_id,device_type_id,'2.0.0',2,0,0,'MAJOR',schema_profile,model_snapshot,schema_digest,digest_algorithm
                  FROM dev_thing_model_version WHERE id=?
                """, nextModel, data.model());
        owner.update("UPDATE dev_device SET thing_model_version_id=? WHERE id=?", nextModel, data.first());
        String anchor = json(request(get(route("context"))), 200).path("historyAnchorAt").asString();
        JsonNode current = json(request(post(route("devices/current-values/query")).content(currentBody(data.first()))), 200);
        assertThat(current.path("devices").get(0).path("status").asString()).isEqualTo("MODEL_MISMATCH");
        assertThat(current.path("devices").get(0).path("values")).isEmpty();
        error(request(history(anchor)), 400, 10001);
        error(request(post(route("alarms/query")).content(alarmBody(data.first()))), 400, 10001);
        owner.update("UPDATE dev_device SET deleted_at=clock_timestamp() WHERE id=?", data.first());
        error(request(history(anchor)), 404, 60053);
        error(request(post(route("alarms/query")).content(alarmBody(data.first()))), 404, 60053);
    }

    /** 新token拥有相同冻结变量scope，但独立secret/selector保证跨分享游标反例不是错误凭据。 */
    private void newShare() throws Exception {
        shareId = UUID.randomUUID(); secret = randomSecret();
        hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)));
        insertToken(shareId, hash, "HOST_ORIGIN", false);
        scope("sensor", 0, List.of(data.first()));
        scope("sensors", 1, List.of(data.first(), data.second()));
        seal(shareId);
    }

    /** 所有候选先于creation_result封存写入，复合FK保留真实版本及模型归属。 */
    private void scope(String key, int position, List<UUID> devices) {
        owner.update("""
                INSERT INTO dash_share_scope(tenant_id,project_id,dashboard_id,dashboard_version_id,share_id,
                    variable_key,position,thing_model_version_id) VALUES (?,?,?,?,?,?,?,?)
                """, fixture.tenantId(), fixture.projectId(), fixture.authorized().dashboardId(),
                fixture.authorized().dashboardVersionId(), shareId, key, position, data.model());
        for (int index = 0; index < devices.size(); index++) owner.update("""
                INSERT INTO dash_share_scope_device(tenant_id,project_id,dashboard_id,dashboard_version_id,share_id,
                    variable_key,device_id,thing_model_version_id,position) VALUES (?,?,?,?,?,?,?,?,?)
                """, fixture.tenantId(), fixture.projectId(), fixture.authorized().dashboardId(),
                fixture.authorized().dashboardVersionId(), shareId, key, devices.get(index), data.model(), index);
    }

    /** 写入真实规则与激活告警，避免空集合成功掩盖过滤或映射错误。 */
    private UUID alarm(UUID device, Instant at) {
        UUID rule = UUID.randomUUID(); UUID id = UUID.randomUUID();
        owner.update("""
                INSERT INTO alarm_rule(id,tenant_id,project_id,name,alarm_type,originator_id,property_key,
                    trigger_operator,trigger_threshold,clear_operator,clear_threshold,severity)
                VALUES (?,?,?,'分享温度规则','TEMPERATURE',?,'temperature','GT',30,'LT',25,'WARNING')
                """, rule, fixture.tenantId(), fixture.projectId(), device);
        owner.update("""
                INSERT INTO alarm_instance(id,tenant_id,project_id,rule_id,originator_type,originator_id,alarm_type,severity,
                    condition_state,ack_state,first_condition_at,activated_at,last_received_at,last_value,created_at,updated_at)
                VALUES (?,?,?,?,'DEVICE',?,'TEMPERATURE','WARNING','ACTIVE','UNACKNOWLEDGED',?,?,?,31,?,?)
                """, id, fixture.tenantId(), fixture.projectId(), rule, device, Timestamp.from(at), Timestamp.from(at),
                Timestamp.from(at), Timestamp.from(at), Timestamp.from(at));
        return id;
    }

    /** 指定变量而非客户端模型，变量内候选先于分页限制。 */
    private MockHttpServletRequestBuilder catalog() { return get(route("devices/catalog")).queryParam("variableKey", "sensors"); }
    /** UTC近期锚点对应Schema默认的一小时窗口。 */
    private MockHttpServletRequestBuilder history(String anchor) {
        return get(route("devices/" + data.first() + "/properties/temperature/history"))
                .queryParam("expectedModelVersionId", data.model().toString()).queryParam("windowPreset", "LAST_1_HOUR")
                .queryParam("anchorAt", anchor).queryParam("granularity", "RAW").queryParam("aggregation", "AVG");
    }
    /** 元信息完整模型身份与精确请求属性。 */
    private String snapshotBody(UUID device) {
        return "{\"models\":[{\"versionId\":\"" + data.model() + "\",\"digestAlgorithm\":\"PG_JSONB_TEXT_V1_SHA256\","
                + "\"digest\":\"" + data.digest() + "\",\"profile\":\"TC_PROPERTY_COMPOSITE_V1\"}]," + currentBody(device).substring(1);
    }
    /** CURRENT_VALUE只授权sensor变量的temperature。 */
    private String currentBody(UUID device) {
        return "{\"devices\":[{\"deviceId\":\"" + device + "\",\"expectedModelVersionId\":\"" + data.model()
                + "\",\"propertyKeys\":[\"temperature\"]}]}";
    }
    /** 三组过滤完整匹配sensors变量的同一告警绑定。 */
    private String alarmBody(UUID device) {
        return "{\"devices\":[{\"deviceId\":\"" + device + "\",\"expectedModelVersionId\":\"" + data.model()
                + "\"}],\"conditionStates\":[\"ACTIVE\"],\"ackStates\":[\"UNACKNOWLEDGED\"],\"severities\":[\"WARNING\"]}";
    }

    /** 按DB权威时钟播种合法scope为空的静态封存token，不绕过不可变UPDATE触发器。 */
    private void insertToken(UUID id, String tokenHash, String policy, boolean expired) {
        owner.update("""
                INSERT INTO dash_share_token(id,tenant_id,project_id,dashboard_id,dashboard_version_id,project_generation,
                    secret_hash,host_compatibility,referer_policy,created_at,expires_at,creator_account_id)
                VALUES (?,?,?,?,?,0,?,'{"minInclusive":"1.0.0","maxExclusive":"1.0.1"}'::jsonb,?,
                    clock_timestamp()-(? * interval '1 hour'),clock_timestamp()+(? * interval '1 hour'),?)
                """, id, fixture.tenantId(), fixture.projectId(), fixture.authorized().dashboardId(),
                fixture.authorized().dashboardVersionId(), tokenHash, policy, expired ? 2 : 0, expired ? -1 : 1, fixture.actorId());
    }
    /** 模拟已完成同事务签发的封存记录；未知和未封存历史仍不能成为运行身份。 */
    private void seal(UUID id) {
        owner.update("""
                INSERT INTO dash_share_creation_result(tenant_id,project_id,dashboard_id,account_id,idempotency_key_digest,
                    request_digest,share_id,created_at)
                SELECT tenant_id,project_id,dashboard_id,creator_account_id,
                    encode(digest(id::text,'sha256'),'hex'),repeat('b',64),id,created_at FROM dash_share_token WHERE id=?
                """, id);
    }
    /** 每个合法历史凭据保持hash唯一，测试间不能复用零字节secret污染专库唯一约束。 */
    private static String randomSecret() {
        byte[] value = new byte[32];
        new java.security.SecureRandom().nextBytes(value);
        return "sh_" + Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
    /** 精确受管origin，路径不是权限边界。 */
    private static String referer() { return "https://share.example.test/app/share/page"; }
    /** API路径只带公开shareId，不携带secret或hash。 */
    private String route(String suffix) { return "/api/v1/shares/" + shareId + "/" + suffix; }
    /** 凭据仅单值专用头，实际Referer由该测试客户端提供。 */
    private MvcResult request(MockHttpServletRequestBuilder builder) throws Exception {
        return raw(builder.header("X-Share-Token", secret).header("Referer", referer()));
    }
    /** 不写TenantContext；每次请求经最外层Servlet过滤器与独立capability安全链。 */
    private MvcResult raw(MockHttpServletRequestBuilder builder) throws Exception {
        // 真实分享限流为每秒4次，显式间隔避免安全反例被无关429掩盖；不删除预算键。
        Thread.sleep(275);
        int source = SOURCE.incrementAndGet();
        return mvc.perform(builder.contentType(MediaType.APPLICATION_JSON).with(request -> {
            request.setRemoteAddr("198.18." + source / 250 + "." + (source % 250 + 1));
            return request;
        })).andReturn();
    }
    /** 全部结果应无缓存、无凭据Cookie、不继承Console的CORS。 */
    private static JsonNode json(MvcResult result, int status) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(status);
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(result.getResponse().getHeader("Set-Cookie")).isNull();
        assertThat(result.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
        return JSON.readTree(result.getResponse().getContentAsByteArray());
    }
    /** 状态和业务码联合验证，禁止把Redis/SQL异常误判成隐藏404。 */
    private static void error(MvcResult result, int status, int code) throws Exception {
        assertThat(json(result, status).path("code").asInt()).isEqualTo(code);
    }
    /** 显式测试临时目录仅验证logger可写，不代替生产部署挂载证明。 */
    private static Path logDirectory() {
        try { return Files.createTempDirectory("thingslink-share-security-"); }
        catch (java.io.IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    /** 单一早期属性来源，真实PG/Flyway/Redis与安全开关不会被共享基类抢占。 */
    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "thingslink_app");
        registry.add("spring.datasource.password", () -> "thingslink");
        registry.add("spring.flyway.url", DATABASE::getJdbcUrl);
        registry.add("spring.flyway.user", DATABASE::getUsername);
        registry.add("spring.flyway.password", DATABASE::getPassword);
        registry.add("spring.flyway.placeholders.app_role_password", () -> "thingslink");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("things-link.dashboard.share-runtime.enabled", () -> true);
        registry.add("things-link.dashboard.share-runtime.host-origin", () -> "https://share.example.test");
        registry.add("things-link.dashboard.share-runtime.security-log-path", LOG_PATH::toString);
        registry.add("things-link.outbox.publisher.enabled", () -> false);
        registry.add("spring.kafka.listener.auto-startup", () -> false);
        registry.add("things-link.notification.retry.enabled", () -> false);
    }
    /** 已授权匿名分享仍受项目真实套餐窗口约束，缺投影不是空曲线。 */
    @Test void missingHistoryPlanPreserves503Contract() throws Exception {
        String anchor=json(request(get(route("context"))),200).path("historyAnchorAt").asString();
        owner.update("UPDATE sys_tenant SET quota_policy_id=(SELECT id FROM sys_quota_policy WHERE code='FREE') WHERE id=?",fixture.tenantId());
        error(request(history(anchor)),503,50048);
    }

}
