package com.things.link.ingestion.infrastructure;

import com.things.link.device.application.DeviceIngestionContext;
import com.things.link.device.application.DeviceIngestionService;

import com.things.link.ingestion.application.UplinkTimestampPolicy;
import com.things.link.ingestion.application.InvalidUplinkMessageException;
import com.things.link.rule.application.engine.RuleMessage;
import com.things.link.rule.application.queue.PublishedRuleCatalog;
import com.things.link.rule.application.queue.PublishedRulePlan;
import com.things.link.rule.application.queue.PublishedRuleStep;
import com.things.link.rule.application.queue.RuleExecutionCoordinator;
import com.things.link.rule.application.queue.RuleExecutionEnvelope;
import com.things.link.rule.application.queue.RuleExecutionKey;
import com.things.link.rule.application.queue.RuleExecutionSubmission;
import com.things.link.shared.message.StandardUplinkMessage;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Clock;
import java.util.concurrent.TimeUnit;

/**
 * 把确权后的标准上行交给已发布规则计划，并可靠续接到 processed Topic。
 *
 * <p>本 listener 只冻结活动版本和进入 S8-2B 公平队列，不执行租户脚本。无活动规则也必须等待 processed
 * broker 确认后返回；有规则时等待协调器完成成功续接、retry 或 DLQ 的持久终态，从而可以提交混合租户输入
 * 分区的当前 offset，而不会让慢租户占住 Kafka consumer 线程执行 guest 代码。</p>
 */
public final class NormalizedUplinkKafkaConsumer {

    /** 不记录源码或 payload，只保留稳定消息身份和有限提交结果。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(NormalizedUplinkKafkaConsumer.class);
    /** 标准上行主题，与 raw 标准化发布端保持一致。 */
    public static final String NORMALIZED_UPLINK_TOPIC = RawUplinkKafkaConsumer.NORMALIZED_UPLINK_TOPIC;
    /** 无规则直通等待 broker 确认的上限。 */
    private static final long SEND_TIMEOUT_SECONDS = 10L;

    /** 以可信 tenant/project 二元组读取 ACTIVE 不可变版本。 */
    private final PublishedRuleCatalog ruleCatalog;
    /** 公平队列、回执与有限恢复的唯一入口。 */
    private final RuleExecutionCoordinator coordinator;
    /** 无规则直通 processed Topic 的共享幂等模板。 */
    private final KafkaTemplate<String, Object> kafkaTemplate;
    /** Standard 与规则快照之间的可信字段映射器。 */
    private final RuleUplinkMessageMapper messageMapper;
    /** D-039 在规则或任何业务副作用前拒绝远未来设备时间。 */
    private final UplinkTimestampPolicy timestampPolicy;
    /** 冻结首次入队 UTC 时间，测试不依赖系统墙钟。 */
    private final Clock clock;
    /** 规则前执行版本/Schema admission，HISTORY_ONLY 不得产生规则副作用。 */
    private final DeviceIngestionService deviceIngestionService;

    /**
     * @param ruleCatalog 已发布规则目录
     * @param coordinator 规则公平队列入口
     * @param kafkaTemplate processed 可靠发布模板
     * @param messageMapper 标准信封映射器
     * @param timestampPolicy 设备未来时间戳边界
     * @param clock UTC 时钟
     */
    public NormalizedUplinkKafkaConsumer(
            PublishedRuleCatalog ruleCatalog,
            RuleExecutionCoordinator coordinator,
            KafkaTemplate<String, Object> kafkaTemplate,
            RuleUplinkMessageMapper messageMapper,
            UplinkTimestampPolicy timestampPolicy,
            Clock clock,
            DeviceIngestionService deviceIngestionService) {
        this.ruleCatalog = ruleCatalog;
        this.coordinator = coordinator;
        this.kafkaTemplate = kafkaTemplate;
        this.messageMapper = messageMapper;
        this.timestampPolicy = timestampPolicy;
        this.clock = clock;
        this.deviceIngestionService = deviceIngestionService;
    }

    /** @param record 已认证并完成协议标准化的上行记录 */
    @KafkaListener(topics = NORMALIZED_UPLINK_TOPIC, groupId = "${things-link.kafka.group-prefix:things-link}-ingestion-normalized",
            containerFactory = "normalizedIngressKafkaListenerContainerFactory",
            concurrency = "${things-link.kafka.concurrency.ingestion-normalized:4}")
    public void consume(ConsumerRecord<String, StandardUplinkMessage> record) {
        StandardUplinkMessage message = requireMessage(record);
        timestampPolicy.validate(message);
        DeviceIngestionContext ingestionContext;
        try {
            ingestionContext = deviceIngestionService.validateReportedProperties(
                    message.tenantId(), message.projectId(), message.deviceId(), message.modelVersion(),
                    message.receivedAt(), message.payload());
        } catch (RuntimeException exception) {
            throw new InvalidUplinkMessageException("标准上行未通过版本化物模型 admission", exception);
        }
        if (!message.tenantId().equals(ingestionContext.tenantId())) {
            throw new InvalidUplinkMessageException("标准上行租户归属与版本裁决不一致");
        }
        if (ingestionContext.eligibility() == DeviceIngestionContext.Eligibility.HISTORY_ONLY) {
            // 旧版迟到消息只能进入 telemetry 历史/消息日志，禁止读取规则目录或产生任何规则副作用。
            publishPassthrough(message);
            return;
        }
        PublishedRulePlan plan = ruleCatalog.resolve(
                message.tenantId(), message.projectId(), message.messageId());
        if (plan.isEmpty()) {
            publishPassthrough(message);
            return;
        }
        PublishedRuleStep first = plan.steps().getFirst();
        RuleMessage ruleMessage = messageMapper.toRuleMessage(message);
        RuleExecutionEnvelope envelope = new RuleExecutionEnvelope(
                new RuleExecutionKey(message.projectId(), message.messageId(),
                        first.ruleId(), first.ruleVersionId()),
                message.tenantId(), ruleMessage, plan, 1, clock.instant());
        RuleExecutionSubmission result = coordinator.submitAndAwait(envelope);
        LOGGER.debug("标准上行规则终态已持久化 messageId={} deviceId={} result={}",
                message.messageId(), message.deviceId(), result);
    }

    /** 校验标准信封和设备有序 key，禁止悄悄修正生产端契约。 */
    private static StandardUplinkMessage requireMessage(ConsumerRecord<String, StandardUplinkMessage> record) {
        StandardUplinkMessage message = record.value();
        if (message == null) {
            throw new InvalidUplinkMessageException("标准上行信封不能为空");
        }
        if (!message.deviceId().toString().equals(record.key())) {
            throw new InvalidUplinkMessageException("Kafka key 必须等于标准信封 deviceId");
        }
        return message;
    }

    /** 无规则也经独立续接 Topic 跨越进程退出窗口，不能在本 listener 直接调用 telemetry。 */
    private void publishPassthrough(StandardUplinkMessage message) {
        try {
            kafkaTemplate.send(ProcessedUplinkKafkaConsumer.PROCESSED_UPLINK_TOPIC,
                            message.deviceId().toString(), message)
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待无规则上行续接发布时线程被中断", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("无规则上行续接发布失败", exception);
        }
    }
}
