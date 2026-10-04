package com.things.link.bootstrap.telemetry.overview;

import com.things.link.device.domain.DeviceCurrentValue;
import com.things.link.device.domain.DeviceCurrentValueCache;
import com.things.link.device.domain.DeviceCurrentValueCache.ReportedValue;
import com.things.link.device.domain.DeviceCurrentValueCache.ValueKey;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 Redis 热影子测试，验证 Lua 的属性级PG接受序号 CAS。 */
class DeviceCurrentValueCacheTests extends AbstractIntegrationTest {
    /** 被测 Redis 热影子端口。 */
    @Autowired private DeviceCurrentValueCache cache;
    /** 独立读取真实Redis协议原文，区分Lua写入与JSON读出精度。 */
    @Autowired private StringRedisTemplate redis;

    /** 旧事件不能覆盖新值，相同事件时间只有更大PG序号才覆盖。 */
    @Test
    void mergesByPropertyOccurredAt() {
        UUID projectId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        ValueKey key = new ValueKey(deviceId, "temperature");
        Instant newer = Instant.parse("2026-08-08T04:00:00Z");

        cache.merge(projectId, deviceId, Map.of("temperature", new ReportedValue("30", newer, 4, "9007199254740993", null)));
        cache.merge(projectId, deviceId, Map.of("temperature", new ReportedValue("10", newer.minusSeconds(1), 3, "9007199254740992", null)));
        DeviceCurrentValue afterLate = cache.findAll(projectId, List.of(key)).get(key);
        assertThat(afterLate.value().asInt()).isEqualTo(30);
        assertThat(afterLate.occurredAt()).isEqualTo(newer);
        assertThat(afterLate.shadowVersion()).isEqualTo(4);

        cache.merge(projectId, deviceId, Map.of("temperature", new ReportedValue("31", newer, 5, "9007199254740994", null)));
        assertThat(cache.findAll(projectId, List.of(key)).get(key).value().asInt()).isEqualTo(31);
    }

    /** 项目 ID 是缓存键的一部分，同设备 ID 不能跨项目命中。 */
    @Test
    void isolatesValuesByProject() {
        UUID deviceId = UUID.randomUUID();
        UUID firstProject = UUID.randomUUID();
        UUID secondProject = UUID.randomUUID();
        ValueKey key = new ValueKey(deviceId, "online");
        cache.merge(firstProject, deviceId, Map.of("online", new ReportedValue("true", Instant.now(), 0, "1", null)));

        assertThat(cache.findAll(secondProject, List.of(key))).isEmpty();
    }

    /** 原文写入真实Redis后，热读仍保留嵌套小数与超Long整数；保留完整微秒时间。 */
    @Test
    void preservesExactJsonBytesAndNestedNumbersOnRedisRead() {
        UUID projectId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        String property = "precision";
        String exact = "{\"decimal\":0.12345678901234567890123456789,"
                + "\"nested\":[{\"decimal\":123456789.1234567890123456789,"
                + "\"integer\":9223372036854775808123456789}]}";
        Instant occurredAt = Instant.parse("2026-08-08T04:00:00.123456Z");
        String redisKey = "things-link:shadow:v2:{" + projectId + "}:" + deviceId + ':' + property;
        try {
            cache.merge(projectId, deviceId, Map.of(property, new ReportedValue(exact, occurredAt, 7, "7", null)));
            assertThat(redis.opsForValue().get(redisKey))
                    .isEqualTo("7|" + occurredAt + "|7||" + exact);
            DeviceCurrentValue value = cache.findAll(projectId, List.of(new ValueKey(deviceId, property)))
                    .get(new ValueKey(deviceId, property));
            assertThat(value).isNotNull();
            assertThat(new BigDecimal(value.value().get("decimal").asText()))
                    .isEqualByComparingTo("0.12345678901234567890123456789");
            assertThat(new BigDecimal(value.value().get("nested").get(0).get("decimal").asText()))
                    .isEqualByComparingTo("123456789.1234567890123456789");
            assertThat(value.value().get("nested").get(0).get("integer").asText())
                    .isEqualTo("9223372036854775808123456789");
            assertThat(value.occurredAt()).isEqualTo(occurredAt);
            assertThat(value.shadowVersion()).isEqualTo(7);
        } finally {
            redis.delete(redisKey);
        }
    }

}
