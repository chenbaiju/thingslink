package com.things.link.ingestion.infrastructure.protocol.tcp;

import com.things.link.device.application.DeviceAccessSessionPort;
import com.things.link.ingestion.application.DownlinkPreprocessingChain;
import com.things.link.ingestion.application.access.DeviceAccessPushPort;
import com.things.link.shared.message.DeviceCommandDispatch;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.telemetry.application.DeviceCommandService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.dao.RecoverableDataAccessException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** TCP消费者的操作类型边界；仅证明无编码/网络调用，不冒称真实TCP发送。 */
class TcpPropertySetGuardTests {
    /** 属性设置进入原状态机收束，不能先编码为commandKey为空的TCP命令。 */
    @Test void rejectsPropertyBeforeOwnershipAndPreprocessing() {
        var source = source();
        var sessions = mock(DeviceAccessSessionPort.class);
        var push = mock(DeviceAccessPushPort.class);
        var preprocessing = mock(DownlinkPreprocessingChain.class);
        var commands = mock(DeviceCommandService.class);
        when(sessions.config(source.tenantId(),source.projectId(),source.connectionDeviceId()))
                .thenReturn(Optional.of(new DeviceAccessSessionPort.Config(TransportProtocol.TCP,1,null,true)));
        var consumer = new TcpCommandDownlinkKafkaConsumer(sessions,push,preprocessing,commands,new SimpleMeterRegistry());
        consumer.consume(new ConsumerRecord<>("tc.device.downlink",0,0,source.connectionDeviceId().toString(),source));
        verify(commands).admitDispatch(source);
        verifyNoInteractions(push,preprocessing);
    }

    /** 终态数据库故障必须传播给原Kafka恢复路径，不能确认丢弃。 */
    @Test void propagatesPropertyAdmissionDatabaseFailure() {
        var source = source();
        var sessions = mock(DeviceAccessSessionPort.class);
        var push = mock(DeviceAccessPushPort.class);
        var preprocessing = mock(DownlinkPreprocessingChain.class);
        var commands = mock(DeviceCommandService.class);
        when(sessions.config(source.tenantId(),source.projectId(),source.connectionDeviceId()))
                .thenReturn(Optional.of(new DeviceAccessSessionPort.Config(TransportProtocol.TCP,1,null,true)));
        when(commands.admitDispatch(source)).thenThrow(new RecoverableDataAccessException("test database unavailable"));
        var consumer = new TcpCommandDownlinkKafkaConsumer(sessions,push,preprocessing,commands,new SimpleMeterRegistry());
        assertThatThrownBy(()->consumer.consume(new ConsumerRecord<>("tc.device.downlink",0,0,source.connectionDeviceId().toString(),source)))
                .isInstanceOf(RecoverableDataAccessException.class);
        verifyNoInteractions(push,preprocessing);
    }

    /** 合法属性信封，无传输层自报操作类型。 */
    private static DeviceCommandDispatch source() {
        UUID device=UUID.randomUUID();
        return new DeviceCommandDispatch(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                UUID.randomUUID(),1,device,"device",device,"device","project",DeviceCommandDispatch.OperationType.PROPERTY_SET,
                null,"{}",Instant.now().plusSeconds(30),"test");
    }
}
