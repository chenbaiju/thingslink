package com.things.link.task.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 任务派发双桶在不限、禁用和 Redis 异常时的保守语义。 */
class TaskDispatchRateLimiterTests {

    /** Redis 门面替身，用于精确控制 Lua 返回值和故障。 */
    private StringRedisTemplate redis;
    /** 被测双层令牌桶。 */
    private TaskDispatchRateLimiter limiter;
    /** 租户隔离键。 */
    private UUID tenantId;
    /** 项目隔离键。 */
    private UUID projectId;

    /** 为每个用例创建无共享状态的门面。 */
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        limiter = new TaskDispatchRateLimiter(redis);
        tenantId = UUID.randomUUID();
        projectId = UUID.randomUUID();
    }

    /** 两层都为 NULL 表示套餐不限速，不应为了无效检查访问 Redis。 */
    @Test
    void unlimitedPolicyAllowsWithoutRedisRoundTrip() {
        assertThat(limiter.tryAcquire(tenantId, projectId, null, null)).isTrue();

        verify(redis, never()).execute(any(), any(), any(Object[].class));
    }

    /** 任一层为零都表示禁止新增派发，且必须在扣减另一层之前拒绝。 */
    @Test
    void zeroAtEitherScopeDeniesWithoutConsumingTokens() {
        assertThat(limiter.tryAcquire(tenantId, projectId, 100L, 0L)).isFalse();
        assertThat(limiter.tryAcquire(tenantId, projectId, 0L, 20L)).isFalse();

        verify(redis, never()).execute(any(), any(), any(Object[].class));
    }

    /** Lua 返回 NULL 不是成功；连接抖动不能绕过套餐限制。 */
    @Test
    void nullScriptResultFailsClosed() {
        when(redis.execute(any(), any(), any(Object[].class))).thenReturn(null);

        assertThat(limiter.tryAcquire(tenantId, projectId, null, 20L)).isFalse();
    }

    /** Redis 故障采用 fail-closed，目标留给租约过期后的后续扫描重试。 */
    @Test
    void redisFailureFailsClosed() {
        when(redis.execute(any(), any(), any(Object[].class)))
                .thenThrow(new IllegalStateException("redis down"));

        assertThat(limiter.tryAcquire(tenantId, projectId, 100L, 20L)).isFalse();
    }
}
