package com.things.link.bootstrap.fixture;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;

/**
 * 设备面接入闭环所需的数据面主题，供开启真实 Kafka 的接入集成测试复用。
 *
 * <p>测试 profile 默认不建主题也不启动 listener（避免非消息测试连不上 broker），因此需要走完整管道的接入用例
 * 显式恢复两者；这些声明与既有 MQTT 端到端用例的做法一致，而不是为某一片放宽默认值。HTTP、TCP 两种协议共用
 * 同一条管道，因此主题声明也只能有一份，否则两个协议的用例会各自漂移成不同拓扑。</p>
 */
@TestConfiguration(proxyBeanMethods = false)
public class DeviceDataPlaneTopicsTestConfiguration {

    /** @return 与生产原始上行主题对应的三分区测试主题 */
    @Bean
    NewTopic rawUplinkTopic() {
        return TopicBuilder.name("tc.device.uplink.raw").partitions(3).replicas(1).build();
    }

    /** @return 与生产规范上行主题对应的三分区测试主题 */
    @Bean
    NewTopic normalizedUplinkTopic() {
        return TopicBuilder.name("tc.device.uplink.normalized").partitions(3).replicas(1).build();
    }

    /** @return 规则之后、物模型之前的续接主题 */
    @Bean
    NewTopic processedUplinkTopic() {
        return TopicBuilder.name("tc.device.uplink.processed").partitions(3).replicas(1).build();
    }

    /** @return 命令下行主题 */
    @Bean
    NewTopic commandDownlinkTopic() {
        return TopicBuilder.name("tc.device.downlink").partitions(3).replicas(1).build();
    }

    /** @return 实时推送主题 */
    @Bean
    NewTopic realtimeTopic() {
        return TopicBuilder.name("tc.device.realtime").partitions(3).replicas(1).build();
    }

    /** @return 告警通知主题 */
    @Bean
    NewTopic notificationTopic() {
        return TopicBuilder.name("tc.notification").partitions(3).replicas(1).build();
    }

    /** @return 规则通知主题 */
    @Bean
    NewTopic ruleNotificationTopic() {
        return TopicBuilder.name("tc.rule.notification").partitions(3).replicas(1).build();
    }

    /** @return 命令终态主题 */
    @Bean
    NewTopic deviceCommandTerminalTopic() {
        return TopicBuilder.name("tc.device.command.terminal").partitions(3).replicas(1).build();
    }

    /** @return 一分钟退避重试主题 */
    @Bean
    NewTopic ruleRetryOneMinuteTopic() {
        return TopicBuilder.name("tc.rule.retry.1m").partitions(1).replicas(1).build();
    }

    /** @return 五分钟退避重试主题 */
    @Bean
    NewTopic ruleRetryFiveMinutesTopic() {
        return TopicBuilder.name("tc.rule.retry.5m").partitions(1).replicas(1).build();
    }

    /** @return 统一死信主题 */
    @Bean
    NewTopic deadLetterTopic() {
        return TopicBuilder.name("tc.dlq").partitions(1).replicas(1).build();
    }
}
