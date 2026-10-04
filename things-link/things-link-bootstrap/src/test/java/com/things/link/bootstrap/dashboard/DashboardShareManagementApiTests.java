package com.things.link.bootstrap.dashboard;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.bootstrap.fixture.WebAppRuntimeFixture;
import com.things.link.bootstrap.fixture.WebAppRuntimeFixture.Fixture;
import com.things.link.dashboard.application.publication.DashboardDataAdapterQualificationPort;
import com.things.link.dashboard.application.publication.DashboardHostQualificationDescriptor;
import com.things.link.dashboard.application.publication.DashboardHostQualificationPort;
import com.things.link.dashboard.infrastructure.qualification.ManagedWebAppHostQualificationAdapter;
import com.things.link.dashboard.application.publication.DashboardPublicationEligibilityRequirement;
import com.things.link.dashboard.application.publication.DashboardShareManagementService;
import com.things.link.dashboard.application.publication.DashboardShareCreateRequest;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.iam.application.AuthenticatedPrincipal;
import com.things.link.iam.application.TokenIssuer;
import com.things.link.testing.PausedSchedulerShutdownTestConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * S12-2c1：完整MockMvc过滤链、真实JWT/PG/RLS验收一次性secret签发和管理。
 * 正向仅用明确测试资格适配器模拟尚未交付的D-145宿主事实，不宣称生产分享可放行。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PausedSchedulerShutdownTestConfiguration.class)
@OwnedTestContainers({"DATABASE", "REDIS"})
class DashboardShareManagementApiTests {
    /** 专库容纳不可变版本与签发历史，不对共享Bootstrap数据库全表清理。 */
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("dashboard_share_management").withUsername("thingslink").withPassword("thingslink");
    /** 实际安全链依赖真实Redis，随机端口不污染开发实例。 */
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    /** 显式可写测试日志目录；不把固定/tmp路径带入Windows CI。 */
    private static final java.nio.file.Path SECURITY_LOG = securityLogDirectory();
    /** 精确原始JSON字段检查。 */
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 完整Servlet过滤链，不能直接调用Controller假称认证/幂等覆盖。 */
    @Autowired private MockMvc mvc;
    /** 生产Console签名器保持真实issuer、生命周期声明。 */
    @Autowired private TokenIssuer tokens;
    /** HTTP公共配额先拒绝时，独立验证真实事务内ACTIVE门禁，不能用该调用冒称HTTP结果。 */
    @Autowired private DashboardShareManagementService management;
    /** 业务连接必须使用非owner账户。 */
    @Autowired private JdbcTemplate application;
    /** D-145正向仅替换资格出口，其余签发、授权与持久端口保持真实。 */
    @MockitoBean(enforceOverride = true) private ManagedWebAppHostQualificationAdapter hosts;
    /** 空静态画布无数据需求；明确资格适配器用于检测服务实际调用。 */
    @MockitoBean(enforceOverride = true) private DashboardDataAdapterQualificationPort adapters;
    /** Owner仅播种和核对持久事实，不参与实际HTTP业务事务。 */
    private JdbcTemplate owner;
    /** 每例独立项目、账号和精确发布版本。 */
    private Fixture fixture;

    static {
        DATABASE.start();
        REDIS.start();
    }

    /** 当前版本已由真实PG规范jsonb计算摘要，管理角色显式创建。 */
    @BeforeEach
    void seedAndQualifyTestHost() {
        owner = new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(), "thingslink", "thingslink"));
        assertThat(application.queryForObject("SELECT current_user", String.class)).isEqualTo("thingslink_app");
        assertThat(application.queryForObject("SELECT current_database()", String.class)).isEqualTo("dashboard_share_management");
        // 本例验证20个活动凭据上限，先在专库放宽正交REST速率，不能让快速准备误命中429。
        owner.update("UPDATE sys_quota_policy SET rest_api_write_rate_per_second=1000000, "
                + "rest_api_write_rate_per_minute=60000000,rest_api_read_rate_per_second=1000000");
        fixture = WebAppRuntimeFixture.seed(owner);
        // 公共运行夹具仅提供已发布事实；管理聚合还要求一对一草稿，发布锁真实JOIN两者。
        // 在本管理测试补齐生产创建会同时产生的草稿，不修改只读运行夹具或放宽RLS。
        owner.update("""
                INSERT INTO dash_dashboard_draft(dashboard_id,tenant_id,project_id,content,revision,updated_by)
                SELECT dashboard_id,tenant_id,project_id,schema,0,published_by_account_id
                  FROM dash_dashboard_version WHERE project_id=?
                """, fixture.projectId());
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'ADMIN')",
                UUID.randomUUID(), fixture.projectId(), fixture.actorId());
        when(hosts.current()).thenReturn(Optional.of(new DashboardHostQualificationDescriptor("tc.webapp-host/v1",
                "1.0.0", Set.of("tc.application/v1"), Set.of("tc.dashboard/v1"),
                Map.of(DashboardPublicationEligibilityRequirement.ComponentKind.TEXT, "1.0.0"), Map.of())));
        when(adapters.supports(any(), any())).thenReturn(true);
        when(adapters.supportsHistory(any(), any())).thenReturn(true);
    }

    /** 当前ADMIN通过普通APP/RLS读取最小配置，ARCHIVED沿管理只读许可而非签发写许可。 */
    @Test
    void shareConfigurationUsesManagementReadScopeAndSupportsArchivedProject() throws Exception {
        JsonNode value = json(authenticated(get(path() + "/configuration")), 200);
        assertThat(value.propertyNames()).containsExactlyInAnyOrder("available", "hostOrigin", "hostVersion", "hostCompatibility");
        assertThat(value.path("available").asBoolean()).isTrue();
        assertThat(value.path("hostOrigin").asString()).isEqualTo("https://app.example.com");
        assertThat(value.path("hostVersion").asString()).isEqualTo("1.0.0");
        assertThat(value.path("hostCompatibility").path("maxExclusive").asString()).isEqualTo("1.0.1");
        owner.update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?", fixture.projectId());
        assertThat(json(authenticated(get(path() + "/configuration")), 200).path("available").asBoolean()).isTrue();
        assertThat(owner.queryForObject("SELECT count(*) FROM dash_share_token WHERE project_id=?", Integer.class, fixture.projectId())).isZero();
    }

    /** 管理角色降级及跨项目看板真实拒绝，不能用有部署配置绕过看板归属。 */
    @Test
    void shareConfigurationRejectsReadersAndForeignDashboard() throws Exception {
        Fixture foreign = WebAppRuntimeFixture.seed(owner);
        String other = "/api/v1/projects/" + fixture.projectId() + "/dashboards/"
                + foreign.authorized().dashboardId() + "/shares/configuration";
        error(authenticated(get(other)), 404, 60034);
        for (String role : List.of("VIEWER", "OPERATOR")) {
            owner.update("UPDATE sys_project_member SET role=? WHERE project_id=? AND account_id=?", role, fixture.projectId(), fixture.actorId());
            error(authenticated(get(path() + "/configuration")), 403, 60035);
        }
    }

    /** 缺少受管资格保持全空，只读查询未知参数按原公共参数错误拒绝。 */
    @Test
    void shareConfigurationEmptyHostNeverLeaksPartialConfiguration() throws Exception {
        when(hosts.current()).thenReturn(Optional.empty());
        JsonNode value = json(authenticated(get(path() + "/configuration")), 200);
        assertThat(value.path("available").asBoolean()).isFalse();
        for (String field : List.of("hostOrigin", "hostVersion", "hostCompatibility")) assertThat(value.path(field).isNull()).isTrue();
        error(authenticated(get(path() + "/configuration?include=path")), 400, 10001);
    }

    /** 201只返回一次规范secret；相同请求409只含shareId，异正文10009，通用幂等表没有此项目记录。 */
    @Test
    void issuesSecretOnceAndUsesDomainReplayWithoutPublicResponseStorage() throws Exception {
        String key = "share-once-" + UUID.randomUUID();
        JsonNode created = created(key);
        assertThat(created.propertyNames()).containsExactlyInAnyOrder("shareId", "secret", "expiresAt");
        String secret = created.path("secret").asString();
        assertThat(secret).matches("sh_[A-Za-z0-9_-]{43}");
        assertThat(Base64.getUrlEncoder().withoutPadding().encodeToString(
                Base64.getUrlDecoder().decode(secret.substring(3)))).isEqualTo(secret.substring(3));
        assertThat(UUID.fromString(created.path("shareId").asString()).version()).isEqualTo(7);
        MvcResult replay = authenticated(post(path()).header("Idempotency-Key", key).content(body()));
        JsonNode replayBody = error(replay, 409, 60052);
        assertThat(replayBody.path("details")).hasSize(1);
        assertThat(replayBody.path("details").get(0).asString()).isEqualTo(created.path("shareId").asString());
        assertThat(replay.getResponse().getContentAsString()).doesNotContain(secret, "secretHash");
        error(authenticated(post(path()).header("Idempotency-Key", key)
                .content(body().replace("3600", "7200"))), 409, 10009);
        assertThat(owner.queryForObject("SELECT count(*) FROM sys_idempotency_record WHERE project_id=?",
                Integer.class, fixture.projectId())).isZero();
        JsonNode list = json(authenticated(get(path())), 200);
        assertThat(list.path("items")).hasSize(1);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(secret.getBytes(StandardCharsets.UTF_8)));
        assertThat(list.toString()).doesNotContain(secret, digest, "secret_hash", "secretHash");
        UUID shareId = UUID.fromString(created.path("shareId").asString());
        assertThat(owner.queryForObject("SELECT secret_hash FROM dash_share_token WHERE id=?", String.class, shareId))
                .isEqualTo(digest);
        for (String table : List.of("dash_share_token", "dash_share_creation_result", "sys_audit_log")) {
            // 表名是固定测试枚举而非输入；既有审计/仲裁正文均不能成为secret副本。
            assertThat(owner.queryForList("SELECT row_to_json(f)::text FROM " + table + " f WHERE project_id=?",
                    String.class, fixture.projectId())).allSatisfy(row -> assertThat(row).doesNotContain(secret));
        }
    }

    /** 最外层过滤器不能在匿名或撤权后重放曾经成功的201正文或领域409身份详情。 */
    @Test
    void authenticationAndCurrentRolePrecedeEverySameKeyRetry() throws Exception {
        String key = "auth-first-" + UUID.randomUUID();
        JsonNode issued = created(key);
        MvcResult anonymous = mvc.perform(post(path()).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key).content(body())).andReturn();
        assertThat(anonymous.getResponse().getStatus()).isEqualTo(401);
        assertThat(anonymous.getResponse().getContentAsString()).doesNotContain(issued.path("secret").asString(),
                issued.path("shareId").asString());
        owner.update("UPDATE sys_project_member SET role='VIEWER' WHERE project_id=? AND account_id=?",
                fixture.projectId(), fixture.actorId());
        MvcResult denied = authenticated(post(path()).header("Idempotency-Key", key).content(body()));
        assertThat(denied.getResponse().getStatus()).isEqualTo(403);
        assertThat(denied.getResponse().getContentAsString()).doesNotContain(issued.path("secret").asString(),
                issued.path("shareId").asString());
    }

    /** 列表固定安全摘要，撤销204且重复无变化；归档保留列表但不开放管理写。 */
    @Test
    void listsSafeSummariesAndRevokesWithoutReplayingSecret() throws Exception {
        JsonNode created = created("manage-" + UUID.randomUUID());
        JsonNode page = json(authenticated(get(path())), 200);
        assertThat(page.propertyNames()).containsExactlyInAnyOrder("items", "nextCursor", "hasMore");
        JsonNode item = page.path("items").get(0);
        assertThat(item.propertyNames()).containsExactlyInAnyOrder("shareId", "dashboardVersionId", "dashboardVersionNumber",
                "status", "refererPolicy", "hostCompatibility", "expiresAt", "createdAt", "revokedAt");
        assertThat(item.path("status").asString()).isEqualTo("ACTIVE");
        String revoke = path() + "/" + created.path("shareId").asString() + "/revoke";
        for (String invalidBody : new String[] {"{}", " "}) {
            error(authenticated(post(revoke).content(invalidBody)), 400, 10002);
        }
        MvcResult first = authenticated(post(revoke));
        assertThat(first.getResponse().getStatus()).isEqualTo(204);
        assertThat(first.getResponse().getContentAsString()).isEmpty();
        assertThat(first.getResponse().getHeader("Location")).isNull();
        String once = json(authenticated(get(path())), 200).toString();
        assertThat(authenticated(post(revoke)).getResponse().getStatus()).isEqualTo(204);
        assertThat(json(authenticated(get(path())), 200).toString()).isEqualTo(once);
        owner.update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?", fixture.projectId());
        assertThat(json(authenticated(get(path())), 200).path("items").get(0).path("status").asString()).isEqualTo("REVOKED");
        // 冻结§4.2：外层日配额对ARCHIVED无法判定，HTTP须精确429/10029，不绕过公共过滤器。
        error(authenticated(post(revoke)), 429, 10029);
        error(authenticated(post(path()).header("Idempotency-Key", "archived").content(body())), 429, 10029);
        // 服务入口使用同一真实操作者范围；Spring代理开启实际事务，证明后置ACTIVE门禁仍拒绝。
        TenantContext.set(new TenantScope(fixture.tenantId(), fixture.projectId(), fixture.actorId()));
        try {
            assertThatThrownBy(() -> management.revoke(fixture.projectId(), fixture.authorized().dashboardId(),
                    UUID.fromString(created.path("shareId").asString())))
                    .isInstanceOfSatisfying(BusinessException.class,
                            denied -> assertThat(denied.errorCode().code()).isEqualTo(50017));
            var command = new DashboardShareCreateRequest(fixture.authorized().dashboardVersionId(), "1", 3600,
                    "HOST_ORIGIN", JSON.readTree(body()).path("hostCompatibility"), List.of());
            assertThatThrownBy(() -> management.create(fixture.projectId(), fixture.authorized().dashboardId(),
                    "archived-domain", command)).isInstanceOfSatisfying(BusinessException.class,
                            denied -> assertThat(denied.errorCode().code()).isEqualTo(50017));
        } finally {
            TenantContext.clear();
        }
        assertThat(owner.queryForObject("SELECT count(*) FROM dash_share_token WHERE project_id=?",
                Integer.class, fixture.projectId())).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM sys_audit_log WHERE project_id=? "
                + "AND action IN ('dashboard.share.create','dashboard.share.revoke')",
                Integer.class, fixture.projectId())).isEqualTo(2);
    }

    /** 所有管理路由含只读列表均是manage角色，READ权限不等于可枚举分享凭据身份。 */
    @Test
    void viewerCannotListCreateOrRevokeAndUnknownShareIsHidden() throws Exception {
        error(authenticated(post(path() + "/" + UUID.randomUUID() + "/revoke")), 404, 60053);
        owner.update("UPDATE sys_project_member SET role='VIEWER' WHERE project_id=? AND account_id=?",
                fixture.projectId(), fixture.actorId());
        for (MockHttpServletRequestBuilder call : List.of(get(path()),
                post(path()).header("Idempotency-Key", "viewer").content(body()),
                post(path() + "/" + UUID.randomUUID() + "/revoke"))) {
            assertThat(authenticated(call).getResponse().getStatus()).isEqualTo(403);
        }
    }

    /** keyset按真实createdAt/id取下一页，cursor不能搬到同项目另一看板扩大查询范围。 */
    @Test
    void paginatesAndBindsCursorToDashboard() throws Exception {
        created("page-first");
        created("page-second");
        JsonNode first = json(authenticated(get(path()).queryParam("limit", "1")), 200);
        assertThat(first.path("items")).hasSize(1);
        assertThat(first.path("hasMore").asBoolean()).isTrue();
        String cursor = first.path("nextCursor").asString();
        JsonNode second = json(authenticated(get(path()).queryParam("limit", "1").queryParam("cursor", cursor)), 200);
        assertThat(second.path("items")).hasSize(1);
        assertThat(second.path("hasMore").asBoolean()).isFalse();
        assertThat(second.path("items").get(0).path("shareId").asString())
                .isNotEqualTo(first.path("items").get(0).path("shareId").asString());
        String other = "/api/v1/projects/" + fixture.projectId() + "/dashboards/" + fixture.entry().dashboardId() + "/shares";
        assertThat(authenticated(get(other).queryParam("limit", "1").queryParam("cursor", cursor))
                .getResponse().getStatus()).isEqualTo(400);
    }

    /** 相同用户输入key不能串入另一个真实操作者的领域签发身份。 */
    @Test
    void sameKeyFromAnotherAuthorizedAccountCreatesIndependentFact() throws Exception {
        String key = "actor-bound";
        JsonNode first = created(key);
        UUID actor = UUID.randomUUID();
        owner.update("INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?,?,'test-only','分享协作者')",
                actor, actor + "@example.com");
        owner.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?,?,?,'ADMIN')",
                UUID.randomUUID(), fixture.projectId(), actor);
        String token = tokens.issue(new AuthenticatedPrincipal(actor, fixture.tenantId(), fixture.projectId())).value();
        JsonNode second = json(mvc.perform(post(path()).contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + token).header("Idempotency-Key", key).content(body())).andReturn(), 201);
        assertThat(second.path("shareId").asString()).isNotEqualTo(first.path("shareId").asString());
        assertThat(second.path("secret").asString()).isNotEqualTo(first.path("secret").asString());
    }

    /** D-145真实生产适配器尚缺资格；模拟相同缺失事实应返回60041且不能产生任何token。 */
    @Test
    void missingHostQualificationRefusesCreation() throws Exception {
        when(hosts.current()).thenReturn(Optional.empty());
        error(authenticated(post(path()).header("Idempotency-Key", "missing-host").content(body())), 503, 60041);
        assertThat(json(authenticated(get(path())), 200).path("items")).isEmpty();
    }

    /** 严格信封拒绝未知/重复字段、字符串期限和数字revision，不接受客户端secret或绝对到期时间。 */
    @Test
    void rejectsMalformedBodiesAndInvalidListBounds() throws Exception {
        for (String invalid : List.of(body().replace("\"expiresInSeconds\":3600", "\"expiresInSeconds\":\"3600\""),
                body().replace("\"expectedDashboardPublicationRevision\":\"1\"", "\"expectedDashboardPublicationRevision\":1"),
                body().replace("\"variables\":[]", "\"variables\":[],\"secret\":\"sh_illegal\""),
                body().replace("\"variables\":[]", "\"variables\":[],\"expiresAt\":\"2099-01-01T00:00:00Z\""),
                body().replace("\"variables\":[]", "\"variables\":[],\"variables\":[]"))) {
            assertThat(authenticated(post(path()).header("Idempotency-Key", UUID.randomUUID().toString())
                    .content(invalid)).getResponse().getStatus()).isEqualTo(400);
        }
        assertThat(authenticated(post(path()).content(body())).getResponse().getStatus()).isEqualTo(400);
        for (String limit : new String[] {"0", "51", "x"}) {
            assertThat(authenticated(get(path()).queryParam("limit", limit)).getResponse().getStatus()).isEqualTo(400);
        }
        assertThat(authenticated(get(path()).queryParam("cursor", "invalid")).getResponse().getStatus()).isEqualTo(400);
    }

    /** 第21个活动分享必须锁后拒绝；撤销释放容量，新签发不能复用旧secret。 */
    @Test
    void activeLimitAllowsReplacementOnlyAfterRevocation() throws Exception {
        JsonNode first = created("limit-first");
        // 凭据UPDATE有真实不可变触发器，不能为测试绕过；直接插入按DB时间已到期的独立历史事实。
        owner.update("""
                INSERT INTO dash_share_token(id,tenant_id,project_id,dashboard_id,dashboard_version_id,
                    project_generation,secret_hash,host_compatibility,referer_policy,created_at,expires_at,creator_account_id)
                SELECT ?,tenant_id,project_id,dashboard_id,dashboard_version_id,project_generation,?,host_compatibility,
                    referer_policy,clock_timestamp()-interval '2 hours',clock_timestamp()-interval '1 hour',creator_account_id
                  FROM dash_share_token WHERE id=?
                """, UUID.randomUUID(), "a".repeat(64), UUID.fromString(first.path("shareId").asString()));
        for (int i = 1; i < 20; i++) created("limit-" + i);
        JsonNode all = json(authenticated(get(path()).queryParam("limit", "50")), 200);
        assertThat(all.path("items")).hasSize(21);
        assertThat(all.path("items").toString()).contains("EXPIRED");
        error(authenticated(post(path()).header("Idempotency-Key", "limit-overflow").content(body())), 409, 60051);
        assertThat(authenticated(post(path() + "/" + first.path("shareId").asString() + "/revoke"))
                .getResponse().getStatus()).isEqualTo(204);
        assertThat(created("replacement").path("secret").asString()).isNotEqualTo(first.path("secret").asString());
    }

    /** 两个真实HTTP事务同时争抢同actor/key，数据库锁只允许一份token、仲裁与业务审计。 */
    @Test
    void concurrentSameKeyProducesOneSecretAndOneSafeConflict() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return authenticated(post(path()).header("Idempotency-Key", "concurrent").content(body()));
            });
            var second = executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return authenticated(post(path()).header("Idempotency-Key", "concurrent").content(body()));
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            MvcResult a = first.get(15, TimeUnit.SECONDS);
            MvcResult b = second.get(15, TimeUnit.SECONDS);
            assertThat(List.of(a.getResponse().getStatus(), b.getResponse().getStatus())).containsExactlyInAnyOrder(201, 409);
            JsonNode created = json(a.getResponse().getStatus() == 201 ? a : b, 201);
            JsonNode replay = error(a.getResponse().getStatus() == 409 ? a : b, 409, 60052);
            assertThat(replay.path("details")).hasSize(1);
            assertThat(replay.path("details").get(0).asString()).isEqualTo(created.path("shareId").asString());
            assertThat(replay.toString()).doesNotContain(created.path("secret").asString());
        }
        assertThat(owner.queryForObject("SELECT count(*) FROM dash_share_token WHERE project_id=?",
                Integer.class, fixture.projectId())).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM dash_share_creation_result WHERE project_id=?",
                Integer.class, fixture.projectId())).isEqualTo(1);
        assertThat(owner.queryForObject("SELECT count(*) FROM sys_audit_log WHERE project_id=? AND action='dashboard.share.create'",
                Integer.class, fixture.projectId())).isEqualTo(1);
    }

    /** 同一路径真实Console安全链获取当前项目角色，测试不直接设置TenantContext。 */
    private MvcResult authenticated(MockHttpServletRequestBuilder request) throws Exception {
        String token = tokens.issue(new AuthenticatedPrincipal(fixture.actorId(), fixture.tenantId(), fixture.projectId())).value();
        return mvc.perform(request.contentType(MediaType.APPLICATION_JSON).header("Authorization", "Bearer " + token)).andReturn();
    }
    /** 当前静态画布没有设备变量，空scope是合同允许的有效输入而非跳过模型权限验证。 */
    private String body() {
        return "{\"dashboardVersionId\":\"" + fixture.authorized().dashboardVersionId()
                + "\",\"expectedDashboardPublicationRevision\":\"1\",\"expiresInSeconds\":3600,"
                + "\"refererPolicy\":\"HOST_ORIGIN\",\"hostCompatibility\":{\"minInclusive\":\"1.0.0\","
                + "\"maxExclusive\":\"1.0.1\"},\"variables\":[]}";
    }
    /** 管理路径目标取真实同项目看板，UUID随机保证用例隔离。 */
    private String path() { return "/api/v1/projects/" + fixture.projectId() + "/dashboards/" + fixture.authorized().dashboardId() + "/shares"; }
    /** 集中验证首次201不掩盖安全链或数据库错误。 */
    private JsonNode created(String key) throws Exception {
        return json(authenticated(post(path()).header("Idempotency-Key", key).content(body())), 201);
    }
    /** 固定错误码与HTTP分类同时验收，不以单一非200泛化业务失败。 */
    private static JsonNode error(MvcResult result, int status, int code) throws Exception {
        JsonNode body = json(result, status);
        assertThat(body.path("code").asInt()).isEqualTo(code);
        return body;
    }
    /** 响应原文断言提供失败首因；所有分享管理响应都禁止缓存。 */
    private static JsonNode json(MvcResult result, int status) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(status);
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        return JSON.readTree(result.getResponse().getContentAsByteArray());
    }
    /** 独立临时目录满足生产配置构造器校验，创建失败保留首因。 */
    private static java.nio.file.Path securityLogDirectory() {
        try { return java.nio.file.Files.createTempDirectory("dashboard-share-management-log-"); }
        catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
    /** 唯一早期属性源，迁移owner和普通业务角色严格分离。 */
    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("things-link.dashboard.share-runtime.enabled", () -> "true");
        registry.add("things-link.dashboard.share-runtime.host-origin", () -> "https://app.example.com");
        registry.add("things-link.dashboard.share-runtime.security-log-path", SECURITY_LOG::toString);
        registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "thingslink_app");
        registry.add("spring.datasource.password", () -> "thingslink");
        registry.add("spring.flyway.url", DATABASE::getJdbcUrl);
        registry.add("spring.flyway.user", DATABASE::getUsername);
        registry.add("spring.flyway.password", DATABASE::getPassword);
        registry.add("spring.flyway.placeholders.app_role_password", () -> "thingslink");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("things-link.outbox.publisher.enabled", () -> "false");
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("things-link.notification.retry.enabled", () -> "false");
    }
}
