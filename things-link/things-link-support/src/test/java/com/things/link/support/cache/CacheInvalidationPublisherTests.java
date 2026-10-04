package com.things.link.support.cache;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 统一失效事件只能在事实事务提交后广播，回滚不能制造幽灵失效。 */
class CacheInvalidationPublisherTests {
    /** 每个用例后清理线程事务状态，避免污染同线程执行的其他测试。 */
    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /** 成功提交后才发送固定频道 JSON。 */
    @Test
    void publishesOnlyAfterCommit() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        CacheInvalidationMetrics metrics = mock(CacheInvalidationMetrics.class);
        var publisher = new CacheInvalidationPublisher(redis, new ObjectMapper(), metrics);
        TransactionSynchronizationManager.initSynchronization();

        publisher.publishAfterCommit(event());
        verify(redis, never()).convertAndSend(anyString(), anyString());
        TransactionSynchronizationManager.getSynchronizations().forEach(
                synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));

        verify(redis).convertAndSend(org.mockito.ArgumentMatchers.eq(CacheInvalidationPublisher.CHANNEL), anyString());
    }

    /** 事务回滚时不向其他实例发布无效版本。 */
    @Test
    void rollbackDoesNotPublish() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        var publisher = new CacheInvalidationPublisher(
                redis, new ObjectMapper(), mock(CacheInvalidationMetrics.class));
        TransactionSynchronizationManager.initSynchronization();

        publisher.publishAfterCommit(event());
        TransactionSynchronizationManager.getSynchronizations().forEach(
                synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(redis, never()).convertAndSend(anyString(), anyString());
    }

    /** 安全撤销在提交后先驱逐本机，再依赖 Redis 广播其他实例。 */
    @Test
    void appliesLocalHandlerAfterCommit() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        CacheInvalidationHandler handler = mock(CacheInvalidationHandler.class);
        CacheInvalidationEvent event = event();
        when(handler.handle(event)).thenReturn(true);
        var publisher = new CacheInvalidationPublisher(redis, new ObjectMapper(),
                mock(CacheInvalidationMetrics.class), List.of(handler));
        TransactionSynchronizationManager.initSynchronization();

        publisher.publishAfterCommit(event);
        verify(handler, never()).handle(event);
        TransactionSynchronizationManager.getSynchronizations().forEach(
                synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));

        verify(handler).handle(event);
    }

    /** @return 一个不含业务指标标签的控制面事件 */
    private static CacheInvalidationEvent event() {
        return new CacheInvalidationEvent(UUID.randomUUID(), CacheResource.QUOTA_POLICY,
                CacheInvalidationOperation.UPDATE, UUID.randomUUID(), null, 2L, 0L, Instant.now());
    }
}
