package com.things.link.support.cache;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/** 三类缓存统一 Redis 失效频道装配。 */
@Configuration(proxyBeanMethods = false)
public class CacheInvalidationConfiguration {

    /**
     * @param connectionFactory Redis 连接工厂
     * @param subscriber 统一事件消费者
     * @return 独立监听容器；频道故障不得阻塞事实事务
     */
    @Bean
    public RedisMessageListenerContainer cacheInvalidationRedisMessageListenerContainer(
            RedisConnectionFactory connectionFactory, CacheInvalidationSubscriber subscriber) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, new ChannelTopic(CacheInvalidationPublisher.CHANNEL));
        return container;
    }
}
