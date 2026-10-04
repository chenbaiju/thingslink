package com.things.link.ingestion.infrastructure.protocol.tcp;

import com.things.link.device.application.DeviceAccessSessionPort;
import com.things.link.ingestion.application.CommandDownlinkPublisher;
import com.things.link.ingestion.application.DownlinkPreprocessingChain;
import com.things.link.ingestion.application.access.DeviceAccessPushPort;
import com.things.link.ingestion.infrastructure.DeviceCommandDownlinkKafkaConsumer;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.DeviceCommandDispatch;
import com.things.link.shared.message.DeviceCommandDispatchFailure;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.telemetry.application.DeviceCommandService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.time.Instant;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

/** 角色分流、非归属无副作用及数据库异常的编排边界；跨进程事实另由真实Kafka验收。 */
class TcpDownlinkRoutingTests {
    /** 各用例独立配置端口。 */ private final DeviceAccessSessionPort sessions = mock(DeviceAccessSessionPort.class);
    /** TCP写入观察。 */ private final DeviceAccessPushPort push = mock(DeviceAccessPushPort.class);
    /** 原状态机观察。 */ private final DeviceCommandService commands = mock(DeviceCommandService.class);
    /** 准入前禁止执行规则。 */ private final DownlinkPreprocessingChain chain = mock(DownlinkPreprocessingChain.class);
    /** MQTT发布观察。 */ private final CommandDownlinkPublisher mqtt = mock(CommandDownlinkPublisher.class);
    /** 完整原消息形状。 */ private final DeviceCommandDispatch message = message();
    /** TCP角色只注入TCP出口。 */ private final TcpCommandDownlinkKafkaConsumer tcp =
            new TcpCommandDownlinkKafkaConsumer(sessions, push, chain, commands, new SimpleMeterRegistry());
    /** 共享角色只注入MQTT出口。 */ private final DeviceCommandDownlinkKafkaConsumer shared =
            new DeviceCommandDownlinkKafkaConsumer(chain, mqtt, commands, sessions, mock(com.things.link.ingestion.application.MqttDownlinkAdmissionService.class));

    /** 非MQTT记录在共享角色中不得准入、预处理或发布。 */
    @ParameterizedTest @EnumSource(value = TransportProtocol.class, names = {"TCP", "HTTP", "COAP"})
    void sharedSkipsOtherProtocols(TransportProtocol protocol) {
        config(protocol); shared.consume(record());
        verifyNoInteractions(chain, commands, mqtt, push);
    }
    /** 没有本地会话是延期，不能成为可重试失败。 */
    @Test void nonOwnerDoesNothing() {
        config(TransportProtocol.TCP); tcp.consume(record());
        verifyNoInteractions(chain, commands, mqtt);
        verify(push, never()).push(any());
    }
    /** 明确结果才推进状态；延期不伪造成功时间。 */
    @ParameterizedTest @EnumSource(DeviceAccessPushPort.PushOutcome.class)
    void outcomeControlsState(DeviceAccessPushPort.PushOutcome result) {
        config(TransportProtocol.TCP);
        when(push.owns(any())).thenReturn(true);
        when(commands.admitDispatch(message)).thenReturn(true);
        when(chain.apply(message)).thenReturn(message);
        when(push.push(any())).thenReturn(result);
        tcp.consume(record());
        if (result == DeviceAccessPushPort.PushOutcome.DELIVERED) verify(commands).markDispatched(eq(message), any());
        else verify(commands, never()).markDispatched(any(), any());
        if (result == DeviceAccessPushPort.PushOutcome.WRITE_FAILED)
            verify(commands).recordDispatchFailure(eq(message), eq(DeviceCommandDispatchFailure.DISPATCH_FAILED), any());
        else verify(commands, never()).recordDispatchFailure(any(), any(), any());
        verifyNoInteractions(mqtt);
    }
    /** 广播不会创建MQTT发布者，也不会触发领取型状态机。 */
    @ParameterizedTest @EnumSource(value = TransportProtocol.class, names = {"MQTT", "HTTP", "COAP"})
    void broadcastSkipsOtherProtocols(TransportProtocol protocol) {
        config(protocol); tcp.consume(record()); verifyNoInteractions(push, chain, commands, mqtt);
    }
    /** 数据库读取故障必须传播，让Kafka恢复路径接管。 */
    @Test void databaseFailureIsNotOffline() {
        when(sessions.config(any(), any(), any())).thenThrow(new IllegalStateException("db unavailable"));
        assertThatThrownBy(() -> tcp.consume(record())).hasMessage("db unavailable");
        verifyNoInteractions(push, chain, commands, mqtt);
    }
    /** 规则执行期间协议变更，写前的新检查拒绝旧出口。 */
    @Test void configSwitchAfterPreprocessingDefers() {
        when(sessions.config(any(), any(), any())).thenReturn(Optional.of(new DeviceAccessSessionPort.Config(TransportProtocol.TCP, 1, 10, true)), Optional.of(new DeviceAccessSessionPort.Config(TransportProtocol.HTTP, 2, 10, true)));
        when(push.owns(any())).thenReturn(true); when(commands.admitDispatch(message)).thenReturn(true);
        when(chain.apply(message)).thenReturn(message); tcp.consume(record());
        verify(push, never()).push(any()); verify(commands, never()).recordDispatchFailure(any(), any(), any());
    }
    /** 生效配置由应用端口给出。 */
    private void config(TransportProtocol protocol) { when(sessions.config(any(), any(), any())).thenReturn(Optional.of(new DeviceAccessSessionPort.Config(protocol, 1, 10, true))); }
    /** 真实形状的Kafka key。 */
    private ConsumerRecord<String, DeviceCommandDispatch> record() { return new ConsumerRecord<>(DeviceCommandDownlinkKafkaConsumer.DOWNLINK_TOPIC, 0, 0, message.connectionDeviceId().toString(), message); }
    /** 原信封逻辑替身。 */
    private static DeviceCommandDispatch message() {
        var device = Uuid7.generate();
        return new DeviceCommandDispatch(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), 1, device, "d", device, "d", "p", "reboot", "{}", Instant.now().plusSeconds(30), "trace");
    }
}
