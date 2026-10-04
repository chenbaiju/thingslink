package com.things.link.ingestion.infrastructure.protocol.tcp;

import com.things.link.ingestion.application.access.DeviceAccessBusinessBudget;
import com.things.link.ingestion.application.access.DeviceAccessHandoffUnavailableException;
import com.things.link.ingestion.application.access.DeviceAccessUplinkIngressService;
import com.things.link.ingestion.application.access.DeviceAccessUplinkIngressService.DeviceAccessUplinkAcceptance;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 接入用例与传输应答的时间边界；故障替身仅用于失败注入，真实交接另由TLS集成测试验证。 */
class DeviceAccessTcpAcceptanceTests {

    /** 测试身份；没有启动监听或访问外部基础设施。 */
    private final AuthenticatedDeviceIdentity identity = new AuthenticatedDeviceIdentity(
            Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), 1);

    /** 下游受理端口替身，控制成功与交接异常的时间点。 */
    private final DeviceAccessUplinkIngressService ingress = mock(DeviceAccessUplinkIngressService.class);

    /** 只调用业务帧处理，其他监听依赖不参与此边界测试。 */
    private final DeviceAccessTcpServer server = new DeviceAccessTcpServer(null, null, new ObjectMapper(),
            0, new DeviceAccessTcpRuntime(), null, null, null, 10, ingress, null, null, null, null, null,
            mock(DeviceAccessBusinessBudget.class), 1, 1, null);

    /** 应答不得在用例完成前提前写入；结果使用应用返回的权威消息ID。 */
    @Test
    void acceptanceIsWrittenOnlyAfterUseCaseReturns() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        UUID messageId = Uuid7.generate();
        when(ingress.accept(any())).thenAnswer(invocation -> {
            assertThat(output.size()).as("交接未完成不得提前确认").isZero();
            return new DeviceAccessUplinkAcceptance(DeviceAccessUplinkAcceptance.Status.ACCEPTED,
                    messageId, Instant.now(), Instant.now());
        });
        handle(output);
        var input = new ByteArrayInputStream(output.toByteArray());
        var frame = new DeviceAccessTcpFrameReader(input).readFrame();
        assertThat(frame.type()).isEqualTo(DeviceAccessTcpFrameType.ACCEPTED);
        assertThat(new ObjectMapper().readTree(frame.payload()).get("messageId").asString())
                .isEqualTo(messageId.toString());
        assertThat(input.available()).isZero();
    }

    /** 总线交接失败必须仅返回ERROR，不能紧随成功确认。 */
    @Test
    void failedHandoffNeverProducesAccepted() throws Exception {
        when(ingress.accept(any())).thenThrow(new DeviceAccessHandoffUnavailableException("injected"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        handle(output);
        var input = new ByteArrayInputStream(output.toByteArray());
        var frame = new DeviceAccessTcpFrameReader(input).readFrame();
        assertThat(frame.type()).isEqualTo(DeviceAccessTcpFrameType.ERROR);
        assertThat(new ObjectMapper().readTree(frame.payload()).get("errorCode").asString())
                .isEqualTo("HANDOFF_UNAVAILABLE");
        assertThat(input.available()).isZero();
    }

    /** 已受理但应答写失败必须向连接循环抛错，不在传输层重复执行受理。 */
    @Test
    void lostResponseTerminatesTransportWithoutReaccepting() {
        when(ingress.accept(any())).thenReturn(new DeviceAccessUplinkAcceptance(
                DeviceAccessUplinkAcceptance.Status.ACCEPTED, Uuid7.generate(), Instant.now(), Instant.now()));
        OutputStream broken = new OutputStream() {
            /** 模拟对端已断开。 */
            @Override
            public void write(int value) throws IOException {
                throw new IOException("injected disconnect");
            }
        };
        assertThatThrownBy(() -> handle(broken)).hasRootCauseInstanceOf(IOException.class);
        verify(ingress).accept(any());
    }

    /** 经生产私有分派入口执行，保留连接串行编码与失败传播。 */
    private void handle(OutputStream output) {
        var connection = new DeviceAccessTcpConnection(identity.tenantId(), identity.projectId(), identity.deviceId(), Uuid7.generate(), 1, 1, output);
        ReflectionTestUtils.invokeMethod(server, "handleUplink", connection, identity, new byte[] {123, 125});
    }
}
