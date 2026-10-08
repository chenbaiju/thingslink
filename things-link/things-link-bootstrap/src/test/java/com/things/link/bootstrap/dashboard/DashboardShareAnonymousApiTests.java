package com.things.link.bootstrap.dashboard;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.bootstrap.fixture.WebAppRuntimeFixture;
import com.things.link.bootstrap.fixture.WebAppRuntimeFixture.Fixture;
import com.things.link.enduser.application.AppAuthenticatedPrincipal;
import com.things.link.enduser.application.AppTokenIssuer;
import com.things.link.iam.application.AuthenticatedPrincipal;
import com.things.link.iam.application.TokenIssuer;
import com.things.link.testing.PausedSchedulerShutdownTestConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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
    /** 删除走真实Console管理身份，已有运行观察走独立App签名身份。 */
    @Autowired private TokenIssuer consoleTokens;
    @Autowired private AppTokenIssuer appTokens;
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

    /**
     * 真实管理POST删除可被应用引用的已发布看板，旧分享与App精确Schema立即失效。
     * 前置沿用既有封存版本和分享夹具，不认领生产发布或签发宿主资格；删除本身不插deleted_at。
     */
    @Test void managementSoftDeleteClosesExistingShareAndExactAppSchemaButPreservesHistoricalFacts() throws Exception {
        owner.update("UPDATE sys_account SET email_verified_at=clock_timestamp() WHERE id=?", fixture.actorId());
        owner.update("INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?,?,?)",
                UUID.randomUUID(), fixture.tenantId(), fixture.actorId());
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'OWNER')",
                UUID.randomUUID(), fixture.projectId(), fixture.actorId());
        owner.update("""
                INSERT INTO dash_dashboard_draft(dashboard_id,tenant_id,project_id,content,revision,updated_by)
                SELECT dashboard_id,tenant_id,project_id,schema,0,published_by_account_id
                  FROM dash_dashboard_version WHERE id=?
                """, fixture.authorized().dashboardVersionId());
        WebAppRuntimeFixture.grant(owner, fixture, fixture.entry());
        String consoleBearer = "Bearer " + consoleTokens.issue(new AuthenticatedPrincipal(
                fixture.actorId(), fixture.tenantId(), fixture.projectId())).value();
        String appBearer = "Bearer " + appTokens.issue(new AppAuthenticatedPrincipal(
                fixture.tenantId(), fixture.projectId(), fixture.appUserId(), 0)).value();
        String schemaPath = "/api/v1/app/applications/" + fixture.appKey() + "/versions/"
                + fixture.applicationVersionId() + "/dashboards/" + fixture.authorized().dashboardVersionId() + "/schema";
        String grantPath = "/api/v1/projects/" + fixture.projectId() + "/end-users/" + fixture.appUserId()
                + "/dashboard-grants/" + fixture.authorized().dashboardId();
        String deletePath = "/api/v1/projects/" + fixture.projectId() + "/dashboards/"
                + fixture.authorized().dashboardId() + "/soft-delete";
        json(request(get(route("context"))), 200);
        json(request(get(route("schema"))), 200);
        json(raw(get(schemaPath).queryParam("expectedPublicationRevision", "1")
                .header(HttpHeaders.AUTHORIZATION, appBearer)), 200);
        JsonNode originalGrant = json(raw(get(grantPath).header(HttpHeaders.AUTHORIZATION, consoleBearer)), 200);
        assertThat(originalGrant.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(owner.queryForObject("SELECT revoked_at IS NULL AND expires_at>clock_timestamp() FROM dash_share_token WHERE id=?",
                Boolean.class, shareId)).isTrue();
        Map<String, Object> retained = retainedDashboardFacts();
        assertThat((List<?>) retained.get("versions")).hasSize(1);
        assertThat((List<?>) retained.get("draft")).hasSize(1);
        assertThat((List<?>) retained.get("references")).hasSize(2);
        assertThat((List<?>) retained.get("share")).hasSize(1);
        assertThat((List<?>) retained.get("grant")).hasSize(2);
        String body = "{\"expectedPublicationRevision\":\"1\"}";
        String key = "propagation-delete-" + fixture.authorized().dashboardId();
        MvcResult deleted = raw(post(deletePath).header(HttpHeaders.AUTHORIZATION, consoleBearer)
                .header("Idempotency-Key", key).content(body));
        assertThat(deleted.getResponse().getStatus()).as(deleted.getResponse().getContentAsString()).isEqualTo(204);
        assertThat(deleted.getResponse().getContentAsByteArray()).isEmpty();
        assertThat(deleted.getResponse().getHeader("Location")).isNull();
        assertThat(deleted.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(owner.queryForObject("SELECT publication_revision FROM dash_dashboard WHERE id=?", Long.class,
                fixture.authorized().dashboardId())).isEqualTo(2);
        assertThat(owner.queryForObject("SELECT current_version_id IS NULL AND deleted_at IS NOT NULL FROM dash_dashboard WHERE id=?",
                Boolean.class, fixture.authorized().dashboardId())).isTrue();
        error(request(get(route("context"))), 404, 60053);
        error(request(get(route("schema"))), 404, 60053);
        error(raw(get(schemaPath).queryParam("expectedPublicationRevision", "1")
                .header(HttpHeaders.AUTHORIZATION, appBearer)), 404, 60023);
        JsonNode current = json(raw(get("/api/v1/app/applications/" + fixture.appKey() + "/current")
                .header(HttpHeaders.AUTHORIZATION, appBearer)), 200);
        assertThat(current.path("dashboards")).hasSize(1);
        assertThat(current.path("dashboards").get(0).path("dashboardVersionId").asString())
                .isEqualTo(fixture.entry().dashboardVersionId().toString());
        assertThat(json(raw(get(grantPath).header(HttpHeaders.AUTHORIZATION, consoleBearer)), 200)).isEqualTo(originalGrant);
        error(raw(put(grantPath).header(HttpHeaders.AUTHORIZATION, consoleBearer)
                .content("{\"expectedRevision\":\"1\",\"status\":\"REVOKED\"}")), 404, 60025);
        error(raw(post(deletePath).header(HttpHeaders.AUTHORIZATION, consoleBearer)
                .header("Idempotency-Key", key).content(body)), 409, 10014);
        error(raw(post(deletePath).header(HttpHeaders.AUTHORIZATION, consoleBearer)
                .header("Idempotency-Key", key).content("{\"expectedPublicationRevision\":\"2\"}")), 409, 10009);
        assertThat(retainedDashboardFacts()).isEqualTo(retained);
        assertThat(owner.queryForObject("""
                SELECT count(*) FROM sys_audit_log
                 WHERE target_type='dashboard' AND target_id=? AND action='dashboard.deleted'
                """, Long.class, fixture.authorized().dashboardId())).isOne();
    }

    /**
     * 真实管理POST软删应用后，原App运行入口全部停读，引用看板和独立分享继续可读。
     * 预置版本、引用和分享沿用封存夹具，不认领真实发布或签发宿主资格；删除不插deleted_at。
     */
    @Test void managementApplicationSoftDeleteClosesAppRuntimeButKeepsDashboardShareAndHistoricalFacts() throws Exception {
        owner.update("UPDATE sys_account SET email_verified_at=clock_timestamp() WHERE id=?", fixture.actorId());
        owner.update("INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?,?,?)",
                UUID.randomUUID(), fixture.tenantId(), fixture.actorId());
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'OWNER')",
                UUID.randomUUID(), fixture.projectId(), fixture.actorId());
        owner.update("""
                INSERT INTO dash_dashboard_draft(dashboard_id,tenant_id,project_id,content,revision,updated_by)
                SELECT dashboard_id,tenant_id,project_id,schema,0,published_by_account_id
                  FROM dash_dashboard_version WHERE id=?
                """, fixture.authorized().dashboardVersionId());
        var draft = JSON.createObjectNode();
        draft.put("formatVersion", "tc.application/v1");
        draft.put("displayName", "保留历史应用草稿");
        draft.putObject("hostCompatibility").put("minInclusive", "1.0.0").put("maxExclusive", "2.0.0");
        var references = draft.putArray("dashboardRefs");
        for (var board : List.of(fixture.entry(), fixture.authorized())) {
            references.addObject().put("dashboardId", board.dashboardId().toString())
                    .put("dashboardVersionId", board.dashboardVersionId().toString()).put("title", board.title());
        }
        draft.put("entryDashboardId", fixture.entry().dashboardId().toString());
        owner.update("""
                INSERT INTO app_application_draft(application_id,tenant_id,project_id,content,revision,updated_by)
                VALUES (?,?,?,?::jsonb,0,?)
                """, fixture.applicationId(), fixture.tenantId(), fixture.projectId(), draft.toString(), fixture.actorId());
        String consoleBearer = "Bearer " + consoleTokens.issue(new AuthenticatedPrincipal(
                fixture.actorId(), fixture.tenantId(), fixture.projectId())).value();
        String appBearer = "Bearer " + appTokens.issue(new AppAuthenticatedPrincipal(
                fixture.tenantId(), fixture.projectId(), fixture.appUserId(), 0)).value();
        String applicationPath = "/api/v1/app/applications/" + fixture.appKey();
        String schemaPath = applicationPath + "/versions/" + fixture.applicationVersionId()
                + "/dashboards/" + fixture.authorized().dashboardVersionId() + "/schema";
        String dashboardPath = "/api/v1/projects/" + fixture.projectId() + "/dashboards/"
                + fixture.authorized().dashboardId();
        String grantPath = "/api/v1/projects/" + fixture.projectId() + "/end-users/" + fixture.appUserId()
                + "/dashboard-grants/" + fixture.authorized().dashboardId();
        String deletePath = "/api/v1/projects/" + fixture.projectId() + "/applications/"
                + fixture.applicationId() + "/soft-delete";
        json(raw(get(applicationPath + "/resolve").header(HttpHeaders.AUTHORIZATION, appBearer)), 200);
        json(raw(get(applicationPath + "/current").header(HttpHeaders.AUTHORIZATION, appBearer)), 200);
        json(raw(get(schemaPath).queryParam("expectedPublicationRevision", "1")
                .header(HttpHeaders.AUTHORIZATION, appBearer)), 200);
        JsonNode originalDashboard = json(raw(get(dashboardPath).header(HttpHeaders.AUTHORIZATION, consoleBearer)), 200);
        JsonNode originalGrant = json(raw(get(grantPath).header(HttpHeaders.AUTHORIZATION, consoleBearer)), 200);
        assertThat(originalGrant.path("status").asString()).isEqualTo("ACTIVE");
        JsonNode originalContext = json(request(get(route("context"))), 200);
        JsonNode originalSchema = json(request(get(route("schema"))), 200);
        Map<String, Object> retained = retainedApplicationFacts();
        assertThat((List<?>) retained.get("versions")).hasSize(1);
        assertThat((List<?>) retained.get("draft")).hasSize(1);
        assertThat((List<?>) retained.get("references")).hasSize(2);
        assertThat((List<?>) retained.get("grant")).hasSize(1);
        assertThat((List<?>) retained.get("share")).hasSize(1);
        String body = "{\"expectedPublicationRevision\":\"1\"}";
        String key = "application-existing-runtime-soft-delete-" + fixture.applicationId();
        MvcResult deleted = raw(post(deletePath).header(HttpHeaders.AUTHORIZATION, consoleBearer)
                .header("Idempotency-Key", key).content(body));
        assertThat(deleted.getResponse().getStatus()).as(deleted.getResponse().getContentAsString()).isEqualTo(204);
        assertThat(deleted.getResponse().getContentAsByteArray()).isEmpty();
        assertThat(deleted.getResponse().getHeader("Location")).isNull();
        assertThat(deleted.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(owner.queryForObject("SELECT publication_revision FROM app_application WHERE id=?", Long.class,
                fixture.applicationId())).isEqualTo(2);
        assertThat(owner.queryForObject("SELECT current_version_id IS NULL AND deleted_at IS NOT NULL FROM app_application WHERE id=?",
                Boolean.class, fixture.applicationId())).isTrue();
        error(raw(get(applicationPath + "/resolve").header(HttpHeaders.AUTHORIZATION, appBearer)), 404, 60023);
        error(raw(get(applicationPath + "/current").header(HttpHeaders.AUTHORIZATION, appBearer)), 404, 60023);
        error(raw(get(schemaPath).queryParam("expectedPublicationRevision", "1")
                .header(HttpHeaders.AUTHORIZATION, appBearer)), 404, 60023);
        assertThat(json(raw(get(dashboardPath).header(HttpHeaders.AUTHORIZATION, consoleBearer)), 200)).isEqualTo(originalDashboard);
        assertThat(json(raw(get(grantPath).header(HttpHeaders.AUTHORIZATION, consoleBearer)), 200)).isEqualTo(originalGrant);
        JsonNode retainedContext = json(request(get(route("context"))), 200);
        assertThat(retainedContext.size()).isEqualTo(originalContext.size());
        originalContext.propertyNames().forEach(name -> {
            // 每次读取的数据库历史锚点允许前进，其他冻结分享身份和授权投影不能变化。
            if (!"historyAnchorAt".equals(name)) {
                assertThat(retainedContext.path(name)).as(name).isEqualTo(originalContext.path(name));
            }
        });
        assertThat(retainedContext.path("historyAnchorAt").isString()).isTrue();
        assertThat(json(request(get(route("schema"))), 200)).isEqualTo(originalSchema);
        error(raw(post(deletePath).header(HttpHeaders.AUTHORIZATION, consoleBearer)
                .header("Idempotency-Key", key).content(body)), 409, 10014);
        error(raw(post(deletePath).header(HttpHeaders.AUTHORIZATION, consoleBearer)
                .header("Idempotency-Key", key).content("{\"expectedPublicationRevision\":\"2\"}")), 409, 10009);
        assertThat(retainedApplicationFacts()).isEqualTo(retained);
        assertThat(owner.queryForObject("""
                SELECT count(*) FROM sys_audit_log
                 WHERE target_type='application' AND target_id=? AND action='application.deleted'
                """, Long.class, fixture.applicationId())).isOne();
    }

    /** 应用软删保留全部历史、精确引用、看板状态、独立分享和READ授权，只推进应用目录指针与revision。 */
    private Map<String, Object> retainedApplicationFacts() {
        return Map.of(
                "versions", owner.queryForList("SELECT * FROM app_application_version WHERE application_id=? ORDER BY id", fixture.applicationId()),
                "draft", owner.queryForList("SELECT * FROM app_application_draft WHERE application_id=?", fixture.applicationId()),
                "references", owner.queryForList("SELECT * FROM app_application_version_dashboard_ref WHERE application_version_id=? ORDER BY dashboard_id", fixture.applicationVersionId()),
                "dashboards", owner.queryForList("SELECT * FROM dash_dashboard WHERE project_id=? ORDER BY id", fixture.projectId()),
                "dashboardVersions", owner.queryForList("SELECT * FROM dash_dashboard_version WHERE project_id=? ORDER BY id", fixture.projectId()),
                "dashboardDraft", owner.queryForList("SELECT * FROM dash_dashboard_draft WHERE dashboard_id=?", fixture.authorized().dashboardId()),
                "share", owner.queryForList("SELECT id,tenant_id,project_id,dashboard_id,dashboard_version_id,project_generation,host_compatibility::text,referer_policy,created_at,expires_at,creator_account_id,revoked_at,revoked_by,(secret_hash=?) AS original_hash_unchanged FROM dash_share_token WHERE id=?", hash, shareId),
                "shareCreation", owner.queryForList("SELECT * FROM dash_share_creation_result WHERE share_id=?", shareId),
                "grant", owner.queryForList("SELECT * FROM app_user_dashboard WHERE project_id=? ORDER BY dashboard_id", fixture.projectId()));
    }

    /** 删除仅改目录，不清理版本、草稿、应用精确引用、旧ACTIVE分享或历史授权。 */
    private Map<String, Object> retainedDashboardFacts() {
        return Map.of(
                "versions", owner.queryForList("SELECT id,schema::text,schema_digest,required_components::text,required_resources::text FROM dash_dashboard_version WHERE dashboard_id=? ORDER BY id", fixture.authorized().dashboardId()),
                "draft", owner.queryForList("SELECT content::text,revision,updated_by,updated_at FROM dash_dashboard_draft WHERE dashboard_id=?", fixture.authorized().dashboardId()),
                "references", owner.queryForList("SELECT * FROM app_application_version_dashboard_ref WHERE project_id=? ORDER BY dashboard_id", fixture.projectId()),
                "share", owner.queryForList("SELECT id,dashboard_version_id,revoked_at,revoked_by,expires_at FROM dash_share_token WHERE id=?", shareId),
                "grant", owner.queryForList("SELECT id,dashboard_id,status,revision,revoked_at,revoked_by FROM app_user_dashboard WHERE project_id=? ORDER BY dashboard_id", fixture.projectId()),
                "application", owner.queryForList("SELECT current_version_id,publication_revision FROM app_application WHERE id=?", fixture.applicationId()));
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
