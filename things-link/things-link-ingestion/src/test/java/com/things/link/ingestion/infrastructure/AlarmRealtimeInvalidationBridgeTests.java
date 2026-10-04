package com.things.link.ingestion.infrastructure;

import com.things.link.ingestion.application.DashboardRealtimeRegistry;
import com.things.link.ingestion.application.RealtimeMetrics;
import com.things.link.shared.message.AlarmStateInvalidated;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListenerFactory;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 提示事件跟随真实Spring事务同步，回滚不发且提交后Redis错误不能传播回业务。 */
class AlarmRealtimeInvalidationBridgeTests {
    /** 固定来源租户仅存在于本地类型事件，不进入提示正文。 */ private final UUID tenant = UUID.randomUUID();
    /** 频道与正文双向绑定项目。 */ private final UUID project = UUID.randomUUID();
    /** 命中冻结告警组的设备。 */ private final UUID device = UUID.randomUUID();
    /** 发布端无需真实Redis，仅验证提示边界与失败语义。 */ private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    /** 收件端不自行查询数据库。 */ private final DashboardRealtimeRegistry registry = mock(DashboardRealtimeRegistry.class);
    /** 提示失败使用有限度量。 */ private final RealtimeMetrics metrics = mock(RealtimeMetrics.class);
    /** 被测真实桥接器。 */ private final AlarmRealtimeInvalidationBridge bridge = new AlarmRealtimeInvalidationBridge(redis, registry, metrics);

    /** 独立发布者也必须在每个测试后释放。 */
    @AfterEach
    void cleanup() { bridge.shutdown(); }

    /** 在事务中不可早发；无事务和回滚均无消息，成功提交后仅一次。 */
    @Test
    void publishesOnlyAfterCommitAndKeepsCommittedSuccessWhenRedisFails() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().registerSingleton("alarmBridge", bridge);
            context.registerBean(TransactionalEventListenerFactory.class);
            context.refresh();
            AlarmStateInvalidated event = new AlarmStateInvalidated(tenant, project, device);
            TransactionTemplate transaction = new TransactionTemplate(new SynchronizationTransactions());
            context.publishEvent(event); verifyNoInteractions(redis);
            transaction.executeWithoutResult(status -> { context.publishEvent(event); verifyNoInteractions(redis); status.setRollbackOnly(); });
            verifyNoInteractions(redis);
            transaction.executeWithoutResult(status -> { context.publishEvent(event); verifyNoInteractions(redis); });
            verify(redis, timeout(2000)).convertAndSend(eq(AlarmRealtimeInvalidationBridge.CHANNEL_PREFIX + project), anyString());
            when(redis.convertAndSend(anyString(), anyString())).thenThrow(new IllegalStateException("private infrastructure detail"));
            assertThatCode(() -> transaction.executeWithoutResult(status -> context.publishEvent(event))).doesNotThrowAnyException();
            verify(metrics, timeout(2000)).recordRedisFailure();
        }
    }

    /** 合法频道只发送项目/设备，跨频道、重复字段、额外数据和超限帧全部丢弃。 */
    @Test
    void consumesOnlyExactChannelBoundKeys() {
        String body = "{\"projectId\":\"" + project + "\",\"deviceId\":\"" + device + "\"}";
        bridge.consume(message(project, body)); verify(registry).invalidateAlarms(project, device);
        bridge.consume(message(UUID.randomUUID(), body));
        bridge.consume(message(project, body.replace("}", ",\"value\":1}")));
        bridge.consume(message(project, body.replace("}", ",\"deviceId\":\"" + device + "\"}")));
        bridge.consume(message(project, " ".repeat(257)));
        verify(registry, times(1)).invalidateAlarms(project, device); verify(metrics, times(4)).recordRedisFailure();
    }

    /** 过载不在调用线程执行，128队列不增长；关闭清空待处理并拒绝新任务。 */
    @Test
    void consumerOverloadAndShutdownDropHintsWithoutCallerRuns() throws Exception {
        ThreadPoolExecutor executor = (ThreadPoolExecutor) bridge.alarmRealtimeConsumerExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger queuedExecuted = new AtomicInteger();
        try {
            executor.execute(() -> {
                entered.countDown();
                try { release.await(); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            });
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            for (int index = 0; index < 128; index++) executor.execute(queuedExecuted::incrementAndGet);
            executor.execute(queuedExecuted::incrementAndGet);
            assertThat(executor.getQueue()).hasSize(128); assertThat(executor.getPoolSize()).isEqualTo(1);
            assertThat(queuedExecuted).hasValue(0); verify(metrics).recordRedisFailure();
            assertThat(executor.shutdownNow()).hasSize(128);
            executor.execute(queuedExecuted::incrementAndGet);
            verify(metrics, times(2)).recordRedisFailure(); assertThat(queuedExecuted).hasValue(0);
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    /** 慢Redis不能占住提交线程，超128待发布事件丢弃且不回抛，销毁取消积压。 */
    @Test
    void asynchronousPublisherIsBoundedAndShutdownDoesNotDrainQueuedHints() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(redis.convertAndSend(anyString(), anyString())).thenAnswer(call -> {
            entered.countDown();
            try { release.await(); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            return 1L;
        });
        AlarmStateInvalidated event = new AlarmStateInvalidated(tenant, project, device);
        bridge.committed(event); assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        try {
            for (int index = 0; index < 129; index++) bridge.committed(event);
            verify(metrics).recordRedisFailure();
            verify(redis, times(1)).convertAndSend(anyString(), anyString());
            bridge.shutdown(); bridge.committed(event);
            verify(metrics, times(2)).recordRedisFailure();
            verify(redis, times(1)).convertAndSend(anyString(), anyString());
        } finally { release.countDown(); }
    }

    /** 订阅任务不借消费者线程，且独立池最多只有一个排队位置。 */
    @Test
    void subscriptionWorkerIsIndependentAndHasOneQueueSlot() {
        ThreadPoolExecutor subscription = (ThreadPoolExecutor) bridge.alarmRealtimeSubscriptionExecutor();
        ThreadPoolExecutor consumer = (ThreadPoolExecutor) bridge.alarmRealtimeConsumerExecutor();
        try {
            assertThat(subscription).isNotSameAs(consumer);
            assertThat(subscription.getMaximumPoolSize()).isEqualTo(1);
            assertThat(subscription.getQueue().remainingCapacity()).isEqualTo(1);
            assertThat(consumer.getQueue().remainingCapacity()).isEqualTo(128);
        } finally { subscription.shutdownNow(); consumer.shutdownNow(); }
    }

    /** 构造实际Redis Message，单测不模拟JSON解析结果。 */
    private static Message message(UUID project, String body) {
        Message message = mock(Message.class);
        when(message.getChannel()).thenReturn((AlarmRealtimeInvalidationBridge.CHANNEL_PREFIX + project).getBytes(StandardCharsets.UTF_8));
        when(message.getBody()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        return message;
    }

    /** 无数据库的真实Spring同步管理器，仅隔离事件提交/回滚回调机制。 */
    private static final class SynchronizationTransactions extends AbstractPlatformTransactionManager {
        /** 每次事务是独立同步上下文。 */ @Override protected Object doGetTransaction() { return new Object(); }
        /** 本测试不创建持久资源。 */ @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        /** 正常返回驱动AFTER_COMMIT。 */ @Override protected void doCommit(DefaultTransactionStatus status) { }
        /** 回滚不能驱动AFTER_COMMIT。 */ @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
