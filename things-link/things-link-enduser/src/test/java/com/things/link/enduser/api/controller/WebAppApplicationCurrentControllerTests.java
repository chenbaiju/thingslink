package com.things.link.enduser.api.controller;

import com.things.link.dashboard.application.publication.ApplicationPublishedDashboardReference;
import com.things.link.enduser.api.dto.response.WebAppApplicationCurrentResponse;
import com.things.link.enduser.application.AppTokenIssuer;
import com.things.link.enduser.application.CurrentWebAppApplication;
import com.things.link.enduser.application.WebAppApplicationCurrentService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** WebApp当前应用控制器的身份传递、九字段响应与最终UTF-8边界单测。 */
@ExtendWith(MockitoExtension.class)
class WebAppApplicationCurrentControllerTests {

    /** 冻结语法的应用公开键。 */
    private static final String APP_KEY = "app_0123456789abcdef0123456789abcdef";
    /** 测试App身份的租户ID。 */
    private static final UUID TENANT_ID = UUID.randomUUID();
    /** 测试App身份的项目ID。 */
    private static final UUID PROJECT_ID = UUID.randomUUID();
    /** 测试App JWT subject。 */
    private static final UUID APP_USER_ID = UUID.randomUUID();

    /** 领域服务替身；事务、RLS和grant交集由其自身测试证明。 */
    @Mock private WebAppApplicationCurrentService currentService;

    /** 与生产同类型的Jackson 3最终序列化器。 */
    private ObjectMapper objectMapper;

    /** 被测独立current控制器。 */
    private WebAppApplicationCurrentController controller;

    /** 每例重建控制器，避免大小边界夹具相互影响。 */
    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        controller = new WebAppApplicationCurrentController(currentService, objectMapper);
    }

    /** 九个根字段与嵌套闭集精确，全部Long.MAX_VALUE仍以字符串输出且nullable入口字段存在。 */
    @Test
    void returnsExactCurrentProjectionWithStringLongsAndNullableEntry() throws Exception {
        CurrentWebAppApplication current = current("看板导航", null, Long.MAX_VALUE, Long.MAX_VALUE);
        when(currentService.current(TENANT_ID, PROJECT_ID, APP_USER_ID, Long.MAX_VALUE, APP_KEY))
                .thenReturn(current);

        var response = controller.current(jwt(Long.MAX_VALUE), APP_KEY, request());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
        assertThat(response.getHeaders().getContentType().getCharset()).isEqualTo(StandardCharsets.UTF_8);
        JsonNode root = objectMapper.readTree(response.getBody());
        assertThat(fields(root)).containsExactlyInAnyOrder(
                "identity", "application", "publicationRevision", "applicationVersionId",
                "applicationVersionNumber", "applicationFormatVersion", "hostCompatibility",
                "entryDashboardId", "dashboards");
        assertThat(fields(root.get("identity"))).containsExactlyInAnyOrder("kind", "appUserId", "projectId");
        assertThat(fields(root.get("application"))).containsExactlyInAnyOrder("id", "appKey", "displayName");
        assertThat(fields(root.get("hostCompatibility"))).containsExactlyInAnyOrder("minInclusive", "maxExclusive");
        JsonNode dashboard = root.get("dashboards").get(0);
        assertThat(fields(dashboard)).containsExactlyInAnyOrder(
                "dashboardId", "dashboardVersionId", "dashboardVersionNumber", "title", "schemaVersion",
                "schemaDigestAlgorithm", "schemaDigest", "pages");
        assertThat(fields(dashboard.get("pages").get(0))).containsExactlyInAnyOrder("id", "title");
        assertThat(root.get("identity").get("kind").asString()).isEqualTo("APP");
        assertThat(root.get("publicationRevision").asString()).isEqualTo(Long.toString(Long.MAX_VALUE));
        assertThat(root.get("applicationVersionNumber").asString()).isEqualTo(Long.toString(Long.MAX_VALUE));
        assertThat(dashboard.get("dashboardVersionNumber").asString()).isEqualTo(Long.toString(Long.MAX_VALUE));
        assertThat(root.has("entryDashboardId")).isTrue();
        assertThat(root.get("entryDashboardId").isNull()).isTrue();
        verify(currentService).current(TENANT_ID, PROJECT_ID, APP_USER_ID, Long.MAX_VALUE, APP_KEY);
    }

    /** 旧JWT缺少pgv时控制器按兼容代次0调用领域服务。 */
    @Test
    void passesLegacyMissingProjectGenerationAsZero() throws Exception {
        when(currentService.current(TENANT_ID, PROJECT_ID, APP_USER_ID, 0, APP_KEY))
                .thenReturn(current("导航", UUID.randomUUID(), 1, 1));

        controller.current(jwt(null), APP_KEY, request());

        verify(currentService).current(TENANT_ID, PROJECT_ID, APP_USER_ID, 0, APP_KEY);
    }

    /** query与GET正文均在领域调用前按10001拒绝，不能成为隐藏授权输入。 */
    @Test
    void rejectsAnyQueryOrBodyBeforeCurrentLookup() {
        MockHttpServletRequest query = request();
        query.setQueryString("unused=1");
        MockHttpServletRequest body = request();
        body.setContent("{}".getBytes(StandardCharsets.UTF_8));

        assertInvalidParameter(() -> controller.current(jwt(0L), APP_KEY, query));
        assertInvalidParameter(() -> controller.current(jwt(0L), APP_KEY, body));

        verifyNoInteractions(currentService);
    }

    /** 最终Jackson正文恰好65536字节仍成功，证明边界使用实际UTF-8输出。 */
    @Test
    void acceptsResponseAtExactUtf8ByteLimit() throws Exception {
        int fixed = encodedSize("");
        String title = "a".repeat(WebAppApplicationCurrentController.MAX_CURRENT_RESPONSE_BYTES - fixed);
        when(currentService.current(TENANT_ID, PROJECT_ID, APP_USER_ID, 0, APP_KEY))
                .thenReturn(current(title, null, 1, 1));

        var response = controller.current(jwt(0L), APP_KEY, request());

        assertThat(response.getBody()).hasSize(WebAppApplicationCurrentController.MAX_CURRENT_RESPONSE_BYTES);
    }

    /** 最终正文只多一个ASCII字节即以内部异常拒绝，不能截断或降格为60023。 */
    @Test
    void rejectsResponseOneByteBeyondUtf8Limit() throws Exception {
        int fixed = encodedSize("");
        String title = "a".repeat(WebAppApplicationCurrentController.MAX_CURRENT_RESPONSE_BYTES - fixed + 1);
        when(currentService.current(TENANT_ID, PROJECT_ID, APP_USER_ID, 0, APP_KEY))
                .thenReturn(current(title, null, 1, 1));

        assertThatThrownBy(() -> controller.current(jwt(0L), APP_KEY, request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("64KiB");
    }

    /** 数据库摘要或关系错误保持原异常，控制器不得把它转成资源不可用。 */
    @Test
    void propagatesPersistenceIntegrityFailure() {
        DataIntegrityViolationException failure = new DataIntegrityViolationException("应用摘要损坏");
        when(currentService.current(TENANT_ID, PROJECT_ID, APP_USER_ID, 0, APP_KEY)).thenThrow(failure);

        assertThatThrownBy(() -> controller.current(jwt(0L), APP_KEY, request())).isSameAs(failure);
    }

    /** 建立包含一个精确看板引用的已授权应用投影。 */
    private static CurrentWebAppApplication current(
            String dashboardTitle, UUID entryDashboardId, long publicationRevision, long versionNumber) {
        UUID dashboardId = entryDashboardId == null ? UUID.randomUUID() : entryDashboardId;
        ApplicationPublishedDashboardReference reference = new ApplicationPublishedDashboardReference(
                dashboardId, UUID.randomUUID(), versionNumber, dashboardTitle, "tc.dashboard/v1",
                "PG_JSONB_TEXT_V1_SHA256", "d".repeat(64),
                List.of(new ApplicationPublishedDashboardReference.Page("overview", "总览")));
        return new CurrentWebAppApplication(
                TENANT_ID, PROJECT_ID, APP_USER_ID, UUID.randomUUID(), APP_KEY, "生产总览",
                publicationRevision, UUID.randomUUID(), versionNumber, "tc.application/v1",
                "1.0.0", "2.0.0", entryDashboardId, List.of(reference));
    }

    /** 计算同一响应仅替换导航标题后的最终JSON字节数。 */
    private int encodedSize(String dashboardTitle) throws Exception {
        return objectMapper.writeValueAsBytes(WebAppApplicationCurrentResponse.from(
                current(dashboardTitle, null, 1, 1))).length;
    }

    /** 创建包含可信三轴及可选pgv的App access JWT。 */
    private static Jwt jwt(Long projectGeneration) {
        Jwt.Builder builder = Jwt.withTokenValue("unit-token").header("alg", "HS256")
                .subject(APP_USER_ID.toString())
                .claim(AppTokenIssuer.CLAIM_TENANT_ID, TENANT_ID.toString())
                .claim(AppTokenIssuer.CLAIM_PROJECT_ID, PROJECT_ID.toString());
        if (projectGeneration != null) {
            builder.claim(AppTokenIssuer.CLAIM_PROJECT_GENERATION, projectGeneration);
        }
        return builder.build();
    }

    /** 创建无query和正文的规范current请求。 */
    private static MockHttpServletRequest request() {
        return new MockHttpServletRequest("GET", "/api/v1/app/applications/" + APP_KEY + "/current");
    }

    /** 返回对象节点字段名集合。 */
    private static Set<String> fields(JsonNode node) {
        Set<String> fields = new java.util.HashSet<>();
        node.propertyNames().forEach(fields::add);
        return fields;
    }

    /** 断言合同外HTTP输入沿通用10001拒绝。 */
    private static void assertInvalidParameter(ThrowingInvocation invocation) {
        assertThatThrownBy(invocation::invoke)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(CommonErrorCode.INVALID_PARAMETER));
    }

    /** 允许lambda保留控制器读取请求流时声明的受检异常。 */
    @FunctionalInterface
    private interface ThrowingInvocation {

        /** @throws Exception 控制器读取或编码失败 */
        void invoke() throws Exception;
    }
}
