package com.things.link.simulator.infrastructure.mqtt;

import com.things.link.simulator.api.dto.SimulationRequest;
import com.things.link.simulator.application.DeviceSimulator;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/** 以千设备样本钉住重连风暴的退避上限和时间离散性。 */
@Tag("s7-recovery")
class PahoReconnectStormTests {

    /** 与本地 deploy MQTT 协议代际一致的真实 Broker；测试配置只在随机映射端口允许匿名。 */
    private static final DockerImageName MOSQUITTO_IMAGE =
            DockerImageName.parse("eclipse-mosquitto:2.0.22");

    /** 真实连接数量足以证明批量风暴，同时控制 Docker Desktop 聚焦回归耗时。 */
    private static final int REAL_DEVICE_COUNT = 12;

    /** 真实 Broker 用例的 manifest 归档临时目录，避免污染工作目录。 */
    @TempDir
    Path manifestDir;

    /** 每一退避阶梯都必须落在“半上限到上限”的等抖动窗口内。 */
    @Test
    void shouldBoundEveryReconnectAttempt() {
        assertDelayRange(1, 500, 1_000);
        assertDelayRange(2, 1_000, 2_000);
        assertDelayRange(3, 2_000, 4_000);
        assertDelayRange(7, 30_000, 60_000);
        assertDelayRange(50, 30_000, 60_000);
    }

    /**
     * 同一时刻断线的 1000 台设备不应落入单一毫秒桶；这是 S7 恢复验收对重连风暴的可执行证据。
     */
    @Test
    void shouldSpreadOneThousandDevicesAcrossReconnectWindow() {
        Set<Long> millisecondBuckets = new HashSet<>();
        long minimum = Long.MAX_VALUE;
        long maximum = Long.MIN_VALUE;
        for (int device = 0; device < 1_000; device++) {
            long delay = PahoMqttDeviceClientFactory.PahoDeviceClient
                    .reconnectDelay(4, mix(device))
                    .toMillis();
            millisecondBuckets.add(delay);
            minimum = Math.min(minimum, delay);
            maximum = Math.max(maximum, delay);
        }

        assertThat(millisecondBuckets).hasSizeGreaterThan(850);
        assertThat(minimum).isBetween(4_000L, 8_000L);
        assertThat(maximum - minimum).isGreaterThan(3_500L);
        assertThat(maximum).isLessThanOrEqualTo(8_000L);
    }

    /**
     * 真实 Mosquitto 上批量建立设备会话，强制 TCP 异常断开后等待生产重连调度恢复并量化完成时间分散度。
     *
     * <p>确定序列只控制等抖动样本，连接、断线、调度、订阅恢复和 Broker 会话均使用生产 Paho 实现；
     * 因而不会把纯 {@link #shouldSpreadOneThousandDevicesAcrossReconnectWindow()} 计算误报成网络恢复证据。</p>
     */
    @Test
    void reconnectsRealDeviceBatchAfterForcedConnectionLossWithSpread() throws Exception {
        GenericContainer<?> broker = new GenericContainer<>(MOSQUITTO_IMAGE)
                .withExposedPorts(1883)
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("mosquitto-s7.conf"),
                        "/mosquitto/config/mosquitto.conf")
                .waitingFor(Wait.forListeningPort())
                .withStartupTimeout(Duration.ofSeconds(30));
        PahoMqttDeviceClientFactory factory = null;
        DeviceSimulator simulator = null;
        try {
            broker.start();
            AtomicLong jitterSequence = new AtomicLong();
            // 第一次退避窗口为 500~1000ms；45ms 步长让 12 台覆盖约 495ms，避免随机测试偶发聚团。
            factory = new PahoMqttDeviceClientFactory(() -> jitterSequence.getAndAdd(45L));
            simulator = new DeviceSimulator(factory, new ObjectMapper(), manifestDir.toString());
            simulator.start(realRequest(broker));
            DeviceSimulator.SimulationStats running = awaitStats(simulator,
                    stats -> stats.connectedDevices() == REAL_DEVICE_COUNT
                            && stats.deliveredReports() >= REAL_DEVICE_COUNT,
                    Duration.ofSeconds(5));
            assertThat(running.deviceCount()).isEqualTo(REAL_DEVICE_COUNT);
            DeviceSimulator.SimulationStats beforeReconnect = running;

            simulator.reconnectStorm();
            assertThat(simulator.stats().forcedDisconnects()).isEqualTo(REAL_DEVICE_COUNT);
            assertThat(simulator.stats().connectedDevices()).isZero();

            DeviceSimulator.SimulationStats recovered = awaitStats(simulator,
                    stats -> stats.reconnectedDevices() == REAL_DEVICE_COUNT
                            && stats.connectedDevices() == REAL_DEVICE_COUNT,
                    Duration.ofSeconds(10));
            assertThat(recovered.reconnectSpreadMillis()).isGreaterThanOrEqualTo(250L);
            // 资格批次采用一秒周期；恢复后等待每台设备至少新增一次 PUBACK，证明发布链路恢复而非只看 connected。
            DeviceSimulator.SimulationStats afterReconnect = awaitStats(simulator,
                    stats -> stats.deliveredReports() >= beforeReconnect.deliveredReports() + REAL_DEVICE_COUNT,
                    Duration.ofSeconds(5));
            assertThat(afterReconnect.failedOperations()).isZero();
        } finally {
            if (simulator != null) {
                simulator.stop();
            }
            if (factory != null) {
                factory.shutdown();
            }
            if (broker.isRunning()) {
                broker.stop();
            }
        }
    }

    /**
     * 真实 Broker 下，入站命令处理必须离开 Paho 回调线程后再同步发布 QoS 1 回执；否则会形成回调重入等待，
     * 平台只能看到下行已投递而永远收不到终态。纯内存 {@code RecordingClient} 无法暴露该网络线程约束。
     */
    @Test
    void repliesToCommandThroughRealBrokerWithoutCallbackDeadlock() throws Exception {
        GenericContainer<?> broker = new GenericContainer<>(MOSQUITTO_IMAGE)
                .withExposedPorts(1883)
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("mosquitto-s7.conf"),
                        "/mosquitto/config/mosquitto.conf")
                .waitingFor(Wait.forListeningPort())
                .withStartupTimeout(Duration.ofSeconds(30));
        PahoMqttDeviceClientFactory factory = null;
        DeviceSimulator simulator = null;
        MqttClient observer = null;
        try {
            broker.start();
            String brokerUri = "tcp://" + broker.getHost() + ":" + broker.getMappedPort(1883);
            factory = new PahoMqttDeviceClientFactory();
            simulator = new DeviceSimulator(factory, new ObjectMapper(), manifestDir.toString());
            simulator.start(new SimulationRequest(brokerUri, "command_project",
                    List.of(new SimulationRequest.DeviceCredential("command_device", "test-token")),
                    3_600, true));

            UUID commandId = UUID.randomUUID();
            String replyTopic = "tc/v1/command_project/command_device/up/command/" + commandId + "/reply";
            CountDownLatch replyArrived = new CountDownLatch(1);
            observer = new MqttClient(brokerUri, "command-observer-" + commandId, new MemoryPersistence());
            MqttConnectOptions options = new MqttConnectOptions();
            options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
            observer.connect(options);
            observer.subscribe(replyTopic, 1, (topic, message) -> replyArrived.countDown());

            String commandTopic = "tc/v1/command_project/command_device/down/command/" + commandId;
            MqttMessage command = new MqttMessage(
                    "{\"targetDeviceKey\":\"command_device\",\"commandKey\":\"reboot\","
                            .concat("\"input\":{},\"attempt\":1}").getBytes(StandardCharsets.UTF_8));
            command.setQos(1);
            observer.publish(commandTopic, command);

            assertThat(replyArrived.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            if (observer != null) {
                if (observer.isConnected()) {
                    observer.disconnect();
                }
                observer.close();
            }
            if (simulator != null) {
                simulator.stop();
            }
            if (factory != null) {
                factory.shutdown();
            }
            if (broker.isRunning()) {
                broker.stop();
            }
        }
    }

    /**
     * 离开 Paho 回调线程不能以牺牲单设备顺序为代价：第二条消息必须等第一条处理完才可进入业务处理器。
     */
    @Test
    void serializesMessagesForOneDeviceWhileUsingAsyncExecutor() throws Exception {
        ExecutorService delegate = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
        PahoMqttDeviceClientFactory.SerialExecutor serial =
                new PahoMqttDeviceClientFactory.SerialExecutor(delegate);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondCompleted = new CountDownLatch(1);
        List<Integer> order = new CopyOnWriteArrayList<>();
        try {
            serial.execute(() -> {
                order.add(1);
                firstStarted.countDown();
                try {
                    releaseFirst.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
                order.add(2);
            });
            assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();

            serial.execute(() -> {
                order.add(3);
                secondCompleted.countDown();
            });
            assertThat(secondCompleted.await(200, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(order).containsExactly(1);

            releaseFirst.countDown();
            assertThat(secondCompleted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(order).containsExactly(1, 2, 3);
        } finally {
            releaseFirst.countDown();
            delegate.shutdownNow();
        }
    }

    /**
     * 构造使用随机宿主端口和独立设备身份的真实模拟批次。
     *
     * <p>一秒周期让完整相位分散后的全部设备在有界测试窗口内各产生一条 QoS 1 报文；这既不调用
     * C4a 仅允许单设备的定向 {@code publishOnce()}，也不恢复旧版“3600 秒周期仍会在五秒内齐发”的错误假设。</p>
     *
     * @param broker 随机映射端口的真实 Broker
     * @return 十二台设备的恢复资格批次
     */
    private static SimulationRequest realRequest(GenericContainer<?> broker) {
        List<SimulationRequest.DeviceCredential> devices = new ArrayList<>();
        for (int index = 0; index < REAL_DEVICE_COUNT; index++) {
            devices.add(new SimulationRequest.DeviceCredential("storm_" + index, "test-token-" + index));
        }
        return new SimulationRequest(
                "tcp://" + broker.getHost() + ":" + broker.getMappedPort(1883),
                "s7_project", devices, 1, false);
    }

    /**
     * 按条件轮询真实异步连接状态；超时时携带最终快照，避免固定 sleep 让慢机产生假阳性。
     *
     * @param simulator 真实模拟器
     * @param condition 完成条件
     * @param timeout 最长恢复时间
     * @return 首个满足条件的统计快照
     */
    private static DeviceSimulator.SimulationStats awaitStats(
            DeviceSimulator simulator,
            Predicate<DeviceSimulator.SimulationStats> condition,
            Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        DeviceSimulator.SimulationStats latest = simulator.stats();
        while (Instant.now().isBefore(deadline)) {
            latest = simulator.stats();
            if (condition.test(latest)) {
                return latest;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("真实 MQTT 重连未在期限内收敛: " + latest);
    }

    /** 使用稳定整数混合函数让负载证据可复现，同时覆盖负 long 的 floorMod 分支。 */
    private static long mix(int value) {
        long mixed = value * 0x9E3779B97F4A7C15L;
        mixed ^= mixed >>> 30;
        mixed *= 0xBF58476D1CE4E5B9L;
        mixed ^= mixed >>> 27;
        mixed *= 0x94D049BB133111EBL;
        return mixed ^ (mixed >>> 31);
    }

    /** 断言给定阶梯的一组边界随机值始终处于有限窗口。 */
    private static void assertDelayRange(int attempt, long lower, long upper) {
        for (long random : new long[]{Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE}) {
            Duration delay = PahoMqttDeviceClientFactory.PahoDeviceClient.reconnectDelay(attempt, random);
            assertThat(delay.toMillis()).isBetween(lower, upper);
        }
    }
}
