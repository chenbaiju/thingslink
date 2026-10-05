package com.things.link.support.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** 解码统一事件并分发给各业务模块处理器。 */
@Component
public class CacheInvalidationSubscriber implements MessageListener {

    /** 损坏或未知事件不得杀死 Redis 监听线程。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(CacheInvalidationSubscriber.class);
    /** 统一 JSON 解码器。 */
    private final ObjectMapper objectMapper;
    /** Spring 收集的业务处理器；每个处理器只能处理固定资源。 */
    private final List<CacheInvalidationHandler> handlers;
    /** 统一低基数指标。 */
    private final CacheInvalidationMetrics metrics;

    /**
     * @param objectMapper 统一 JSON 解码器
     * @param handlers 各业务模块处理器
     * @param metrics 统一低基数指标
     */
    public CacheInvalidationSubscriber(ObjectMapper objectMapper, List<CacheInvalidationHandler> handlers,
                                       CacheInvalidationMetrics metrics) {
        this.objectMapper = objectMapper;
        this.handlers = List.copyOf(handlers);
        this.metrics = metrics;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        CacheInvalidationEvent event = null;
        try {
            event = objectMapper.readValue(message.getBody(), CacheInvalidationEvent.class);
            boolean applied = false;
            for (CacheInvalidationHandler handler : handlers) {
                try {
                    applied |= handler.handle(event);
                } catch (RuntimeException exception) {
                    // 一个派生缓存故障不能阻止安全/控制面处理器观察同一频道的后续事件。
                    metrics.recordInvalidation(event.resource(), CacheInvalidationMetrics.Result.FAILURE);
                    LOGGER.warn("缓存失效处理器失败 handler={} resource={} eventId={}",
                            handler.getClass().getSimpleName(), event.resource(), event.eventId(), exception);
                }
            }
            metrics.recordInvalidation(event.resource(), applied
                    ? CacheInvalidationMetrics.Result.APPLIED : CacheInvalidationMetrics.Result.IGNORED);
        } catch (RuntimeException exception) {
            if (event != null) {
                metrics.recordInvalidation(event.resource(), CacheInvalidationMetrics.Result.FAILURE);
            }
            LOGGER.warn("忽略损坏或处理失败的缓存失效事件 channel={}",
                    new String(message.getChannel(), StandardCharsets.UTF_8), exception);
        }
    }
}
