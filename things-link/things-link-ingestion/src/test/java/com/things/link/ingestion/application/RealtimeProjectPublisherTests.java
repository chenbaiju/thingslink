package com.things.link.ingestion.application;

import com.things.link.ingestion.infrastructure.RealtimeProperties;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.DeviceRealtimeUpdate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;

/** Redis Pub/Sub 故障必须被实时适配层吞掉，不能回传到 Kafka 消费。 */
@ExtendWith(MockitoExtension.class)
class RealtimeProjectPublisherTests {

    /** Redis 发布入口替身。 */
    @Mock private StringRedisTemplate redis;

    /** Redis 连接异常只增加失败指标且方法正常返回。 */
    @Test
    void redisFailureDoesNotEscapeRealtimePipeline() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RealtimeMetrics metrics = new RealtimeMetrics(registry);
        RealtimeProjectPublisher publisher = new RealtimeProjectPublisher(
                redis, new ObjectMapper(), new RealtimeProperties(), metrics);
        doThrow(new IllegalStateException("Redis unavailable"))
                .when(redis).convertAndSend(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());

        assertThatCode(() -> publisher.publish(update())).doesNotThrowAnyException();
        assertThat(registry.get(RealtimeMetrics.REDIS_PUBLISH).tag("result", "failure").counter().count())
                .isEqualTo(1.0);
    }

    /** 构造冻结的、含一个数值属性的增量。 */
    private static DeviceRealtimeUpdate update() {
        return new DeviceRealtimeUpdate(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                Uuid7.generate(), "1.0.0", Instant.parse("2026-08-09T08:00:00Z"), 1,
                "0123456789abcdef0123456789abcdef", Map.of("temperature", "26.5"),
                Map.of("temperature", "NUMBER"));
    }

}
