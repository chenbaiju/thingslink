package com.things.link.ingestion.infrastructure.protocol.tcp;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import com.things.link.support.observability.KafkaConsumerProcessingMetrics;
import com.things.link.support.kafka.KafkaTraceRecordInterceptor;

import java.util.HashMap;

/** TCP独立组从保留历史开始，沿用Boot错误处理及已有外部调用的单记录poll边界。 */
@ConditionalOnProperty(name = "things-link.deployment.role",
        havingValue = "device-access", matchIfMissing = true)
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "things-link.access.tcp", name = "enabled", havingValue = "true")
public class TcpDownlinkKafkaConfiguration {
    /** 使用隔离配置副本，不改变MQTT及其他共享组。 */
    @Bean
    ConcurrentKafkaListenerContainerFactory<Object, Object> tcpDownlinkKafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer, ConsumerFactory<Object, Object> base,
            TcpDownlinkReadiness readiness, KafkaConsumerProcessingMetrics metrics) {
        var properties = new HashMap<String, Object>(base.getConfigurationProperties());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
        properties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1);
        properties.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300000);
        var factory = new ConcurrentKafkaListenerContainerFactory<Object, Object>();
        configurer.configure(factory, new DefaultKafkaConsumerFactory<>(properties));
        factory.setAutoStartup(true);
        factory.setRecordInterceptor(new org.springframework.kafka.listener.CompositeRecordInterceptor<>(
                new KafkaTraceRecordInterceptor(metrics), (record, consumer) -> {
                    readiness.sampleLag(consumer);
                    return record;
                }));
        factory.getContainerProperties().setIdleEventInterval(1000L);
        // 不能将该接口暴露成Bean：Boot会把唯一同类型Bean装到所有共享消费工厂。
        factory.getContainerProperties().setConsumerRebalanceListener(new org.springframework.kafka.listener.ConsumerAwareRebalanceListener() {
            @Override public void onPartitionsAssigned(org.apache.kafka.clients.consumer.Consumer<?, ?> consumer,
                    java.util.Collection<org.apache.kafka.common.TopicPartition> partitions) {
                readiness.onPartitionsAssigned(consumer, partitions);
            }
            @Override public void onPartitionsRevokedBeforeCommit(org.apache.kafka.clients.consumer.Consumer<?, ?> consumer,
                    java.util.Collection<org.apache.kafka.common.TopicPartition> partitions) {
                readiness.onPartitionsRevokedBeforeCommit(consumer, partitions);
            }
            @Override public void onPartitionsLost(org.apache.kafka.clients.consumer.Consumer<?, ?> consumer,
                    java.util.Collection<org.apache.kafka.common.TopicPartition> partitions) {
                readiness.onPartitionsLost(consumer, partitions);
            }
        });
        return factory;
    }
}
