package com.things.link.telemetry.infrastructure.cache;

import com.things.link.telemetry.application.OverviewCache;
import com.things.link.telemetry.application.OverviewSnapshot;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;

/** Redis 项目概要 JSON 快照实现；值版本进入键名，契约变更时不会误读旧值。 */
@Component
public class RedisOverviewCache implements OverviewCache {
    /** Redis 字符串客户端。 */
    private final StringRedisTemplate redisTemplate;
    /** JSON 编解码器。 */
    private final ObjectMapper objectMapper;
    /** 缓存时长配置。 */
    private final OverviewCacheProperties properties;

    /** @param redisTemplate Redis 客户端 @param objectMapper JSON 编解码器 @param properties 缓存配置 */
    public RedisOverviewCache(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                              OverviewCacheProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<OverviewSnapshot> find(UUID projectId) {
        String value = redisTemplate.opsForValue().get(redisKey(projectId));
        if (value == null) {
            return Optional.empty();
        }
        try {
            OverviewSnapshot snapshot = objectMapper.readValue(value, OverviewSnapshot.class);
            if (snapshot.generatedAt() == null || snapshot.window() == null || snapshot.devices() == null
                    || snapshot.messages24h() == null || snapshot.alarmRate() == null || snapshot.alarmSeverityDeviceCounts() == null) {
                throw new IllegalArgumentException("概要缓存字段不完整");
            }
            return Optional.of(snapshot);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("概要缓存 JSON 无法解码", exception);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void put(UUID projectId, OverviewSnapshot snapshot) {
        redisTemplate.opsForValue().set(redisKey(projectId), objectMapper.writeValueAsString(snapshot),
                properties.getCacheTtl());
    }

    /** 使用项目 hash tag；未来同项目的概要组件快照可在 Redis Cluster 同槽批量读取。 */
    private static String redisKey(UUID projectId) {
        return "things-link:overview:{" + projectId + "}:v3";
    }
}
