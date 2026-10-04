package com.things.link.ingestion.infrastructure;

import com.things.link.device.application.ModbusConfigService;
import com.things.link.ingestion.application.InvalidUplinkMessageException;
import com.things.link.shared.message.DeviceConfigReply;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;

/**
 * 消费 {@code tc.device.config.reply} 配置回执并交给 device 模块做版本诊断与落后重推。
 *
 * <p>分区键固定为网关 ID；回执至少一次投递，版本对比 + 重推是幂等的（全量点位集整体替换）。</p>
 */
public final class DeviceConfigReplyKafkaConsumer {

    /** 只记录标识与版本，不打印点位或诊断详情。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(DeviceConfigReplyKafkaConsumer.class);

    /** 配置回执主题，与 raw 分流发布端保持一致。 */
    public static final String CONFIG_REPLY_TOPIC = RawUplinkKafkaConsumer.CONFIG_REPLY_TOPIC;

    /** 配置版本诊断与重推端口。 */
    private final ModbusConfigService configService;

    /**
     * @param configService 配置版本诊断端口
     */
    public DeviceConfigReplyKafkaConsumer(ModbusConfigService configService) {
        this.configService = configService;
    }

    /** @param record 已确权并完成协议解析的配置回执记录 */
    @KafkaListener(topics = CONFIG_REPLY_TOPIC, groupId = "${things-link.kafka.group-prefix:things-link}-ingestion-config-reply",
            containerFactory = "factKafkaListenerContainerFactory",
            concurrency = "${things-link.kafka.concurrency.ingestion-config-reply:2}")
    public void consume(ConsumerRecord<String, DeviceConfigReply> record) {
        DeviceConfigReply reply = record.value();
        if (reply == null || reply.gatewayId() == null) {
            throw new InvalidUplinkMessageException("配置回执信封及网关不能为空");
        }
        if (!reply.gatewayId().toString().equals(record.key())) {
            throw new InvalidUplinkMessageException("Kafka key 必须等于配置回执网关 ID");
        }
        configService.processReply(reply);
        LOGGER.debug("配置回执已处理 gatewayId={} version={} status={}",
                reply.gatewayId(), reply.version(), reply.status());
    }
}
