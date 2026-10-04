package com.things.link.ingestion.application;

import com.things.link.project.application.EffectiveQuotaPolicy;
import io.micrometer.core.instrument.MeterRegistry;
import com.things.link.testing.AbstractKafkaIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 单设备上行令牌桶的真实 Redis 集成测试。 */
class DeviceUplinkRateLimiterTests extends AbstractKafkaIntegrationTest {

    /** 被测 Redis 令牌桶。 */
    @Autowired private DeviceUplinkRateLimiter limiter;
    /** 用于断言超限计数与清理测试键。 */
    @Autowired private StringRedisTemplate redis;
    /** 验证 Redis 设备明细之外还有低基数 Prometheus 总计。 */
    @Autowired private MeterRegistry meterRegistry;

    /** 满桶允许 20 条、后续消息计数丢弃，并按每秒 1 个额度恢复。 */
    @Test
    void limitsPerDeviceCountsDropsAndRefills() throws InterruptedException {
        UUID deviceId = UUID.randomUUID();
        String slot = "{" + deviceId + "}";
        String bucketKey = "ingestion:uplink:rate:" + slot + ":bucket";
        String droppedKey = "ingestion:uplink:rate:" + slot + ":dropped";
        EffectiveQuotaPolicy deterministicPolicy = policy(1L, 20L, null, null);
        try {
            for (int index = 0; index < DeviceUplinkRateLimiter.BUCKET_CAPACITY; index++) {
                assertThat(limiter.tryAcquire(deviceId, deviceId, deterministicPolicy)).isTrue();
            }
            assertThat(limiter.tryAcquire(deviceId, deviceId, deterministicPolicy)).isFalse();
            assertThat(redis.opsForValue().get(droppedKey)).isEqualTo("1");
            assertThat(meterRegistry.get("thingslink.ingestion.uplink.rate_limited").counter().count())
                    .isGreaterThanOrEqualTo(1D);

            awaitRefill(deviceId, deterministicPolicy, Duration.ofSeconds(2));
        } finally {
            redis.delete(java.util.List.of(bucketKey, droppedKey));
        }
    }

    /**
     * 两台设备共享同一所有者租户的秒窗口；跨租户不能互相消耗额度。
     */
    @Test
    void sharesTenantWindowAcrossDevicesButNotAcrossTenants() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        EffectiveQuotaPolicy tenantOneMessage = policy(100L, 100L, 1L, 1_000L);

        assertThat(limiter.tryAcquire(tenantA, UUID.randomUUID(), tenantOneMessage)).isTrue();
        assertThat(limiter.tryAcquire(tenantA, UUID.randomUUID(), tenantOneMessage)).isFalse();
        assertThat(limiter.tryAcquire(tenantB, UUID.randomUUID(), tenantOneMessage)).isTrue();

        redis.delete(java.util.List.of(
                "ingestion:uplink:rate:{" + tenantA + "}:tenant:1000",
                "ingestion:uplink:rate:{" + tenantA + "}:tenant:60000",
                "ingestion:uplink:rate:{" + tenantB + "}:tenant:1000",
                "ingestion:uplink:rate:{" + tenantB + "}:tenant:60000"));
    }

    /**
     * 套餐策略可即时改变同一租户对新设备的设备桶突发容量，不能继续读取 S3 的固定 20。
     */
    @Test
    void appliesSuppliedPolicyInsteadOfFixedDeviceBaseline() {
        UUID tenantId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        EffectiveQuotaPolicy oneBurst = policy(1L, 1L, 1_000L, 60_000L);
        EffectiveQuotaPolicy twoBurst = policy(1L, 2L, 1_000L, 60_000L);

        assertThat(limiter.tryAcquire(tenantId, deviceId, oneBurst)).isTrue();
        assertThat(limiter.tryAcquire(tenantId, deviceId, oneBurst)).isFalse();
        assertThat(limiter.tryAcquire(tenantId, UUID.randomUUID(), twoBurst)).isTrue();
        assertThat(limiter.tryAcquire(tenantId, UUID.randomUUID(), twoBurst)).isTrue();
    }

    /** 等待 Redis 服务端时间产生至少一个新令牌，避免固定 sleep 在慢机器上产生偶发失败。 */
    private void awaitRefill(UUID deviceId, EffectiveQuotaPolicy policy, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (limiter.tryAcquire(deviceId, deviceId, policy)) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(25);
        }
        throw new AssertionError("令牌桶未在预期时间内恢复额度");
    }

    /** 构造只改变上行短窗口字段的完整有效策略。 */
    private static EffectiveQuotaPolicy policy(Long deviceRefill, Long deviceBurst, Long tenantPerSecond,
                                               Long tenantPerMinute) {
        return new EffectiveQuotaPolicy(UUID.randomUUID(), UUID.randomUUID(), 1L, 1L,
                deviceRefill, deviceBurst, tenantPerSecond, tenantPerMinute,
                20L, 10L, 1_200L, 600L, 200L);
    }
}
