package com.things.link.device.infrastructure.cache;

import com.things.link.device.domain.DeviceCurrentValue;
import com.things.link.device.domain.DeviceCurrentValueCache;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.DeserializationFeature;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 基于 Redis 字符串键的设备当前值热副本。
 *
 * <p>键包含项目、设备和属性三个隔离维度；v2值前缀保存接受序号、完整发生时间、desired版本与模型来源。Lua 在单键内完成比较、
 * 写入和续期，避免两个已提交事务的回调倒序到达时把新值覆盖掉。</p>
 */
@Component
public class RedisDeviceCurrentValueCache implements DeviceCurrentValueCache {
    /** 24 小时只保留活跃设备，冷设备由 PostgreSQL 回源后自然升温。 */
    private static final Duration ENTRY_TTL = Duration.ofHours(24);
    /** 值字段分隔符；JSON 可以包含分隔符，但解析只切前四个位置。 */
    private static final String SEPARATOR = "|";
    /** 按规范正Long字符串长度/字典序比较，不使用Lua浮点数承载接受序号。 */
    private static final DefaultRedisScript<Long> MERGE_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if current then
              local separator = string.find(current, '|', 1, true)
              if not separator then return redis.error_reply('invalid reported cache') end
              local revision = string.sub(current, 1, separator - 1)
              if not string.match(revision, '^[1-9][0-9]*$') or #revision > 19
                 or (#revision == 19 and revision > '9223372036854775807') then
                return redis.error_reply('invalid reported revision')
              end
              if #revision > #ARGV[1] or (#revision == #ARGV[1] and revision >= ARGV[1]) then
                return 0
              end
            end
            redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
            return 1
            """, Long.class);

    /** Redis 字符串客户端。 */
    private final StringRedisTemplate redisTemplate;
    /** 仅缓存JSON值恢复精确小数，前四个字段分别保留接受序号、完整时间、desired版本和模型来源。 */
    private final ObjectReader currentValueReader;

    /** @param redisTemplate Redis 客户端 @param objectMapper JSON 解析器 */
    public RedisDeviceCurrentValueCache(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.currentValueReader = objectMapper.readerFor(JsonNode.class)
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Map<ValueKey, DeviceCurrentValue> findAll(UUID projectId, Collection<ValueKey> keys) {
        List<ValueKey> orderedKeys = new ArrayList<>(keys);
        if (orderedKeys.isEmpty()) {
            return Map.of();
        }
        List<String> redisKeys = orderedKeys.stream().map(key -> redisKey(projectId, key)).toList();
        List<String> cachedValues = redisTemplate.opsForValue().multiGet(redisKeys);
        Map<ValueKey, DeviceCurrentValue> result = new LinkedHashMap<>();
        if (cachedValues == null) {
            return result;
        }
        for (int index = 0; index < cachedValues.size(); index++) {
            String cached = cachedValues.get(index);
            if (cached != null) {
                ValueKey key = orderedKeys.get(index);
                result.put(key, decode(key, cached));
            }
        }
        return result;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public void merge(UUID projectId, UUID deviceId, Map<String, ReportedValue> values) {
        String ttl = Long.toString(ENTRY_TTL.toMillis());
        for (Map.Entry<String, ReportedValue> property : values.entrySet()) {
            ReportedValue fact = property.getValue();
            String revision = com.things.link.shared.message.ReportedRevision.require(fact.reportedRevision());
            String source = fact.thingModelVersionId() == null ? "" : fact.thingModelVersionId().toString();
            String value = revision + SEPARATOR + fact.occurredAt() + SEPARATOR + fact.shadowVersion()
                    + SEPARATOR + source + SEPARATOR + fact.jsonValue();
            redisTemplate.execute(MERGE_SCRIPT,
                    List.of(redisKey(projectId, new ValueKey(deviceId, property.getKey()))), revision, value, ttl);
        }
    }

    /** v2前四段仅为可信协议元数据；JSON原文可含任意竖线，不能全量拆分。 */
    private DeviceCurrentValue decode(ValueKey key, String cached) {
        String[] fields = cached.split("\\|", 5);
        if (fields.length != 5) throw new IllegalStateException("Redis热影子v2格式无效");
        String revision = com.things.link.shared.message.ReportedRevision.require(fields[0]);
        Instant occurredAt = Instant.parse(fields[1]);
        int version = Integer.parseInt(fields[2]);
        if (version < 0) throw new IllegalStateException("desired版本无效");
        UUID source = fields[3].isEmpty() ? null : UUID.fromString(fields[3]);
        JsonNode value = currentValueReader.readValue(fields[4]);
        return new DeviceCurrentValue(key.deviceId(), key.propertyKey(), value, occurredAt, version, revision, source);
    }

    /** 键显式携带项目隔离轴，防止相同设备或属性标识跨项目碰撞。 */
    private static String redisKey(UUID projectId, ValueKey key) {
        // 项目花括号是 Redis 集群哈希标签，使同一批项目键可安全执行 MGET。
        return "things-link:shadow:v2:{" + projectId + "}:" + key.deviceId() + ':' + key.propertyKey();
    }
}
