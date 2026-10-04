package com.things.link.ingestion.infrastructure;

import com.things.link.device.application.DeviceMqttDownlinkRoute;
import com.things.link.ingestion.application.MqttDownlinkAdmissionService;
import static com.things.link.ingestion.infrastructure.MqttRouteFixtures.route;

import com.things.link.ingestion.application.CommandDispatchException;
import com.things.link.ingestion.application.CommandDownlinkPublisher;
import com.things.link.ingestion.application.DownlinkPreprocessingChain;
import com.things.link.ingestion.application.InvalidDownlinkMessageException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.DeviceCommandDispatch;
import com.things.link.shared.message.DeviceCommandDispatchFailure;
import com.things.link.telemetry.application.DeviceCommandService;
import com.things.link.telemetry.application.InvalidCommandDispatchException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Kafka 到 EMQX 下行消费者测试，固定连接设备分区键与状态推进顺序。 */
class DeviceCommandDownlinkKafkaConsumerTests {
    /** 每例独立MQTT事务准入替身。 */
    private final MqttDownlinkAdmissionService admission = mock(MqttDownlinkAdmissionService.class);

    /** 只有 EMQX 成功返回后才能调用 telemetry 推进 DISPATCHED。 */
    @Test
    void publishesThenMarksDispatched() {
        DeviceCommandDispatch dispatch = dispatch();
        Instant publishedAt = Instant.parse("2026-08-08T03:00:01Z");
        CommandDownlinkPublisher publisher = mock(CommandDownlinkPublisher.class);
        DeviceCommandService commandService = mock(DeviceCommandService.class);
        when(admission.command(dispatch)).thenReturn(java.util.Optional.of(route(dispatch)));
        when(publisher.publish(dispatch, route(dispatch))).thenReturn(publishedAt);
        DeviceCommandDownlinkKafkaConsumer consumer = new DeviceCommandDownlinkKafkaConsumer(
                new DownlinkPreprocessingChain(List.of()), publisher, commandService, mock(com.things.link.device.application.DeviceAccessSessionPort.class), admission);

        consumer.consume(new ConsumerRecord<>(DeviceCommandDownlinkKafkaConsumer.DOWNLINK_TOPIC, 0, 0,
                dispatch.connectionDeviceId().toString(), dispatch));

        var order = inOrder(admission, commandService, publisher);
        order.verify(admission).command(dispatch);
        order.verify(publisher).publish(dispatch, route(dispatch));
        order.verify(commandService).markDispatched(dispatch, publishedAt);
    }

    /** D-037：发布失败只报告 telemetry 状态机，不再把可重试异常抛回 Kafka 与业务重试叠加。 */
    @Test
    void recordsDispatchFailureWithoutRethrowing() {
        DeviceCommandDispatch dispatch = dispatch();
        CommandDownlinkPublisher publisher = mock(CommandDownlinkPublisher.class);
        DeviceCommandService commandService = mock(DeviceCommandService.class);
        when(admission.command(dispatch)).thenReturn(java.util.Optional.of(route(dispatch)));
        CommandDispatchException failure = new CommandDispatchException(
                DeviceCommandDispatchFailure.DISPATCH_CONNECTION_FAILED, "EMQX 发布 API 网络失败");
        when(publisher.publish(dispatch, route(dispatch))).thenThrow(failure);
        DeviceCommandDownlinkKafkaConsumer consumer = new DeviceCommandDownlinkKafkaConsumer(
                new DownlinkPreprocessingChain(List.of()), publisher, commandService, mock(com.things.link.device.application.DeviceAccessSessionPort.class), admission);

        consumer.consume(new ConsumerRecord<>(DeviceCommandDownlinkKafkaConsumer.DOWNLINK_TOPIC, 0, 0,
                dispatch.connectionDeviceId().toString(), dispatch));

        verify(commandService).recordDispatchFailure(eq(dispatch), eq(failure.failure()), any(Instant.class));
        verify(commandService, never()).markDispatched(any(), any());
    }

    /** 错误 key 会打断网关连接内顺序，必须在调用发布端口前作为永久协议错误拒绝。 */
    @Test
    void rejectsWrongConnectionPartitionKey() {
        DeviceCommandDispatch dispatch = dispatch();
        CommandDownlinkPublisher publisher = mock(CommandDownlinkPublisher.class);
        DeviceCommandService commandService = mock(DeviceCommandService.class);
        DeviceCommandDownlinkKafkaConsumer consumer = new DeviceCommandDownlinkKafkaConsumer(
                new DownlinkPreprocessingChain(List.of()), publisher, commandService, mock(com.things.link.device.application.DeviceAccessSessionPort.class), admission);

        ConsumerRecord<String, DeviceCommandDispatch> record = new ConsumerRecord<>(
                DeviceCommandDownlinkKafkaConsumer.DOWNLINK_TOPIC, 0, 0, UUID.randomUUID().toString(), dispatch);
        assertThatThrownBy(() -> consumer.consume(record))
                .isInstanceOf(InvalidDownlinkMessageException.class)
                .hasMessageContaining("实际连接设备");
        verifyNoInteractions(publisher, commandService);
    }

    /** ADR0070：冻结或幂等吸收不进入预处理、网络和发送结果写入。 */
    @Test
    void deniedAdmissionDoesNotProcessOrPublish() {
        DeviceCommandDispatch dispatch = dispatch();
        CommandDownlinkPublisher publisher = mock(CommandDownlinkPublisher.class);
        DeviceCommandService service = mock(DeviceCommandService.class);
        DownlinkPreprocessingChain chain = mock(DownlinkPreprocessingChain.class);
        new DeviceCommandDownlinkKafkaConsumer(chain, publisher, service, mock(com.things.link.device.application.DeviceAccessSessionPort.class), admission).consume(record(dispatch));
        verify(admission).command(dispatch);
        verifyNoInteractions(chain, publisher);
        verify(service, never()).markDispatched(any(), any());
        verify(service, never()).recordDispatchFailure(any(), any(), any());
    }

    /** 只有确定性信封损坏映射到现有永久错误；不能把数据库故障当作DLQ业务拒绝。 */
    @Test
    void mapsOnlyPersistentIdentityConflictsToPermanentRejection() {
        DeviceCommandDispatch dispatch = dispatch();
        DeviceCommandService service = mock(DeviceCommandService.class);
        CommandDownlinkPublisher publisher = mock(CommandDownlinkPublisher.class);
        DownlinkPreprocessingChain chain = mock(DownlinkPreprocessingChain.class);
        var consumer = new DeviceCommandDownlinkKafkaConsumer(chain, publisher, service, mock(com.things.link.device.application.DeviceAccessSessionPort.class), admission);
        when(admission.command(dispatch)).thenThrow(new InvalidCommandDispatchException("身份不一致"));
        assertThatThrownBy(() -> consumer.consume(record(dispatch)))
                .isInstanceOf(InvalidDownlinkMessageException.class)
                .hasCauseInstanceOf(InvalidCommandDispatchException.class);
        var databaseFailure = new org.springframework.dao.DataAccessResourceFailureException("连接失败");
        reset(admission);
        when(admission.command(dispatch)).thenThrow(databaseFailure);
        assertThatThrownBy(() -> consumer.consume(record(dispatch))).isSameAs(databaseFailure);
        verifyNoInteractions(chain, publisher);
    }

    /** 调用方事务尚未提交时不能发送；本例只验证入口防御，真实提交失败由PG验收覆盖。 */
    @Test
    void refusesAmbientTransactionBeforeAdmission() {
        DeviceCommandDispatch dispatch = dispatch();
        DeviceCommandService service = mock(DeviceCommandService.class);
        CommandDownlinkPublisher publisher = mock(CommandDownlinkPublisher.class);
        DownlinkPreprocessingChain chain = mock(DownlinkPreprocessingChain.class);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> new DeviceCommandDownlinkKafkaConsumer(chain, publisher, service, mock(com.things.link.device.application.DeviceAccessSessionPort.class), admission)
                    .consume(record(dispatch))).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("调用方事务");
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verifyNoInteractions(service, chain, publisher);
    }

    /** 创建符合原分区合同的输入，不绕过真实consumer入口。 */
    private static ConsumerRecord<String, DeviceCommandDispatch> record(DeviceCommandDispatch dispatch) {
        return new ConsumerRecord<>(DeviceCommandDownlinkKafkaConsumer.DOWNLINK_TOPIC, 0, 0,
                dispatch.connectionDeviceId().toString(), dispatch);
    }

    /** 创建直连设备第一次派发信封。 */
    private static DeviceCommandDispatch dispatch() {
        UUID deviceId = Uuid7.generate();
        return new DeviceCommandDispatch(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                Uuid7.generate(), 1, deviceId, "device_1", deviceId, "device_1", "project_1", "reboot", "{}",
                Instant.parse("2026-08-08T03:00:10Z"), "0123456789abcdef0123456789abcdef");
    }
}
