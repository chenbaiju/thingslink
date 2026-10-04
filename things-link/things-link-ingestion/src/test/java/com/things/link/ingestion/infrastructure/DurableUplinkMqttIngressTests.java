package com.things.link.ingestion.infrastructure;

import com.things.link.ingestion.application.BrokerHandoffDispatcher;
import com.things.link.ingestion.application.HandoffDisposition;
import com.things.link.support.mqtt.BrokerIngressProperties;
import com.things.link.support.mqtt.BrokerIngressReadiness;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Paho manual-ACK 边界测试，证明 ACK 严格晚于分派成功且异常路径不确认。 */
class DurableUplinkMqttIngressTests {

    /** 下游持久事实成功后才调用 messageArrivedComplete。 */
    @Test
    void acknowledgesOnlyAfterDurableDispatch() throws Exception {
        BrokerHandoffDispatcher dispatcher = mock(BrokerHandoffDispatcher.class);
        BrokerHandoffMetrics metrics = mock(BrokerHandoffMetrics.class);
        BrokerHandoffQualificationRecorder recorder = mock(BrokerHandoffQualificationRecorder.class);
        MqttAsyncClient client = mock(MqttAsyncClient.class);
        when(client.isConnected()).thenReturn(true);
        when(dispatcher.dispatch(any())).thenReturn(HandoffDisposition.ACCEPTED);
        DurableUplinkMqttIngress ingress = new DurableUplinkMqttIngress(
                properties(), dispatcher, metrics, new BrokerIngressReadiness(), recorder, client);
        MqttMessage message = message();

        ingress.messageArrived(BrokerIngressProperties.INTERNAL_TOPIC, message);

        verify(dispatcher).dispatch(message.getPayload());
        verify(client).messageArrivedComplete(message.getId(), 1);
        verify(metrics).record(HandoffDisposition.ACCEPTED);
        verify(recorder).beforeManualAck(HandoffDisposition.ACCEPTED);
    }

    /** Kafka/数据库暂时失败在同一 callback 原地重试，成功前不得调用 manual ACK。 */
    @Test
    void retriesTransientFailureBeforeManualAck() throws Exception {
        BrokerHandoffDispatcher dispatcher = mock(BrokerHandoffDispatcher.class);
        BrokerHandoffMetrics metrics = mock(BrokerHandoffMetrics.class);
        BrokerHandoffQualificationRecorder recorder = mock(BrokerHandoffQualificationRecorder.class);
        MqttAsyncClient client = mock(MqttAsyncClient.class);
        when(client.isConnected()).thenReturn(true);
        when(dispatcher.dispatch(any())).thenThrow(new IllegalStateException("kafka down"))
                .thenReturn(HandoffDisposition.DUPLICATE);
        DurableUplinkMqttIngress ingress = new DurableUplinkMqttIngress(
                properties(), dispatcher, metrics, new BrokerIngressReadiness(), recorder, client);
        MqttMessage message = message();

        ingress.messageArrived(BrokerIngressProperties.INTERNAL_TOPIC, message);

        verify(dispatcher, times(2)).dispatch(message.getPayload());
        verify(metrics).transientRetry();
        verify(recorder).beforeManualAck(HandoffDisposition.DUPLICATE);
        verify(client).messageArrivedComplete(message.getId(), 1);
        verify(metrics).record(HandoffDisposition.DUPLICATE);
    }

    /** 应用关闭必须立即唤醒退避、保留未 ACK，并且不能等满 30 秒才退出。 */
    @Test
    void shutdownWakesRetryAndLeavesMessageUnacknowledged() throws Exception {
        BrokerHandoffDispatcher dispatcher = mock(BrokerHandoffDispatcher.class);
        BrokerHandoffMetrics metrics = mock(BrokerHandoffMetrics.class);
        BrokerHandoffQualificationRecorder recorder = mock(BrokerHandoffQualificationRecorder.class);
        MqttAsyncClient client = mock(MqttAsyncClient.class);
        CountDownLatch attempted = new CountDownLatch(1);
        when(dispatcher.dispatch(any())).thenAnswer(invocation -> {
            attempted.countDown();
            throw new IllegalStateException("database down");
        });
        DurableUplinkMqttIngress ingress = new DurableUplinkMqttIngress(
                properties(), dispatcher, metrics, new BrokerIngressReadiness(), recorder, client);
        MqttMessage message = message();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> future = executor.submit(() -> {
                try {
                    ingress.messageArrived(BrokerIngressProperties.INTERNAL_TOPIC, message);
                } catch (Exception exception) {
                    throw new IllegalStateException("callback failed", exception);
                }
            });
            attempted.await(2, TimeUnit.SECONDS);
            ingress.close();

            assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasRootCauseInstanceOf(IllegalStateException.class);
        }

        verify(dispatcher, atLeastOnce()).dispatch(message.getPayload());
        verify(client, never()).messageArrivedComplete(message.getId(), 1);
        verify(recorder, never()).beforeManualAck(any());
        verify(metrics, atLeastOnce()).transientRetry();
    }

    /** 构造与内部 Topic 契约一致的 QoS 1 非 retained 消息。 */
    private static MqttMessage message() {
        MqttMessage message = new MqttMessage("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        message.setQos(1);
        message.setRetained(false);
        message.setId(17);
        return message;
    }

    /** 测试不建立网络连接，密码只存在于测试对象。 */
    private static BrokerIngressProperties properties() {
        return new BrokerIngressProperties(false, "tcp://localhost:1883", "thingslink-uplink-ingress",
                "x".repeat(32));
    }
}
