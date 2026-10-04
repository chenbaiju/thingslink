package com.things.link.enduser.api.controller;

import com.things.link.dashboard.application.RuntimeDashboardSchema;
import com.things.link.dashboard.application.publication.DashboardRequiredComponent;
import com.things.link.dashboard.application.publication.DashboardRequiredResource;
import com.things.link.enduser.api.dto.response.WebAppDashboardSchemaResponse;
import com.things.link.enduser.application.AppTokenIssuer;
import com.things.link.enduser.application.WebAppDashboardSchemaService;
import com.things.link.shared.error.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Schema HTTP边界纯测；持久语义/500KiB根上限由真实Dashboard验证，不拿此处服务替身证明。 */
class WebAppDashboardSchemaControllerTests {
    /** 规范应用公开键。 */
    private static final String APP_KEY = "app_" + "a".repeat(32);
    /** 可信App租户。 */
    private static final UUID TENANT = UUID.randomUUID();
    /** 可信App项目。 */
    private static final UUID PROJECT = UUID.randomUUID();
    /** 可信App主体。 */
    private static final UUID USER = UUID.randomUUID();
    /** 应用精确版本路径。 */
    private static final UUID APPLICATION_VERSION = UUID.randomUUID();
    /** 单看板精确版本路径。 */
    private static final UUID DASHBOARD_VERSION = UUID.randomUUID();
    /** 单看板稳定目录身份。 */
    private static final UUID DASHBOARD = UUID.randomUUID();
    /** 真实Jackson 3字节编码器。 */
    private final ObjectMapper mapper = new ObjectMapper();
    /** 应用服务替身隔离HTTP守卫层。 */
    private WebAppDashboardSchemaService service;
    /** 被测HTTP封闭输入与包装控制器。 */
    private WebAppDashboardSchemaController controller;

    /** 不预置服务调用，语法反例能够证明零业务调用。 */
    @BeforeEach
    void setup() {
        service = mock(WebAppDashboardSchemaService.class);
        controller = new WebAppDashboardSchemaController(service, mapper);
    }

    /** 十一根字段和嵌套需求均闭集，Long.MAX_VALUE精确字符串化且不泄漏可信范围或原规范文本。 */
    @Test
    void projectsExactSchemaEnvelopeWithStringLongs() throws Exception {
        RuntimeDashboardSchema runtime = runtime("中文导航", true);
        allow(runtime);
        var response = call(Long.toString(Long.MAX_VALUE), request(Long.toString(Long.MAX_VALUE)));
        JsonNode body = mapper.readTree(response.getBody());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("applicationVersionId", "publicationRevision", "dashboardId",
                "dashboardVersionId", "dashboardVersionNumber", "schemaVersion", "schemaDigestAlgorithm", "schemaDigest",
                "requiredComponents", "requiredResources", "schema");
        assertThat(body.path("requiredComponents").get(0).propertyNames()).containsExactlyInAnyOrder("kind", "componentVersion");
        assertThat(body.path("requiredResources").get(0).propertyNames()).containsExactlyInAnyOrder("resourceId", "digest");
        assertThat(body.path("publicationRevision").isString()).isTrue();
        assertThat(body.path("publicationRevision").asString()).isEqualTo(Long.toString(Long.MAX_VALUE));
        assertThat(body.path("dashboardVersionNumber").isString()).isTrue();
        assertThat(body.path("dashboardVersionNumber").asString()).isEqualTo(Long.toString(Long.MAX_VALUE));
        assertThat(body.path("schema").isObject()).isTrue();
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).contains("中文导航")
                .doesNotContain(TENANT.toString(), PROJECT.toString(), USER.toString(), "schemaUtf8Bytes");
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().getCharset()).isEqualTo(StandardCharsets.UTF_8);
        verify(service).schema(TENANT, PROJECT, USER, Long.MAX_VALUE, APP_KEY,
                APPLICATION_VERSION, Long.MAX_VALUE, DASHBOARD_VERSION);
    }

    /** 空组件/资源需求合法并保留为必填空数组。 */
    @Test
    void preservesEmptyRequirementArrays() throws Exception {
        allow(runtime("无需求", false));
        JsonNode body = mapper.readTree(call("1", request("1")).getBody());
        assertThat(body.path("requiredComponents").isArray()).isTrue();
        assertThat(body.path("requiredComponents")).isEmpty();
        assertThat(body.path("requiredResources").isArray()).isTrue();
        assertThat(body.path("requiredResources")).isEmpty();
    }

    /** 组件和资源数组按冻结上限保留全量；任一超限都不能静默截断后返回原Schema摘要。 */
    @ParameterizedTest
    @ValueSource(strings = {"MAXIMUM", "COMPONENT_OVER", "RESOURCE_OVER"})
    void enforcesRequirementArrayBounds(String boundary) throws Exception {
        RuntimeDashboardSchema runtime = runtime("需求边界", false);
        when(runtime.requiredComponents()).thenReturn(IntStream.range(0, "COMPONENT_OVER".equals(boundary) ? 11 : 10)
                .mapToObj(index -> new DashboardRequiredComponent("kind" + index, "1.0.0")).toList());
        when(runtime.requiredResources()).thenReturn(IntStream.range(0, "RESOURCE_OVER".equals(boundary) ? 51 : 50)
                .mapToObj(index -> new DashboardRequiredResource("resource" + index, "b".repeat(64))).toList());
        allow(runtime);
        if ("MAXIMUM".equals(boundary)) {
            JsonNode body = mapper.readTree(call("1", request("1")).getBody());
            assertThat(body.path("requiredComponents")).hasSize(10);
            assertThat(body.path("requiredResources")).hasSize(50);
        } else {
            assertThatThrownBy(() -> call("1", request("1")))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("结构边界");
        }
    }

    /** Schema必须是单个根对象，不接受字符串化JSON或数组作为成功包。 */
    @Test
    void rejectsNonObjectSchemaProjection() {
        RuntimeDashboardSchema runtime = runtime("非法根", false);
        when(runtime.schema()).thenReturn(mapper.createArrayNode());
        allow(runtime);
        assertThatThrownBy(() -> call("1", request("1")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("结构边界");
    }

    /** DTO深复制保证端口或序列化调用方不能在包已投影后改写该次Schema。 */
    @Test
    void keepsSchemaProjectionIsolatedFromMutations() {
        RuntimeDashboardSchema runtime = runtime("原始标题", false);
        WebAppDashboardSchemaResponse response = WebAppDashboardSchemaResponse.from(runtime);
        ((tools.jackson.databind.node.ObjectNode) runtime.schema()).put("title", "外部改写");
        ((tools.jackson.databind.node.ObjectNode) response.schema()).put("title", "调用方改写");
        assertThat(response.schema().path("title").asString()).isEqualTo("原始标题");
    }

    /** 规范正Long不允许零、前导零、指数、小数、空白、符号、溢出或空值。 */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "0", "01", "-1", "+1", "1.0", "1e1", " 1", "1 ", "9223372036854775808"})
    void rejectsInvalidRevisionBeforeBusiness(String revision) {
        assertInvalid(() -> call(revision, request(revision == null ? "1" : revision)));
        verifyNoInteractions(service);
    }

    /** Servlet已解码参数map中未知、重复、缺失字段或正文都拒绝，不能以多个值偷偷替换预期代次。 */
    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "DUPLICATE", "MISSING", "BODY"})
    void rejectsOpenRequestShape(String invalid) {
        MockHttpServletRequest request = request("1");
        switch (invalid) {
            case "UNKNOWN" -> request.addParameter("tenantId", TENANT.toString());
            case "DUPLICATE" -> request.addParameter("expectedPublicationRevision", "2");
            case "MISSING" -> request.removeParameter("expectedPublicationRevision");
            case "BODY" -> request.setContent("{}".getBytes(StandardCharsets.UTF_8));
            default -> throw new AssertionError(invalid);
        }
        assertInvalid(() -> call("1", request));
        verifyNoInteractions(service);
    }

    /** 元数据规范UUID拒绝缩写、大写与隐式trim；两个路径都适用同一规则。 */
    @ParameterizedTest
    @ValueSource(strings = {"invalid", "1-1-1-1-1", "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA", " 00000000-0000-0000-0000-000000000000"})
    void rejectsNoncanonicalVersionPaths(String invalid) {
        assertInvalid(() -> controller.schema(jwt(), APP_KEY, invalid, DASHBOARD_VERSION.toString(), "1", request("1")));
        assertInvalid(() -> controller.schema(jwt(), APP_KEY, APPLICATION_VERSION.toString(), invalid, "1", request("1")));
        verifyNoInteractions(service);
    }

    /** 仅测HTTP包装守卫：替身构造不可能通过500KiB持久门禁的大Schema，最终786432字节仍允许发送。 */
    @Test
    void acceptsExactFinalUtf8EnvelopeLimit() throws Exception {
        int fixed = mapper.writeValueAsBytes(WebAppDashboardSchemaResponse.from(runtime("", false))).length;
        allow(runtime("a".repeat(WebAppDashboardSchemaController.MAX_SCHEMA_RESPONSE_BYTES - fixed), false));
        assertThat(call("1", request("1")).getBody()).hasSize(786432);
    }

    /** 仅测HTTP包装守卫：比最终768KiB多一字节就内部失败，不能截断Schema或沿原摘要伪装成功。 */
    @Test
    void rejectsOneByteBeyondFinalUtf8EnvelopeLimit() throws Exception {
        int fixed = mapper.writeValueAsBytes(WebAppDashboardSchemaResponse.from(runtime("", false))).length;
        allow(runtime("a".repeat(WebAppDashboardSchemaController.MAX_SCHEMA_RESPONSE_BYTES - fixed + 1), false));
        assertThatThrownBy(() -> call("1", request("1"))).isInstanceOf(IllegalStateException.class).hasMessageContaining("768KiB");
    }

    /** 数据库摘要失败必须保留同一首因，不映射成资源隐藏或空Schema。 */
    @Test
    void preservesSchemaPersistenceFailure() {
        var failure = new DataIntegrityViolationException("Schema摘要损坏");
        when(service.schema(any(), any(), any(), anyLong(), anyString(), any(), anyLong(), any())).thenThrow(failure);
        assertThatThrownBy(() -> call("1", request("1"))).isSameAs(failure);
    }

    /** 将合规或专门越界的HTTP夹具交给服务替身，不声称通过持久发布语义。 */
    private void allow(RuntimeDashboardSchema runtime) {
        when(service.schema(any(), any(), any(), anyLong(), anyString(), any(), anyLong(), any())).thenReturn(runtime);
    }

    /** 创建仅用于HTTP字段投影与最终编码的端口替身；超大文本专用于隔离包装守卫。 */
    private RuntimeDashboardSchema runtime(String text, boolean requirements) {
        RuntimeDashboardSchema result = mock(RuntimeDashboardSchema.class);
        when(result.applicationVersionId()).thenReturn(APPLICATION_VERSION);
        when(result.publicationRevision()).thenReturn(Long.MAX_VALUE);
        when(result.dashboardId()).thenReturn(DASHBOARD);
        when(result.dashboardVersionId()).thenReturn(DASHBOARD_VERSION);
        when(result.dashboardVersionNumber()).thenReturn(Long.MAX_VALUE);
        when(result.schemaVersion()).thenReturn("tc.dashboard/v1");
        when(result.schemaDigestAlgorithm()).thenReturn("PG_JSONB_TEXT_V1_SHA256");
        when(result.schemaDigest()).thenReturn("a".repeat(64));
        when(result.requiredComponents()).thenReturn(requirements ? List.of(new DashboardRequiredComponent("TEXT", "1.0.0")) : List.of());
        when(result.requiredResources()).thenReturn(requirements ? List.of(new DashboardRequiredResource("logo", "b".repeat(64))) : List.of());
        when(result.schema()).thenReturn(mapper.createObjectNode().put("title", text));
        return result;
    }

    /** 真实Jackson控制器调用，JWT代次使用Long上限验证无浮点中间值。 */
    private ResponseEntity<byte[]> call(String revision, MockHttpServletRequest request) throws Exception {
        return controller.schema(jwt(), APP_KEY, APPLICATION_VERSION.toString(), DASHBOARD_VERSION.toString(), revision, request);
    }

    /** 建立Servlet已解析唯一query的GET请求。 */
    private static MockHttpServletRequest request(String revision) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/schema");
        request.addParameter("expectedPublicationRevision", revision);
        return request;
    }

    /** 模拟已经过安全链校验的App令牌；此处只测试身份参数传递。 */
    private static Jwt jwt() {
        return Jwt.withTokenValue("schema-unit-token").header("alg", "HS256").subject(USER.toString())
                .claim(AppTokenIssuer.CLAIM_TENANT_ID, TENANT.toString())
                .claim(AppTokenIssuer.CLAIM_PROJECT_ID, PROJECT.toString())
                .claim(AppTokenIssuer.CLAIM_PROJECT_GENERATION, Long.MAX_VALUE).build();
    }

    /** 将受检异常动作交给AssertJ以断言统一10001。 */
    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode().code()).isEqualTo(10001));
    }
}
