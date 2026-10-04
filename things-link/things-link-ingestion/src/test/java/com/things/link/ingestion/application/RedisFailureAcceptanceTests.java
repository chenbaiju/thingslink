package com.things.link.ingestion.application;

import com.things.link.project.application.EffectiveQuotaPolicy;
import com.things.link.project.application.QuotaRuntimeMetrics;
import com.things.link.support.observability.DataPlaneMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** S7-5 使用可实际停机的专用 Redis 容器验证数据面连续请求降级。 */
@Tag("s7-recovery")
class RedisFailureAcceptanceTests {

    /** 与仓库 Testcontainers/deploy 基线一致，避免用内存替身伪造连接失败。 */
    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7.4-alpine");
    /** 故障请求次数；多次调用用于证明不是只对一次异常做兜底。 */
    private static final int REQUESTS_DURING_OUTAGE = 8;

    /** Redis 容器停机期间连续短窗请求仍应 fail-open，且每次都留下低基数降级指标。 */
    @Test
    void uplinkRequestsContinueWhileRedisIsDown() {
        GenericContainer<?> redisContainer = new GenericContainer<>(REDIS_IMAGE).withExposedPorts(6379);
        LettuceConnectionFactory connectionFactory = null;
        try {
            redisContainer.start();
            connectionFactory = connectionFactory(redisContainer);
            StringRedisTemplate redis = new StringRedisTemplate(connectionFactory);
            redis.afterPropertiesSet();
            SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
            DeviceUplinkRateLimiter limiter = new DeviceUplinkRateLimiter(redis,
                    new DataPlaneMetrics(meterRegistry), new QuotaRuntimeMetrics(meterRegistry));
            UUID tenantId = UUID.randomUUID();
            EffectiveQuotaPolicy policy = EffectiveQuotaPolicy.safeDefault(tenantId);

            assertThat(limiter.tryAcquire(tenantId, UUID.randomUUID(), policy)).isTrue();
            redisContainer.stop();
            double failuresBefore = failOpenCount(meterRegistry);
            Instant startedAt = Instant.now();

            for (int request = 0; request < REQUESTS_DURING_OUTAGE; request++) {
                assertThat(limiter.tryAcquire(tenantId, UUID.randomUUID(), policy)).isTrue();
            }

            assertThat(failOpenCount(meterRegistry) - failuresBefore).isEqualTo(REQUESTS_DURING_OUTAGE);
            // 150ms 客户端超时确保降级不会把接入线程挂到 Redis 默认的长连接超时。
            assertThat(Duration.between(startedAt, Instant.now())).isLessThan(Duration.ofSeconds(5));
        } finally {
            if (connectionFactory != null) {
                connectionFactory.destroy();
            }
            if (redisContainer.isRunning()) {
                redisContainer.stop();
            }
        }
    }

    /**
     * 创建短命令超时客户端；测试目标是持续请求的故障策略，而不是等待 TCP 默认超时。
     *
     * @param container 已启动专用 Redis
     * @return 初始化完成的 Lettuce 连接工厂
     */
    private static LettuceConnectionFactory connectionFactory(GenericContainer<?> container) {
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(
                container.getHost(), container.getMappedPort(6379));
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(150))
                .shutdownTimeout(Duration.ZERO)
                .build();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(standalone, client);
        factory.afterPropertiesSet();
        return factory;
    }

    /** @param registry 测试注册表 @return 数据面 Redis 故障放行累计值。 */
    private static double failOpenCount(SimpleMeterRegistry registry) {
        return registry.get(QuotaRuntimeMetrics.RATE_LIMIT)
                .tags("dimension", "uplink_device", "result", "fail_open")
                .counter().count();
    }
}
