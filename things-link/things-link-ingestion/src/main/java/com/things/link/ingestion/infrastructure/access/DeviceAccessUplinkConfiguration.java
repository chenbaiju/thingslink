package com.things.link.ingestion.infrastructure.access;

import com.things.link.device.application.DeviceAccessAcceptancePort;
import com.things.link.ingestion.application.DevicePropertyReportReader;
import com.things.link.ingestion.application.UplinkTimestampPolicy;
import com.things.link.ingestion.application.access.DeviceAccessUplinkIngressService;
import com.things.link.ingestion.application.access.DeviceAccessUplinkNormalizer;
import com.things.link.ingestion.application.access.DeviceAccessUplinkPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * 新协议接入骨架装配：统一信封标准化、受理用例与总线接管端口。
 *
 * <p>与 MQTT 路径共用 {@link DevicePropertyReportReader} 与 {@link UplinkTimestampPolicy}，
 * 协议服务器（HTTP／TCP／CoAP）在各自切片加入时只需注入受理用例，不再复制解析与交接逻辑。
 */
@Configuration(proxyBeanMethods = false)
public class DeviceAccessUplinkConfiguration {

    /**
     * 创建协议无关接入标准化器。
     *
     * @param reportReader 与 MQTT 共用的业务载荷读取器
     * @param timestampPolicy D-039 设备未来时间边界
     * @return 新协议接入标准化器
     */
    @Bean
    DeviceAccessUplinkNormalizer deviceAccessUplinkNormalizer(
            DevicePropertyReportReader reportReader,
            UplinkTimestampPolicy timestampPolicy) {
        return new DeviceAccessUplinkNormalizer(reportReader, timestampPolicy);
    }

    /**
     * 创建标准上行总线接管端口。
     *
     * @param kafkaTemplate 标准上行发布模板
     * @return Kafka 接管端口
     */
    @Bean
    DeviceAccessUplinkPublisher deviceAccessUplinkPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        return new KafkaDeviceAccessUplinkPublisher(kafkaTemplate);
    }

    /**
     * 创建新协议上行受理用例。
     *
     * @param normalizer 协议无关标准化器
     * @param publisher 总线接管端口
     * @param acceptance device 域拥有的幂等受理事实端口
     * @return 受理用例
     */
    @Bean
    DeviceAccessUplinkIngressService deviceAccessUplinkIngressService(
            DeviceAccessUplinkNormalizer normalizer,
            DeviceAccessUplinkPublisher publisher,
            DeviceAccessAcceptancePort acceptance) {
        return new DeviceAccessUplinkIngressService(normalizer, publisher, acceptance);
    }
}
