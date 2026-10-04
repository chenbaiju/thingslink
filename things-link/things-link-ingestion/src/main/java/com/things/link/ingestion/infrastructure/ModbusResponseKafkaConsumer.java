package com.things.link.ingestion.infrastructure;

import com.things.link.device.application.ModbusResponseAcceptanceService;
import com.things.link.ingestion.application.InvalidUplinkMessageException;
import com.things.link.shared.message.ModbusResponse;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;

/**
 * 消费 {@code tc.device.modbus.response}，交给 device 模块在同事务持久接纳 normalized Outbox。
 *
 * <p>响应按 requestId 关联轮询行，解码后的属性值以子设备 ID 为 key 进入既有 telemetry 管道；
 * 稳定 messageId 沿用 requestId，保证响应重投幂等。响应分区键固定为网关 ID。
 * ADR0063：消费返回前只提交数据库接纳，Kafka发布由共享Outbox异步完成。</p>
 * <p>事务失败传播给原消费重试链；不得恢复先完成请求再直接发送的路径。</p>
 */
public final class ModbusResponseKafkaConsumer {

    /** 只记录标识，不打印寄存器原始值。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(ModbusResponseKafkaConsumer.class);

    /** 响应主题，与 raw 分流发布端保持一致。 */
    public static final String MODBUS_RESPONSE_TOPIC = RawUplinkKafkaConsumer.MODBUS_RESPONSE_TOPIC;

    /** 标准交付目标合同；实际发送归共享Outbox发布器。 */
    public static final String NORMALIZED_UPLINK_TOPIC = RawUplinkKafkaConsumer.NORMALIZED_UPLINK_TOPIC;

    /** ADR0063规定的唯一生产可靠接管入口。 */
    private final ModbusResponseAcceptanceService acceptanceService;

    /** @param acceptanceService 请求完成与持久交付的事务编排代理 */
    public ModbusResponseKafkaConsumer(ModbusResponseAcceptanceService acceptanceService) {
        this.acceptanceService = acceptanceService;
    }

    /** @param record 已确权并完成协议解析的响应记录 */
    @KafkaListener(topics = MODBUS_RESPONSE_TOPIC, groupId = "${things-link.kafka.group-prefix:things-link}-ingestion-modbus-response",
            containerFactory = "factKafkaListenerContainerFactory",
            concurrency = "${things-link.kafka.concurrency.ingestion-modbus-response:2}")
    public void consume(ConsumerRecord<String, ModbusResponse> record) {
        ModbusResponse response = record.value();
        if (response == null || response.gatewayId() == null) {
            throw new InvalidUplinkMessageException("Modbus 响应信封及网关不能为空");
        }
        if (!response.gatewayId().toString().equals(record.key())) {
            throw new InvalidUplinkMessageException("Kafka key 必须等于响应网关 ID");
        }
        acceptanceService.accept(response);
        LOGGER.debug("Modbus 响应已处理 gatewayId={} requestId={} status={}",
                response.gatewayId(), response.requestId(), response.status());
    }
}
