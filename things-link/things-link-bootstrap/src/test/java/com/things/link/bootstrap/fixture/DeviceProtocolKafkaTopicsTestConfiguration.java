package com.things.link.bootstrap.fixture;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

import static com.things.link.ingestion.infrastructure.DeviceConfigKafkaConsumer.CONFIG_TOPIC;
import static com.things.link.ingestion.infrastructure.ModbusRequestKafkaConsumer.MODBUS_REQUEST_TOPIC;
import static com.things.link.ingestion.infrastructure.RawUplinkKafkaConsumer.BATCH_TOPIC;
import static com.things.link.ingestion.infrastructure.RawUplinkKafkaConsumer.CONFIG_REPLY_TOPIC;
import static com.things.link.ingestion.infrastructure.RawUplinkKafkaConsumer.MODBUS_RESPONSE_TOPIC;
import static com.things.link.ingestion.infrastructure.RawUplinkKafkaConsumer.TOPO_TOPIC;
import static com.things.link.ingestion.infrastructure.TopologyReplyKafkaConsumer.TOPOLOGY_REPLY_TOPIC;

/**
 * S10 新增的 Kafka 主题声明，供 bootstrap 中开启 Kafka listener 的集成测试复用。
 *
 * <p>生产环境由 deploy 脚本创建 12 分区；测试用 3 分区，仅验证契约与分区键语义。</p>
 */
@Configuration(proxyBeanMethods = false)
public class DeviceProtocolKafkaTopicsTestConfiguration {

    /** BE-001-B 独立事件摄取主题，保留生产十二分区和七天原始事实期限。 */
    @Bean
    NewTopic eventNormalizedTopic() {
        return TopicBuilder.name(com.things.link.ingestion.infrastructure.RawUplinkKafkaConsumer.EVENT_NORMALIZED_TOPIC)
                .partitions(12).replicas(1).config("retention.ms", "604800000").build();
    }

    /** S10-2a 拓扑消息主题。 */
    @Bean
    NewTopic topoTopic() {
        return TopicBuilder.name(TOPO_TOPIC).partitions(3).replicas(1).build();
    }

    /** S10-2b 批量属性上报主题。 */
    @Bean
    NewTopic batchTopic() {
        return TopicBuilder.name(BATCH_TOPIC).partitions(3).replicas(1).build();
    }

    /** S10-3 拓扑回执主题。 */
    @Bean
    NewTopic topologyReplyTopic() {
        return TopicBuilder.name(TOPOLOGY_REPLY_TOPIC).partitions(3).replicas(1).build();
    }

    /** S10-4b 配置下发主题。 */
    @Bean
    NewTopic configTopic() {
        return TopicBuilder.name(CONFIG_TOPIC).partitions(3).replicas(1).build();
    }

    /** S10-4b 配置回执主题。 */
    @Bean
    NewTopic configReplyTopic() {
        return TopicBuilder.name(CONFIG_REPLY_TOPIC).partitions(3).replicas(1).build();
    }

    /** S10-4c Modbus 读请求主题。 */
    @Bean
    NewTopic modbusRequestTopic() {
        return TopicBuilder.name(MODBUS_REQUEST_TOPIC).partitions(3).replicas(1).build();
    }

    /** S10-4c Modbus 响应主题。 */
    @Bean
    NewTopic modbusResponseTopic() {
        return TopicBuilder.name(MODBUS_RESPONSE_TOPIC).partitions(3).replicas(1).build();
    }
}
