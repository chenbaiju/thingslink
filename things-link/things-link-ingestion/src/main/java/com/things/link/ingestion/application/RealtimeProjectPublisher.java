package com.things.link.ingestion.application;

import com.things.link.ingestion.infrastructure.RealtimeProperties;
import com.things.link.shared.message.DeviceRealtimeUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 将单组 Kafka 消费到的增量发布到项目隔离的 Redis Pub/Sub 频道。
 *
 * <p>Redis Pub/Sub 不保存离线消息，也不确认每个本机订阅者收到；它只负责将一个已提交的
 * Kafka 增量扇出给当前在线实例。连接故障只丢本次增量并记指标，绝不向 Kafka listener 抛异常，
 * 防止浏览器实时需求阻塞遥测链路。</p>
 */
@Component
public class RealtimeProjectPublisher {

    /** 不记录属性值，避免实时故障日志泄露遥测内容。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(RealtimeProjectPublisher.class);

    /** Redis 字符串发布入口。 */
    private final StringRedisTemplate redis;
    /** 统一 JSON 序列化器，确保数值、布尔值仍保留为 JSON 文本而非 Java toString。 */
    private final ObjectMapper objectMapper;
    /** S4 固定频道前缀。 */
    private final RealtimeProperties properties;
    /** 实时链路指标。 */
    private final RealtimeMetrics metrics;

    /**
     * @param redis Redis Pub/Sub 发布入口
     * @param objectMapper Boot 统一 JSON 映射器
     * @param properties 实时频道配置
     * @param metrics 实时指标
     */
    public RealtimeProjectPublisher(StringRedisTemplate redis, ObjectMapper objectMapper,
                                    RealtimeProperties properties, RealtimeMetrics metrics) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * 发布到目标项目频道。
     *
     * @param update 已提交且已由 Kafka 消费的属性增量
     */
    public void publish(DeviceRealtimeUpdate update) {
        try {
            String channel = properties.getChannelPrefix() + update.projectId();
            redis.convertAndSend(channel, objectMapper.writeValueAsString(update));
            // Redis 返回 0 代表当前没有任何本机实例订阅该项目，仍是成功且无需告警。
            metrics.recordRedisPublished();
        } catch (RuntimeException failure) {
            metrics.recordRedisFailure();
            LOGGER.warn("实时增量 Redis 扇出失败: messageId={} projectId={}",
                    update.messageId(), update.projectId(), failure);
        }
    }
}
