package com.things.link.ingestion.infrastructure;

import com.things.link.ingestion.application.RealtimeMetrics;
import com.things.link.ingestion.application.DashboardRealtimeRegistry;
import com.things.link.ingestion.application.RealtimePropertyBatch;
import com.things.link.ingestion.application.RealtimeSubscriptionRegistry;
import com.things.link.shared.message.DeviceRealtimeUpdate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.DeserializationFeature;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Redis 项目频道到本机 WebSocket 注册表的跨实例扇出适配器。
 *
 * <p>Redis Pub/Sub 是允许丢失的在线信号，不参与遥测事务。监听器必须捕获坏消息和 Redis 回调
 * 异常，只记录低基数指标；浏览器重连后由 S4-2 当前值接口恢复事实。</p>
 */
@Configuration(proxyBeanMethods = false)
public class RealtimeRedisSubscriber {

    /**
     * 注册项目频道监听。模式订阅只覆盖专用前缀，注册表仍按已认证 projectId 二次过滤。
     *
     * @param connectionFactory Redis 连接工厂
     * @param properties 实时频道配置
     * @param objectMapper 平台 JSON 序列化器
     * @param registry 本机会话注册表
     * @param metrics 实时链路指标
     * @return 生命周期由 Spring 管理的监听容器
     */
    @Bean
    RedisMessageListenerContainer realtimeRedisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            RealtimeProperties properties,
            ObjectMapper objectMapper,
            RealtimeSubscriptionRegistry registry,
            RealtimeMetrics metrics, DashboardRealtimeRegistry dashboards) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        // D-146：仅属性JSON原文使用精确树reader，不能改共享信封映射器或先经Double树。
        ObjectReader propertyReader = objectMapper.readerFor(JsonNode.class)
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        MessageListener listener = (message, pattern) ->
                consume(message, properties, objectMapper, propertyReader, registry, metrics, dashboards);
        container.addMessageListener(listener, new PatternTopic(properties.getChannelPrefix() + "*"));
        return container;
    }

    /**
     * 将共享契约解码为本机 JsonNode 批次，并互证频道后缀与正文项目身份。
     *
     * <p>ADR0072 要求 Redis 通道和正文都不能单独充当授权事实；二者不一致说明发布链路已串租户，
     * 必须整批丢弃并沿用既有低基数失败指标，不能把消息扇出到任一项目。</p>
     *
     * @param message Redis模式订阅收到的频道与正文
     * @param realtimeProperties 实时频道前缀配置
     * @param objectMapper 平台JSON序列化器
     * @param propertyReader 仅恢复属性原文数值的局部精确树reader
     * @param registry 本机会话注册表
     * @param metrics 实时链路指标
     */
    private static void consume(Message message, RealtimeProperties realtimeProperties, ObjectMapper objectMapper,
                                ObjectReader propertyReader,
                                RealtimeSubscriptionRegistry registry, RealtimeMetrics metrics, DashboardRealtimeRegistry dashboards) {
        try {
            String payload = new String(message.getBody(), StandardCharsets.UTF_8);
            DeviceRealtimeUpdate update = objectMapper.readValue(payload, DeviceRealtimeUpdate.class);
            String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
            String expectedChannel = realtimeProperties.getChannelPrefix() + update.projectId();
            if (!expectedChannel.equals(channel)) {
                throw new IllegalArgumentException("Redis实时频道与消息项目不一致");
            }
            // ADR0099：频道互证后先交键集合，不让旧属性值反序列化支配新提示。
            dashboards.invalidate(update.projectId(), update.deviceId(), update.propertiesJson().keySet());
            Map<String, JsonNode> properties = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : update.propertiesJson().entrySet()) {
                // 架构第5节：从Redis携带的原JSON文本读取十进制，最终WS仍输出原生JSON number。
                properties.put(entry.getKey(), propertyReader.readValue(entry.getValue()));
            }
            registry.fanout(new RealtimePropertyBatch(update.projectId(), update.deviceId(), update.occurredAt(),
                    update.shadowVersion(), update.thingModelVersionId(), update.modelVersion(),
                    update.propertyDataTypes(), properties, update.reportedRevisions()));
        } catch (RuntimeException exception) {
            metrics.recordRedisFailure();
        }
    }
}
