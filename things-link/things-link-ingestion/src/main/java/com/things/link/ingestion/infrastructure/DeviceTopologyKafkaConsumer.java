package com.things.link.ingestion.infrastructure;

import com.things.link.device.application.DeviceTopologyIngestionService;
import com.things.link.ingestion.application.InvalidUplinkMessageException;
import com.things.link.shared.message.DeviceTopologyMessage;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;

/**
 * 消费 {@code tc.device.topo} 拓扑消息并交给 device 在线状态机。
 *
 * <p>本 listener 只做信封校验与事务边界入口，业务状态机在 device 应用端口（{@link DeviceTopologyIngestionService}）。
 * 分区键固定为网关 ID，同一网关的拓扑/在线态消息在同一分区内有序；业务拒绝由状态机吸收，不触发重试。</p>
 */
public final class DeviceTopologyKafkaConsumer {

    /** 只记录标识与链路信息，禁止把网关 payload 写入日志。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(DeviceTopologyKafkaConsumer.class);

    /** 拓扑消息主题，与 raw 分流发布端保持一致。 */
    public static final String TOPO_TOPIC = RawUplinkKafkaConsumer.TOPO_TOPIC;

    /** 拓扑在线状态机应用端口。 */
    private final DeviceTopologyIngestionService ingestionService;

    /** @param ingestionService 拓扑在线状态机 */
    public DeviceTopologyKafkaConsumer(DeviceTopologyIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    /** @param record 已确权并完成拓扑协议解析的消息记录 */
    @KafkaListener(topics = TOPO_TOPIC, groupId = "${things-link.kafka.group-prefix:things-link}-ingestion-topology",
            containerFactory = "factKafkaListenerContainerFactory",
            concurrency = "${things-link.kafka.concurrency.ingestion-topology:2}")
    public void consume(ConsumerRecord<String, DeviceTopologyMessage> record) {
        DeviceTopologyMessage message = record.value();
        if (message == null) {
            throw new InvalidUplinkMessageException("拓扑消息信封不能为空");
        }
        if (!message.gatewayId().toString().equals(record.key())) {
            throw new InvalidUplinkMessageException("Kafka key 必须等于拓扑消息网关 ID");
        }
        ingestionService.ingest(message);
        LOGGER.debug("拓扑消息已处理 type={} gatewayId={} subDeviceKey={} traceId={}",
                message.type(), message.gatewayId(), message.subDeviceKey(), message.traceId());
    }
}
