package com.things.link.ingestion.infrastructure;

import com.things.link.ingestion.application.InvalidUplinkMessageException;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.message.EventUplinkMessage;
import com.things.link.telemetry.application.EventIngestionService;
import com.things.link.telemetry.application.ProjectIngestionRejectedException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;

import java.util.Objects;

/** 独立事件消费者，仅进入同事务发生事实端口，不执行属性规则、影子或自动化。 */
public final class EventUplinkKafkaConsumer {
    /** 生产事件事实事务入口。 */
    private final EventIngestionService ingestionService;

    /** @param ingestionService 事件领域的原子摄入服务 */
    public EventUplinkKafkaConsumer(EventIngestionService ingestionService) {
        this.ingestionService = Objects.requireNonNull(ingestionService);
    }

    /**
     * @param record 按已认证deviceId分区的独立事件记录
     * @throws InvalidUplinkMessageException 永久信封、模型、额度或ACTIVE许可拒绝
     */
    @KafkaListener(topics = RawUplinkKafkaConsumer.EVENT_NORMALIZED_TOPIC,
            groupId = "${things-link.kafka.group-prefix:things-link}-ingestion-event",
            containerFactory = "factKafkaListenerContainerFactory",
            concurrency = "${things-link.kafka.concurrency.ingestion-event:4}")
    public void consume(ConsumerRecord<String, EventUplinkMessage> record) {
        EventUplinkMessage message = record.value();
        if (message == null || !message.deviceId().toString().equals(record.key()))
            throw new InvalidUplinkMessageException("EVENT_KAFKA_ENVELOPE_INVALID");
        try {
            ingestionService.ingest(message);
        } catch (BusinessException exception) {
            // 仅确定业务拒绝直接死信，不附参数或含SQL的异常链。
            throw new InvalidUplinkMessageException("EVENT_REJECTED_" + exception.errorCode().code());
        } catch (ProjectIngestionRejectedException exception) {
            throw new InvalidUplinkMessageException("EVENT_PROJECT_WRITE_REJECTED");
        }
        // SQL、事务锁和额度权威暂时不可用保留原异常类型，由公共错误处理器有限重试。
    }
}
