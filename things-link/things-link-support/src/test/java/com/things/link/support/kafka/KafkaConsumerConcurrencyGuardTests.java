package com.things.link.support.kafka;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** G1-C3b Kafka 消费边界目录测试，防止默认值、上限或组数在配置与守卫间静默漂移。 */
class KafkaConsumerConcurrencyGuardTests {

    @Test void enabledWebhookRejectsConcurrencyAboveSixBeforeBrokerAccess(){
        var env=new org.springframework.mock.env.MockEnvironment().withProperty("things-link.integration.webhook.enabled","true").withProperty("things-link.kafka.concurrency.webhook-source","7");
        var admin=org.mockito.Mockito.mock(org.springframework.kafka.core.KafkaAdmin.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->new KafkaConsumerConcurrencyGuard(admin,env).run(null)).isInstanceOf(IllegalStateException.class).hasMessageContaining("webhook-source");
        org.mockito.Mockito.verifyNoInteractions(admin);
    }
    /** 冻结目录必须完整覆盖 16 个生产组，processed 在 D-048 前上限固定为四。 */
    @Test
    void exposesAllFrozenBoundariesAndProcessedCap() {
        Map<String, KafkaConsumerConcurrencyGuard.ConsumerBoundary> boundaries =
                KafkaConsumerConcurrencyGuard.boundaries();

        assertThat(boundaries).hasSize(16);
        assertThat(boundaries.get("things-link.kafka.concurrency.ingestion-processed"))
                .satisfies(boundary -> {
                    assertThat(boundary.defaultConcurrency()).isEqualTo(4);
                    assertThat(boundary.maximumConcurrency()).isEqualTo(4);
                    assertThat(boundary.topics()).containsExactly("tc.device.uplink.processed");
                });
        assertThat(boundaries.values())
                .allSatisfy(boundary -> {
                    assertThat(boundary.defaultConcurrency()).isBetween(1, boundary.maximumConcurrency());
                    assertThat(boundary.topics()).isNotEmpty();
                });
    }

    @Test
    void deviceAccessOnlyChecksDedicatedTcpTopicAndRejectsExcessiveConcurrency() {
        assertThat(KafkaConsumerConcurrencyGuard.accessBoundaries()).hasSize(1);
        assertThat(KafkaConsumerConcurrencyGuard.accessBoundaries()
                .get("things-link.kafka.concurrency.tcp-downlink"))
                .satisfies(boundary -> {
                    assertThat(boundary.defaultConcurrency()).isEqualTo(1);
                    assertThat(boundary.maximumConcurrency()).isEqualTo(16);
                    assertThat(boundary.topics()).containsExactly("tc.device.downlink");
                });

        var env = new org.springframework.mock.env.MockEnvironment()
                .withProperty("things-link.deployment.role", "device-access")
                .withProperty("things-link.access.tcp.enabled", "true")
                .withProperty("things-link.kafka.concurrency.tcp-downlink", "17");
        var admin = org.mockito.Mockito.mock(org.springframework.kafka.core.KafkaAdmin.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new KafkaConsumerConcurrencyGuard(admin, env).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tcp-downlink");
        org.mockito.Mockito.verifyNoInteractions(admin);
    }

    @Test
    void deviceAccessWithoutTcpDoesNotRequireKafkaMetadata() {
        var env = new org.springframework.mock.env.MockEnvironment()
                .withProperty("things-link.deployment.role", "device-access")
                .withProperty("things-link.access.tcp.enabled", "false");
        var admin = org.mockito.Mockito.mock(org.springframework.kafka.core.KafkaAdmin.class);
        new KafkaConsumerConcurrencyGuard(admin, env).run(null);
        org.mockito.Mockito.verifyNoInteractions(admin);
    }
}
