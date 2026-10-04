package com.things.link.ingestion.infrastructure.protocol.tcp;

import com.things.link.device.application.DeviceAccessSessionPort;
import com.things.link.ingestion.application.access.DeviceAccessPushPort;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.TransportProtocol;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

/** 本地句柄不能替代权威代次；错误归属及写失败后的接管都只能延期。 */
class TcpSessionOwnershipTests {
    /** 权威配置/会话读取。 */ private final DeviceAccessSessionPort sessions = mock(DeviceAccessSessionPort.class);
    /** 同进程共享UUID运行身份。 */ private final DeviceAccessTcpRuntime runtime = new DeviceAccessTcpRuntime();
    /** 实际帧编解码注册表。 */ private final DeviceAccessTcpSessionRegistry registry = new DeviceAccessTcpSessionRegistry(new ObjectMapper(), sessions, runtime);
    /** 认证归属。 */ private final java.util.UUID tenant = Uuid7.generate(), project = Uuid7.generate(), device = Uuid7.generate(), row = Uuid7.generate();
    /** 合法当前权威会话。 */ private final DeviceAccessSessionPort.Active active = new DeviceAccessSessionPort.Active(row, TransportProtocol.TCP, "session", runtime.id(), 2, 3, Instant.now(), Instant.now());

    /** 正常写出一帧，配置代次变化后禁止再写。 */
    @Test void configChangeRejectsOldHandle() {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); install(output);
        assertThat(registry.push(push())).isEqualTo(DeviceAccessPushPort.PushOutcome.DELIVERED);
        int bytes = output.size();
        when(sessions.config(any(), any(), any())).thenReturn(Optional.of(new DeviceAccessSessionPort.Config(TransportProtocol.TCP, 4, 10, true)));
        assertThat(registry.push(push())).isEqualTo(DeviceAccessPushPort.PushOutcome.DEFERRED_NOT_OWNER);
        assertThat(output.size()).isEqualTo(bytes);
    }
    /** 行ID相同也不能忽略generation；伪造租户/项目不读取权威表。 */
    @Test void generationAndIdentityAreRequired() {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); install(output);
        when(sessions.active(any(), any(), any())).thenReturn(Optional.of(new DeviceAccessSessionPort.Active(row, TransportProtocol.TCP, "s", runtime.id(), 9, 3, Instant.now(), Instant.now())));
        assertThat(registry.owns(push())).isFalse();
        assertThat(registry.owns(new DeviceAccessPushPort.Push(Uuid7.generate(), project, device, Uuid7.generate(), "reboot", "{}", 1, Instant.now().plusSeconds(30)))).isFalse();
        assertThat(output.size()).isZero();
    }
    /** 写失败同时接管，旧句柄不能记录真实当前attempt失败。 */
    @Test void writeFailureAfterTakeoverDefers() {
        install(new OutputStream() {
            @Override public void write(int value) throws IOException {
                when(sessions.active(any(), any(), any())).thenReturn(Optional.empty());
                throw new IOException("test takeover");
            }
        });
        assertThat(registry.push(push())).isEqualTo(DeviceAccessPushPort.PushOutcome.DEFERRED_NOT_OWNER);
    }
    /** 未变更权威会话时的实际IOException才可记失败。 */
    @Test void currentWriteFailureIsRealFailure() {
        install(new OutputStream() { @Override public void write(int value) throws IOException { throw new IOException("test failed write"); } });
        assertThat(registry.push(push())).isEqualTo(DeviceAccessPushPort.PushOutcome.WRITE_FAILED);
    }
    /** 设备面配置及会话读取异常必须由Kafka恢复，不能摘成正常离线。 */
    @Test void databaseFailurePropagates() {
        install(new ByteArrayOutputStream());
        when(sessions.active(any(), any(), any())).thenThrow(new IllegalStateException("SQL failed"));
        assertThatThrownBy(() -> registry.push(push())).hasMessage("SQL failed");
    }
    /** 安装真实写句柄与匹配快照。 */
    private void install(OutputStream output) {
        registry.register(new DeviceAccessTcpConnection(tenant, project, device, row, 2, 3, output));
        when(sessions.config(any(), any(), any())).thenReturn(Optional.of(new DeviceAccessSessionPort.Config(TransportProtocol.TCP, 3, 10, true)));
        when(sessions.active(any(), any(), any())).thenReturn(Optional.of(active));
    }
    /** 待发消息。 */
    private DeviceAccessPushPort.Push push() { return new DeviceAccessPushPort.Push(tenant, project, device, Uuid7.generate(), "reboot", "{}", 1, Instant.now().plusSeconds(30)); }
}
