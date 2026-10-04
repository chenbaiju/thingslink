package com.things.link.support.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 汇总 S3.5-4 冻结的数据面业务指标，避免各模块自行拼写指标名与标签。
 *
 * <p>标签只使用端点、结果和 Topic 这类有界集合，绝不放 tenantId、projectId 或 deviceId；
 * 后三者会随业务量无限增长，最终先把 Prometheus 的时序基数打满，再让真正的异常不可见。</p>
 */
@Component
public class DataPlaneMetrics {

    /** DLQ 成功写入计数；Prometheus 导出时自动追加 {@code _total}。 */
    static final String DLQ_MESSAGES = "thingslink.ingestion.dlq.messages";
    /** 单设备令牌桶拒绝计数；设备维度继续保留在 Redis，不进入 Prometheus 标签。 */
    static final String RATE_LIMITED = "thingslink.ingestion.uplink.rate_limited";
    /** 存量标量省略 modelVersion 被解析到已绑定初始版本的次数（X-01 §5.2 兼容窗口）。 */
    static final String LEGACY_MODEL_VERSION_INFERRED = "thingslink.ingestion.model_version.legacy_inferred";
    /** 设备发生时间到影子事务提交完成的端到端延迟。 */
    static final String UPLINK_END_TO_END = "thingslink.ingestion.uplink.end_to_end";
    /** 六个 Broker 回调的耗时与结果。 */
    static final String BROKER_CALLBACK = "thingslink.emqx.callback";

    /** Micrometer 注册表，由启动模块选择 Prometheus 实现。 */
    private final MeterRegistry registry;
    /** 无高基数标签的限流计数器可在构造时复用。 */
    private final Counter rateLimited;
    /** 存量标量省略推断计数，无标签。 */
    private final Counter legacyModelVersionInferred;
    /** 上行延迟直方图用于跨实例聚合 P99，不能只发布进程内 percentile。 */
    private final Timer uplinkEndToEnd;

    /**
     * Spring 装配入口。业务模块的隔离测试可能不加载 Actuator，此时退到进程内注册表，
     * 不能因为可观测出口缺席而让被测业务上下文无法启动。
     *
     * @param registryProvider 可选的应用统一指标注册表
     */
    @Autowired
    public DataPlaneMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        this(registryProvider.getIfAvailable(SimpleMeterRegistry::new));
    }

    /**
     * 显式注册表入口，供单元测试和非 Spring 嵌入场景使用。
     *
     * @param registry 应用统一指标注册表
     */
    public DataPlaneMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.rateLimited = Counter.builder(RATE_LIMITED)
                .description("单设备上行令牌桶拒绝的消息数")
                .register(registry);
        this.legacyModelVersionInferred = Counter.builder(LEGACY_MODEL_VERSION_INFERRED)
                .description("存量标量省略 modelVersion 被推断为已绑定初始版本的消息数")
                .register(registry);
        this.uplinkEndToEnd = Timer.builder(UPLINK_END_TO_END)
                .description("设备发生时间到属性影子提交可见的端到端延迟")
                .publishPercentileHistogram()
                // G1-C3d / B-002：Micrometer 1.17 默认 30 秒上界曾截顶真实 P99；300 秒仍溢出就应由机器门禁拒绝。
                .maximumExpectedValue(Duration.ofMinutes(5))
                .serviceLevelObjectives(Duration.ofMillis(100), Duration.ofMillis(500),
                        Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(5),
                        Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(60),
                        Duration.ofSeconds(120), Duration.ofSeconds(300))
                .register(registry);
    }

    /** 记录一条确权设备因令牌桶耗尽而未进入 raw Kafka 的消息。 */
    public void recordRateLimited() {
        rateLimited.increment();
    }

    /** 记录一条存量标量省略 modelVersion、被解析到已绑定初始版本的消息。 */
    public void recordLegacyModelVersionInferred() {
        legacyModelVersionInferred.increment();
    }

    /**
     * 记录一条已经得到 Kafka broker 确认的死信。
     *
     * @param sourceTopic 原始记录所属主题，主题集合由部署清单控制
     */
    public void recordDeadLetter(String sourceTopic) {
        Counter.builder(DLQ_MESSAGES)
                .description("成功写入统一死信主题的消息数")
                .tag("source_topic", sourceTopic)
                .register(registry)
                .increment();
    }

    /**
     * 在属性影子事务提交后记录端到端延迟。
     *
     * <p>设备时钟可能快于平台时钟；Timer 不接受负数，因此未来时间按零延迟记录，
     * 时钟质量另由后续设备诊断指标负责，不能让一台坏时钟破坏整条指标链。</p>
     *
     * @param occurredAt 设备声明的消息发生时间
     */
    public void recordUplinkVisible(Instant occurredAt) {
        Duration duration = Duration.between(occurredAt, Instant.now());
        uplinkEndToEnd.record(duration.isNegative() ? Duration.ZERO : duration);
    }

    /**
     * 记录一次 Broker 回调的完整过滤链耗时及最终结果。
     *
     * @param endpoint 有界的回调路径
     * @param status HTTP 最终状态码，四百及以上均视为失败
     * @param elapsed 请求在应用内的耗时
     */
    public void recordBrokerCallback(String endpoint, int status, Duration elapsed) {
        Timer.builder(BROKER_CALLBACK)
                .description("EMQX Broker 回调耗时与失败率")
                .publishPercentileHistogram()
                .serviceLevelObjectives(Duration.ofMillis(25), Duration.ofMillis(50),
                        Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofSeconds(1))
                .tags("endpoint", endpoint, "result", status >= 400 ? "failure" : "success")
                .register(registry)
                .record(elapsed);
    }
}
