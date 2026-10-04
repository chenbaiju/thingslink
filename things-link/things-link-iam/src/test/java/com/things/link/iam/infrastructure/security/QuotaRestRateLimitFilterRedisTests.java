package com.things.link.iam.infrastructure.security;

import com.things.link.iam.application.RestQuotaPolicy;
import com.things.link.iam.application.RestQuotaPolicyResolver;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.testing.AbstractIntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** REST 配额过滤器的真实 Redis 令牌桶集成测试。 */
class QuotaRestRateLimitFilterRedisTests extends AbstractIntegrationTest {

    /** 真实 Redis 用于验证 Lua 脚本的 Redis TIME 补充行为，而不是只模拟脚本返回值。 */
    @Autowired
    private StringRedisTemplate redis;

    /** 每个用例结束后清除 ThreadLocal，防止后续请求继承错误的租户范围。 */
    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    /** 令牌桶耗尽后必须拒绝，Redis 服务端时间补充一个令牌后才可再次通过。 */
    @Test
    void rejectsAfterCapacityIsExhaustedAndPermitsAfterRedisTimeRefill() throws Exception {
        UUID ownerTenantId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        String bucketKey = "quota:rest:rate:read:account:" + accountId;
        QuotaRestRateLimitFilter filter = filter(ownerTenantId);
        TenantScope scope = new TenantScope(ownerTenantId, projectId, accountId);
        try {
            assertThat(invoke(filter, scope).getStatus()).isEqualTo(200);
            assertThat(invoke(filter, scope).getStatus()).isEqualTo(200);
            assertThat(invoke(filter, scope).getStatus()).isEqualTo(429);

            awaitRefill(filter, scope, Duration.ofSeconds(2));
        } finally {
            redis.delete(bucketKey);
        }
    }

    /**
     * @param ownerTenantId 项目所属租户；本用例让当前范围一致以聚焦令牌桶时间语义
     * @return 只启用账号读秒桶的过滤器，项目和租户窗口显式不限以避免干扰断言
     */
    private QuotaRestRateLimitFilter filter(UUID ownerTenantId) {
        RestQuotaPolicy policy = new RestQuotaPolicy(ownerTenantId, 2L, null, null, null);
        RestQuotaPolicyResolver resolver = projectId -> java.util.Optional.of(policy);
        var daily = org.mockito.Mockito.mock(
                com.things.link.project.application.ProjectDailyQuotaDecisionService.class);
        var recorder = org.mockito.Mockito.mock(
                com.things.link.project.application.ProjectUsageFactRecorder.class);
        org.mockito.Mockito.when(daily.decideTrustedProject(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(
                com.things.link.project.application.QuotaStatus.NORMAL);
        org.mockito.Mockito.when(recorder.record(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(true);
        return new QuotaRestRateLimitFilter(resolver, redis,
                new RestQuotaRateLimitMetrics(new SimpleMeterRegistry()), new ObjectMapper(), daily, recorder);
    }

    /**
     * @param filter 被测过滤器
     * @param scope 已认证且选定项目的请求范围
     * @return 过滤器写出的 HTTP 响应
     * @throws Exception Servlet 过滤链执行失败
     */
    private MockHttpServletResponse invoke(QuotaRestRateLimitFilter filter, TenantScope scope) throws Exception {
        TenantContext.set(scope);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/projects/devices");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> { });
        return response;
    }

    /**
     * @param filter 被测过滤器
     * @param scope 已认证且选定项目的请求范围
     * @param timeout 等待 Redis 服务端补充令牌的最长时长
     * @throws Exception Servlet 过滤链或轮询被中断
     */
    private void awaitRefill(QuotaRestRateLimitFilter filter, TenantScope scope, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (invoke(filter, scope).getStatus() == 200) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(25);
        }
        throw new AssertionError("Redis TIME 令牌桶未在预期时间内恢复额度");
    }
}
