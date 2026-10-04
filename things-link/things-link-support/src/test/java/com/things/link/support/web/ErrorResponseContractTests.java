package com.things.link.support.web;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.support.trace.TraceContext;
import com.things.link.support.trace.TraceIdFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 错误响应契约测试。
 *
 * <p>验证架构文档 11.1 那条<b>全平台契约</b>：所有接口的错误响应形状一致，且带
 * traceId。这个形状一旦上线就改不动了 —— 客户端会按它写死处理逻辑 —— 所以值得
 * 用测试钉住。
 *
 * <h2>为什么用 standaloneSetup 而不是 @SpringBootTest</h2>
 * support 模块的 classpath 上有 JPA 与 Flyway，加载完整上下文会连带触发
 * DataSource 自动配置，于是一个纯 Web 层的契约测试变成了需要数据库才能跑 ——
 * 又慢又脆，而且失败原因会指向与被测内容完全无关的地方。
 *
 * <p>standaloneSetup 直接组装 Controller、异常处理器与过滤器，不加载任何 Spring
 * 上下文。代价是它<b>不验证这些组件在真实应用里确实被注册了</b>；那一层由
 * bootstrap 模块的上下文加载测试覆盖。
 */
@DisplayName("错误响应契约（BACKEND_ARCHITECTURE.md 11.1）")
class ErrorResponseContractTests {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 用于触发各类异常的探针接口。仅测试可见，不进生产代码。 */
    @RestController
    static class ProbeController {

        @GetMapping("/probe/business-error")
        String businessError() {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "设备不存在");
        }

        @GetMapping("/probe/unexpected-error")
        String unexpectedError() {
            throw new IllegalStateException("内部细节：table device_shadow, jdbc://user:password@host/db");
        }
    }

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new TraceIdFilter())
                .build();
    }

    @Test
    @DisplayName("业务异常返回四字段结构，HTTP 状态码与业务码并存")
    void businessErrorFollowsContract() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/probe/business-error"))
                .andReturn().getResponse();
        JsonNode body = OBJECT_MAPPER.readTree(response.getContentAsString());

        // HTTP 状态码表达语义类别
        assertThat(response.getStatus()).isEqualTo(404);
        // 业务错误码表达具体原因 —— 二者都要有（架构文档 11.1）
        assertThat(body.get("code").asInt()).isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND.code());
        assertThat(body.get("message").asText()).isEqualTo("设备不存在");
        assertThat(body.get("traceId").asText()).isNotBlank();
        // details 无内容时是空数组而非 null，客户端不必做 null 判断
        assertThat(body.get("details").isArray()).isTrue();
        assertThat(body.get("details")).isEmpty();
    }

    @Test
    @DisplayName("响应头与响应体中的 traceId 一致")
    void traceIdIsConsistentBetweenHeaderAndBody() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/probe/business-error"))
                .andReturn().getResponse();
        JsonNode body = OBJECT_MAPPER.readTree(response.getContentAsString());

        String header = response.getHeader(TraceContext.TRACE_ID_HEADER);
        assertThat(header).isNotBlank();
        assertThat(body.get("traceId").asText())
                .as("用户拿到的响应头必须能对上日志里的 traceId，否则报障时无从定位")
                .isEqualTo(header);
    }

    @Test
    @DisplayName("每个请求生成不同的 traceId")
    void generatesDistinctTraceIdPerRequest() throws Exception {
        String first = mockMvc.perform(get("/probe/business-error"))
                .andReturn().getResponse().getHeader(TraceContext.TRACE_ID_HEADER);
        String second = mockMvc.perform(get("/probe/business-error"))
                .andReturn().getResponse().getHeader(TraceContext.TRACE_ID_HEADER);

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("沿用客户端传入的合法 traceId，以串起完整调用链")
    void reusesIncomingTraceId() throws Exception {
        String incoming = "0af7651916cd43dd8448eb211c80319c";

        MockHttpServletResponse response = mockMvc
                .perform(get("/probe/business-error").header(TraceContext.TRACE_ID_HEADER, incoming))
                .andReturn().getResponse();

        assertThat(response.getHeader(TraceContext.TRACE_ID_HEADER)).isEqualTo(incoming);
        assertThat(OBJECT_MAPPER.readTree(response.getContentAsString()).get("traceId").asText())
                .isEqualTo(incoming);
    }

    /**
     * 含换行符的 traceId 能在日志里伪造出额外的行，让排障时看到并不存在的事件。
     */
    @Test
    @DisplayName("拒绝含非法字符的传入 traceId，防日志注入")
    void rejectsUnsafeIncomingTraceId() throws Exception {
        String injection = "abc\n2026-08-01 ERROR 伪造的日志行";

        String actual = mockMvc
                .perform(get("/probe/business-error").header(TraceContext.TRACE_ID_HEADER, injection))
                .andReturn().getResponse().getHeader(TraceContext.TRACE_ID_HEADER);

        assertThat(actual).isNotEqualTo(injection);
        assertThat(actual).doesNotContain("\n");
    }

    @Test
    @DisplayName("超长的传入 traceId 被拒绝，防日志膨胀")
    void rejectsOverlongIncomingTraceId() throws Exception {
        String overlong = "a".repeat(4096);

        String actual = mockMvc
                .perform(get("/probe/business-error").header(TraceContext.TRACE_ID_HEADER, overlong))
                .andReturn().getResponse().getHeader(TraceContext.TRACE_ID_HEADER);

        assertThat(actual).hasSizeLessThanOrEqualTo(64);
    }

    /**
     * 不存在的路径必须是 404，不能落进兜底变成 500。
     *
     * <p>这个缺陷是手工 curl 真实服务时发现的 —— 单测里没有「访问不存在的路径」
     * 这种场景，因为测试总是精确地打已知端点。
     */
    @Test
    @DisplayName("不存在的路径返回 404 而非 500")
    void unknownPathReturnsNotFound() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/probe/no-such-endpoint"))
                .andReturn().getResponse();

        assertThat(response.getStatus())
                .as("落进兜底会变成 500，客户端会误以为服务端故障并重试；"
                        + "而且每个拼错的 URL 都会写一条 ERROR 日志，淹没真正的故障")
                .isEqualTo(404);
    }

    /**
     * 未预期异常的消息可能包含表名、SQL 片段、连接串。泄露给外部会成为攻击面 ——
     * 真实原因只进服务端日志，客户端凭 traceId 找运维定位。
     */
    @Test
    @DisplayName("未预期异常不泄露内部细节")
    void doesNotLeakInternalsOnUnexpectedError() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/probe/unexpected-error"))
                .andReturn().getResponse();
        String content = response.getContentAsString();

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(OBJECT_MAPPER.readTree(content).get("code").asInt())
                .isEqualTo(CommonErrorCode.INTERNAL_ERROR.code());
        assertThat(content)
                .as("响应体不得出现表名、连接串等内部细节")
                .doesNotContain("device_shadow", "jdbc://", "password");
        assertThat(OBJECT_MAPPER.readTree(content).get("traceId").asText()).isNotBlank();
    }

}
