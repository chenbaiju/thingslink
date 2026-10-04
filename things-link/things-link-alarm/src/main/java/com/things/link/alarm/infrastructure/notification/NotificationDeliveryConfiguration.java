package com.things.link.alarm.infrastructure.notification;

import com.things.link.alarm.application.NotificationDeliveryProperties;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** 启用 S6-3 通知投递的类型安全配置。 */
@Configuration
@EnableConfigurationProperties(NotificationDeliveryProperties.class)
public class NotificationDeliveryConfiguration {
    /** @return 所有通知截止时间统一使用 UTC，禁止混入节点本地时区 */
    @Bean
    public Clock notificationClock() {
        return Clock.systemUTC();
    }
}
