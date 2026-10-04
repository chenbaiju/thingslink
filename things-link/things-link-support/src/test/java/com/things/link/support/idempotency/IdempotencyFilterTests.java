package com.things.link.support.idempotency;

import com.things.link.testing.AbstractIntegrationTest;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.atomic.AtomicInteger;
import java.security.Principal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 幂等过滤器的端到端测试。
 *
 * <p>{@link IdempotencyStoreTests} 验证的是存储层；这里验证的是<b>决策逻辑</b>：
 * 什么时候执行业务、什么时候写完成墓碑、什么时候报错、失败后能不能重试。
 *
 * <p>核心断言方式是数<b>业务实际被执行了几次</b>。只断言响应状态码是不够的 ——
 * 只看响应状态不能证明业务没有重复执行，因此同时检查执行计数与数据库墓碑。
 */
@AutoConfigureMockMvc
@DisplayName("幂等过滤器（BACKEND_ARCHITECTURE.md 11.1）")
class IdempotencyFilterTests extends AbstractIntegrationTest {

    /** 测试范围模拟安全链已经建立的可信租户/项目/账号。 */
    private static final TenantScope TEST_SCOPE = new TenantScope(
            Uuid7.generate(), Uuid7.generate(), Uuid7.generate());

    /** 默认已认证主体；公共层存储键必须包含该身份。 */
    private static final Principal TEST_PRINCIPAL = () -> TEST_SCOPE.accountId().toString();

    /** 业务被真正执行的次数。完成墓碑命中不应该让它增加。 */
    static final AtomicInteger EXECUTION_COUNT = new AtomicInteger();

    /** 应用集合POST必须留给应用领域恢复原创建身份，不能被公共完成墓碑提前截获。 */
    @Test
    @DisplayName("应用创建路由使用领域幂等")
    void delegatesApplicationCreationToDomainIdempotency() {
        String path = "/api/v1/projects/019915d5-4be6-7a01-a1be-89a36f8047b0/applications";
        assertThat(IdempotencyFilter.usesDomainIdempotency("POST", path)).isTrue();
        assertThat(IdempotencyFilter.usesDomainIdempotency("PUT", path)).isFalse();
        assertThat(IdempotencyFilter.usesDomainIdempotency("POST", path + "/extra")).isFalse();
    }

    /** 看板集合POST必须留给看板领域恢复服务端生成身份，且不能扩大到其他方法或子路径。 */
    @Test
    @DisplayName("看板创建路由使用领域幂等")
    void delegatesDashboardCreationToDomainIdempotency() {
        String path = "/api/v1/projects/019915d5-4be6-7a01-a1be-89a36f8047b0/dashboards";
        assertThat(IdempotencyFilter.usesDomainIdempotency("POST", path)).isTrue();
        assertThat(IdempotencyFilter.usesDomainIdempotency("PUT", path)).isFalse();
        assertThat(IdempotencyFilter.usesDomainIdempotency("POST", path + "/extra")).isFalse();
    }

    @TestConfiguration
    static class ProbeConfig {
        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }
    }

    @RestController
    static class ProbeController {

        @PostMapping(
                path = {"/probe/devices", "/api/probe/devices"},
                consumes = MediaType.APPLICATION_JSON_VALUE)
        @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
        String create(@RequestBody String body) {
            return "{\"executions\":%d}".formatted(EXECUTION_COUNT.incrementAndGet());
        }

        @PostMapping(path = "/probe/failing", consumes = MediaType.APPLICATION_JSON_VALUE)
        String fail(@RequestBody String body) {
            EXECUTION_COUNT.incrementAndGet();
            throw new IllegalStateException("模拟业务失败");
        }

        @GetMapping("/probe/read")
        String read() {
            return "{\"executions\":%d}".formatted(EXECUTION_COUNT.incrementAndGet());
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void reset() {
        EXECUTION_COUNT.set(0);
        // 用 DELETE 而不是 TRUNCATE：TRUNCATE 是独立权限，应用角色不该拥有它
        // （能 TRUNCATE 就能绕过 RLS 清空整张表）
        jdbcTemplate.update("DELETE FROM sys_idempotency_record");
    }

    @Test
    @DisplayName("重复请求返回无正文完成墓碑，业务只执行一次")
    void rejectsCompletedRequestWithoutReplayingResponse() throws Exception {
        String key = "key-replay";
        String body = "{\"name\":\"温度传感器\"}";

        MvcResult first = performAuthenticated(post("/probe/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(IdempotencyFilter.IDEMPOTENCY_KEY_HEADER, key)
                        .content(body))
                .andReturn();

        MvcResult second = performAuthenticated(post("/probe/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(IdempotencyFilter.IDEMPOTENCY_KEY_HEADER, key)
                        .content(body))
                .andReturn();

        assertThat(EXECUTION_COUNT.get())
                .as("业务必须只执行一次，这是幂等的全部意义")
                .isEqualTo(1);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(second.getResponse().getStatus()).isEqualTo(409);
        assertThat(second.getResponse().getContentAsString()).contains("10014").doesNotContain("executions");
        assertThat(second.getResponse().getHeader("Idempotency-Replayed")).isNull();
    }

    /**
     * 同一个 key 配不同 body 说明调用方复用了幂等键，是客户端 bug。
     * 静默返回首次的响应会掩盖问题，让调用方以为第二次请求生效了。
     */
    @Test
    @DisplayName("相同幂等键配不同请求体返回 409")
    void rejectsSameKeyWithDifferentBody() throws Exception {
        String key = "key-conflict";

        performAuthenticated(post("/probe/devices")
                .contentType(MediaType.APPLICATION_JSON)
                .header(IdempotencyFilter.IDEMPOTENCY_KEY_HEADER, key)
                .content("{\"name\":\"设备甲\"}"));

        performAuthenticated(post("/probe/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(IdempotencyFilter.IDEMPOTENCY_KEY_HEADER, key)
                        .content("{\"name\":\"设备乙\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isConflict());

        assertThat(EXECUTION_COUNT.get()).isEqualTo(1);
    }

    /**
     * 不释放的话记录卡在 IN_PROGRESS，客户端后续重试全部收到 409，直到 24 小时后
     * 过期 —— 一次偶发故障变成一整天不可用。
     */
    @Test
    @DisplayName("业务失败后释放幂等键，允许重试")
    void releasesKeyWhenBusinessFails() throws Exception {
        String key = "key-failure";
        String body = "{\"x\":1}";

        try {
            performAuthenticated(post("/probe/failing")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(IdempotencyFilter.IDEMPOTENCY_KEY_HEADER, key)
                    .content(body));
        } catch (Exception ignored) {
            // standalone 环境下异常会冒泡，这里只关心幂等记录的状态
        }

        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sys_idempotency_record", Integer.class);
        assertThat(remaining)
                .as("业务失败后记录必须被删除，否则客户端永远拿不到重试机会")
                .isZero();
    }

    @Test
    @DisplayName("没有 Idempotency-Key 时每次都执行")
    void skipsWhenHeaderAbsent() throws Exception {
        String body = "{\"x\":1}";

        mockMvc.perform(post("/probe/devices").contentType(MediaType.APPLICATION_JSON).content(body));
        mockMvc.perform(post("/probe/devices").contentType(MediaType.APPLICATION_JSON).content(body));

        assertThat(EXECUTION_COUNT.get())
                .as("幂等是可选能力，未提供 key 时不应改变原有语义")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("GET 请求不做幂等处理")
    void skipsReadMethods() throws Exception {
        mockMvc.perform(get("/probe/read").header(IdempotencyFilter.IDEMPOTENCY_KEY_HEADER, "key-get"));
        mockMvc.perform(get("/probe/read").header(IdempotencyFilter.IDEMPOTENCY_KEY_HEADER, "key-get"));

        assertThat(EXECUTION_COUNT.get()).isEqualTo(2);
        Integer records = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sys_idempotency_record", Integer.class);
        assertThat(records).as("读方法不应产生幂等记录").isZero();
    }

    @Test
    @DisplayName("不同幂等键各自独立执行")
    void treatsDistinctKeysIndependently() throws Exception {
        String body = "{\"x\":1}";

        performAuthenticated(post("/probe/devices").contentType(MediaType.APPLICATION_JSON)
                .header(IdempotencyFilter.IDEMPOTENCY_KEY_HEADER, "key-a").content(body));
        performAuthenticated(post("/probe/devices").contentType(MediaType.APPLICATION_JSON)
                .header(IdempotencyFilter.IDEMPOTENCY_KEY_HEADER, "key-b").content(body));

        assertThat(EXECUTION_COUNT.get()).isEqualTo(2);
    }

    /** 无可信身份范围的公开请求即使伪造幂等头也不得把凭据类响应交给公共存储。 */
    @Test
    @DisplayName("未认证请求不进入公共幂等存储")
    void skipsWhenTrustedIdentityScopeIsAbsent() throws Exception {
        String body = "{\"x\":1}";

        mockMvc.perform(post("/probe/devices").principal(TEST_PRINCIPAL)
                .contentType(MediaType.APPLICATION_JSON)
                .header(IdempotencyFilter.IDEMPOTENCY_KEY_HEADER, "public-key").content(body));
        mockMvc.perform(post("/probe/devices").principal(TEST_PRINCIPAL)
                .contentType(MediaType.APPLICATION_JSON)
                .header(IdempotencyFilter.IDEMPOTENCY_KEY_HEADER, "public-key").content(body));

        assertThat(EXECUTION_COUNT.get()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM sys_idempotency_record", Integer.class)).isZero();
    }

    /** 同项目账号共享客户端生成器也不能互相占用或探测幂等键。 */
    @Test
    @DisplayName("同项目不同认证主体的相同原始键互不影响")
    void isolatesKeysAcrossAuthenticatedActors() throws Exception {
        String body = "{\"x\":1}";

        performAuthenticated(post("/probe/devices").contentType(MediaType.APPLICATION_JSON)
                .header(IdempotencyFilter.IDEMPOTENCY_KEY_HEADER, "shared-key").content(body),
                () -> UUID.randomUUID().toString());
        performAuthenticated(post("/probe/devices").contentType(MediaType.APPLICATION_JSON)
                .header(IdempotencyFilter.IDEMPOTENCY_KEY_HEADER, "shared-key").content(body),
                () -> UUID.randomUUID().toString());

        assertThat(EXECUTION_COUNT.get()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM sys_idempotency_record", Integer.class))
                .isEqualTo(2);
    }

    /** JSON 写请求即使没有 Idempotency-Key，也必须在进入 Controller 前执行统一大小门禁。 */
    @Test
    @DisplayName("超过 1 MiB 的 JSON 写请求返回 413")
    void rejectsOversizedWriteBeforeBusinessExecution() throws Exception {
        byte[] oversized = new byte[RequestBodySizeFilter.MAXIMUM_REQUEST_BYTES + 1];

        MvcResult result = mockMvc.perform(post("/api/probe/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversized))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(413);
        assertThat(result.getResponse().getContentAsString()).contains("10013");
        assertThat(EXECUTION_COUNT.get()).isZero();
    }

    /** @param request 待执行请求 @return 已执行断言入口 @throws Exception MockMvc失败 */
    private ResultActions performAuthenticated(MockHttpServletRequestBuilder request) throws Exception {
        return performAuthenticated(request, TEST_PRINCIPAL);
    }

    /**
     * 在MockMvc同步线程建立与生产安全链等价的可信范围；外层清理过滤器结束后仍显式清理，防止失败泄漏。
     *
     * @param request 待执行请求 @param principal 已认证主体 @return 已执行断言入口 @throws Exception MockMvc失败
     */
    private ResultActions performAuthenticated(MockHttpServletRequestBuilder request, Principal principal) throws Exception {
        TenantContext.set(TEST_SCOPE);
        try {
            return mockMvc.perform(request.principal(principal));
        } finally {
            TenantContext.clear();
        }
    }

}
