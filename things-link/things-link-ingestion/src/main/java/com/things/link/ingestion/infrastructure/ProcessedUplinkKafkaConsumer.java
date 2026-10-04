package com.things.link.ingestion.infrastructure;

import com.things.link.ingestion.application.InvalidUplinkMessageException;
import com.things.link.ingestion.application.UplinkPreprocessingChain;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.message.StandardUplinkMessage;
import com.things.link.telemetry.application.PropertyIngestionService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;

/**
 * 消费规则处理完成的标准上行，并在物模型校验与持久化前执行其余确定性预处理器。
 *
 * <p>S8-2C 把规则执行从 normalized listener 拆到公平队列；只有规则成功结果已得到 processed Topic 的
 * broker 确认，原消费位点才可提交。本消费者因此成为唯一 telemetry 续接点，避免脚本在 Kafka consumer
 * 线程运行，也避免规则执行成功与遥测摄入之间的进程退出造成消息丢失。</p>
 */
public final class ProcessedUplinkKafkaConsumer {

    /** 高频成功只写 DEBUG，且不记录租户 payload。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessedUplinkKafkaConsumer.class);

    /** 规则成功或无活动规则直通后的可靠续接主题。 */
    public static final String PROCESSED_UPLINK_TOPIC = "tc.device.uplink.processed";

    /** 标准属性摄入应用端口。 */
    private final PropertyIngestionService propertyIngestionService;
    /** 规则处理之后、物模型校验之前的其余预处理扩展链。 */
    private final UplinkPreprocessingChain preprocessingChain;

    /**
     * @param propertyIngestionService telemetry 批量摄入应用端口
     * @param preprocessingChain ADR 0017 冻结位置的确定性预处理链
     */
    public ProcessedUplinkKafkaConsumer(
            PropertyIngestionService propertyIngestionService,
            UplinkPreprocessingChain preprocessingChain) {
        this.propertyIngestionService = propertyIngestionService;
        this.preprocessingChain = preprocessingChain;
    }

    /**
     * 校验设备有序键，并只在规则成功事实之后续接物模型事务。
     *
     * @param record 已经完成规则处理的标准上行记录
     */
    @KafkaListener(topics = PROCESSED_UPLINK_TOPIC, groupId = "${things-link.kafka.group-prefix:things-link}-ingestion-processed",
            containerFactory = "factKafkaListenerContainerFactory",
            concurrency = "${things-link.kafka.concurrency.ingestion-processed:4}")
    public void consume(ConsumerRecord<String, StandardUplinkMessage> record) {
        StandardUplinkMessage message = record.value();
        if (message == null) {
            throw new InvalidUplinkMessageException("规则处理后标准上行信封不能为空");
        }
        if (!message.deviceId().toString().equals(record.key())) {
            throw new InvalidUplinkMessageException("Kafka key 必须等于规则处理后信封 deviceId");
        }
        StandardUplinkMessage preprocessed = preprocessingChain.apply(message);
        try {
            boolean firstProcessing = propertyIngestionService.ingest(preprocessed);
            LOGGER.debug("规则处理后上行业务事务完成 messageId={} deviceId={} firstProcessing={}",
                    preprocessed.messageId(), preprocessed.deviceId(), firstProcessing);
        } catch (BusinessException exception) {
            // 设备归属或物模型约束不会因重放改变，直接进入统一 DLQ，避免无意义阻塞同设备分区。
            throw new InvalidUplinkMessageException("规则处理后属性消息未通过业务契约校验", exception);
        }
    }
}
