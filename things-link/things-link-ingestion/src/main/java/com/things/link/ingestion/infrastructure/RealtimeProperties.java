package com.things.link.ingestion.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 实时增量异步分发的固定保护阈值。
 *
 * <p>S4 先以静态配置保护 Kafka 消费和遥测落库；套餐驱动的配额归 S7。这里的队列只隔离
 * “提交后、允许丢失”的推送工作，不能作为事实消息堆积区。</p>
 */
@ConfigurationProperties(prefix = "things-link.realtime")
public class RealtimeProperties {

    /** 常态并发足以覆盖 Redis/Kafka 的短时 I/O，不用为实时通道抢占数据面 CPU。 */
    private int corePoolSize = 2;
    /** 外部依赖抖动时最多四个分发任务并行，防止无界扩线程。 */
    private int maxPoolSize = 4;
    /** 排队达到此值时宁可丢增量并由 REST 补拉，也不积压到 OOM。 */
    private int queueCapacity = 500;
    /** 进程关闭时给已入队任务的最长收尾时间，实时增量超时后允许丢失。 */
    private int shutdownAwaitSeconds = 5;
    /** Redis 项目频道前缀，项目 UUID 是唯一隔离键，禁止混用租户频道。 */
    private String channelPrefix = "things-link:realtime:";

    /** @return 常态核心线程数 */
    public int getCorePoolSize() { return corePoolSize; }

    /** @param corePoolSize 常态核心线程数 */
    public void setCorePoolSize(int corePoolSize) { this.corePoolSize = corePoolSize; }

    /** @return 最大线程数 */
    public int getMaxPoolSize() { return maxPoolSize; }

    /** @param maxPoolSize 最大线程数 */
    public void setMaxPoolSize(int maxPoolSize) { this.maxPoolSize = maxPoolSize; }

    /** @return 有界等待队列容量 */
    public int getQueueCapacity() { return queueCapacity; }

    /** @param queueCapacity 有界等待队列容量 */
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }

    /** @return 停机等待秒数 */
    public int getShutdownAwaitSeconds() { return shutdownAwaitSeconds; }

    /** @param shutdownAwaitSeconds 停机等待秒数 */
    public void setShutdownAwaitSeconds(int shutdownAwaitSeconds) { this.shutdownAwaitSeconds = shutdownAwaitSeconds; }

    /** @return 项目 Redis 频道前缀 */
    public String getChannelPrefix() { return channelPrefix; }

    /** @param channelPrefix 项目 Redis 频道前缀 */
    public void setChannelPrefix(String channelPrefix) { this.channelPrefix = channelPrefix; }

    /**
     * 在执行器创建前拒绝危险配置，不能让负数容量在运行中退化为框架默认无界队列。
     */
    public void validate() {
        if (corePoolSize < 1 || maxPoolSize < corePoolSize || queueCapacity < 1 || shutdownAwaitSeconds < 0
                || channelPrefix == null || channelPrefix.isBlank()) {
            throw new IllegalStateException("实时分发配置必须满足线程、队列和频道前缀均为有效值");
        }
    }
}
