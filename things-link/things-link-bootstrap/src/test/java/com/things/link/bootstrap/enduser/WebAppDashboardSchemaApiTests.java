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

/** S12-2a5：真实App JWT、安全链与精确Schema投影，不以管理端权限或模拟用户绕过授权。 */
@AutoConfigureMockMvc
@Import(WebAppDashboardSchemaApiTests.IsolatedDatabaseConfiguration.class)
@OwnedTestContainers({"RUNTIME_POSTGRES"})
class WebAppDashboardSchemaApiTests extends AbstractIntegrationTest {
    /** 专库隔离发布历史与故障夹具，既有共享Bootstrap测试无需增加清理依赖。 */
    private static final PostgreSQLContainer<?> RUNTIME_POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("webapp_schema_api").withUsername("thingslink").withPassword("thingslink");
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
        assertThat(application.queryForObject("SELECT current_database()", String.class)).isEqualTo("webapp_schema_api");
        f = WebAppRuntimeFixture.seed(owner);
    }

    /** 成功包固定11字段，只含所选Schema，版本Long保持字符串且不泄露身份/发布人。 */
    @Test void returnsClosedExactSchemaPackage() throws Exception {
        MvcResult result = request(get(path()).queryParam("expectedPublicationRevision", "1"));
        JsonNode body = success(result);
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("applicationVersionId", "publicationRevision",
                "dashboardId", "dashboardVersionId", "dashboardVersionNumber", "schemaVersion", "schemaDigestAlgorithm",
                "schemaDigest", "requiredComponents", "requiredResources", "schema");
        assertThat(body.path("applicationVersionId").asString()).isEqualTo(f.applicationVersionId().toString());
        assertThat(body.path("dashboardVersionId").asString()).isEqualTo(f.authorized().dashboardVersionId().toString());
        assertThat(body.path("dashboardId").asString()).isEqualTo(f.authorized().dashboardId().toString());
        assertThat(body.path("publicationRevision").isString()).isTrue();
        assertThat(body.path("dashboardVersionNumber").isString()).isTrue();
        assertThat(body.path("requiredComponents").isArray()).isTrue();
        assertThat(body.path("requiredResources").isArray()).isTrue();
        assertThat(body.path("schema").path("pages").get(0).path("title").asString()).isEqualTo("授权看板");
        JsonNode current = success(request(get("/api/v1/app/applications/" + f.appKey() + "/current")));
        assertThat(body.path("schemaDigest")).isEqualTo(current.path("dashboards").get(0).path("schemaDigest"));
        assertThat(body.path("schemaDigestAlgorithm")).isEqualTo(current.path("dashboards").get(0).path("schemaDigestAlgorithm"));
        assertThat(result.getResponse().getContentAsString()).doesNotContain(f.entry().dashboardId().toString(),
                f.tenantId().toString(), f.actorId().toString(), f.appUserId().toString(), "postgresqlCanonicalText");
        assertThat(result.getResponse().getContentAsByteArray()).hasSizeLessThanOrEqualTo(786432);
        assertThat(result.getResponse().getCharacterEncoding()).isEqualTo(StandardCharsets.UTF_8.name());
    }

    /** 只有App Bearer能够读取，Cookie或Console JWT不继承运行权限。 */
    @Test void rejectsAnonymousCookieAndConsoleIdentity() throws Exception {
        String console = consoleTokens.issue(new AuthenticatedPrincipal(f.actorId(), f.tenantId(), f.projectId())).value();
        for (MockHttpServletRequestBuilder call : List.of(get(path()), get(path()).cookie(new Cookie("refresh_token", "test")),
                get(path()).header(HttpHeaders.AUTHORIZATION, "Bearer " + console),
                get(path()).header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token"))) {
            var result = mvc.perform(call.queryParam("expectedPublicationRevision", "1")).andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(401);
            noStore(result);
        }
        assertThat(mvc.perform(post(path())).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get(path() + "/extra")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    /** 重复、未知、非规范或溢出revision均由实际HTTP拒绝，不能借首值解析忽略第二值。 */
    @ParameterizedTest
    @ValueSource(strings = {"", "0", "01", "-1", "1.0", "1e0", " 1", "9223372036854775808"})
    void rejectsNonCanonicalRevision(String revision) throws Exception {
        error(request(get(path()).queryParam("expectedPublicationRevision", revision)), 400, 10001);
    }

    /** query与body及路径都是封闭输入；错误也保留预认证no-store。 */
    @Test void rejectsMissingDuplicateUnknownBodyAndMalformedPath() throws Exception {
        for (MockHttpServletRequestBuilder call : List.of(get(path()),
                get(path()).queryParam("expectedPublicationRevision", "1", "1"),
                get(path()).queryParam("expectedPublicationRevision", "1").queryParam("tenantId", f.tenantId().toString()),
                get(path()).queryParam("expectedPublicationRevision", "1").contentType(MediaType.APPLICATION_JSON).content("{}"),
                get(path().replace(f.applicationVersionId().toString(), "1-1-1-1-1")).queryParam("expectedPublicationRevision", "1"),
                get(path().replace(f.authorized().dashboardVersionId().toString(), "bad-uuid")).queryParam("expectedPublicationRevision", "1"),
                get(path().replace(f.appKey(), "invalid-key")).queryParam("expectedPublicationRevision", "1"))) {
            error(request(call), 400, 10001);
        }
    }

    /** 原版本ID不能绕当前代次；最大Long在HTTP上仍精确且已归档项目保留读取。 */
    @Test void enforcesCurrentRevisionAndReadsArchivedWithoutConditionalCache() throws Exception {
        error(request(get(path()).queryParam("expectedPublicationRevision", "2")), 404, 60023);
        owner.update("UPDATE app_application SET publication_revision=? WHERE id=?", Long.MAX_VALUE, f.applicationId());
        owner.update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?", f.projectId());
        JsonNode body = success(request(get(path()).queryParam("expectedPublicationRevision", Long.toString(Long.MAX_VALUE))
                .header(HttpHeaders.IF_NONE_MATCH, "*").header(HttpHeaders.IF_MODIFIED_SINCE, "Wed, 21 Oct 2030 07:28:00 GMT")));
        assertThat(body.path("publicationRevision").asString()).isEqualTo(Long.toString(Long.MAX_VALUE));
    }

    /** 下一请求必须感知撤权和用户代次，不能缓存刚成功的Schema授权。 */
    @ParameterizedTest
    @ValueSource(strings = {"grant", "generation"})
    void rechecksAuthorizationAfterSuccessfulRead(String change) throws Exception {
        success(request(get(path()).queryParam("expectedPublicationRevision", "1")));
        if (change.equals("grant")) {
            owner.update("UPDATE app_user_dashboard SET status='REVOKED',revision=2,updated_at=now(),revoked_at=now(),revoked_by=? WHERE app_user_id=?",
                    f.actorId(), f.appUserId());
        } else owner.update("UPDATE sys_project SET lifecycle_generation=1 WHERE id=?", f.projectId());
        error(request(get(path()).queryParam("expectedPublicationRevision", "1")),
                change.equals("grant") ? 404 : 401, change.equals("grant") ? 60023 : 60009);
    }

    /** 真实持久摘要损坏保留500且不回显SQL或内部身份，不能转换成60023。 */
    @Test void keepsPersistentCorruptionInternalAndNoStore() throws Exception {
        UUID broken = UUID.randomUUID();
        owner.update("""
                INSERT INTO app_application_version(id,tenant_id,project_id,application_id,version_number,source_draft_revision,
                    snapshot,snapshot_digest_algorithm,snapshot_digest,published_by_account_id,published_at)
                SELECT ?,tenant_id,project_id,application_id,2,source_draft_revision,snapshot,snapshot_digest_algorithm,
                    repeat('f',64),published_by_account_id,now() FROM app_application_version WHERE id=?
                """, broken, f.applicationVersionId());
        owner.update("UPDATE app_application SET current_version_id=?,publication_revision=2 WHERE id=?", broken, f.applicationId());
        MvcResult result = request(get(path().replace(f.applicationVersionId().toString(), broken.toString()))
                .queryParam("expectedPublicationRevision", "2"));
        error(result, 500, 90000);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("snapshot", "SELECT", f.tenantId().toString());
    }

    /** App身份只来自真实签名JWT，不发送tenant/project等客户端范围字段。 */
    private MvcResult request(MockHttpServletRequestBuilder call) throws Exception {
        String token = appTokens.issue(new AppAuthenticatedPrincipal(f.tenantId(), f.projectId(), f.appUserId(), 0)).value();
        return mvc.perform(call.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
    }
    /** 路径只携带公开应用选择器。 */
    private String path() { return "/api/v1/app/applications/" + f.appKey() + "/versions/" + f.applicationVersionId()
            + "/dashboards/" + f.authorized().dashboardVersionId() + "/schema"; }
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
