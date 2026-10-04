package com.things.link.enduser.application;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** App CLAIM 用户/IP 低基数限流的纯逻辑合同。 */
class AppDeviceClaimRateLimiterTests {

    /** 同一用户前 30 次放行，第 31 次拒绝；不依赖令牌是否真实存在。 */
    @Test
    void limitsAuthenticatedUserDimension() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        Map<String, Long> counters = new HashMap<>();
        when(values.increment(anyString())).thenAnswer(invocation ->
                counters.merge(invocation.getArgument(0), 1L, Long::sum));
        AppDeviceClaimRateLimiter limiter = new AppDeviceClaimRateLimiter(redis);
        UUID appUserId = UUID.randomUUID();

        for (int attempt = 0; attempt < 30; attempt++) {
            assertThat(limiter.tryAcquire(appUserId, null)).isTrue();
        }
        assertThat(limiter.tryAcquire(appUserId, null)).isFalse();
    }

    /** Redis 故障 fail-open，PostgreSQL 令牌与关系仲裁仍是权威防线。 */
    @Test
    void redisFailureFailsOpen() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString()))
                .thenThrow(new DataAccessResourceFailureException("redis unavailable"));
        AppDeviceClaimRateLimiter limiter = new AppDeviceClaimRateLimiter(redis);

        assertThat(limiter.tryAcquire(UUID.randomUUID(), "127.0.0.1")).isTrue();
    }
}
