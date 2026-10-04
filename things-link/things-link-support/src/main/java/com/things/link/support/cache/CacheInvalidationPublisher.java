package com.things.link.support.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/** 在事实事务提交后把三类缓存事件广播到固定 Redis 频道。 */
@Component
public class CacheInvalidationPublisher {

    /** 所有缓存类别共用一个固定频道，避免频道数随租户线性增长。 */
    public static final String CHANNEL = "things-link:cache:invalidation:v1";
    /** 发布失败只记录低基数证据和业务 ID 日志，由各缓存自己的 TTL/安全策略兜底。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(CacheInvalidationPublisher.class);
    /** Redis 文本发布入口。 */
    private final StringRedisTemplate redis;
    /** 统一 JSON 编码器。 */
    private final ObjectMapper objectMapper;
    /** 统一低基数指标。 */
    private final CacheInvalidationMetrics metrics;
    /** 提交后先同步应用本机处理器，安全撤销不依赖 Redis 往返或五秒 TTL。 */
    private final List<CacheInvalidationHandler> localHandlers;

    /**
     * @param redis Redis 文本发布入口
     * @param objectMapper 统一 JSON 编码器
     * @param metrics 统一低基数指标
     */
    @Autowired
    public CacheInvalidationPublisher(StringRedisTemplate redis, ObjectMapper objectMapper,
                                      CacheInvalidationMetrics metrics,
                                      List<CacheInvalidationHandler> localHandlers) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.localHandlers = List.copyOf(localHandlers);
    }

    /** 隔离测试构造器不启动业务处理器，只验证事务后发布语义。 */
    public CacheInvalidationPublisher(StringRedisTemplate redis, ObjectMapper objectMapper,
                                      CacheInvalidationMetrics metrics) {
        this(redis, objectMapper, metrics, List.of());
    }

    /**
     * 事务提交后发布；回滚不得制造幽灵失效事件。
     *
     * @param event 已持久化事实对应的失效事件
     */
    public void publishAfterCommit(CacheInvalidationEvent event) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publish(event);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            /** @param status 只有 COMMITTED 才允许其他实例观察新版本 */
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) {
                    publish(event);
                }
            }
        });
    }

    /** @param event 立即广播的已验证事件，供无事务恢复操作和集成测试使用 */
    public void publish(CacheInvalidationEvent event) {
        applyLocally(event);
        try {
            redis.convertAndSend(CHANNEL, objectMapper.writeValueAsString(event));
            metrics.recordInvalidation(event.resource(), CacheInvalidationMetrics.Result.PUBLISHED);
        } catch (RuntimeException exception) {
            metrics.recordInvalidation(event.resource(), CacheInvalidationMetrics.Result.FAILURE);
            LOGGER.warn("缓存失效事件发布失败 resource={} resourceId={} version={}",
                    event.resource(), event.resourceId(), event.version(), exception);
        }
    }

    /** 本机先失效，再 best-effort 广播；任一处理器故障不能阻止其他缓存或跨实例收敛。 */
    private void applyLocally(CacheInvalidationEvent event) {
        for (CacheInvalidationHandler handler : localHandlers) {
            try {
                if (handler.handle(event)) {
                    metrics.recordInvalidation(event.resource(), CacheInvalidationMetrics.Result.APPLIED);
                }
            } catch (RuntimeException exception) {
                metrics.recordInvalidation(event.resource(), CacheInvalidationMetrics.Result.FAILURE);
                LOGGER.warn("本机缓存失效处理失败 handler={} resource={} eventId={}",
                        handler.getClass().getSimpleName(), event.resource(), event.eventId(), exception);
            }
        }
    }
}
