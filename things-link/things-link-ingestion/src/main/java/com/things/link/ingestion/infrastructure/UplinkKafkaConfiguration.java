package com.things.link.ingestion.infrastructure;

import com.things.link.device.application.DeviceIngestionService;
import com.things.link.ingestion.application.DevicePropertyReportReader;
import com.things.link.ingestion.application.OtaReportUplinkHandler;
import com.things.link.ingestion.application.OtaDownloadRequestUplinkHandler;
import com.things.link.ingestion.application.OtaProgressUplinkHandler;
import com.things.link.ingestion.application.OtaHealthUplinkHandler;
import com.things.link.ingestion.application.OtaCommitReceiptUplinkHandler;
import com.things.link.ingestion.application.OtaCommitReconciliationUplinkHandler;
import com.things.link.ingestion.application.OtaRollbackPreflightUplinkHandler;
import com.things.link.ingestion.application.OtaRollbackUplinkHandler;
import com.things.link.ingestion.application.OtaInstallStopUplinkHandler;
import com.things.link.ingestion.application.CommandDownlinkPublisher;
import com.things.link.ingestion.application.MqttDownlinkAdmissionService;
import com.things.link.ingestion.application.ConfigReplyUplinkNormalizer;
import com.things.link.ingestion.application.DownlinkPreprocessingChain;
import com.things.link.ingestion.application.DownlinkPreprocessor;
import com.things.link.ingestion.application.GatewayBatchMessageNormalizer;
import com.things.link.ingestion.application.InvalidDownlinkMessageException;
import com.things.link.ingestion.application.InvalidUplinkMessageException;
import com.things.link.ingestion.application.ModbusResponseUplinkNormalizer;
import com.things.link.ingestion.application.RawUplinkMessageNormalizer;
import com.things.link.ingestion.application.RealtimeProjectPublisher;
import com.things.link.ingestion.application.TopologyUplinkMessageNormalizer;
import com.things.link.ingestion.application.UplinkPreprocessingChain;
import com.things.link.ingestion.application.UplinkPreprocessor;
import com.things.link.ingestion.application.UplinkTimestampPolicy;
import com.things.link.device.application.DeviceBatchIngestionService;
import com.things.link.device.application.DeviceTopologyIngestionService;
import com.things.link.device.application.ModbusConfigService;
import com.things.link.device.application.DeviceConfigDeliveryAdmissionService;
import com.things.link.device.application.ModbusResponseAcceptanceService;
import com.things.link.support.kafka.MeteredDeadLetterPublishingRecoverer;
import com.things.link.support.observability.DataPlaneMetrics;
import com.things.link.telemetry.application.PropertyIngestionService;
import com.things.link.telemetry.application.DeviceCommandService;
import com.things.link.rule.application.queue.PublishedRuleCatalog;
import com.things.link.rule.application.queue.RuleExecutionCoordinator;
import com.things.link.rule.application.queue.RuleExecutionSuccessPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * S3-11D 原始上行消费、标准发布与失败恢复装配。
 */
@Configuration(proxyBeanMethods = false)
public class UplinkKafkaConfiguration {

    /** 所有不可恢复或重试耗尽记录汇入统一 DLQ，由后续运维闭环分类处理。 */
    public static final String DEAD_LETTER_TOPIC = "tc.dlq";

    /** 基础设施异常首次失败后等待 200ms，避免 broker 抖动时立即形成忙循环。 */
    private static final long RETRY_INTERVAL_MILLIS = 200L;

    /** 首次消费之外再尝试两次，在可恢复性与分区阻塞时间之间取明确上限。 */
    private static final long RETRY_ATTEMPTS = 2L;

    /**
     * 创建协议无关的设备业务载荷读取器。
     *
     * <p>MQTT 与新协议接入共用同一实例：载荷契约、数字精度与未知字段容错必须跨协议一致，
     * 各协议各自构造 reader 会让同一份设备 JSON 得到不同解析结果。</p>
     *
     * @param objectMapper Boot 统一 JSON 映射器
     * @return 设备业务载荷读取器
     */
    @Bean
    DevicePropertyReportReader devicePropertyReportReader(ObjectMapper objectMapper) {
        return new DevicePropertyReportReader(objectMapper);
    }

    /**
     * 创建冻结设备报文的标准化器。
     *
     * @param reportReader 设备业务载荷读取器
     * @return 原始上行标准化器
     */
    @Bean
    RawUplinkMessageNormalizer rawUplinkMessageNormalizer(DevicePropertyReportReader reportReader) {
        return new RawUplinkMessageNormalizer(reportReader);
    }

    /**
     * 创建 D-039 设备未来时间边界。
     *
     * @param maxFutureSkew 设备发生时间允许领先平台接收时间的最大窗口
     * @return 确定性的标准信封时间策略
     */
    @Bean
    UplinkTimestampPolicy uplinkTimestampPolicy(
            @Value("${things-link.ingestion.max-future-skew:5m}") Duration maxFutureSkew) {
        return new UplinkTimestampPolicy(maxFutureSkew);
    }

    /**
     * 创建拓扑报文标准化器。
     *
     * @param objectMapper Boot JSON 映射器
     * @return 拓扑消息标准化器
     */
    @Bean
    TopologyUplinkMessageNormalizer topologyUplinkMessageNormalizer(ObjectMapper objectMapper) {
        return new TopologyUplinkMessageNormalizer(objectMapper);
    }

    /**
     * 创建批量上报报文标准化器。
     *
     * @param objectMapper Boot JSON 映射器
     * @param meterRegistry 帧级拒绝字节指标注册表
     * @return 批量上报标准化器
     */
    @Bean
    GatewayBatchMessageNormalizer gatewayBatchMessageNormalizer(
            ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        return new GatewayBatchMessageNormalizer(objectMapper, meterRegistry);
    }

    /**
     * 创建配置回执标准化器。
     *
     * @param objectMapper Boot JSON 映射器
     * @return 配置回执标准化器
     */
    @Bean
    ConfigReplyUplinkNormalizer configReplyUplinkNormalizer(ObjectMapper objectMapper) {
        return new ConfigReplyUplinkNormalizer(objectMapper);
    }

    /**
     * 创建 Modbus 响应标准化器。
     *
     * @param objectMapper Boot JSON 映射器
     * @return Modbus 响应标准化器
     */
    @Bean
    ModbusResponseUplinkNormalizer modbusResponseUplinkNormalizer(ObjectMapper objectMapper) {
        return new ModbusResponseUplinkNormalizer(objectMapper);
    }

    /**
     * 创建 raw topic listener。
     *
     * @param normalizer 原始报文标准化器
     * @param topologyNormalizer 拓扑报文标准化器
     * @param batchNormalizer 批量上报标准化器
     * @param configReplyNormalizer 配置回执标准化器
     * @param kafkaTemplate 标准消息发布模板
     * @return 原始上行 Kafka 消费者
     */
    @Bean
    RawUplinkKafkaConsumer rawUplinkKafkaConsumer(
            RawUplinkMessageNormalizer normalizer,
            TopologyUplinkMessageNormalizer topologyNormalizer,
            GatewayBatchMessageNormalizer batchNormalizer,
            ConfigReplyUplinkNormalizer configReplyNormalizer,
            ModbusResponseUplinkNormalizer modbusResponseNormalizer,
            KafkaTemplate<String, Object> kafkaTemplate, OtaReportUplinkHandler otaReports,
            OtaDownloadRequestUplinkHandler otaDownloads, OtaProgressUplinkHandler otaProgress,
            OtaHealthUplinkHandler otaHealth, OtaCommitReceiptUplinkHandler otaCommitReceipts,
            OtaCommitReconciliationUplinkHandler otaReconciliation, OtaRollbackPreflightUplinkHandler otaPreflight, OtaRollbackUplinkHandler otaRollback, OtaInstallStopUplinkHandler otaInstallStop) {
        return new RawUplinkKafkaConsumer(normalizer, topologyNormalizer, batchNormalizer,
                configReplyNormalizer, modbusResponseNormalizer, kafkaTemplate, otaReports, otaDownloads, otaProgress, otaHealth, otaCommitReceipts, otaReconciliation, otaPreflight, otaRollback, otaInstallStop);
    }

    /**
     * 创建拓扑消息消费者，交给 device 模块在线状态机。
     *
     * @param ingestionService 拓扑在线状态机应用端口
     * @return 拓扑消息 Kafka 消费者
     */
    @Bean
    DeviceTopologyKafkaConsumer deviceTopologyKafkaConsumer(
            DeviceTopologyIngestionService ingestionService) {
        return new DeviceTopologyKafkaConsumer(ingestionService);
    }

    /**
     * 创建批量属性上报消费者，复核归属后拆分到 normalized。
     *
     * @param ingestionService 批量归属复核应用端口
     * @param kafkaTemplate normalized 可靠发布模板
     * @return 批量上报 Kafka 消费者
     */
    @Bean
    DeviceBatchKafkaConsumer deviceBatchKafkaConsumer(
            DeviceBatchIngestionService ingestionService,
            KafkaTemplate<String, Object> kafkaTemplate) {
        return new DeviceBatchKafkaConsumer(ingestionService, kafkaTemplate);
    }

    /**
     * 创建拓扑回执消费者，发布到网关的 {@code down/topo/reply}。
     *
     * @param publisher 下行发布端口
     * @return 拓扑回执 Kafka 消费者
     */
    @Bean
    TopologyReplyKafkaConsumer topologyReplyKafkaConsumer(CommandDownlinkPublisher publisher, MqttDownlinkAdmissionService admission) {
        return new TopologyReplyKafkaConsumer(publisher, admission);
    }

    /**
     * 创建配置下发消费者，发布到网关的 {@code down/config}。
     *
     * @param publisher 下行发布端口
     * @return 配置下发 Kafka 消费者
     */
    @Bean
    @ConditionalOnBean(DeviceConfigDeliveryAdmissionService.class)
    DeviceConfigKafkaConsumer deviceConfigKafkaConsumer(CommandDownlinkPublisher publisher,
                                                         MqttDownlinkAdmissionService admissionService) {
        return new DeviceConfigKafkaConsumer(publisher, admissionService);
    }

    /**
     * 创建配置回执消费者，交给 device 模块做版本诊断与落后重推。
     *
     * @param configService Modbus 配置版本诊断端口
     * @return 配置回执 Kafka 消费者
     */
    @Bean
    DeviceConfigReplyKafkaConsumer deviceConfigReplyKafkaConsumer(ModbusConfigService configService) {
        return new DeviceConfigReplyKafkaConsumer(configService);
    }

    /**
     * 创建 Modbus 读请求消费者，发布到网关的 {@code down/modbus/request}。
     *
     * @param publisher 下行发布端口
     * @return Modbus 读请求 Kafka 消费者
     */
    @Bean
    ModbusRequestKafkaConsumer modbusRequestKafkaConsumer(CommandDownlinkPublisher publisher, MqttDownlinkAdmissionService admission) {
        return new ModbusRequestKafkaConsumer(publisher, admission);
    }

    /**
     * 创建 Modbus 响应消费者，经ADR0063事务入口持久接纳normalized交付。
     *
     * @param acceptanceService 请求完成与Outbox同事务接管入口
     * @return Modbus 响应 Kafka 消费者
     */
    @Bean
    ModbusResponseKafkaConsumer modbusResponseKafkaConsumer(
            ModbusResponseAcceptanceService acceptanceService) {
        return new ModbusResponseKafkaConsumer(acceptanceService);
    }

    /**
     * 创建标准消息到 telemetry 事务的业务消费者。
     *
     * @param propertyIngestionService telemetry 批量属性摄入应用端口
     * @param preprocessingChain 上行规则预处理链
     * @return 标准上行 Kafka 消费者
     */
    @Bean
    NormalizedUplinkKafkaConsumer normalizedUplinkKafkaConsumer(
            PublishedRuleCatalog ruleCatalog,
            RuleExecutionCoordinator coordinator,
            KafkaTemplate<String, Object> kafkaTemplate,
            RuleUplinkMessageMapper messageMapper,
            UplinkTimestampPolicy timestampPolicy,
            DeviceIngestionService deviceIngestionService) {
        return new NormalizedUplinkKafkaConsumer(ruleCatalog, coordinator, kafkaTemplate,
                messageMapper, timestampPolicy, Clock.systemUTC(), deviceIngestionService);
    }

    /** @param objectMapper Boot JSON 映射器 @return 保护可信信封字段的规则消息映射器 */
    @Bean
    RuleUplinkMessageMapper ruleUplinkMessageMapper(ObjectMapper objectMapper) {
        return new RuleUplinkMessageMapper(objectMapper);
    }

    /**
     * @param kafkaTemplate 共享幂等 Kafka 模板
     * @param messageMapper 标准信封映射器
     * @return 规则回执完成前的可靠成功续接端口
     */
    @Bean
    RuleExecutionSuccessPublisher ruleExecutionSuccessPublisher(
            KafkaTemplate<String, Object> kafkaTemplate,
            RuleUplinkMessageMapper messageMapper) {
        return new KafkaRuleExecutionSuccessPublisher(kafkaTemplate, messageMapper);
    }

    /**
     * 创建规则成功续接 Topic 到 telemetry 事务的消费者。
     *
     * @param propertyIngestionService telemetry 批量属性摄入应用端口
     * @param preprocessingChain 规则之后、物模型之前的确定性预处理链
     * @return 规则处理后上行消费者
     */
    @Bean
    ProcessedUplinkKafkaConsumer processedUplinkKafkaConsumer(
            PropertyIngestionService propertyIngestionService,
            UplinkPreprocessingChain preprocessingChain) {
        return new ProcessedUplinkKafkaConsumer(propertyIngestionService, preprocessingChain);
    }

    /**
     * 组装物模型校验前的扩展链；当前空列表直通，S8 新增实现 Bean 即可接入。
     * @param preprocessors 容器中全部预处理实现
     * @return 稳定排序的预处理链
     */
    @Bean
    UplinkPreprocessingChain uplinkPreprocessingChain(List<UplinkPreprocessor> preprocessors) {
        return new UplinkPreprocessingChain(preprocessors);
    }

    /**
     * 组装协议编码前下行扩展链；当前空列表直通，S8 新增实现 Bean 即可接入。
     *
     * @param preprocessors 容器中全部下行预处理实现
     * @return 稳定排序并保护路由事实的预处理链
     */
    @Bean
    DownlinkPreprocessingChain downlinkPreprocessingChain(List<DownlinkPreprocessor> preprocessors) {
        return new DownlinkPreprocessingChain(preprocessors);
    }

    /**
     * 创建 Outbox Kafka 到 EMQX 的命令下行消费者。
     *
     * @param preprocessingChain 协议编码前预处理链
     * @param publisher EMQX HTTP 发布端口
     * @param commandService telemetry 命令状态机入口
     * @return 下行 Kafka 消费者
     */
    @Bean
    DeviceCommandDownlinkKafkaConsumer deviceCommandDownlinkKafkaConsumer(
            DownlinkPreprocessingChain preprocessingChain,
            CommandDownlinkPublisher publisher,
            DeviceCommandService commandService,
            com.things.link.device.application.DeviceAccessSessionPort sessions, MqttDownlinkAdmissionService admission) {
        return new DeviceCommandDownlinkKafkaConsumer(preprocessingChain, publisher, commandService, sessions, admission);
    }

    /**
     * 创建唯一的实时 Kafka 到 Redis 项目频道桥接消费者。
     *
     * @param projectPublisher 已提交增量的 Redis 扇出端口
     * @return 实时 Kafka 消费者
     */
    @Bean
    RealtimeKafkaConsumer realtimeKafkaConsumer(RealtimeProjectPublisher projectPublisher,com.things.link.integration.application.PublicRealtimeIngress publicIngress) {
        return new RealtimeKafkaConsumer(projectPublisher,publicIngress);
    }

    /**
     * 区分不可重试协议错误与可重试基础设施错误，并在最终失败时保留原记录到 DLQ。
     *
     * @param kafkaTemplate 继承幂等 producer 与 trace interceptor 的发布模板
     * @param metrics 数据面指标门面，只在死信得到 broker 确认后计数
     * @return Boot 默认 listener factory 使用的公共错误处理器
     */
    @Bean
    CommonErrorHandler uplinkKafkaErrorHandler(
            KafkaTemplate<String, Object> kafkaTemplate,
            DataPlaneMetrics metrics) {
        MeteredDeadLetterPublishingRecoverer recoverer = new MeteredDeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> new TopicPartition(DEAD_LETTER_TOPIC, -1),
                metrics);
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                recoverer,
                new FixedBackOff(RETRY_INTERVAL_MILLIS, RETRY_ATTEMPTS));
        // 协议与分区键错误重放不会改变结果，直接恢复到 DLQ，避免阻塞同设备后续消息。
        errorHandler.addNotRetryableExceptions(
                InvalidUplinkMessageException.class,
                InvalidDownlinkMessageException.class);
        return errorHandler;
    }
}
