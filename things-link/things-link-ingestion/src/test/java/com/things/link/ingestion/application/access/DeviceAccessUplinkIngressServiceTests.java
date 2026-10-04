package com.things.link.ingestion.application.access;

import com.things.link.device.application.DeviceAccessAcceptancePort;
import com.things.link.ingestion.application.DevicePropertyReportReader;
import com.things.link.ingestion.application.InvalidUplinkMessageException;
import com.things.link.ingestion.application.UplinkTimestampPolicy;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import com.things.link.shared.message.StandardUplinkMessage;
import com.things.link.shared.message.TransportProtocol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证受理用例的「先解析、再幂等判定、后交接」顺序与三条失败路径。 */
class DeviceAccessUplinkIngressServiceTests {

    /** 总线接管端口替身。 */
    private DeviceAccessUplinkPublisher publisher;

    /** 幂等受理事实端口替身。 */
    private DeviceAccessAcceptancePort acceptance;

    /** 被测受理用例，使用真实标准化器以便同时覆盖解析失败路径。 */
    private DeviceAccessUplinkIngressService service;

    /** 每个用例使用全新替身，避免调用计数串场。 */
    @BeforeEach
    void setUp() {
        publisher = mock(DeviceAccessUplinkPublisher.class);
        acceptance = mock(DeviceAccessAcceptancePort.class);
        service = new DeviceAccessUplinkIngressService(
                new DeviceAccessUplinkNormalizer(new DevicePropertyReportReader(new ObjectMapper()),
                        new UplinkTimestampPolicy(Duration.ofMinutes(5))),
                publisher, acceptance);
    }

    /** 首次受理：未受理过才交接，事实携带数据库写入时刻并在总线确认之后写入。 */
    @Test
    void acceptsFreshAttemptAfterBusTakeoverAndRecordsFact() {
        UUID deviceId = Uuid7.generate();
        UUID messageId = Uuid7.generate();
        byte[] payload = report(messageId, "{\"t\":21}");
        AcceptanceFixture fixture = new AcceptanceFixture(deviceId, messageId,
                DeviceAccessPayloadDigest.sha256Hex(payload));
        when(acceptance.decide(any())).thenReturn(new DeviceAccessAcceptancePort.Decision.Fresh());
        when(acceptance.record(any())).thenReturn(fixture.recording(true));

        DeviceAccessUplinkIngressService.DeviceAccessUplinkAcceptance result =
                service.accept(uplink(deviceId, payload));

        assertThat(result.status())
                .isEqualTo(DeviceAccessUplinkIngressService.DeviceAccessUplinkAcceptance.Status.ACCEPTED);
        assertThat(result.messageId()).isEqualTo(messageId);
        assertThat(result.receivedAt()).isEqualTo(RECEIVED_AT);
        assertThat(result.acceptedAt()).isEqualTo(fixture.acceptedAt);

        var captor = org.mockito.ArgumentCaptor.forClass(DeviceAccessAcceptancePort.Attempt.class);
        verify(acceptance).decide(captor.capture());
        verify(acceptance).record(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(attempt -> {
            assertThat(attempt.messageId()).isEqualTo(messageId);
            assertThat(attempt.deviceId()).isEqualTo(deviceId);
            assertThat(attempt.receivedAt()).isEqualTo(RECEIVED_AT);
            assertThat(attempt.payloadDigest()).hasSize(64);
        });
        // 交接必须发生在写入受理事实之前：先写事实再交接会留下「已受理但从未上车」的假事实。
        var order = inOrder(publisher, acceptance);
        order.verify(acceptance).decide(any());
        order.verify(publisher).publish(any());
        order.verify(acceptance).record(any());
    }

    /** 同键同摘要的重放只返回首次结果，绝不再次上车。 */
    @Test
    void duplicateReplayNeverReentersBus() {
        UUID deviceId = Uuid7.generate();
        UUID messageId = Uuid7.generate();
        byte[] payload = report(messageId, "{\"t\":21}");
        AcceptanceFixture fixture = new AcceptanceFixture(deviceId, messageId,
                DeviceAccessPayloadDigest.sha256Hex(payload));
        when(acceptance.decide(any())).thenReturn(new DeviceAccessAcceptancePort.Decision.Duplicate(
                fixture.acceptance()));

        DeviceAccessUplinkIngressService.DeviceAccessUplinkAcceptance result =
                service.accept(uplink(deviceId, payload));

        assertThat(result.status())
                .isEqualTo(DeviceAccessUplinkIngressService.DeviceAccessUplinkAcceptance.Status.DUPLICATE);
        assertThat(result.receivedAt()).isEqualTo(RECEIVED_AT);
        assertThat(result.acceptedAt()).isEqualTo(fixture.acceptedAt);
        verify(publisher, never()).publish(any());
        verify(acceptance, never()).record(any());
    }

    /** 同键异载荷必须拒绝且不交接，首次事实保持不变。 */
    @Test
    void conflictIsRejectedWithoutHandoff() {
        UUID deviceId = Uuid7.generate();
        UUID messageId = Uuid7.generate();
        AcceptanceFixture fixture = new AcceptanceFixture(deviceId, messageId, "f".repeat(64));
        when(acceptance.decide(any())).thenReturn(new DeviceAccessAcceptancePort.Decision.Conflict(
                fixture.acceptance()));

        assertThatThrownBy(() -> service.accept(uplink(deviceId, report(messageId, "{\"t\":22}"))))
                .isInstanceOf(DeviceAccessIdempotencyConflictException.class)
                .hasMessageContaining(messageId.toString());

        verify(publisher, never()).publish(any());
        verify(acceptance, never()).record(any());
    }

    /** 并发同键异载荷：本次已上车但事实以先写入者为准，仍必须向设备报告冲突。 */
    @Test
    void conflictDetectedAtRecordingTimeStillFailsTheAttempt() {
        UUID deviceId = Uuid7.generate();
        UUID messageId = Uuid7.generate();
        AcceptanceFixture fixture = new AcceptanceFixture(deviceId, messageId, "f".repeat(64));
        when(acceptance.decide(any())).thenReturn(new DeviceAccessAcceptancePort.Decision.Fresh());
        when(acceptance.record(any())).thenReturn(new DeviceAccessAcceptancePort.Recording(false,
                fixture.acceptanceWithDigest("f".repeat(64))));

        assertThatThrownBy(() -> service.accept(uplink(deviceId, report(messageId, "{\"t\":21}"))))
                .isInstanceOf(DeviceAccessIdempotencyConflictException.class);
    }

    /** 非法载荷必须在幂等判定与总线之前失败。 */
    @Test
    void rejectsPermanentPayloadBeforeAnyAcceptanceStep() {
        assertThatThrownBy(() -> service.accept(uplink(Uuid7.generate(),
                "not-json".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(InvalidUplinkMessageException.class)
                .hasMessageContaining("DevicePropertyReport");

        verifyNoInteractions(publisher, acceptance);
    }

    /** 总线未接管时异常必须上抛，且不得写入受理事实。 */
    @Test
    void surfacesHandoffUnavailableWithoutRecordingFact() {
        UUID deviceId = Uuid7.generate();
        UUID messageId = Uuid7.generate();
        when(acceptance.decide(any())).thenReturn(new DeviceAccessAcceptancePort.Decision.Fresh());
        doThrow(new DeviceAccessHandoffUnavailableException("总线未确认接管设备接入消息"))
                .when(publisher).publish(any());

        assertThatThrownBy(() -> service.accept(uplink(deviceId, report(messageId, "{\"t\":21}"))))
                .isInstanceOf(DeviceAccessHandoffUnavailableException.class)
                .hasMessageContaining("总线未确认接管");

        verify(acceptance, never()).record(any());
    }

    /** 时钟超限同样在幂等判定之前拒绝，避免永久非法消息占用事实表。 */
    @Test
    void rejectsFutureSkewBeforeAcceptanceDecision() {
        UUID tenantId = Uuid7.generate();
        UUID projectId = Uuid7.generate();
        UUID deviceId = Uuid7.generate();
        DeviceAccessUplinkMessage uplink = new DeviceAccessUplinkMessage(
                tenantId, projectId, deviceId, TransportProtocol.COAP,
                reportAt(Uuid7.generate(), RECEIVED_AT.plusSeconds(301)),
                RECEIVED_AT, TRACE, new AuthenticatedDeviceIdentity(tenantId, projectId, deviceId, 2));

        assertThatThrownBy(() -> service.accept(uplink))
                .isInstanceOf(InvalidUplinkMessageException.class)
                .hasMessageContaining("未来偏差");
        verifyNoInteractions(publisher, acceptance);
    }

    /** 受理事实替身，按设备与消息标识构造一次受理。 */
    private static final class AcceptanceFixture {

        /** 数据库写入时刻。 */
        private final Instant acceptedAt = Instant.parse("2026-09-18T08:00:02Z");

        /** 设备标识。 */
        private final UUID deviceId;

        /** 消息标识。 */
        private final UUID messageId;

        /** 首次受理时的载荷摘要。 */
        private final String digest;

        /**
         * @param deviceId 设备标识
         * @param messageId 消息标识
         * @param digest 首次受理时的载荷摘要
         */
        private AcceptanceFixture(UUID deviceId, UUID messageId, String digest) {
            this.deviceId = deviceId;
            this.messageId = messageId;
            this.digest = digest;
        }

        /** @return 首次受理事实 */
        private DeviceAccessAcceptancePort.Acceptance acceptance() {
            return acceptanceWithDigest(digest);
        }

        /**
         * @param digest 载荷摘要
         * @return 指定摘要的受理事实
         */
        private DeviceAccessAcceptancePort.Acceptance acceptanceWithDigest(String digest) {
            return new DeviceAccessAcceptancePort.Acceptance(deviceId, messageId, digest,
                    TransportProtocol.TCP, RECEIVED_AT, acceptedAt);
        }

        /**
         * @param firstRecording 是否首次写入
         * @return 写入结果
         */
        private DeviceAccessAcceptancePort.Recording recording(boolean firstRecording) {
            return new DeviceAccessAcceptancePort.Recording(firstRecording, acceptance());
        }
    }

    /**
     * 构造接入信封。
     *
     * @param deviceId 设备标识
     * @param payload 业务载荷
     * @return 新协议接入信封
     */
    private static DeviceAccessUplinkMessage uplink(UUID deviceId, byte[] payload) {
        UUID tenantId = Uuid7.generate();
        UUID projectId = Uuid7.generate();
        return new DeviceAccessUplinkMessage(tenantId, projectId, deviceId, TransportProtocol.TCP, payload,
                RECEIVED_AT, TRACE, new AuthenticatedDeviceIdentity(tenantId, projectId, deviceId, 2));
    }

    /**
     * 构造属性上报载荷。
     *
     * @param messageId 设备消息标识
     * @param payload 属性对象 JSON
     * @return 业务载荷字节
     */
    private static byte[] report(UUID messageId, String payload) {
        return ("{\"messageId\":\"" + messageId + "\",\"occurredAt\":\"2026-09-18T08:00:00Z\",\"payload\":"
                + payload + "}").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 构造指定设备发生时刻的属性上报载荷。
     *
     * @param messageId 设备消息标识
     * @param occurredAt 设备采集时刻
     * @return 业务载荷字节
     */
    private static byte[] reportAt(UUID messageId, Instant occurredAt) {
        return ("{\"messageId\":\"" + messageId + "\",\"occurredAt\":\"" + occurredAt
                + "\",\"payload\":{\"t\":21}}").getBytes(StandardCharsets.UTF_8);
    }

    /** 平台接收时刻。 */
    private static final Instant RECEIVED_AT = Instant.parse("2026-09-18T08:00:01Z");

    /** 测试链路标识。 */
    private static final String TRACE = "0123456789abcdef0123456789abcdef";
}
