package com.things.link.ingestion.infrastructure;

import com.things.link.device.application.DeviceMqttDownlinkRoute;
import com.things.link.ingestion.application.MqttDownlinkAdmissionService;
import static com.things.link.ingestion.infrastructure.MqttRouteFixtures.route;

import com.things.link.ingestion.application.CommandDownlinkPublisher;
import com.things.link.ingestion.application.InvalidDownlinkMessageException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.TopologyReplyMessage;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** 拓扑回执消费者：校验网关分区键并把回执交给下行发布端口。 */
class TopologyReplyKafkaConsumerTests {
    /** 当前路由许可替身。 */
    private final MqttDownlinkAdmissionService admission = mock(MqttDownlinkAdmissionService.class);

    /** 分区键一致时发布回执。 */
    @Test
    void publishesReplyWhenKeyMatchesGateway() {
        CommandDownlinkPublisher publisher = mock(CommandDownlinkPublisher.class);
        TopologyReplyKafkaConsumer consumer = new TopologyReplyKafkaConsumer(publisher, admission);
        TopologyReplyMessage reply = reply();
        org.mockito.Mockito.when(admission.topology(reply)).thenReturn(route(reply));

        consumer.consume(new ConsumerRecord<>("tc.device.topo.reply", 0, 0L,
                reply.gatewayId().toString(), reply));

        verify(publisher).publishTopologyReply(reply, route(reply));
    }

    /** 分区键不一致属协议错误，禁止悄悄修正后继续。 */
    @Test
    void rejectsMismatchedGatewayKey() {
        CommandDownlinkPublisher publisher = mock(CommandDownlinkPublisher.class);
        TopologyReplyKafkaConsumer consumer = new TopologyReplyKafkaConsumer(publisher, admission);
        TopologyReplyMessage reply = reply();
        org.mockito.Mockito.when(admission.topology(reply)).thenReturn(route(reply));

        assertThatThrownBy(() -> consumer.consume(new ConsumerRecord<>("tc.device.topo.reply", 0, 0L,
                Uuid7.generate().toString(), reply)))
                .isInstanceOf(InvalidDownlinkMessageException.class)
                .hasMessageContaining("网关 ID");
    }

    private static TopologyReplyMessage reply() {
        return new TopologyReplyMessage(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                "project_1", "gateway_1", "sub_01", TopologyReplyMessage.Status.SUCCESS, null, null,
                Instant.parse("2026-08-14T09:00:00Z"), "0123456789abcdef0123456789abcdef");
    }
}
