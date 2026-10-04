package com.things.link.ingestion.application;

import com.things.link.ingestion.infrastructure.BrokerHandoffQualificationRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** durable handoff 分派测试，锁定 raw/command 路由与 poison 先 DLQ 后 ACK 前提。 */
class BrokerHandoffDispatcherTests {

    /** 普通上行服务替身。 */
    private RawUplinkIngestionService rawService;
    /** 命令回复服务替身。 */
    private CommandReplyIngestionService commandService;
    /** Kafka 模板替身。 */
    private KafkaTemplate<String, Object> kafkaTemplate;
    /** 资格证据记录器替身。 */
    private BrokerHandoffQualificationRecorder qualificationRecorder;
    /** 被测分派器。 */
    private BrokerHandoffDispatcher dispatcher;

    /** 每个测试隔离调用计数。 */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        rawService  =  mock(RawUplinkIngestionService.class);
        commandService  =  mock(CommandReplyIngestionService.class);
        kafkaTemplate  =  mock(KafkaTemplate.class);
        qualificationRecorder  =  mock(BrokerHandoffQualificationRecorder.class);
        dispatcher  =  new BrokerHandoffDispatcher(new BrokerHandoffEnvelopeParser(new ObjectMapper()), rawService,
                commandService, kafkaTemplate, qualificationRecorder);
    }

    /** 普通上行只进入 raw 接管服务。 */
    @Test
    void dispatchesOrdinaryUplinkToRawKafkaHandoff() {
        when(rawService.ingestHandoff(any(), any())).thenReturn(HandoffDisposition.ACCEPTED);

        assertThat(dispatcher.dispatch(BrokerHandoffEnvelopeParserTests.validJson()))
                .isEqualTo(HandoffDisposition.ACCEPTED);

        verify(rawService).ingestHandoff(any(), any());
        verify(commandService, never()).ingestHandoff(any());
        verify(qualificationRecorder).record(any(), anyString(), any());
    }

    /** 专用身份只传给raw入口，HTTP回调DTO仍无可注入身份字段。 */
    @Test
    void forwardsVersionTwoIdentityWithoutReconstruction() {
        when(rawService.ingestHandoff(any(), any())).thenReturn(HandoffDisposition.ACCEPTED);
        String json = new String(BrokerHandoffEnvelopeParserTests.validJson(), StandardCharsets.UTF_8)
                .replace("\"schemaVersion\":1","\"schemaVersion\":2")
                .replace("\"brokerNode\"", "\"authenticatedIdentity\":{"
                        + "\"tenantId\":\"00000000-0000-0000-0000-000000000001\","
                        + "\"projectId\":\"00000000-0000-0000-0000-000000000002\","
                        + "\"deviceId\":\"00000000-0000-0000-0000-000000000003\","
                        + "\"credentialVersion\":\"7\"},\"brokerNode\"");
        assertThat(dispatcher.dispatch(json.getBytes(StandardCharsets.UTF_8))).isEqualTo(HandoffDisposition.ACCEPTED);
        var captor = org.mockito.ArgumentCaptor.forClass(com.things.link.shared.message.AuthenticatedDeviceIdentity.class);
        verify(rawService).ingestHandoff(any(), captor.capture());
        assertThat(captor.getValue().credentialVersion()).isEqualTo(7);
        verify(commandService, never()).ingestHandoff(any());
    }

    /** 命令回复必须绕过 raw 标准化管道，直接进入事务状态机。 */
    @Test
    void dispatchesCommandReplyToStateMachine() {
        when(commandService.ingestHandoff(any())).thenReturn(HandoffDisposition.DUPLICATE);
        String json  =  new String(BrokerHandoffEnvelopeParserTests.validJson(), StandardCharsets.UTF_8)
                .replace("property/report","command/019c0000-0000-7000-8000-000000000001/reply");

        assertThat(dispatcher.dispatch(json.getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(HandoffDisposition.DUPLICATE);

        verify(commandService).ingestHandoff(any());
        verify(rawService, never()).ingestHandoff(any(), any());
        verify(qualificationRecorder).record(any(), anyString(), any());
    }

    /** poison 只有在统一 DLQ 获得 Kafka ACK 后才返回 QUARANTINED。 */
    @Test
    void quarantinesPoisonAfterKafkaAck() {
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        assertThat(dispatcher.dispatch("not-json".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(HandoffDisposition.QUARANTINED);

        verify(kafkaTemplate).send(anyString(), anyString(), any(BrokerHandoffDeadLetter.class));
        verify(qualificationRecorder).recordPoison(any(), any());
    }

    /** DLQ 不可用时必须抛出以保留 MQTT 未确认消息，不能把 poison 静默吞掉。 */
    @Test
    void retainsPoisonWhenDlqIsUnavailable() {
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("kafka down")));

        assertThatThrownBy(() -> dispatcher.dispatch("not-json".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("死信发送失败");

        verify(qualificationRecorder, never()).recordPoison(any(), any());
    }

    /** 暂时故障只写资格标签并继续抛出，不能折叠成可 ACK 的正常返回值。 */
    @Test
    void recordsTransientRetryAndRethrowsOriginalFailure() {
        IllegalStateException failure  =  new IllegalStateException("database down");
        when(rawService.ingestHandoff(any(), any())).thenThrow(failure);

        assertThatThrownBy(() -> dispatcher.dispatch(BrokerHandoffEnvelopeParserTests.validJson()))
                .isSameAs(failure);

        verify(qualificationRecorder).record(any(), anyString(),
                org.mockito.ArgumentMatchers.eq(HandoffDisposition.TRANSIENT_RETRY));
    }
}
