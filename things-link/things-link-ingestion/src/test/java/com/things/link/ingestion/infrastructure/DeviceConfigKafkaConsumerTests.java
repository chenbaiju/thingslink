package com.things.link.ingestion.infrastructure;

import com.things.link.device.application.DeviceMqttDownlinkRoute;
import com.things.link.ingestion.application.MqttDownlinkAdmissionService;
import static com.things.link.ingestion.infrastructure.MqttRouteFixtures.route;

import com.things.link.device.application.InvalidDeviceConfigPushException;
import com.things.link.device.application.ProjectFrozenConfigDeliveryException;
import com.things.link.ingestion.application.CommandDownlinkPublisher;
import com.things.link.ingestion.application.InvalidDownlinkMessageException;
import com.things.link.shared.message.DeviceConfigPush;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 配置Kafka到EMQX消费者测试，固定提交先于网络及永久错误分类。 */
class DeviceConfigKafkaConsumerTests {

    /** 原Outbox与项目许可准入返回后才允许访问HTTP发布端口。 */
    @Test
    void admitsBeforePublishing() {
        DeviceConfigPush push = push();
        MqttDownlinkAdmissionService admission = mock(MqttDownlinkAdmissionService.class);
        CommandDownlinkPublisher publisher = mock(CommandDownlinkPublisher.class);
        when(admission.config(push)).thenReturn(route(push));

        new DeviceConfigKafkaConsumer(publisher, admission).consume(record(push));

        var order = inOrder(admission, publisher);
        order.verify(admission).config(push);
        order.verify(publisher).publishConfig(push, route(push));
    }

    /** 等价身份缺失映射为现有不可重试错误，且不访问EMQX。 */
    @Test
    void mapsInvalidPersistentIdentityToPermanentDownlinkFailure() {
        DeviceConfigPush push = push();
        MqttDownlinkAdmissionService admission = mock(MqttDownlinkAdmissionService.class);
        CommandDownlinkPublisher publisher = mock(CommandDownlinkPublisher.class);
        when(admission.config(push)).thenThrow(new InvalidDeviceConfigPushException("missing outbox"));

        assertThatThrownBy(() -> new DeviceConfigKafkaConsumer(publisher, admission).consume(record(push)))
                .isInstanceOf(InvalidDownlinkMessageException.class)
                .hasCauseInstanceOf(InvalidDeviceConfigPushException.class);
        verifyNoInteractions(publisher);
    }

    /** 冻结拒绝保留稳定PROJECT_FROZEN分类并阻止HTTP。 */
    @Test
    void mapsFrozenProjectToPermanentDownlinkFailure() {
        DeviceConfigPush push = push();
        MqttDownlinkAdmissionService admission = mock(MqttDownlinkAdmissionService.class);
        CommandDownlinkPublisher publisher = mock(CommandDownlinkPublisher.class);
        when(admission.config(push)).thenThrow(new ProjectFrozenConfigDeliveryException());

        assertThatThrownBy(() -> new DeviceConfigKafkaConsumer(publisher, admission).consume(record(push)))
                .isInstanceOf(InvalidDownlinkMessageException.class)
                .hasMessageContaining("PROJECT_FROZEN")
                .hasCauseInstanceOf(ProjectFrozenConfigDeliveryException.class);
        verifyNoInteractions(publisher);
    }

    /** 数据库故障不转换为永久信封错误，保留Kafka可恢复路径。 */
    @Test
    void propagatesDatabaseFailure() {
        DeviceConfigPush push = push();
        MqttDownlinkAdmissionService admission = mock(MqttDownlinkAdmissionService.class);
        CommandDownlinkPublisher publisher = mock(CommandDownlinkPublisher.class);
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("db unavailable");
        when(admission.config(push)).thenThrow(failure);

        assertThatThrownBy(() -> new DeviceConfigKafkaConsumer(publisher, admission).consume(record(push)))
                .isSameAs(failure);
        verifyNoInteractions(publisher);
    }

    /** 外层事务会把项目SHARE锁跨越HTTP，必须在准入前拒绝。 */
    @Test
    void rejectsAmbientTransactionBeforeAdmission() {
        DeviceConfigPush push = push();
        MqttDownlinkAdmissionService admission = mock(MqttDownlinkAdmissionService.class);
        CommandDownlinkPublisher publisher = mock(CommandDownlinkPublisher.class);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> new DeviceConfigKafkaConsumer(publisher, admission).consume(record(push)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("调用方事务");
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verifyNoInteractions(admission, publisher);
    }

    /** 错误网关分区键在数据库和网络前作为永久协议错误拒绝。 */
    @Test
    void rejectsWrongGatewayKeyBeforeAdmission() {
        DeviceConfigPush push = push();
        MqttDownlinkAdmissionService admission = mock(MqttDownlinkAdmissionService.class);
        CommandDownlinkPublisher publisher = mock(CommandDownlinkPublisher.class);
        ConsumerRecord<String, DeviceConfigPush> record = new ConsumerRecord<>(
                DeviceConfigKafkaConsumer.CONFIG_TOPIC, 0, 0, UUID.randomUUID().toString(), push);

        assertThatThrownBy(() -> new DeviceConfigKafkaConsumer(publisher, admission).consume(record))
                .isInstanceOf(InvalidDownlinkMessageException.class)
                .hasMessageContaining("网关 ID");
        verifyNoInteractions(admission, publisher);
    }

    /** 创建Kafka测试记录。 */
    private static ConsumerRecord<String, DeviceConfigPush> record(DeviceConfigPush push) {
        return new ConsumerRecord<>(DeviceConfigKafkaConsumer.CONFIG_TOPIC, 0, 0,
                push.gatewayId().toString(), push);
    }

    /** 创建不依赖协议编码细节的最小有效配置。 */
    private static DeviceConfigPush push() {
        return new DeviceConfigPush(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "project_1", "gateway_1", DeviceConfigPush.CONFIG_TYPE, 1, List.of());
    }
}
