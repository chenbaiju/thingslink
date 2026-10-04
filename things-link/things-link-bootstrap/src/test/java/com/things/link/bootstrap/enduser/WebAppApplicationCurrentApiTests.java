package com.things.link.bootstrap.enduser;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.bootstrap.fixture.WebAppRuntimeFixture;
import com.things.link.bootstrap.fixture.WebAppRuntimeFixture.Fixture;
import com.things.link.enduser.application.AppAuthenticatedPrincipal;
import com.things.link.enduser.application.AppTokenIssuer;
import com.things.link.iam.application.AuthenticatedPrincipal;
import com.things.link.iam.application.TokenIssuer;
import com.things.link.testing.AbstractIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** S12-2a4b：真实App JWT、安全链与数据库current投影，不以管理端权限或模拟用户绕过授权。 */
@AutoConfigureMockMvc
@Import(WebAppApplicationCurrentApiTests.IsolatedDatabaseConfiguration.class)
@OwnedTestContainers({"RUNTIME_POSTGRES"})
class WebAppApplicationCurrentApiTests extends AbstractIntegrationTest {
    /** 专库隔离发布历史与故障夹具，既有共享Bootstrap测试无需增加清理依赖。 */
    private static final PostgreSQLContainer<?> RUNTIME_POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("webapp_current_api").withUsername("thingslink").withPassword("thingslink");
    /** Spring连接池及Flyway创建前启动专库。 */
    private static final String DATABASE_URL = startDatabase();
    /** 逐字段检查原始JSON，避免DTO反序列化悄悄忽略泄漏字段。 */
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 完整App过滤链及MVC入口。 */
    @Autowired private MockMvc mvc;
    /** 真正App签名器，issuer/secret与Console分离。 */
    @Autowired private AppTokenIssuer appTokens;
    /** Console令牌只用于证明它不能冒充App身份。 */
    @Autowired private TokenIssuer consoleTokens;
    /** 应用数据源用于确认没有用owner执行HTTP。 */
    @Autowired private JdbcTemplate application;
    /** 基类quota runner固定共享库；本App只读测试既不需要它，也不能误改共享库。 */
    @MockitoBean(enforceOverride = true, name = "relaxRestQuota")
    private ApplicationRunner unusedSharedQuotaRunner;
    /** Owner只建立与修改当前用例的发布夹具。 */
    private JdbcTemplate owner;
    /** 每例唯一租户、项目、App用户、应用与两个精确看板版本。 */
    private Fixture f;

    /** 不共享用户或全表DELETE；所有事实由独占容器最终回收。 */
    @BeforeEach void setup() {
        owner = new JdbcTemplate(new DriverManagerDataSource(DATABASE_URL, "thingslink", "thingslink"));
        assertThat(application.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        assertThat(application.queryForObject("SELECT current_database()", String.class)).isEqualTo("webapp_current_api");
        f = WebAppRuntimeFixture.seed(owner);
    }

    /** 成功响应固定九根字段和有权精确引用，无全局摘要或不可见入口导航。 */
    @Test void returnsOnlyAuthenticatedIdentityAndAuthorizedCurrentReference() throws Exception {
        MvcResult result = request(get(path()));
        JsonNode body = success(result);
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("identity", "application", "publicationRevision",
                "applicationVersionId", "applicationVersionNumber", "applicationFormatVersion", "hostCompatibility",
                "entryDashboardId", "dashboards");
        assertThat(body.path("identity").propertyNames()).containsExactlyInAnyOrder("kind", "appUserId", "projectId");
        assertThat(body.path("identity").path("kind").asString()).isEqualTo("APP");
        assertThat(body.path("identity").path("appUserId").asString()).isEqualTo(f.appUserId().toString());
        assertThat(body.path("identity").path("projectId").asString()).isEqualTo(f.projectId().toString());
        assertThat(body.path("application").propertyNames()).containsExactlyInAnyOrder("id", "appKey", "displayName");
        assertThat(body.path("application").path("id").asString()).isEqualTo(f.applicationId().toString());
        assertThat(body.path("applicationVersionId").asString()).isEqualTo(f.applicationVersionId().toString());
        assertThat(body.path("publicationRevision").isString()).isTrue();
        assertThat(body.path("applicationVersionNumber").isString()).isTrue();
        assertThat(body.path("applicationFormatVersion").asString()).isEqualTo("tc.application/v1");
        assertThat(body.path("hostCompatibility").propertyNames()).containsExactlyInAnyOrder("minInclusive", "maxExclusive");
        assertThat(body.path("entryDashboardId").isNull()).isTrue();
        assertThat(body.path("dashboards")).hasSize(1);
        JsonNode board = body.path("dashboards").get(0);
        assertThat(board.propertyNames()).containsExactlyInAnyOrder("dashboardId", "dashboardVersionId", "dashboardVersionNumber",
                "title", "schemaVersion", "schemaDigestAlgorithm", "schemaDigest", "pages");
        assertThat(board.path("dashboardId").asString()).isEqualTo(f.authorized().dashboardId().toString());
        assertThat(board.path("dashboardVersionId").asString()).isEqualTo(f.authorized().dashboardVersionId().toString());
        assertThat(board.path("dashboardVersionNumber").isString()).isTrue();
        assertThat(board.path("pages").get(0).propertyNames()).containsExactlyInAnyOrder("id", "title");
        assertThat(result.getResponse().getContentAsString()).doesNotContain(f.entry().dashboardId().toString(), f.tenantId().toString(), f.actorId().toString());
        assertThat(result.getResponse().getContentAsByteArray()).hasSizeLessThanOrEqualTo(65536);
        assertThat(result.getResponse().getCharacterEncoding()).isEqualTo(StandardCharsets.UTF_8.name());
    }

    /** 入口重新显式授权才可返回，Long.MAX_VALUE必须仍为精确十进制字符串。 */
    @Test void explicitEntryGrantAndMaximumRevisionPreserveExactJson() throws Exception {
        WebAppRuntimeFixture.grant(owner, f, f.entry());
        owner.update("UPDATE app_application SET publication_revision=? WHERE id=?", Long.MAX_VALUE, f.applicationId());
        JsonNode body = success(request(get(path())));
        assertThat(body.path("entryDashboardId").asString()).isEqualTo(f.entry().dashboardId().toString());
        assertThat(body.path("publicationRevision").asString()).isEqualTo(Long.toString(Long.MAX_VALUE));
        assertThat(body.path("dashboards").valueStream().map(node -> node.path("dashboardId").asString()).toList())
                .containsExactly(f.entry().dashboardId().toString(), f.authorized().dashboardId().toString());
    }

    /** 匿名、Cookie和Console Bearer均不能借公开resolve的相邻路径取得current。 */
    @Test void acceptsOnlyAppBearerAndNeverRefreshCookieOrConsoleIdentity() throws Exception {
        String console = consoleTokens.issue(new AuthenticatedPrincipal(f.actorId(), f.tenantId(), f.projectId())).value();
        for (MockHttpServletRequestBuilder call : List.of(get(path()), get(path()).cookie(new Cookie("refresh_token", "arbitrary")),
                get(path()).header(HttpHeaders.AUTHORIZATION, "Bearer " + console),
                get(path()).header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token"))) {
            MvcResult denied = mvc.perform(call).andReturn();
            assertThat(denied.getResponse().getStatus()).isEqualTo(401);
            noStore(denied);
        }
        assertThat(mvc.perform(post(path())).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get(path() + "/extra")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    /** 路径、query与GET正文属于封闭输入，不能成为隐藏身份或缓存键。 */
    @Test void rejectsMalformedKeyAndAllQueryOrBodyInputs() throws Exception {
        for (MockHttpServletRequestBuilder call : List.of(get(path()).queryParam("unexpected", "1"),
                get(path()).queryParam("expectedPublicationRevision", "1"),
                get(path()).contentType(MediaType.APPLICATION_JSON).content("{}"),
                get("/api/v1/app/applications/not-valid/current"))) {
            error(request(call), 400, 10001);
        }
    }

    /** 用户状态或删除代次失效以60009拒绝，资源撤回或无grant独立沿60023。 */
    @ParameterizedTest
    @ValueSource(strings = {"locked", "disabled", "generation", "withdrawn", "grant"})
    void rereadsIdentityAndGrantForEveryHttpRequest(String change) throws Exception {
        success(request(get(path())));
        switch (change) {
            case "locked" -> owner.update("UPDATE app_user SET status='LOCKED' WHERE id=?", f.appUserId());
            case "disabled" -> owner.update("UPDATE app_user_role SET status='DISABLED' WHERE app_user_id=?", f.appUserId());
            case "generation" -> owner.update("UPDATE sys_project SET lifecycle_generation=1 WHERE id=?", f.projectId());
            case "withdrawn" -> owner.update("UPDATE app_application SET current_version_id=NULL,publication_revision=2 WHERE id=?", f.applicationId());
            case "grant" -> owner.update("UPDATE app_user_dashboard SET status='REVOKED',revision=2,updated_at=now(),revoked_at=now(),revoked_by=updated_by WHERE app_user_id=?", f.appUserId());
            default -> throw new AssertionError(change);
        }
        int code = change.equals("withdrawn") || change.equals("grant") ? 60023 : 60009;
        error(request(get(path())), code == 60023 ? 404 : 401, code);
    }

    /** ARCHIVED维持App只读运行，缓存条件头不能生成304或复用已失效授权。 */
    @Test void archivedReadAndConditionalHeadersNeverReturnNotModified() throws Exception {
        owner.update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?", f.projectId());
        success(request(get(path()).header(HttpHeaders.IF_NONE_MATCH, "*")
                .header(HttpHeaders.IF_MODIFIED_SINCE, "Wed, 01 Jan 2031 00:00:00 GMT")));
        owner.update("UPDATE app_application SET current_version_id=NULL,publication_revision=2 WHERE id=?", f.applicationId());
        error(request(get(path()).header(HttpHeaders.IF_NONE_MATCH, "*")), 404, 60023);
    }

    /** 数据库持久摘要损坏是真实系统故障；错误也无缓存且不泄露SQL或隐藏引用。 */
    @Test void corruptCurrentVersionReturnsInternalErrorWithoutCaching() throws Exception {
        UUID broken = UUID.randomUUID();
        owner.update("""
                INSERT INTO app_application_version(id,tenant_id,project_id,application_id,version_number,source_draft_revision,
                    snapshot,snapshot_digest_algorithm,snapshot_digest,published_by_account_id,published_at)
                SELECT ?,tenant_id,project_id,application_id,2,source_draft_revision,snapshot,snapshot_digest_algorithm,
                    repeat('f',64),published_by_account_id,now() FROM app_application_version WHERE id=?
                """, broken, f.applicationVersionId());
        owner.update("UPDATE app_application SET current_version_id=?,publication_revision=2 WHERE id=?", broken, f.applicationId());
        MvcResult result = request(get(path()));
        error(result, 500, 90000);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("snapshot", "SELECT", f.tenantId().toString());
    }

    /** App身份只来自真实签名JWT，不发送tenant/project等客户端范围字段。 */
    private MvcResult request(MockHttpServletRequestBuilder call) throws Exception {
        String token = appTokens.issue(new AppAuthenticatedPrincipal(f.tenantId(), f.projectId(), f.appUserId(), 0)).value();
        return mvc.perform(call.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
    }
    /** 路径只携带公开应用选择器。 */
    private String path() { return "/api/v1/app/applications/" + f.appKey() + "/current"; }
    /** 成功与原始字节均由真实Jackson解析器验证。 */
    private static JsonNode success(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        noStore(result);
        return JSON.readTree(result.getResponse().getContentAsByteArray());
    }
    /** 错误状态与业务码必须同时一致，不把系统故障当业务隐藏。 */
    private static void error(MvcResult result, int status, int code) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(status);
        assertThat(JSON.readTree(result.getResponse().getContentAsByteArray()).path("code").asInt()).isEqualTo(code);
        noStore(result);
    }
    /** 在安全链前置的no-store覆盖全部路径结果，且没有可驱动304的ETag。 */
    private static void noStore(MvcResult result) {
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(result.getResponse().getHeader(HttpHeaders.ETAG)).isNull();
        assertThat(result.getResponse().getStatus()).isNotEqualTo(304);
    }
    /** @return 专库的JDBC身份，在Spring初始化之前唯一建立 */
    private static String startDatabase() { RUNTIME_POSTGRES.start(); return RUNTIME_POSTGRES.getJdbcUrl(); }
    /** 本类独占迁移和连接配置，不改变生产的数据库或调度默认值。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class IsolatedDatabaseConfiguration {
        /** 单一专库贯穿Flyway和普通APP业务数据源。 */
        @Bean DynamicPropertyRegistrar runtimeDatabase() {
            return registry -> {
                registry.add("spring.datasource.url", () -> DATABASE_URL);
                registry.add("spring.flyway.url", () -> DATABASE_URL);
                registry.add("spring.flyway.user", RUNTIME_POSTGRES::getUsername);
                registry.add("spring.flyway.password", RUNTIME_POSTGRES::getPassword);
                registry.add("things-link.outbox.publisher.enabled", () -> "false");
                registry.add("spring.kafka.listener.auto-startup", () -> "false");
                registry.add("things-link.notification.retry.enabled", () -> "false");
            };
        }
    }
}
