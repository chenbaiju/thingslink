package com.things.link.ingestion.infrastructure;

import com.things.link.ingestion.application.CommandDownlinkPublisher;
import com.things.link.ingestion.application.InvalidDownlinkMessageException;
import com.things.link.ingestion.application.MqttDownlinkAdmissionService;
import com.things.link.device.application.DeviceMqttDownlinkRoute;
import com.things.link.device.application.InvalidDeviceConfigPushException;
import com.things.link.device.application.ProjectFrozenConfigDeliveryException;
import com.things.link.shared.message.DeviceConfigPush;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 消费 {@code tc.device.config} 配置下发并发布到网关的 {@code down/config}。
 *
 * <p>配置下发经 Outbox 至少一次投递，本消费者幂等发布：重复投递只重复发布同一配置，网关按 version 幂等
 * 应用（全量点位集整体替换）。分区键固定为网关 ID，保证同一网关的配置有序。</p>
 */
public final class DeviceConfigKafkaConsumer {

    /** 只记录标识与版本，不打印点位详情。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(DeviceConfigKafkaConsumer.class);

    /** 配置下发主题，与 Outbox 发布端保持一致。 */
    public static final String CONFIG_TOPIC = "tc.device.config";

    /** 下行发布端口。 */
    private final CommandDownlinkPublisher publisher;
    /** HTTP外发前的原Outbox与项目冻结准入。 */
    private final MqttDownlinkAdmissionService admissionService;

    /**
     * @param publisher 配置下发发布端口
     * @param admissionService 原Outbox与项目冻结准入
     */
    public DeviceConfigKafkaConsumer(CommandDownlinkPublisher publisher,
                                     MqttDownlinkAdmissionService admissionService) {
        this.publisher = publisher;
        this.admissionService = admissionService;
    }

    /** @param record 已确权并完成序列化的配置下发记录 */
    @KafkaListener(topics = CONFIG_TOPIC, groupId = "${things-link.kafka.group-prefix:things-link}-ingestion-config",
            containerFactory = "externalKafkaListenerContainerFactory",
            concurrency = "${things-link.kafka.concurrency.ingestion-config:2}")
    public void consume(ConsumerRecord<String, DeviceConfigPush> record) {
        // ADR0071决策2：准入必须先独立提交；外层事务会让项目锁跨HTTP并造成回滚后已发送。
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("配置下行消费不能加入调用方事务");
        }
        DeviceConfigPush push = record.value();
        if (push == null || push.gatewayId() == null) {
            throw new InvalidDownlinkMessageException("配置下发信封及网关不能为空");
        }
        if (!push.gatewayId().toString().equals(record.key())) {
            throw new InvalidDownlinkMessageException("Kafka key 必须等于配置下发网关 ID");
        }
        DeviceMqttDownlinkRoute route;
        try {
            route = admissionService.config(push);
        } catch (InvalidDeviceConfigPushException exception) {
            throw new InvalidDownlinkMessageException("配置下发与原持久交付身份不一致", exception);
        } catch (ProjectFrozenConfigDeliveryException exception) {
            throw new InvalidDownlinkMessageException("PROJECT_FROZEN: 项目已冻结，设备配置停止交付", exception);
        }
        publisher.publishConfig(push, route);
        LOGGER.debug("配置下发已发布 gatewayId={} version={} points={}",
                push.gatewayId(), push.version(), push.points().size());
    }
}
