package com.things.link.enduser.api.controller;

import com.things.link.enduser.api.dto.response.WebAppApplicationResolutionResponse;
import com.things.link.enduser.application.ResolvedWebAppApplication;
import com.things.link.enduser.application.WebAppApplicationResolutionService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** WebApp公开应用定位控制器的封闭输入与最终字节合同测试。 */
@ExtendWith(MockitoExtension.class)
class WebAppApplicationControllerTests {

    /** 数据库与安全matcher共同使用的规范应用公开键。 */
    private static final String APP_KEY = "app_0123456789abcdef0123456789abcdef";

    /** 应用定位编排替身；领域事务与RLS另由服务测试验证。 */
    @Mock private WebAppApplicationResolutionService resolutionService;

    /** 与生产同类型的Jackson 3序列化器。 */
    private ObjectMapper objectMapper;

    /** 被测控制器。 */
    private WebAppApplicationController controller;

    /** 每例使用独立序列化器和控制器，避免配置变化跨用例泄漏。 */
    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        controller = new WebAppApplicationController(resolutionService, objectMapper);
    }

    /** 成功响应按最终字节输出且根对象仅含冻结的三个公开字段。 */
    @Test
    void returnsExactPublicProjectionAsUtf8JsonBytes() throws Exception {
        when(resolutionService.resolve(APP_KEY)).thenReturn(
                new ResolvedWebAppApplication(APP_KEY, "工厂总览", "project-01"));

        var response = controller.resolve(APP_KEY, new MockHttpServletRequest("GET", resolvePath()));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
        assertThat(response.getHeaders().getContentType().getCharset()).isEqualTo(StandardCharsets.UTF_8);
        assertThat(response.getBody()).isNotNull()
                .hasSizeLessThanOrEqualTo(WebAppApplicationController.MAX_RESOLUTION_RESPONSE_BYTES);
        assertThat(objectMapper.readTree(response.getBody()).properties())
                .extracting(java.util.Map.Entry::getKey)
                .containsExactlyInAnyOrder("appKey", "displayName", "projectKey");
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).contains("工厂总览");
    }

    /** 任意query都会形成冻结合同外缓存键，必须在调用领域前按10001拒绝。 */
    @Test
    void rejectsAnyQueryBeforeResolvingApplication() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", resolvePath());
        request.setQueryString("unused=1");

        assertInvalidParameter(() -> controller.resolve(APP_KEY, request));

        verifyNoInteractions(resolutionService);
    }

    /** GET请求携带任意正文同样是隐藏输入，不能被忽略后返回看似成功的定位结果。 */
    @Test
    void rejectsAnyBodyBeforeResolvingApplication() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", resolvePath());
        request.setContent("{}".getBytes(StandardCharsets.UTF_8));

        assertInvalidParameter(() -> controller.resolve(APP_KEY, request));

        verifyNoInteractions(resolutionService);
    }

    /** 最终Jackson UTF-8正文恰好2KiB时仍允许返回，证明边界按字节而非字符计量。 */
    @Test
    void acceptsResponseAtExactUtf8ByteLimit() throws Exception {
        int fixedBytes = encodedSize("");
        String displayName = "a".repeat(WebAppApplicationController.MAX_RESOLUTION_RESPONSE_BYTES - fixedBytes);
        when(resolutionService.resolve(APP_KEY)).thenReturn(
                new ResolvedWebAppApplication(APP_KEY, displayName, "project-01"));

        var response = controller.resolve(APP_KEY, new MockHttpServletRequest("GET", resolvePath()));

        assertThat(response.getBody()).hasSize(WebAppApplicationController.MAX_RESOLUTION_RESPONSE_BYTES);
    }

    /** 最终正文超过2KiB属于服务端投影不变量失败，不能降格为60023或截断成功。 */
    @Test
    void rejectsResponseOneByteBeyondUtf8LimitAsInvariantFailure() throws Exception {
        int fixedBytes = encodedSize("");
        String displayName = "a".repeat(
                WebAppApplicationController.MAX_RESOLUTION_RESPONSE_BYTES - fixedBytes + 1);
        when(resolutionService.resolve(APP_KEY)).thenReturn(
                new ResolvedWebAppApplication(APP_KEY, displayName, "project-01"));

        assertThatThrownBy(() -> controller.resolve(
                APP_KEY, new MockHttpServletRequest("GET", resolvePath())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2KiB");
    }

    /** @param displayName 待计量名称 @return 最终三字段JSON字节数 */
    private int encodedSize(String displayName) throws Exception {
        return objectMapper.writeValueAsBytes(new WebAppApplicationResolutionResponse(
                APP_KEY, displayName, "project-01")).length;
    }

    /** @return 当前规范应用resolve路径 */
    private static String resolvePath() {
        return "/api/v1/app/applications/" + APP_KEY + "/resolve";
    }

    /**
     * 断言控制器以10001收敛合同外输入。
     *
     * @param invocation 可能抛出读取异常的控制器调用
     */
    private static void assertInvalidParameter(ThrowingInvocation invocation) {
        assertThatThrownBy(invocation::invoke)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(CommonErrorCode.INVALID_PARAMETER));
    }

    /** 允许测试lambda保留控制器读取请求流时声明的受检异常。 */
    @FunctionalInterface
    private interface ThrowingInvocation {

        /** @throws Exception 控制器读取或编码失败 */
        void invoke() throws Exception;
    }
}
