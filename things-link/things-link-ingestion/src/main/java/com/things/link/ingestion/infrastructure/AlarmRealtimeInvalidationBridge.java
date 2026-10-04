package com.things.link.ingestion.infrastructure;

import com.things.link.ingestion.application.DashboardRealtimeRegistry;
import com.things.link.ingestion.application.RealtimeMetrics;
import com.things.link.shared.message.AlarmStateInvalidated;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** ADR0109：提交后发布不含业务值的失效提示，Redis故障不改变已提交告警事实。 */
@Configuration(proxyBeanMethods = false)
public class AlarmRealtimeInvalidationBridge {
    /** 独立固定频道前缀，不能复用属性值协议或接受租户自选频道。 */
    public static final String CHANNEL_PREFIX = "tc:dashboard:alarm:";
    /** 只记录固定诊断，不回显正文、账号、设备或异常消息。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(AlarmRealtimeInvalidationBridge.class);
    /** 两字段正文严格拒绝重复及尾随JSON。 */
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    /** Pub/Sub只作为可丢提示，不能进入告警提交结果。 */
    private final StringRedisTemplate redis;
    /** 当前本机按已冻结App v2组扇出。 */
    private final DashboardRealtimeRegistry registry;
    /** 固定低基数Redis故障度量。 */
    private final RealtimeMetrics metrics;

    /** 提交响应不等待Redis；单发布者和128队列限制后端异常时内存及线程。 */
    private final ExecutorService publisher;

    /** 装配公开事实事件与独立提示基础设施。 */
    public AlarmRealtimeInvalidationBridge(StringRedisTemplate redis, DashboardRealtimeRegistry registry, RealtimeMetrics metrics) {
        this.redis = redis;
        this.registry = registry;
        this.metrics = metrics;
        this.publisher = boundedExecutor("alarm-hint-publisher", 128);
    }

    /** 无事务事件默认不发送；回滚不会触发AFTER_COMMIT，不设置fallbackExecution。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void committed(AlarmStateInvalidated event) {
        try {
            if (event.tenantId() == null || event.projectId() == null || event.deviceId() == null)
                throw new IllegalArgumentException("非法告警失效事实");
            publisher.execute(() -> publish(event));
        } catch (RuntimeException failure) {
            metrics.recordRedisFailure();
            LOGGER.warn("Alarm realtime invalidation publish unavailable");
        }
    }

    /** 有限发布者独占可能等待至Lettuce默认60秒的命令，Redis失败不回抛业务线程。 */
    private void publish(AlarmStateInvalidated event) {
        try {
            redis.convertAndSend(CHANNEL_PREFIX + event.projectId(), JSON.writeValueAsString(
                    Map.of("projectId", event.projectId().toString(), "deviceId", event.deviceId().toString())));
        } catch (RuntimeException failure) {
            metrics.recordRedisFailure();
            LOGGER.warn("Alarm realtime invalidation publish unavailable");
        }
    }

    /** 销毁立即丢弃可恢复提示并中断发布者，最多等待500ms，不拖住容器关闭。 */
    @PreDestroy
    public void shutdown() {
        publisher.shutdownNow();
        try { publisher.awaitTermination(500, TimeUnit.MILLISECONDS); }
        catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
    }

    /** 独立模式订阅，仅此频道可触发v2告警组；生命周期由Spring管理。 */
    @Bean
    RedisMessageListenerContainer alarmRealtimeRedisMessageListenerContainer(RedisConnectionFactory connectionFactory,
            @Qualifier("alarmRealtimeConsumerExecutor") ExecutorService consumer,
            @Qualifier("alarmRealtimeSubscriptionExecutor") ExecutorService subscription) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setTaskExecutor(consumer);
        container.setSubscriptionExecutor(subscription);
        container.addMessageListener((message, pattern) -> consume(message), new PatternTopic(CHANNEL_PREFIX + "*"));
        return container;
    }

    /** 单消费者最多128个待处理提示，过载丢失由REST校准恢复，不派生无界线程。 */
    @Bean(destroyMethod = "shutdownNow")
    ExecutorService alarmRealtimeConsumerExecutor() {
        return boundedExecutor("alarm-hint-consumer", 128);
    }

    /** Redis订阅建立可能阻塞，必须与消费线程分开；销毁顺序先关闭依赖它的容器。 */
    @Bean(destroyMethod = "shutdownNow")
    ExecutorService alarmRealtimeSubscriptionExecutor() {
        return boundedExecutor("alarm-hint-subscription", 1);
    }

    /** 不使用CallerRuns，Redis回调线程绝不替消费者执行工作或等待队列空位。 */
    private ExecutorService boundedExecutor(String name, int capacity) {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(capacity),
                Thread.ofPlatform().daemon().name(name).factory(), (task, executor) -> {
                    metrics.recordRedisFailure();
                    LOGGER.warn("Alarm realtime invalidation worker unavailable");
                });
    }

    /** 频道/正文项目互证后才交付设备提示，坏帧不得影响订阅线程或其他项目。 */
    void consume(Message message) {
        try {
            if (message.getBody().length > 256 || message.getChannel().length > 128) throw new IllegalArgumentException("告警失效帧超限");
            var root = JSON.readTree(message.getBody());
            if (!root.isObject() || !root.propertyNames().equals(Set.of("projectId", "deviceId")))
                throw new IllegalArgumentException("非法告警失效字段");
            UUID project = canonical(root.get("projectId").stringValue());
            UUID device = canonical(root.get("deviceId").stringValue());
            if (!(CHANNEL_PREFIX + project).equals(new String(message.getChannel(), StandardCharsets.UTF_8)))
                throw new IllegalArgumentException("告警失效频道不匹配");
            registry.invalidateAlarms(project, device);
        } catch (RuntimeException failure) {
            metrics.recordRedisFailure();
            LOGGER.warn("Alarm realtime invalidation consume rejected");
        }
    }

    /** 不接受Java UUID宽松短段语法，不让不同字面值命中同一身份。 */
    private static UUID canonical(String value) {
        UUID result = UUID.fromString(value);
        if (!result.toString().equals(value)) throw new IllegalArgumentException("非法告警失效身份");
        return result;
    }
}
