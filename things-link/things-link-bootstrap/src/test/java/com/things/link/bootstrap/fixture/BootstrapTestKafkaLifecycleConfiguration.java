package com.things.link.bootstrap.fixture;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

/**
 * 约束 bootstrap 集成测试中的 Kafka listener 生命周期。
 *
 * <p>Spring 7 会暂停并恢复缓存的测试上下文。Spring Kafka 注册中心默认在上下文恢复时
 * 启动全部 listener，即使容器首次创建时配置了 {@code auto-startup=false}。这会让数据库、
 * HTTP 等非消息测试在 CI 中隐式连接 {@code localhost:19092}；开发机运行着 Redpanda 时则
 * 会掩盖该依赖。这里关闭“恢复后一律启动”，让每个容器继续遵守自己的 auto-startup 配置。
 * 真实消息测试把 auto-startup 设为 true，因此仍会连接 Testcontainers Kafka。</p>
 */
@Configuration(proxyBeanMethods = false)
class BootstrapTestKafkaLifecycleConfiguration {

    /**
     * 在 listener 注册中心完成初始化后收紧恢复策略。
     *
     * @return 只处理 Kafka listener 注册中心的测试 Bean 后处理器
     */
    @Bean
    static BeanPostProcessor kafkaListenerEndpointRegistryLifecycleCustomizer() {
        return new KafkaListenerEndpointRegistryLifecycleCustomizer();
    }

    /**
     * 将测试上下文恢复行为对齐到容器声明的 auto-startup，而不改变生产配置。
     */
    private static final class KafkaListenerEndpointRegistryLifecycleCustomizer implements BeanPostProcessor {

        /**
         * 关闭 Spring Kafka 默认的“上下文刷新后无条件启动”行为。
         *
         * @param bean 已完成常规初始化的 Bean
         * @param beanName Bean 名称
         * @return 原 Bean，不替换框架对象
         * @throws BeansException Bean 后处理失败时交由 Spring 终止上下文启动
         */
        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
            if (bean instanceof KafkaListenerEndpointRegistry registry) {
                registry.setAlwaysStartAfterRefresh(false);
            }
            return bean;
        }
    }
}
