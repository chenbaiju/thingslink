package com.things.link.bootstrap.dashboard;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.bootstrap.fixture.WebAppRuntimeFixture;
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
import org.springframework.mock.web.MockHttpSession;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** S12-2c2：完整匿名Security链、真实PG/RLS/Redis与封存凭据事实；不mock授权或伪造Console主体。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PausedSchedulerShutdownTestConfiguration.class)
@OwnedTestContainers({"DATABASE", "REDIS"})
class DashboardShareAnonymousApiTests {
    /** 专库保持匿名封存事实与其他全局清理测试隔离。 */
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("dashboard_share_anonymous").withUsername("thingslink").withPassword("thingslink");
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
    /** 已封存独立token选择器。 */
    private UUID shareId;
    /** 明文仅内存用于实际请求，数据库只保存hash。 */
    private String secret;
    /** 精确定位SHA-256，不记录到日志或响应。 */
    private String hash;

    static { DATABASE.start(); REDIS.start(); }

    /** 使用合法封存事实，不借未交付的D-145 Host/数据资格伪称生产签发成功。 */
    @BeforeEach void seedSealedShare() throws Exception {
        owner = new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(), "thingslink", "thingslink"));
        assertThat(application.queryForObject("SELECT current_user", String.class)).isEqualTo("thingslink_app");
        assertThat(application.queryForObject("SELECT current_database()", String.class)).isEqualTo("dashboard_share_anonymous");
        fixture = WebAppRuntimeFixture.seed(owner);
        shareId = UUID.randomUUID();
        byte[] random = new byte[32];
        new java.security.SecureRandom().nextBytes(random);
        secret = "sh_" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)));
        insertToken(shareId, hash, "HOST_ORIGIN", false);
        seal(shareId);
    }

    /** 两个真实匿名读取成功，Cookie/Session不作身份也不签发Cookie，精确版本和摘要保持完整。 */
    @Test void readsContextAndExactSchemaWithoutCredentialsInResponse() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("untrustedAccount", UUID.randomUUID().toString());
        MvcResult contextResponse = request(get(route("context")).session(session)
                .header("Cookie", "session=untrusted"));
        JsonNode context = json(contextResponse, 200);
        assertThat(context.path("shareId").asString()).isEqualTo(shareId.toString());
        assertThat(context.path("dashboardVersionId").asString()).isEqualTo(fixture.authorized().dashboardVersionId().toString());
        assertThat(context.path("variableScopes")).isEmpty();
        assertThat(context.has("historyAnchorAt")).isTrue();
        JsonNode schema = json(request(get(route("schema"))), 200);
        assertThat(schema.path("dashboardVersionId").asString()).isEqualTo(fixture.authorized().dashboardVersionId().toString());
        assertThat(schema.path("schema").path("schemaVersion").asString()).isEqualTo("tc.dashboard/v1");
        for (String forbidden : List.of(secret, hash, "creatorAccountId", "secretHash", "secret_hash")) {
            assertThat(context.toString()).doesNotContain(forbidden);
            assertThat(schema.toString()).doesNotContain(forbidden);
        }
        assertThat(contextResponse.getResponse().getHeader("Set-Cookie")).isNull();
        assertThat(contextResponse.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
        assertThat(session.getAttribute("untrustedAccount")).isNotNull();
    }

    /** 有效头格式仍必须匹配精确shareId/hash；未封存凭据不能越过定位函数成为capability。 */
    @Test void wrongSelectorHashAndUnsealedTokenAreUniformlyHidden() throws Exception {
        error(request(get("/api/v1/shares/" + UUID.randomUUID() + "/context")), 404, 60053);
        String other = "sh_" + Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        error(raw(get(route("context")).header("X-Share-Token", other).header("Referer", referer())), 404, 60053);
        owner.update("DELETE FROM dash_share_creation_result WHERE share_id=?", shareId);
        error(request(get(route("context"))), 404, 60053);
    }

    /** Authorization不能与capability混用，缺/重复/逗号/不规范尾位token均在独立链拒绝。 */
    @Test void refusesJwtAndMalformedCredentialTransport() throws Exception {
        error(raw(get(route("context")).header("Referer", referer())), 400, 10001);
        for (String value : new String[] {"sh_bad", secret + "," + secret, "sh_" + "A".repeat(42) + "B"}) {
            error(raw(get(route("context")).header("Referer", referer()).header("X-Share-Token", value)), 400, 10001);
        }
        error(request(get(route("context")).header("Authorization", "Bearer ignored-console-jwt")), 400, 10001);
        error(request(get(route("context")).header("X-Share-Token", secret)), 400, 10001);
        error(request(get(route("context")).queryParam("token", secret)), 400, 10001);
    }

    /** 实际Referer必须是唯一绝对URL并精确同scheme/host/有效port，不接受前缀、userinfo或fragment。 */
    @Test void validatesRefererOriginWithoutTrustingCookieOrSourceClaims() throws Exception {
        for (String invalid : new String[] {"null", "https://share.example.test.attacker.invalid/path",
                "http://share.example.test/path", "https://share.example.test:444/path",
                "https://someone@share.example.test/path", "https://share.example.test/path#fragment"}) {
            error(raw(get(route("context")).header("X-Share-Token", secret).header("Referer", invalid)), 403, 60054);
        }
        error(raw(get(route("context")).header("X-Share-Token", secret)), 403, 60054);
        error(request(get(route("context")).header("Referer", referer())), 403, 60054);
        json(raw(get(route("context")).header("X-Share-Token", secret)
                .header("Referer", "https://share.example.test:443/other/page?from=external")), 200);
    }

    /** S12-2c2建立的错误方法/未知路由拒绝保持；2c3增加明确数据POST仍不能让幂等键开放其他方法。 */
    @Test void deniesOtherRoutesAndMethodsBeforeCapabilityRead() throws Exception {
        for (MockHttpServletRequestBuilder call : List.of(post(route("context")),
                post(route("schema")), post(route("devices/catalog")), get(route("unknown")))) {
            error(request(call.header("Idempotency-Key", "same-key")), 403, 60054);
        }
        assertThat(owner.queryForObject("SELECT count(*) FROM sys_idempotency_record WHERE project_id=?",
                Integer.class, fixture.projectId())).isZero();
    }

    /** 项目ARCHIVED保留只读，DELETING拒绝；恢复ACTIVE但代次提高后旧token永久无效。 */
    @Test void archivedReadsButDeletingAndNewProjectGenerationReject() throws Exception {
        owner.update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?", fixture.projectId());
        json(request(get(route("context"))), 200);
        owner.update("UPDATE sys_project SET status='DELETING' WHERE id=?", fixture.projectId());
        error(request(get(route("context"))), 404, 60053);
        owner.update("UPDATE sys_project SET status='ACTIVE',lifecycle_generation=1 WHERE id=?", fixture.projectId());
        error(request(get(route("context"))), 404, 60053);
    }

    /** 看板撤回停读，重新指向另一发布版本后原token仍读取签发时精确旧版本，而非current。 */
    @Test void withdrawAndRepublishRestoreOnlyTheFrozenOldVersion() throws Exception {
        owner.update("UPDATE dash_dashboard SET current_version_id=NULL,publication_revision=2 WHERE id=?",
                fixture.authorized().dashboardId());
        error(request(get(route("schema"))), 404, 60053);
        UUID newer = UUID.randomUUID();
        owner.update("""
                INSERT INTO dash_dashboard_version(id,tenant_id,project_id,dashboard_id,version_number,source_draft_revision,
                    schema,schema_version,schema_digest_algorithm,schema_digest,required_components,required_resources,
                    published_by_account_id,published_at)
                SELECT ?,tenant_id,project_id,dashboard_id,2,1,schema,schema_version,schema_digest_algorithm,schema_digest,
                    required_components,required_resources,published_by_account_id,clock_timestamp()
                  FROM dash_dashboard_version WHERE id=?
                """, newer, fixture.authorized().dashboardVersionId());
        owner.update("UPDATE dash_dashboard SET current_version_id=?,publication_revision=3 WHERE id=?", newer,
                fixture.authorized().dashboardId());
        JsonNode restored = json(request(get(route("schema"))), 200);
        assertThat(restored.path("dashboardVersionId").asString()).isEqualTo(fixture.authorized().dashboardVersionId().toString());
    }

    /** 已撤销与DB已到期都统一不可用，不使用应用缓存TTL放行；过期夹具以合法初始历史建立。 */
    @Test void revokedAndExpiredSharesRemainUnavailable() throws Exception {
        owner.update("UPDATE dash_share_token SET revoked_at=clock_timestamp(),revoked_by=? WHERE id=?", fixture.actorId(), shareId);
        error(request(get(route("context"))), 404, 60053);
        UUID expired = UUID.randomUUID();
        String expiredSecret = randomSecret();
        String expiredHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(expiredSecret.getBytes(StandardCharsets.UTF_8)));
        insertToken(expired, expiredHash, "NONE", true);
        seal(expired);
        assertThat(application.queryForList("SELECT * FROM resolve_dashboard_share_identity(?,?)", expired, expiredHash)).isEmpty();
        error(raw(get("/api/v1/shares/" + expired + "/context").header("X-Share-Token", expiredSecret)), 404, 60053);
    }

    /** NONE仅省Referer附加判断，不能免除精确token身份；签发策略不能在运行中自动降级。 */
    @Test void nonePolicyStillRequiresMatchingCapability() throws Exception {
        UUID none = UUID.randomUUID();
        String noneSecret = randomSecret();
        String noneHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(noneSecret.getBytes(StandardCharsets.UTF_8)));
        insertToken(none, noneHash, "NONE", false);
        seal(none);
        json(raw(get("/api/v1/shares/" + none + "/context").header("X-Share-Token", noneSecret)), 200);
        error(raw(get("/api/v1/shares/" + none + "/context").header("X-Share-Token", secret)), 404, 60053);
    }

    /** 普通APP无scope看不到表，受限函数仅返回命中的两轴且没有PUBLIC执行权或可注入search_path。 */
    @Test void locatorExposesOnlyMatchingScopeWithoutDisablingRls() throws Exception {
        assertThat(application.queryForObject("SELECT count(*) FROM dash_share_token", Integer.class)).isZero();
        var located = application.queryForList("SELECT * FROM resolve_dashboard_share_identity(?,?)", shareId, hash);
        assertThat(located).hasSize(1);
        assertThat(located.getFirst().keySet()).containsExactlyInAnyOrder("tenant_id", "project_id");
        assertThat(located.getFirst().get("tenant_id")).isEqualTo(fixture.tenantId());
        assertThat(application.queryForList("SELECT * FROM resolve_dashboard_share_identity(?,?)", shareId, "0".repeat(64))).isEmpty();
        assertThat(owner.queryForObject("""
                SELECT count(*) FROM pg_proc p,LATERAL aclexplode(p.proacl) a
                 WHERE p.oid='public.resolve_dashboard_share_identity(uuid,character varying)'::regprocedure
                   AND a.grantee=0 AND a.privilege_type='EXECUTE'
                """, Integer.class)).isZero();
        assertThat(owner.queryForObject("SELECT proconfig::text FROM pg_proc WHERE oid="
                + "'public.resolve_dashboard_share_identity(uuid,character varying)'::regprocedure", String.class))
                .contains("search_path=pg_catalog, public");
    }

    /** 未知合法格式凭据只产生固定来源桶，不创建share/project/tenant或以hash为名的Redis键。 */
    @Test void unknownCredentialsOnlyAllocateFixedSourceBuckets() throws Exception {
        Set<String> before = redis.keys("things-link:{dashboard-share}:*");
        error(request(get("/api/v1/shares/" + UUID.randomUUID() + "/context")), 404, 60053);
        Set<String> after = redis.keys("things-link:{dashboard-share}:*");
        after.removeAll(before);
        assertThat(after).allSatisfy(key -> {
            assertThat(key).matches("things-link:\\{dashboard-share}:source:[0-9]{1,4}");
            int bucket = Integer.parseInt(key.substring(key.lastIndexOf(':') + 1));
            assertThat(bucket).isBetween(0, 1023);
            assertThat(key).doesNotContain(secret, hash, shareId.toString());
        });
    }

    /** 已知身份预算结算使用真实UTF8正文长度，而不是把768KiB上限长期记成已发送值。 */
    @Test void redisResponseBudgetSettlesToActualEncodedBytes() throws Exception {
        MvcResult result = request(get(route("schema")));
        json(result, 200);
        String key = "things-link:{dashboard-share}:bytes:values:share:" + shareId;
        assertThat(redis.opsForHash().get(key, "_total")).isEqualTo(Integer.toString(result.getResponse().getContentAsByteArray().length));
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
}
