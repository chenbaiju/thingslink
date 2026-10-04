package com.things.link.ingestion.infrastructure;

import com.things.link.ingestion.application.RealtimeMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.RejectedExecutionHandler;

/**
 * 将提交后实时分发与 Kafka 消费线程隔离的专用执行器。
 *
 * <p>实时推送允许丢失而遥测事实不允许被慢 Redis/Kafka 反压；因此队列必须有界，满时记录指标并
 * 丢弃，客户端通过 REST 补拉。禁止使用 CallerRunsPolicy，否则 Kafka 消费线程会回头等待网络 I/O。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
@EnableConfigurationProperties(RealtimeProperties.class)
public class RealtimeDispatchExecutorConfiguration {

    /** 分发执行器名称，监听器必须显式引用，避免误落入应用默认异步池。 */
    public static final String REALTIME_DISPATCH_EXECUTOR = "realtimeDispatchTaskExecutor";

    /** 队列拒绝日志不含设备或项目标识，避免高频遥测把日志变成资源目录。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(RealtimeDispatchExecutorConfiguration.class);

    /**
     * 创建有界实时分发执行器。
     *
     * @param properties S4 冻结的保护阈值
     * @param metrics 队列饱和指标
     * @return 专用异步执行器
     */
    @Bean(name = REALTIME_DISPATCH_EXECUTOR)
    public ThreadPoolTaskExecutor realtimeDispatchTaskExecutor(
            RealtimeProperties properties, RealtimeMetrics metrics) {
        properties.validate();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getCorePoolSize());
        executor.setMaxPoolSize(properties.getMaxPoolSize());
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setThreadNamePrefix("realtime-dispatch-");
        executor.setRejectedExecutionHandler(logAndDiscard(metrics));
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(properties.getShutdownAwaitSeconds());
        return executor;
    }

    /**
     * 在队列饱和时只丢弃在线增量，不能回到调用线程阻塞事务提交或 Kafka 消费。
     *
     * @param metrics 队列拒绝指标
     * @return 丢弃并记录的拒绝策略
     */
    private RejectedExecutionHandler logAndDiscard(RealtimeMetrics metrics) {
        return (runnable, executor) -> {
            metrics.recordDispatchRejected();
            LOGGER.warn("实时分发队列已满，丢弃允许补拉的在线增量: queueSize={} activeThreads={}",
                    executor.getQueue().size(), executor.getActiveCount());
        };
    }
}
