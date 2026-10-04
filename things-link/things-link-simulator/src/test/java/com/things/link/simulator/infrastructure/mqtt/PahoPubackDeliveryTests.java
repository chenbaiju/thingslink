package com.things.link.simulator.infrastructure.mqtt;

import com.things.link.simulator.application.MqttDeviceClient;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 以真实 Mosquitto 钉住 Paho 适配器的 PUBACK 收据语义。
 *
 * <p>内存 {@code RecordingClient} 只能证明「future 完成时计数如何变化」，无法证明真实 {@code IMqttActionListener}
 * 在 PUBACK/断线/关闭三条路径上确实把 future 收敛。这里直接用生产 {@code PahoDeviceClient} 对真实 Broker，
 * 覆盖 A4-0 成功集合口径的三个网络生命周期边界。</p>
 */
@Tag("s7-recovery")
class PahoPubackDeliveryTests {

    /** 与本地 deploy MQTT 协议代际一致的真实 Broker；测试配置只在随机映射端口允许匿名。 */
    private static final DockerImageName MOSQUITTO_IMAGE =
            DockerImageName.parse("eclipse-mosquitto:2.0.22");

    /** 启动一个匿名可连的 Mosquitto 容器。 */
    private static GenericContainer<?> broker() {
        return new GenericContainer<>(MOSQUITTO_IMAGE)
                .withExposedPorts(1883)
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("mosquitto-s7.conf"),
                        "/mosquitto/config/mosquitto.conf")
                .waitingFor(Wait.forListeningPort())
                .withStartupTimeout(Duration.ofSeconds(30));
    }

    /** 真实 Broker 收到 QoS 1 PUBLISH 必须回 PUBACK，future 应在期限内正常完成（不是「发起即成功」）。 */
    @Test
    void completesDeliveryOnRealPuback() throws Exception {
        GenericContainer<?> broker = broker();
        PahoMqttDeviceClientFactory factory = new PahoMqttDeviceClientFactory();
        MqttDeviceClient client = null;
        try {
            broker.start();
            client = factory.connect(brokerUri(broker), "puback_project", "puback_device", "test-token");
            CompletableFuture<Void> delivery = client.publish(
                    "tc/v1/puback_project/puback_device/up/property/report",
                    "{\"messageId\":\"x\"}".getBytes(StandardCharsets.UTF_8), 1, false);

            // get 正常返回即证明 future 已成功完成（若异常完成这里会抛 ExecutionException）。
            delivery.get(5, TimeUnit.SECONDS);
            assertThat(delivery).isCompleted();
        } finally {
            if (client != null) {
                client.close();
            }
            factory.shutdown();
            if (broker.isRunning()) {
                broker.stop();
            }
        }
    }

    /** 主动关闭时在途交付必须收敛且不挂起：close 在期限内返回，future 无论成败都已完成。 */
    @Test
    void closeSettlesInFlightDeliveryWithoutHanging() throws Exception {
        GenericContainer<?> broker = broker();
        PahoMqttDeviceClientFactory factory = new PahoMqttDeviceClientFactory();
        MqttDeviceClient client = null;
        try {
            broker.start();
            MqttDeviceClient connected = factory.connect(brokerUri(broker), "close_project", "close_device", "test-token");
            client = connected;
            CompletableFuture<Void> delivery = connected.publish(
                    "tc/v1/close_project/close_device/up/property/report",
                    "{\"messageId\":\"x\"}".getBytes(StandardCharsets.UTF_8), 1, false);

            // 不等 PUBACK 立即关闭；若 failPendingDeliveries 或 Paho close 挂起，这里会超时失败。
            assertThatCode(connected::close).doesNotThrowAnyException();
            // 交付必须已收敛（成功或失败皆可），不能仍处于 pending。
            assertThat(delivery.isDone()).isTrue();
            client = null; // 已关闭，避免 finally 二次关闭
        } finally {
            if (client != null) {
                client.close();
            }
            factory.shutdown();
            if (broker.isRunning()) {
                broker.stop();
            }
        }
    }

    /** 异常断线后，在途交付必须在期限内收敛（成功或失败皆可，永久悬挂才是缺陷），随后连接应能恢复。 */
    @Test
    void settlesInFlightDeliveryOnConnectionLoss() throws Exception {
        GenericContainer<?> broker = broker();
        PahoMqttDeviceClientFactory factory = new PahoMqttDeviceClientFactory();
        MqttDeviceClient client = null;
        try {
            broker.start();
            client = factory.connect(brokerUri(broker), "storm_project", "storm_device", "test-token");
            CompletableFuture<Void> delivery = client.publish(
                    "tc/v1/storm_project/storm_device/up/property/report",
                    "{\"messageId\":\"x\"}".getBytes(StandardCharsets.UTF_8), 1, false);

            client.forceConnectionLoss();
            // PUBACK 与断线谁先到不确定：成功收敛或异常收敛都合法，关键是不得永久悬挂。
            try {
                delivery.get(5, TimeUnit.SECONDS);
            } catch (ExecutionException expectedFailure) {
                // 断线先于 PUBACK 时以「发布未确认」失败收敛，符合 failPendingDeliveries 语义。
            }
            assertThat(delivery.isDone()).isTrue();
        } finally {
            if (client != null) {
                client.close();
            }
            factory.shutdown();
            if (broker.isRunning()) {
                broker.stop();
            }
        }
    }

    /** publish 已登记但仍在调用 Paho 时并发 close，返回的收据也必须异常收敛，不能越过关闭结算窗口。 */
    @Test
    void settlesPublishRacingWithCloseAfterPendingRegistration() throws Exception {
        MqttAsyncClient paho = mock(MqttAsyncClient.class);
        IMqttDeliveryToken token = mock(IMqttDeliveryToken.class);
        CountDownLatch publishEntered = new CountDownLatch(1);
        CountDownLatch releasePublish = new CountDownLatch(1);
        when(paho.publish(anyString(), any(MqttMessage.class), any(), any(IMqttActionListener.class)))
                .thenAnswer(invocation -> {
                    publishEntered.countDown();
                    if (!releasePublish.await(2, TimeUnit.SECONDS)) {
                        throw new AssertionError("测试未及时释放 Paho publish");
                    }
                    return token;
                });
        when(paho.isConnected()).thenReturn(false);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        PahoMqttDeviceClientFactory.PahoDeviceClient adapter =
                new PahoMqttDeviceClientFactory.PahoDeviceClient(
                        paho, new MqttConnectOptions(), scheduler, Runnable::run, () -> 0L);
        try {
            Future<CompletableFuture<Void>> publishCall = caller.submit(() -> adapter.publish(
                    "tc/v1/race_project/race_device/up/property/report",
                    "{\"messageId\":\"x\"}".getBytes(StandardCharsets.UTF_8), 1, false));
            assertThat(publishEntered.await(1, TimeUnit.SECONDS)).isTrue();

            adapter.close();
            releasePublish.countDown();
            CompletableFuture<Void> delivery = publishCall.get(2, TimeUnit.SECONDS);

            assertThat(delivery).isCompletedExceptionally();
        } finally {
            releasePublish.countDown();
            caller.shutdownNow();
            scheduler.shutdownNow();
        }
    }

    /** 正常 DISCONNECT 超时后必须强制回收本地连接，不能把可收敛的停机竞态计成运行期失败。 */
    @Test
    void fallsBackToForcedCloseWhenGracefulDisconnectTimesOut() throws Exception {
        MqttAsyncClient paho = mock(MqttAsyncClient.class);
        IMqttToken disconnectToken = mock(IMqttToken.class);
        when(paho.isConnected()).thenReturn(true);
        when(paho.disconnect(2_000L)).thenReturn(disconnectToken);
        org.mockito.Mockito.doThrow(new MqttException(MqttException.REASON_CODE_CLIENT_TIMEOUT))
                .when(disconnectToken).waitForCompletion(2_000L);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        PahoMqttDeviceClientFactory.PahoDeviceClient adapter =
                new PahoMqttDeviceClientFactory.PahoDeviceClient(
                        paho, new MqttConnectOptions(), scheduler, Runnable::run, () -> 0L);
        try {
            assertThatCode(adapter::close).doesNotThrowAnyException();

            verify(paho).disconnectForcibly(0L, 1_000L, true);
            verify(paho).close();
        } finally {
            scheduler.shutdownNow();
        }
    }

    /** @return 随机宿主端口的 Broker 连接地址 */
    private static String brokerUri(GenericContainer<?> broker) {
        return "tcp://" + broker.getHost() + ":" + broker.getMappedPort(1883);
    }
}
