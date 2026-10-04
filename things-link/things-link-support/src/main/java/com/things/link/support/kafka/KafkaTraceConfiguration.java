package com.things.link.support.kafka;

import com.things.link.shared.error.RetryableLeaseConflictException;
import com.things.link.support.observability.DataPlaneMetrics;
import com.things.link.support.observability.KafkaConsumerProcessingMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * 为所有 Kafka producer 与 record listener 统一安装 traceId 传播。
 */
@Configuration(proxyBeanMethods = false)
public class KafkaTraceConfiguration {

    /** 模块隔离应用只导入本配置、不扫描 support 包时，也必须获得同一指标门面。 */
    @Bean
    @ConditionalOnMissingBean
    public KafkaConsumerProcessingMetrics kafkaConsumerProcessingMetrics(
            ObjectProvider<MeterRegistry> provider) {
        return new KafkaConsumerProcessingMetrics(provider);
    }

    /** 数据转换链单次 poll 上限；网络确认仍在 listener 返回前完成。 */
    private static final int DATA_MAX_POLL_RECORDS = 50;

    /** 数据库事实消费者缩小单次 poll，避免重事务累积超过 poll interval。 */
    private static final int FACT_MAX_POLL_RECORDS = 10;

    /** 外部阻塞调用每次只取一条，防止同一 poll 的后续记录等待多个超时窗口。 */
    private static final int EXTERNAL_MAX_POLL_RECORDS = 1;

    /** 通知入口改造后只做短事务持久交接，可使用与转换链相同的 poll 上限。 */
    private static final int NOTIFICATION_MAX_POLL_RECORDS = 50;

    /** normalized 入口只做规则计划持久接管，与数据转换链使用同一批量上限。 */
    private static final int NORMALIZED_INGRESS_MAX_POLL_RECORDS = 50;

    /** 通知入口专属恢复窗口内首次等待一秒，避免数据库断开时紧密争抢连接池。 */
    static final long NOTIFICATION_RETRY_INITIAL_INTERVAL_MILLIS = 1_000L;

    /** 多实例同时恢复时给指数退避加入 250ms 基础抖动，降低数据库刚启动时的同步洪峰。 */
    static final long NOTIFICATION_RETRY_JITTER_MILLIS = 250L;

    /** 指数倍率在短停机时保持快速恢复，同时避免长停机产生每秒固定洪峰。 */
    static final double NOTIFICATION_RETRY_MULTIPLIER = 1.5D;

    /** 单次退避最多五秒；恢复后的持久事实仍能在 C4b 的 180 秒收敛预算内被观察。 */
    static final long NOTIFICATION_RETRY_MAX_INTERVAL_MILLIS = 5_000L;

    /** 210 秒累计退避覆盖 C4b-0 的 180 秒恢复线并保留 30 秒调度余量。 */
    static final long NOTIFICATION_RETRY_MAX_ELAPSED_MILLIS = 210_000L;

    /** 通知重试包含连接池两秒超时，十分钟 poll 窗口覆盖退避、执行时间与恢复余量。 */
    private static final int NOTIFICATION_MAX_POLL_INTERVAL_MILLIS = 600_000;

    /** 重试耗尽或永久错误继续进入既有统一 DLQ，运维与 F9 归因入口保持单一。 */
    static final String NOTIFICATION_DEAD_LETTER_TOPIC = "tc.dlq";

    /** normalized 永久错误或数据库恢复预算耗尽后进入统一数据面 DLQ。 */
    static final String NORMALIZED_INGRESS_DEAD_LETTER_TOPIC = "tc.dlq";

    /** 外部调用工厂的 poll 间隔；3s/5s HTTP 上限与有限重试必须在此窗口内收敛。 */
    private static final int EXTERNAL_MAX_POLL_INTERVAL_MILLIS = 60_000;

    /** 非外部调用沿用 Kafka 默认五分钟窗口，但在这里显式冻结，避免客户端升级漂移。 */
    private static final int STANDARD_MAX_POLL_INTERVAL_MILLIS = 300_000;

    /**
     * 把 producer interceptor 注入 Boot 创建的默认 ProducerFactory。
     *
     * @return producer factory 定制器
     */
    @Bean
    public DefaultKafkaProducerFactoryCustomizer kafkaTraceProducerCustomizer() {
        return factory -> factory.updateConfigs(Map.of(
                ProducerConfig.INTERCEPTOR_CLASSES_CONFIG, KafkaTraceProducerInterceptor.class.getName()));
    }

    /**
     * 建立默认 listener factory，并安装逐记录 MDC 恢复器。
     *
     * <p>保留 Boot configurer 的 ack、并发、转换器等统一配置，只增加 trace interceptor。
     * 若应用显式提供同名 factory，则由应用承担等价的 trace 传播责任。</p>
     *
     * @param configurer Boot 默认配置器
     * @param consumerFactory 消费者工厂
     * @return 默认 Kafka listener factory
     */
    @Bean(name = "kafkaListenerContainerFactory")
    @ConditionalOnMissingBean(name = "kafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            KafkaConsumerProcessingMetrics metrics) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setRecordInterceptor(new KafkaTraceRecordInterceptor(metrics));
        return factory;
    }

    /** @return raw/normalized/规则续接使用的显式数据链 listener 工厂 */
    @Bean(name = "dataKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<Object, Object> dataKafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            KafkaConsumerProcessingMetrics metrics) {
        return listenerFactory(configurer, consumerFactory,
                DATA_MAX_POLL_RECORDS, STANDARD_MAX_POLL_INTERVAL_MILLIS, metrics);
    }

    /** @return processed、回复和终态等数据库事实 listener 工厂 */
    @Bean(name = "factKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<Object, Object> factKafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            KafkaConsumerProcessingMetrics metrics) {
        return listenerFactory(configurer, consumerFactory,
                FACT_MAX_POLL_RECORDS, STANDARD_MAX_POLL_INTERVAL_MILLIS, metrics);
    }

    /** ADR0177：单实时组的PG受理失败不自动推进offset，其他错误确认DLQ后恢复。 */
    @Bean(name="realtimeIngressKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<Object,Object> realtimeIngressKafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,ConsumerFactory<Object,Object> consumerFactory,
            KafkaConsumerProcessingMetrics metrics,KafkaTemplate<String,Object> kafkaTemplate,DataPlaneMetrics dataPlaneMetrics){
        var factory=listenerFactory(configurer,consumerFactory,FACT_MAX_POLL_RECORDS,STANDARD_MAX_POLL_INTERVAL_MILLIS,metrics);
        var recoverer=new MeteredDeadLetterPublishingRecoverer(kafkaTemplate,(record,error)->new TopicPartition("tc.dlq",-1),dataPlaneMetrics);
        factory.setCommonErrorHandler(realtimeIngressErrorHandler(recoverer,1000L));return factory;
    }
    static DefaultErrorHandler realtimeIngressErrorHandler(ConsumerRecordRecoverer recoverer,long retryMillis){
        var handler=new DefaultErrorHandler(recoverer,new org.springframework.util.backoff.FixedBackOff(retryMillis,org.springframework.util.backoff.FixedBackOff.UNLIMITED_ATTEMPTS));
        handler.defaultFalse(true);handler.addRetryableExceptions(com.things.link.shared.error.RealtimeAdmissionUnavailableException.class);return handler;
    }

    /** @return 同步 EMQX HTTP 调用使用的单记录 poll listener 工厂 */
    @Bean(name = "externalKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<Object, Object> externalKafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            KafkaConsumerProcessingMetrics metrics) {
        return listenerFactory(configurer, consumerFactory,
                EXTERNAL_MAX_POLL_RECORDS, EXTERNAL_MAX_POLL_INTERVAL_MILLIS, metrics);
    }

    /**
     * 创建通知 Kafka 短事务持久交接入口的隔离 listener 工厂。
     *
     * @param configurer Boot listener 公共配置器
     * @param consumerFactory Boot 默认消费者工厂
     * @param processingMetrics 消费处理指标
     * @param kafkaTemplate 统一 DLQ 发布入口
     * @param dataPlaneMetrics 数据面 DLQ 指标
     * @return 带专属数据库恢复语义的 listener 工厂
     */
    @Bean(name = "notificationIngressKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<Object, Object> notificationIngressKafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            KafkaConsumerProcessingMetrics processingMetrics,
            KafkaTemplate<String, Object> kafkaTemplate,
            DataPlaneMetrics dataPlaneMetrics) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = listenerFactory(
                configurer, consumerFactory, NOTIFICATION_MAX_POLL_RECORDS,
                NOTIFICATION_MAX_POLL_INTERVAL_MILLIS, processingMetrics);
        MeteredDeadLetterPublishingRecoverer recoverer = new MeteredDeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> new TopicPartition(NOTIFICATION_DEAD_LETTER_TOPIC, -1),
                dataPlaneMetrics);
        // Boot configurer 仍负责 ack/转换/并发；这里只替换该工厂的错误处理器，不能新增第二个全局 Bean。
        factory.setCommonErrorHandler(notificationIngressErrorHandler(
                recoverer, notificationIngressBackOff()));
        return factory;
    }

    /**
     * 创建 normalized 上行持久接管入口的隔离 listener 工厂。
     *
     * <p>G1-C4b-F15 只隔离会在规则目录/回执接管处访问数据库的 normalized listener；raw 转换、规则 retry 和其他
     * 数据链消费者继续使用公共短重试，避免把一个入口的数据库恢复窗口扩散到不同失败语义。</p>
     *
     * @param configurer Boot listener 公共配置器
     * @param consumerFactory Boot 默认消费者工厂
     * @param processingMetrics 消费处理指标
     * @param kafkaTemplate 统一 DLQ 发布入口
     * @param dataPlaneMetrics 数据面 DLQ 指标
     * @return 带专属数据库恢复语义的 normalized listener 工厂
     */
    @Bean(name = "normalizedIngressKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<Object, Object> normalizedIngressKafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            KafkaConsumerProcessingMetrics processingMetrics,
            KafkaTemplate<String, Object> kafkaTemplate,
            DataPlaneMetrics dataPlaneMetrics) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = listenerFactory(
                configurer, consumerFactory, NORMALIZED_INGRESS_MAX_POLL_RECORDS,
                NOTIFICATION_MAX_POLL_INTERVAL_MILLIS, processingMetrics);
        MeteredDeadLetterPublishingRecoverer recoverer = new MeteredDeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> new TopicPartition(NORMALIZED_INGRESS_DEAD_LETTER_TOPIC, -1),
                dataPlaneMetrics);
        // 与通知入口共享已冻结的数据库异常白名单和 ACK 边界，但保留独立工厂，禁止影响 raw/processed 等消费者。
        factory.setCommonErrorHandler(durableDatabaseIngressErrorHandler(
                recoverer, durableDatabaseIngressBackOff()));
        return factory;
    }

    /**
     * 创建通知入口冻结的指数退避；按累计等待而非固定次数限制，避免连接池超时改变恢复窗口。
     *
     * @return 210 秒有界指数退避
     */
    static ExponentialBackOff notificationIngressBackOff() {
        return durableDatabaseIngressBackOff();
    }

    /**
     * 创建持久数据库入口共用的 210 秒有界指数退避。
     *
     * @return 覆盖 C4b 180 秒恢复线并保留调度余量的退避
     */
    static ExponentialBackOff durableDatabaseIngressBackOff() {
        ExponentialBackOff backOff = new ExponentialBackOff(
                NOTIFICATION_RETRY_INITIAL_INTERVAL_MILLIS, NOTIFICATION_RETRY_MULTIPLIER);
        backOff.setJitter(NOTIFICATION_RETRY_JITTER_MILLIS);
        backOff.setMaxInterval(NOTIFICATION_RETRY_MAX_INTERVAL_MILLIS);
        backOff.setMaxElapsedTime(NOTIFICATION_RETRY_MAX_ELAPSED_MILLIS);
        return backOff;
    }

    /**
     * 只重试明确的数据库基础设施异常与未到期持久租约冲突；未知异常直接进入可归因 DLQ。
     *
     * <p>规则/告警通知入口都以稳定 ID 做数据库幂等交接，因此数据库提交结果未知时允许重放；
     * 信封、分区键、反序列化和代码错误重放不会自愈，长期阻塞分区反而扩大故障域。</p>
     *
     * @param recoverer DLQ 已确认恢复器
     * @param backOff 有界恢复窗口
     * @return 通知入口专属错误处理器
     */
    static DefaultErrorHandler notificationIngressErrorHandler(
            ConsumerRecordRecoverer recoverer, BackOff backOff) {
        return durableDatabaseIngressErrorHandler(recoverer, backOff);
    }

    /**
     * 为已具备稳定幂等身份的数据库持久入口建立异常分类与 offset 边界。
     *
     * @param recoverer 只有 DLQ Broker ACK 后才返回的恢复器
     * @param backOff 有界数据库恢复窗口
     * @return 默认拒绝未知错误、只重试数据库基础设施异常和窄化租约冲突的处理器
     */
    static DefaultErrorHandler durableDatabaseIngressErrorHandler(
            ConsumerRecordRecoverer recoverer, BackOff backOff) {
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        errorHandler.defaultFalse(true);
        errorHandler.addRetryableExceptions(
                DataAccessResourceFailureException.class,
                TransientDataAccessException.class,
                RecoverableDataAccessException.class,
                CannotCreateTransactionException.class,
                TransactionSystemException.class,
                RetryableLeaseConflictException.class);
        return errorHandler;
    }

    /**
     * 基于 Boot 已解析的消费者配置派生隔离工厂，并继续安装公共错误处理器与 trace 恢复器。
     *
     * @param configurer Boot listener 公共配置器
     * @param baseConsumerFactory Boot 默认消费者工厂
     * @param maxPollRecords 单次 poll 记录上限
     * @param maxPollIntervalMillis 两次 poll 最大间隔
     * @return 带独立 consumer 配置副本的 listener 工厂
     */
    private static ConcurrentKafkaListenerContainerFactory<Object, Object> listenerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> baseConsumerFactory,
            int maxPollRecords,
            int maxPollIntervalMillis,
            KafkaConsumerProcessingMetrics metrics) {
        Map<String, Object> properties = new HashMap<>(baseConsumerFactory.getConfigurationProperties());
        properties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPollRecords);
        properties.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, maxPollIntervalMillis);
        DefaultKafkaConsumerFactory<Object, Object> isolatedConsumerFactory =
                new DefaultKafkaConsumerFactory<>(properties);
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, isolatedConsumerFactory);
        factory.setRecordInterceptor(new KafkaTraceRecordInterceptor(metrics));
        return factory;
    }
}
