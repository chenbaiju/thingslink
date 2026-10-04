package com.things.link.rule.infrastructure;

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

/** ADR0154独立原始字节入口；数据库失败不得被公共DLQ恢复器跳过。 */
@Configuration(proxyBeanMethods=false)
public class AutomationKafkaConfiguration {
    @Bean("automationPropertyKafkaListenerContainerFactory")
    ConcurrentKafkaListenerContainerFactory<Object,Object> factory(ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object,Object> base){
        var properties=new HashMap<>(base.getConfigurationProperties());
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,false);
        properties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,10);
        Deserializer<Object> raw=(topic,bytes)->bytes;
        var consumer=new DefaultKafkaConsumerFactory<>(properties,raw,raw);
        var factory=new ConcurrentKafkaListenerContainerFactory<Object,Object>();
        configurer.configure(factory,consumer);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        var errors=new DefaultErrorHandler((record,failure)->{throw new IllegalStateException("自动化事实未持久化，禁止跳过offset",failure);},
                new FixedBackOff(1000,FixedBackOff.UNLIMITED_ATTEMPTS));
        // 原始字节不会在反序列化阶段失败；包括未知代码故障在内都失败关闭，无隐藏的零重试类别。
        errors.setClassifications(java.util.Map.of(Exception.class,true),true);
        errors.setAckAfterHandle(false);
        factory.setCommonErrorHandler(errors);
        return factory;
    }
}
