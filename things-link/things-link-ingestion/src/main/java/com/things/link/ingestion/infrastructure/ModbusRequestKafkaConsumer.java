package com.things.link.ingestion.infrastructure;

import com.things.link.ingestion.application.CommandDownlinkPublisher;
import com.things.link.ingestion.application.MqttDownlinkAdmissionService;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.things.link.ingestion.application.InvalidDownlinkMessageException;
import com.things.link.shared.message.ModbusRequest;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;

/**
 * 消费 {@code tc.device.modbus.request} 读请求并发布到网关的 {@code down/modbus/request}。
 *
 * <p>读请求经 Outbox 至少一次投递，本消费者幂等发布：重复投递只重复发布同一请求，网关按 requestId 幂等响应。
 * 分区键固定为网关 ID，保证同一网关的读请求有序。</p>
 */
public final class ModbusRequestKafkaConsumer {

    /** 只记录标识，不打印寄存器参数。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(ModbusRequestKafkaConsumer.class);

    /** 读请求主题，与 Outbox 发布端保持一致。 */
    public static final String MODBUS_REQUEST_TOPIC = "tc.device.modbus.request";

    /** 下行发布端口。 */
    private final CommandDownlinkPublisher publisher;
    /** 短事务冻结网关当前MQTT路由。 */
    private final MqttDownlinkAdmissionService admission;

    /**
     * @param publisher 读请求发布端口
     */
    public ModbusRequestKafkaConsumer(CommandDownlinkPublisher publisher, MqttDownlinkAdmissionService admission) {
        this.admission = admission;
        this.publisher = publisher;
    }

    /** @param record 已确权并完成序列化的读请求记录 */
    @KafkaListener(topics = MODBUS_REQUEST_TOPIC, groupId = "${things-link.kafka.group-prefix:things-link}-ingestion-modbus-request",
            containerFactory = "externalKafkaListenerContainerFactory",
            concurrency = "${things-link.kafka.concurrency.ingestion-modbus-request:2}")
    public void consume(ConsumerRecord<String, ModbusRequest> record) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("MQTT下行消费不能加入调用方事务");
        }
        ModbusRequest request = record.value();
        if (request == null || request.gatewayId() == null) {
            throw new InvalidDownlinkMessageException("Modbus 读请求信封及网关不能为空");
        }
        if (!request.gatewayId().toString().equals(record.key())) {
            throw new InvalidDownlinkMessageException("Kafka key 必须等于读请求网关 ID");
        }
        var route = admission.modbus(request);
        publisher.publishModbusRequest(request, route);
        LOGGER.debug("Modbus 读请求已发布 gatewayId={} requestId={}",
                request.gatewayId(), request.requestId());
    }
}
