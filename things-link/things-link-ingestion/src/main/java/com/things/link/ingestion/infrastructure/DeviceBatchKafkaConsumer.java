package com.things.link.ingestion.infrastructure;

import com.things.link.device.application.DeviceBatchIngestionService;
import com.things.link.device.application.ResolvedSubDeviceReport;
import com.things.link.ingestion.application.InvalidUplinkMessageException;
import com.things.link.shared.message.GatewayBatchMessage;
import com.things.link.shared.message.StandardUplinkMessage;
import com.things.link.shared.message.TransportProtocol;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 消费 {@code tc.device.batch} 批量属性上报，复核归属后拆分到 normalized。
 *
 * <p>本 listener 只做信封校验与发布编排，信任边界复核在 device 应用端口（{@link DeviceBatchIngestionService}）。
 * 分区键固定为网关 ID；拆分后的 normalized 以子设备 ID 为 key，同一子设备仍落在同一分区内保持顺序。
 * 幂等由子设备 messageId 在下游 inbox 去重吸收，本层不抢占 inbox。</p>
 */
public final class DeviceBatchKafkaConsumer {

    /** 高频成功只写 DEBUG，且不记录租户 payload。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(DeviceBatchKafkaConsumer.class);

    /** 批量上报主题，与 raw 分流发布端保持一致。 */
    public static final String BATCH_TOPIC = RawUplinkKafkaConsumer.BATCH_TOPIC;

    /** 标准上行主题，拆分结果继续走既有 telemetry 管道。 */
    public static final String NORMALIZED_UPLINK_TOPIC = RawUplinkKafkaConsumer.NORMALIZED_UPLINK_TOPIC;

    /** 等待 broker 确认的上限，避免 listener 线程在网络故障时无限占用。 */
    private static final long SEND_TIMEOUT_SECONDS = 10L;

    /** 批量归属复核应用端口。 */
    private final DeviceBatchIngestionService ingestionService;

    /** 拆分结果发布到 normalized 的共享幂等模板。 */
    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * @param ingestionService 批量归属复核端口
     * @param kafkaTemplate normalized 可靠发布模板
     */
    public DeviceBatchKafkaConsumer(DeviceBatchIngestionService ingestionService,
                                    KafkaTemplate<String, Object> kafkaTemplate) {
        this.ingestionService = ingestionService;
        this.kafkaTemplate = kafkaTemplate;
    }

    /** @param record 已确权并完成批量协议解析的消息记录 */
    @KafkaListener(topics = BATCH_TOPIC, groupId = "${things-link.kafka.group-prefix:things-link}-ingestion-batch",
            containerFactory = "factKafkaListenerContainerFactory",
            concurrency = "${things-link.kafka.concurrency.ingestion-batch:2}")
    public void consume(ConsumerRecord<String, GatewayBatchMessage> record) {
        GatewayBatchMessage message = record.value();
        if (message == null) {
            throw new InvalidUplinkMessageException("批量上报信封不能为空");
        }
        if (!message.gatewayId().toString().equals(record.key())) {
            throw new InvalidUplinkMessageException("Kafka key 必须等于批量消息网关 ID");
        }
        List<ResolvedSubDeviceReport> resolved = ingestionService.resolve(message);
        for (ResolvedSubDeviceReport report : resolved) {
            publishNormalized(report, message);
        }
        LOGGER.debug("批量上报已拆分 gatewayId={} accepted={} entries={} traceId={}",
                message.gatewayId(), resolved.size(), message.entries().size(), message.traceId());
    }

    /** 把已复核条目转成标准信封，以子设备 ID 为 key 发布；broker 确认后才返回。 */
    private void publishNormalized(ResolvedSubDeviceReport report, GatewayBatchMessage message) {
        StandardUplinkMessage normalized = new StandardUplinkMessage(
                report.messageId(), message.tenantId(), message.projectId(),
                report.deviceId(), message.gatewayId(),
                TransportProtocol.MQTT, StandardUplinkMessage.Direction.UP,
                StandardUplinkMessage.Type.PROPERTY_REPORT,
                report.modelVersion(),
                report.occurredAt(), message.receivedAt(), message.traceId(),
                report.rawBytes(), report.payload());
        try {
            kafkaTemplate.send(NORMALIZED_UPLINK_TOPIC, report.deviceId().toString(), normalized)
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待子设备 normalized 发布确认时线程被中断", exception);
        } catch (Exception exception) {
            // broker/网络错误具备恢复可能，保留普通异常类型以进入有限重试而非直接 DLQ。
            throw new IllegalStateException("子设备 normalized 发布失败", exception);
        }
    }
}
