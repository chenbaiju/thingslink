package com.things.link.ingestion.infrastructure;

import com.things.link.ingestion.application.CommandDownlinkPublisher;
import com.things.link.ingestion.application.MqttDownlinkAdmissionService;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.things.link.ingestion.application.InvalidDownlinkMessageException;
import com.things.link.shared.message.TopologyReplyMessage;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;

/**
 * 消费 {@code tc.device.topo.reply} 拓扑回执并发布到网关的 {@code down/topo/reply}。
 *
 * <p>回执与拓扑事务同提交（Outbox 至少一次），本消费者幂等发布：重复投递只重复发布同一回执，网关侧按
 * {@code requestId} 去重。分区键固定为网关 ID，保证同一网关的回执有序。</p>
 */
public final class TopologyReplyKafkaConsumer {

    /** 只记录标识与结果，不打印可能含子设备详情的 payload。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(TopologyReplyKafkaConsumer.class);

    /** 拓扑回执主题，与 Outbox 发布端保持一致。 */
    public static final String TOPOLOGY_REPLY_TOPIC = "tc.device.topo.reply";

    /** 下行发布端口。 */
    private final CommandDownlinkPublisher publisher;
    /** 短事务冻结网关当前MQTT路由。 */
    private final MqttDownlinkAdmissionService admission;

    /**
     * @param publisher 拓扑回执下行发布端口
     */
    public TopologyReplyKafkaConsumer(CommandDownlinkPublisher publisher, MqttDownlinkAdmissionService admission) {
        this.admission = admission;
        this.publisher = publisher;
    }

    /** @param record 已确权并完成序列化的拓扑回执记录 */
    @KafkaListener(topics = TOPOLOGY_REPLY_TOPIC, groupId = "${things-link.kafka.group-prefix:things-link}-ingestion-topology-reply",
            containerFactory = "externalKafkaListenerContainerFactory",
            concurrency = "${things-link.kafka.concurrency.ingestion-topology-reply:2}")
    public void consume(ConsumerRecord<String, TopologyReplyMessage> record) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("MQTT下行消费不能加入调用方事务");
        }
        TopologyReplyMessage reply = record.value();
        if (reply == null || reply.gatewayId() == null) {
            throw new InvalidDownlinkMessageException("拓扑回执信封及网关不能为空");
        }
        if (!reply.gatewayId().toString().equals(record.key())) {
            throw new InvalidDownlinkMessageException("Kafka key 必须等于拓扑回执网关 ID");
        }
        var route = admission.topology(reply);
        publisher.publishTopologyReply(reply, route);
        LOGGER.debug("拓扑回执已发布 gatewayId={} subDeviceKey={} status={}",
                reply.gatewayId(), reply.subDeviceKey(), reply.status());
    }
}
