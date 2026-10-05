package com.things.link.integration.infrastructure;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.Deserializer;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import java.util.HashMap;

/** 任何死信队列恢复都不能将持久准入不可用的消息推进为已提交偏移。 */
@Configuration(proxyBeanMethods=false)
public class WebhookSourceKafkaConfiguration {
    @Bean("webhookSourceKafkaListenerContainerFactory")
    ConcurrentKafkaListenerContainerFactory<Object,Object> factory(ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object,Object> base){
        var properties=new HashMap<>(base.getConfigurationProperties());
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,false);
        properties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,10);
        Deserializer<Object> raw=(topic,bytes)->bytes;
        var factory=new ConcurrentKafkaListenerContainerFactory<Object,Object>();
        configurer.configure(factory,new DefaultKafkaConsumerFactory<>(properties,raw,raw));
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        var errors=new DefaultErrorHandler((record,failure)->{throw new IllegalStateException("Webhook source not admitted");},
            new FixedBackOff(1000,FixedBackOff.UNLIMITED_ATTEMPTS));
        errors.setClassifications(java.util.Map.of(Exception.class,true),true);
        errors.setAckAfterHandle(false);
        // 下方异常仅包含固定原因码，不包含来源正文。
        factory.setCommonErrorHandler(errors);
        return factory;
    }
}
