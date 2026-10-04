package com.things.link.ingestion.infrastructure.access;

import com.things.link.ingestion.application.access.DeviceAccessHandoffUnavailableException;
import com.things.link.ingestion.application.access.DeviceAccessUplinkPublisher;
import com.things.link.ingestion.infrastructure.RawUplinkKafkaConsumer;
import com.things.link.shared.message.StandardUplinkMessage;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.concurrent.TimeUnit;

/**
 * 通过 Kafka 把新协议标准上行交接到既有 normalized 管道。
 *
 * <p>主题与分区键与 MQTT 路径完全一致：继续使用 {@code tc.device.uplink.normalized} 并以 deviceId
 * 为 key。新协议不得自建主题，否则规则、影子与遥测消费者需要按协议复制一份，顺序性与跨协议去重也会
 * 退化成两套事实。</p>
 *
 * <p>只有 broker 确认后才返回成功；等待超时、网络故障与线程中断一律转换为
 * {@link DeviceAccessHandoffUnavailableException}，由协议层映射 {@code HANDOFF_UNAVAILABLE}。</p>
 */
public class KafkaDeviceAccessUplinkPublisher implements DeviceAccessUplinkPublisher {

    /** 等待 broker 确认的上限，与既有标准发布路径保持一致，避免请求线程无界占用。 */
    private static final long SEND_TIMEOUT_SECONDS = 10L;

    /** 继承 support 模块 trace producer interceptor 的 Kafka 模板。 */
    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * 创建 Kafka 接入发布器。
     *
     * @param kafkaTemplate 标准上行发布模板
     */
    public KafkaDeviceAccessUplinkPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publish(StandardUplinkMessage message) {
        try {
            kafkaTemplate.send(RawUplinkKafkaConsumer.NORMALIZED_UPLINK_TOPIC,
                            message.deviceId().toString(), message)
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DeviceAccessHandoffUnavailableException("等待总线接管设备接入消息时被中断", exception);
        } catch (Exception exception) {
            throw new DeviceAccessHandoffUnavailableException("总线未确认接管设备接入消息", exception);
        }
    }
}
