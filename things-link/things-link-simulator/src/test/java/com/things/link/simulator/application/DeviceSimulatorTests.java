package com.things.link.simulator.application;

import com.things.link.simulator.api.dto.SimulationRequest;
import com.things.link.shared.id.Uuid7;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证模拟器必须经过 MQTT 并遵守 Topic、QoS、retained 和批量启动原子性。 */
class DeviceSimulatorTests {

    /** 记录连接与发布的测试工厂。 */
    private final RecordingFactory factory = new RecordingFactory();

    /** 测试 manifest 落盘目录，避免污染工作目录。 */
    @TempDir
    Path manifestDir;

    /** 被测模拟器；在 @BeforeEach 里用注入后的 @TempDir 构造。 */
    private DeviceSimulator simulator;

    /** 每个用例前构造模拟器（@TempDir 注入在字段初始化之后，必须在此构造）。 */
    @BeforeEach
    void setUp() {
        simulator = new DeviceSimulator(factory, new ObjectMapper(), manifestDir.toString());
    }

    /** #8 修复必须在两核 Runner 提供四个共享 worker，同时不得压低更大 Runner 的原生并行度。 */
    @Test
    void keepsFourSchedulerWorkersOnSmallRunnersWithoutCappingLargeRunners() {
        assertThat(DeviceSimulator.schedulerWorkerCount(2)).isEqualTo(4);
        assertThat(DeviceSimulator.schedulerWorkerCount(4)).isEqualTo(4);
        assertThat(DeviceSimulator.schedulerWorkerCount(8)).isEqualTo(8);
    }

    /** #13 修复必须确定性错开批内周期相位，同时保持所有设备落在完整上报周期内。 */
    @Test
    void spreadsInitialPublishPhasesDeterministicallyWithinReportInterval() {
        assertThat(DeviceSimulator.initialPublishDelayMillis(0, 167, 60)).isZero();
        assertThat(DeviceSimulator.initialPublishDelayMillis(1, 167, 60)).isEqualTo(359L);
        assertThat(DeviceSimulator.initialPublishDelayMillis(83, 167, 60)).isEqualTo(29_820L);
        assertThat(DeviceSimulator.initialPublishDelayMillis(166, 167, 60)).isEqualTo(59_640L);
        assertThat(DeviceSimulator.initialPublishDelayMillis(0, 1, 60)).isZero();
        assertThatThrownBy(() -> DeviceSimulator.initialPublishDelayMillis(167, 167, 60))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DeviceSimulator.initialPublishDelayMillis(0, 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 两条长期负载占用旧有两槽时，共享池仍须让资源采样从新增槽位推进。 */
    @Test
    void samplesResourcesWhenTwoWorkloadTasksOccupySmallRunnerSlots() throws Exception {
        ScheduledExecutorService scheduler = DeviceSimulator.newScheduler(2);
        CountDownLatch workloadStarted = new CountDownLatch(2);
        CountDownLatch releaseWorkload = new CountDownLatch(1);
        CountDownLatch resourceSampled = new CountDownLatch(1);
        Runnable blockingWorkload = () -> {
            workloadStarted.countDown();
            try {
                releaseWorkload.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        };
        try {
            scheduler.execute(blockingWorkload);
            scheduler.execute(blockingWorkload);
            assertThat(workloadStarted.await(1, TimeUnit.SECONDS)).isTrue();

            scheduler.execute(resourceSampled::countDown);

            assertThat(resourceSampled.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseWorkload.countDown();
            scheduler.shutdownNow();
        }
    }

    /** 每个用例结束都断开，避免调度线程影响后续断言。 */
    @AfterEach
    void stopSimulator() {
        simulator.stop();
    }

    /** MQTT 上报必须使用 v1 属性 Topic、QoS 1、非 retained，并携带消息信封关键字段。 */
    @Test
    void publishesPropertyThroughAuthenticatedMqttContract() throws Exception {
        simulator.start(request(List.of(new SimulationRequest.DeviceCredential("device_1", "secret"))));

        assertThat(factory.published.await(2, TimeUnit.SECONDS)).isTrue();
        RecordingClient client = factory.clients.getFirst();
        assertThat(factory.usernames).containsExactly("project_1/device_1");
        PublishedMessage published = client.publishedMessages().stream()
                .filter(message -> message.topic().endsWith("/up/property/report"))
                .findFirst().orElseThrow();
        assertThat(published.topic()).isEqualTo("tc/v1/project_1/device_1/up/property/report");
        assertThat(published.qos()).isEqualTo(1);
        assertThat(published.retained()).isFalse();
        DevicePropertyReportPayload payload = new ObjectMapper().readValue(
                published.payload(), DevicePropertyReportPayload.class);
        assertThat(payload.messageId.version()).isEqualTo(7);
        assertThat(payload.occurredAt).isNotNull();
        assertThat(payload.payload).containsKey("temperature");
        // 发布与计数在同一调度线程上异步推进，轮询等待「发起」计数落账，避免在登记前误读为 0。
        awaitStats(stats -> stats.publishedReports() == 1);
    }

    /** A4-0 标准模型必须真的编码 10 个属性，并把 run/shard、连接爬坡与资源样本绑定到同一轮证据。 */
    @Test
    void exposesQualificationIdentityConnectionResourcesAndTenPropertyPayload() throws Exception {
        SimulationRequest request = new SimulationRequest(
                "tcp://localhost:1883", "project_1",
                List.of(new SimulationRequest.DeviceCredential("device_1", "secret")),
                60, true, "run-20260821", "shard-007", 10);

        simulator.start(request);
        assertThat(factory.published.await(2, TimeUnit.SECONDS)).isTrue();
        awaitStats(stats -> stats.propertyReports().confirmed() == 1);

        PublishedMessage published = factory.clients.getFirst().publishedMessages().getFirst();
        DevicePropertyReportPayload payload = new ObjectMapper().readValue(
                published.payload(), DevicePropertyReportPayload.class);
        assertThat(payload.payload()).hasSize(10).containsKeys("temperature", "metric_02", "metric_10");
        DeviceSimulator.SimulationStats stats = simulator.stats();
        assertThat(stats.runId()).isEqualTo("run-20260821");
        assertThat(stats.shardId()).isEqualTo("shard-007");
        assertThat(stats.propertiesPerReport()).isEqualTo(10);
        // 归档层级是跨平台契约；结构化比较避免把 Linux 的“/”误当成 Windows 路径格式。
        assertThat(Path.of(stats.manifestDir())).endsWith(Path.of("run-20260821", "shard-007"));
        assertThat(stats.connection().target()).isEqualTo(1);
        assertThat(stats.connection().attempted()).isEqualTo(1);
        assertThat(stats.connection().succeeded()).isEqualTo(1);
        assertThat(stats.connection().failed()).isZero();
        assertThat(stats.connection().rampDurationMillis()).isGreaterThanOrEqualTo(0L);
        assertThat(stats.generatorResources().sampleCount()).isPositive();
        assertThat(stats.generatorResources().peakHeapUsedBytes()).isPositive();
        awaitStats(current -> current.generatorResources().sampleCount() >= 2L);
        DeviceSimulator.GeneratorResourceQualification resources = simulator.stats().generatorResources();
        assertThat(resources.maxSampleGapMillis()).isPositive();
        assertThat(resources.maxSampleGapStartedAt()).isNotNull();
        assertThat(resources.maxSampleGapEndedAt()).isAfter(resources.maxSampleGapStartedAt());
    }

    /** 资源采样不能被同步连接爬坡持有的模拟器锁饿死，否则最重阶段会只留下一个启动前样本。 */
    @Test
    void samplesResourcesWhileSynchronousConnectionRampIsBlocked() throws Exception {
        CountDownLatch connectionEntered = new CountDownLatch(1);
        CountDownLatch releaseConnection = new CountDownLatch(1);
        MqttDeviceClientFactory slowFactory = (brokerUri, projectKey, deviceKey, accessToken) -> {
            connectionEntered.countDown();
            try {
                if (!releaseConnection.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("测试未释放连接爬坡");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待连接爬坡被中断", exception);
            }
            return factory.connect(brokerUri, projectKey, deviceKey, accessToken);
        };
        simulator = new DeviceSimulator(slowFactory, new ObjectMapper(), manifestDir.toString());

        Thread starter = Thread.ofVirtual().start(() -> simulator.start(
                request(List.of(new SimulationRequest.DeviceCredential("device_1", "secret")))));
        assertThat(connectionEntered.await(2, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(2_200L);

        assertThat(simulator.stats().generatorResources().sampleCount()).isGreaterThanOrEqualTo(3L);
        releaseConnection.countDown();
        starter.join(Duration.ofSeconds(5));
        assertThat(starter.isAlive()).isFalse();
    }

    /** 测试侧只读取冻结报文字段，避免字符串包含断言掩盖错误 JSON 或错误类型。 */
    private record DevicePropertyReportPayload(java.util.UUID messageId, java.time.Instant occurredAt,
                                               java.util.Map<String, Object> payload) {
    }

    /** A4-0：成功集合以 PUBACK 为准——发起数、确认数与分类型计数必须对账一致。 */
    @Test
    void countsPubackDeliveredReportsAndMessageIds() throws Exception {
        simulator.start(request(List.of(new SimulationRequest.DeviceCredential("device_1", "secret"))));
        assertThat(factory.published.await(2, TimeUnit.SECONDS)).isTrue();

        // 发起与确认都在同一调度线程上异步推进；轮询等两者都落账，避免在登记前误读。
        awaitStats(stats -> stats.publishedReports() == 1 && stats.deliveredReports() == 1);
        assertThat(simulator.stats().propertyReports().confirmed()).isEqualTo(1);
        assertThat(simulator.stats().propertyReports().initiated()).isEqualTo(1);
        assertThat(simulator.stats().propertyReports().failed()).isZero();
    }

    /** C4a 故障窗口单次触发仍必须走真实属性发布与 manifest，不得成为旁路注入器。 */
    @Test
    void publishesOneAdditionalQualifiedPropertyReportOnDemand() throws Exception {
        simulator.start(request(List.of(new SimulationRequest.DeviceCredential("device_1", "secret"))));
        awaitStats(stats -> stats.propertyReports().confirmed() == 1);

        java.util.UUID messageId = simulator.publishOnce();

        awaitStats(stats -> stats.propertyReports().confirmed() == 2);
        assertThat(messageId).isNotNull();
        DevicePropertyReportPayload payload = new ObjectMapper().readValue(
                factory.clients.getFirst().publishedMessages().getLast().payload(), DevicePropertyReportPayload.class);
        assertThat(payload.messageId()).isEqualTo(messageId);
        assertThat(factory.clients.getFirst().publishedMessages().stream()
                .filter(message -> message.topic().endsWith("/up/property/report"))).hasSize(2);
    }

    /** A4-0：PUBACK 之前发起数已计但确认数必须为 0；PUBACK 之后确认数才增加。 */
    @Test
    void doesNotCountConfirmedUntilPuback() throws Exception {
        factory.clientsToHold = true;
        simulator.start(request(List.of(new SimulationRequest.DeviceCredential("device_1", "secret"))));
        assertThat(factory.published.await(2, TimeUnit.SECONDS)).isTrue();
        RecordingClient client = factory.clients.getFirst();

        // PUBACK 前：等「发起」登记完成，确认仍必须为 0（测试侧把 PUBACK 挂起）。
        awaitStats(stats -> stats.propertyReports().initiated() == 1);
        assertThat(simulator.stats().propertyReports().confirmed()).isZero();

        // 模拟 Broker 回 PUBACK：确认数随之增加。whenComplete 注册与手动回 PUBACK 存在微小竞态，
        // 轮询等确认数到位，避免把「回调尚未执行」误判成「不计数」。
        client.completeHeldDeliveries();
        awaitStats(stats -> stats.propertyReports().confirmed() == 1);
    }

    /** stop 必须等待已经发起的 PUBACK 回调收敛，不能在测量边界把正常在途消息制造成失败。 */
    @Test
    void waitsForInFlightPubackBeforeClosingConnections() throws Exception {
        factory.clientsToHold = true;
        simulator.start(request(List.of(new SimulationRequest.DeviceCredential("device_1", "secret"))));
        assertThat(factory.published.await(2, TimeUnit.SECONDS)).isTrue();
        awaitStats(stats -> stats.propertyReports().initiated() == 1);
        RecordingClient client = factory.clients.getFirst();

        Thread stopper = Thread.ofVirtual().start(simulator::stop);
        Thread.sleep(50L);
        assertThat(stopper.isAlive()).isTrue();
        assertThat(client.closed).isFalse();

        client.completeHeldDeliveries();
        stopper.join(Duration.ofSeconds(2));
        assertThat(stopper.isAlive()).isFalse();
        assertThat(client.closed).isTrue();
        assertThat(simulator.stats().propertyReports().confirmed()).isEqualTo(1);
        assertThat(simulator.stats().propertyReports().failed()).isZero();
        assertThat(simulator.stats().manifestHealthy()).isTrue();
    }

    /** 发布前序列化失败属于发生器操作失败，不能伪造一条从未发起的 MQTT 交付失败。 */
    @Test
    void keepsManifestEquationClosedWhenSerializationFailsBeforePublish() throws Exception {
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(any())).thenThrow(new IllegalStateException("模拟序列化失败"));
        simulator = new DeviceSimulator(factory, failingMapper, manifestDir.toString());

        simulator.start(request(List.of(new SimulationRequest.DeviceCredential("device_1", "secret"))));
        awaitStats(stats -> stats.failedOperations() == 1);

        assertThat(simulator.stats().propertyReports().initiated()).isZero();
        assertThat(simulator.stats().propertyReports().confirmed()).isZero();
        assertThat(simulator.stats().propertyReports().failed()).isZero();
        assertThat(factory.clients.getFirst().publishedMessages()).isEmpty();
    }

    /** 自动回复模式必须订阅严格的命令 Topic，并以 QoS 1、非 retained 发布冻结回复形状。 */
    @Test
    void subscribesAndRepliesToDownlinkCommand() throws Exception {
        simulator.start(request(List.of(new SimulationRequest.DeviceCredential("device_1", "secret")), true));

        RecordingClient client = factory.clients.getFirst();
        UUID commandId = Uuid7.generate();
        client.receive("tc/v1/project_1/device_1/down/command/" + commandId,
                "{\"targetDeviceKey\":\"device_1\",\"commandKey\":\"reboot\",\"input\":{},\"attempt\":1}"
                        .getBytes(StandardCharsets.UTF_8));

        assertThat(client.subscriptionTopic).isEqualTo("tc/v1/project_1/device_1/down/command/#");
        assertThat(client.subscriptionQos).isEqualTo(1);
        List<PublishedMessage> replies = client.publishedMessages().stream()
                .filter(message -> message.topic().endsWith("/up/command/" + commandId + "/reply"))
                .toList();
        assertThat(replies).hasSize(1);
        PublishedMessage reply = replies.getFirst();
        assertThat(reply.qos()).isEqualTo(1);
        assertThat(reply.retained()).isFalse();
        CommandReplyPayload payload = new ObjectMapper().readValue(reply.payload(), CommandReplyPayload.class);
        assertThat(payload.messageId.version()).isEqualTo(7);
        assertThat(payload.occurredAt).isNotNull();
        assertThat(payload.status).isEqualTo("SUCCESS");
        assertThat(payload.output).isEmpty();
        assertThat(payload.errorCode).isNull();
        assertThat(payload.message).isNull();
    }

    /** QoS 1 的同一 commandId 重投只能重发首份成功终态，不能生成第二次模拟执行结果。 */
    @Test
    void replaysSameTerminalReplyForRepeatedCommandId() throws Exception {
        simulator.start(request(List.of(new SimulationRequest.DeviceCredential("device_1", "secret")), true));

        RecordingClient client = factory.clients.getFirst();
        UUID commandId = Uuid7.generate();
        String commandTopic = "tc/v1/project_1/device_1/down/command/" + commandId;
        byte[] command = "{\"targetDeviceKey\":\"device_1\",\"commandKey\":\"reboot\",\"input\":{},\"attempt\":1}"
                .getBytes(StandardCharsets.UTF_8);
        client.receive(commandTopic, command);
        client.receive(commandTopic, command);

        List<PublishedMessage> replies = client.publishedMessages().stream()
                .filter(message -> message.topic().endsWith("/up/command/" + commandId + "/reply"))
                .toList();
        assertThat(replies).hasSize(2);
        assertThat(replies.get(0).payload()).isEqualTo(replies.get(1).payload());
    }

    /** 不回复模式仍保持命令订阅，但故意静默以覆盖平台侧超时与重试演练。 */
    @Test
    void suppressesCommandReplyWhenAutoReplyIsDisabled() {
        simulator.start(request(List.of(new SimulationRequest.DeviceCredential("device_1", "secret")), false));

        RecordingClient client = factory.clients.getFirst();
        UUID commandId = Uuid7.generate();
        client.receive("tc/v1/project_1/device_1/down/command/" + commandId,
                "{\"targetDeviceKey\":\"device_1\",\"commandKey\":\"reboot\",\"input\":{},\"attempt\":1}"
                        .getBytes(StandardCharsets.UTF_8));

        assertThat(client.subscriptionTopic).isEqualTo("tc/v1/project_1/device_1/down/command/#");
        assertThat(client.publishedMessages()).noneMatch(message -> message.topic().contains("/up/command/"));
    }

    /** 网关必须同时消费配置与子设备命令，并为配置中的每个子设备显式上报一次在线事实。 */
    @Test
    void gatewayPublishesDistinctSubDeviceLoginAndRepliesToRoutedCommand() throws Exception {
        simulator.start(request(List.of(
                new SimulationRequest.DeviceCredential("gateway_1", "secret", true)), true));
        RecordingClient client = factory.clients.getFirst();
        assertThat(client.subscriptions.keySet()).containsExactlyInAnyOrder(
                "tc/v1/project_1/gateway_1/down/config",
                "tc/v1/project_1/gateway_1/down/command/#");

        client.receive("tc/v1/project_1/gateway_1/down/config", """
                {"version":1,"points":[
                  {"subDeviceKey":"sub_1","propertyKey":"temperature","dataType":"FLOAT32"},
                  {"subDeviceKey":"sub_1","propertyKey":"humidity","dataType":"FLOAT32"},
                  {"subDeviceKey":"sub_2","propertyKey":"temperature","dataType":"FLOAT32"}
                ]}
                """.getBytes(StandardCharsets.UTF_8));

        List<PublishedMessage> logins = client.publishedMessages().stream()
                .filter(message -> message.topic().endsWith("/up/sub/login"))
                .toList();
        assertThat(logins).hasSize(2).allSatisfy(message -> {
            assertThat(message.qos()).isEqualTo(1);
            assertThat(message.retained()).isFalse();
        });
        List<String> subDeviceKeys = new ArrayList<>();
        for (PublishedMessage login : logins) {
            var payload = new ObjectMapper().readTree(login.payload());
            assertThat(UUID.fromString(payload.path("messageId").asString()).version()).isEqualTo(7);
            assertThat(payload.has("gatewayId")).isFalse();
            subDeviceKeys.add(payload.path("subDeviceKey").asString());
        }
        assertThat(subDeviceKeys).containsExactlyInAnyOrder("sub_1", "sub_2");

        UUID commandId = Uuid7.generate();
        client.receive("tc/v1/project_1/gateway_1/down/command/" + commandId,
                "{\"targetDeviceKey\":\"sub_1\",\"commandKey\":\"reboot\",\"input\":{},\"attempt\":1}"
                        .getBytes(StandardCharsets.UTF_8));
        assertThat(client.publishedMessages()).anyMatch(
                message -> message.topic().endsWith("/up/command/" + commandId + "/reply"));
    }

    /** 数据库故障场景可精确回复已受理命令，相同 commandId 必须复用同一业务 messageId。 */
    @Test
    void publishesQualifiedCommandReplyForAcceptedCommand() throws Exception {
        simulator.start(request(List.of(new SimulationRequest.DeviceCredential("device_1", "secret")), false));
        awaitStats(stats -> stats.propertyReports().confirmed() == 1);
        UUID commandId = Uuid7.generate();

        UUID first = simulator.publishCommandReply(commandId);
        UUID repeated = simulator.publishCommandReply(commandId);

        assertThat(repeated).isEqualTo(first);
        awaitStats(stats -> stats.commandReplies().confirmed() == 2);
        List<PublishedMessage> replies = factory.clients.getFirst().publishedMessages().stream()
                .filter(message -> message.topic().endsWith("/up/command/" + commandId + "/reply"))
                .toList();
        assertThat(replies).hasSize(2);
        assertThat(replies.get(0).payload()).isEqualTo(replies.get(1).payload());
    }

    /** stop 关闭设备闸门后，已排队的迟到入站消息不得发布回执或修改已关闭 manifest。 */
    @Test
    void ignoresQueuedInboundMessageAfterStop() throws Exception {
        simulator.start(request(List.of(new SimulationRequest.DeviceCredential("device_1", "secret")), true));
        assertThat(factory.published.await(2, TimeUnit.SECONDS)).isTrue();
        RecordingClient client = factory.clients.getFirst();

        simulator.stop();
        DeviceSimulator.SimulationStats stopped = simulator.stats();
        UUID commandId = Uuid7.generate();
        client.receive("tc/v1/project_1/device_1/down/command/" + commandId,
                "{\"targetDeviceKey\":\"device_1\",\"commandKey\":\"reboot\",\"input\":{},\"attempt\":1}"
                        .getBytes(StandardCharsets.UTF_8));

        assertThat(simulator.stats().commandReplies()).isEqualTo(stopped.commandReplies());
        assertThat(simulator.stats().manifestHealthy()).isTrue();
        assertThat(client.publishedMessages()).noneMatch(message -> message.topic().contains("/up/command/"));
    }

    /** 回复 JSON 按冻结协议读取，避免只断言字符串导致 null 与空对象语义漂移未被发现。 */
    private record CommandReplyPayload(UUID messageId, java.time.Instant occurredAt, String status,
                                       Map<String, Object> output, String errorCode, String message) {
    }

    /** 任一设备连接失败时必须回滚已经连接的设备，不能留下半启动批次。 */
    @Test
    void rollsBackConnectedDevicesWhenBatchStartFails() {
        factory.failDeviceKey = "device_2";

        assertThatThrownBy(() -> simulator.start(request(List.of(
                new SimulationRequest.DeviceCredential("device_1", "secret-1"),
                new SimulationRequest.DeviceCredential("device_2", "secret-2")))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(factory.clients).hasSize(1);
        assertThat(factory.clients.getFirst().closed).isTrue();
        assertThat(simulator.stats().running()).isFalse();
        assertThat(simulator.stats().failedOperations()).isEqualTo(1);
        assertThat(simulator.stats().connection().target()).isEqualTo(2);
        assertThat(simulator.stats().connection().attempted()).isEqualTo(2);
        assertThat(simulator.stats().connection().succeeded()).isEqualTo(1);
        assertThat(simulator.stats().connection().failed()).isEqualTo(1);
        assertThat(simulator.stats().connection().allConnectedAt()).isNull();
    }

    /** 订阅是已认证设备生命周期的一部分；其失败也必须回滚已经建立的连接。 */
    @Test
    void rollsBackCurrentConnectionWhenCommandSubscriptionFails() {
        factory.failSubscribeDeviceKey = "device_1";

        assertThatThrownBy(() -> simulator.start(request(List.of(
                new SimulationRequest.DeviceCredential("device_1", "secret")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("模拟订阅失败");

        assertThat(factory.clients).hasSize(1);
        assertThat(factory.clients.getFirst().closed).isTrue();
        assertThat(simulator.stats().running()).isFalse();
        assertThat(simulator.stats().failedOperations()).isEqualTo(1);
    }

    /** 重复 deviceKey 会造成相同 MQTT 身份互相踢下线，因此启动边界必须拒绝。 */
    @Test
    void rejectsDuplicateDeviceIdentity() {
        assertThatThrownBy(() -> simulator.start(request(List.of(
                new SimulationRequest.DeviceCredential("device_1", "secret-1"),
                new SimulationRequest.DeviceCredential("device_1", "secret-2")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("设备标识重复");

        assertThat(factory.clients.getFirst().closed).isTrue();
        assertThat(simulator.stats().running()).isFalse();
    }

    /**
     * 轮询等待统计快照满足条件；超时时携带最终快照，避免固定 sleep 让慢机产生假阳性。
     *
     * <p>周期发布在虚拟线程上异步推进，「发布返回」早于「计数落账」，直接用 latch 后断言会引入
     * 与调度线程的竞态。这里轮询收敛，把「异步推进」变成确定性等待。</p>
     *
     * @param condition 完成条件
     */
    private void awaitStats(Predicate<DeviceSimulator.SimulationStats> condition) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(2);
        DeviceSimulator.SimulationStats latest = simulator.stats();
        while (Instant.now().isBefore(deadline)) {
            latest = simulator.stats();
            if (condition.test(latest)) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("统计未在期限内收敛: " + latest);
    }

    /** 构造合法请求，测试不重复验证 Bean Validation。 */
    private SimulationRequest request(List<SimulationRequest.DeviceCredential> devices) {
        return new SimulationRequest("tcp://localhost:1883", "project_1", devices, 60);
    }

    /** 构造带明确命令回复策略的合法请求。 */
    private SimulationRequest request(List<SimulationRequest.DeviceCredential> devices, boolean autoReplyCommands) {
        return new SimulationRequest("tcp://localhost:1883", "project_1", devices, 60, autoReplyCommands);
    }

    /** 测试 MQTT 工厂；只记录认证身份，不记录 Token，模拟生产日志的脱敏边界。 */
    private final class RecordingFactory implements MqttDeviceClientFactory {

        /** 已创建的客户端。 */
        private final List<RecordingClient> clients = new ArrayList<>();

        /** 建连 username。 */
        private final List<String> usernames = new ArrayList<>();

        /** 首次发布同步点。 */
        private final CountDownLatch published = new CountDownLatch(1);

        /** 指定需要模拟建连失败的设备。 */
        private String failDeviceKey;

        /** 指定需要模拟下行订阅失败的设备。 */
        private String failSubscribeDeviceKey;

        /** 发布后不自动 PUBACK，改为挂起由测试手动完成。 */
        private boolean clientsToHold;

        /** {@inheritDoc} */
        @Override
        public MqttDeviceClient connect(String brokerUri, String projectKey, String deviceKey, String accessToken) {
            if (deviceKey.equals(failDeviceKey)) {
                throw new IllegalStateException("模拟连接失败");
            }
            usernames.add(projectKey + "/" + deviceKey);
            RecordingClient client = new RecordingClient();
            client.failSubscription = deviceKey.equals(failSubscribeDeviceKey);
            client.holdPublish = clientsToHold;
            clients.add(client);
            return client;
        }
    }

    /** 记录 MQTT 发布契约的测试客户端。 */
    private final class RecordingClient implements MqttDeviceClient {

        /** 全部已发布消息；命令回复与周期属性上报会同时存在。 */
        private final List<PublishedMessage> publishedMessages = new CopyOnWriteArrayList<>();

        /** 最近命令订阅 Topic。 */
        private String subscriptionTopic;

        /** 最近命令订阅 QoS。 */
        private int subscriptionQos;

        /** 模拟 Broker 向该已认证连接投递下行命令的回调。 */
        private MqttDeviceMessageHandler messageHandler;

        /** 全部订阅及其回调；网关须同时订阅配置与命令两棵下行 Topic。 */
        private final Map<String, MqttDeviceMessageHandler> subscriptions = new java.util.LinkedHashMap<>();

        /** 是否模拟 Broker 拒绝此设备的命令订阅。 */
        private boolean failSubscription;

        /** 是否已经关闭。 */
        private boolean closed;

        /** 发布后不自动 PUBACK，改为挂起由测试手动完成；用于证明「PUBACK 前不计数」。 */
        private boolean holdPublish;

        /** 挂起的交付收据。 */
        private final List<CompletableFuture<Void>> heldDeliveries = new CopyOnWriteArrayList<>();

        /** 记录测试连接代次，首次连接已完成。 */
        private long connectionGeneration = 1L;

        /** 最近连接完成时刻。 */
        private Instant lastConnectedAt = Instant.now();

        /** {@inheritDoc} */
        @Override
        public void subscribe(String topic, int qos, MqttDeviceMessageHandler handler) {
            if (failSubscription) {
                throw new IllegalStateException("模拟订阅失败");
            }
            this.subscriptionTopic = topic;
            this.subscriptionQos = qos;
            this.messageHandler = handler;
            this.subscriptions.put(topic, handler);
        }

        /** {@inheritDoc} */
        @Override
        public CompletableFuture<Void> publish(String topic, byte[] payload, int qos, boolean retained) {
            publishedMessages.add(new PublishedMessage(topic, new String(payload, StandardCharsets.UTF_8), qos, retained));
            if (topic.endsWith("/up/property/report")) {
                factory.published.countDown();
            }
            if (holdPublish) {
                // 模拟「已发起但 Broker 尚未回 PUBACK」：future 保持未完成，等测试手动 complete。
                CompletableFuture<Void> held = new CompletableFuture<>();
                heldDeliveries.add(held);
                return held;
            }
            // 默认直接以「已 PUBACK」完成，模拟正常 Broker 即时交付。
            return CompletableFuture.completedFuture(null);
        }

        /** 把当前挂起的交付全部标记为 PUBACK 成功。 */
        private void completeHeldDeliveries() {
            heldDeliveries.forEach(delivery -> delivery.complete(null));
            heldDeliveries.clear();
        }

        /** {@inheritDoc} */
        @Override
        public void forceConnectionLoss() {
            connectionGeneration++;
            lastConnectedAt = Instant.now();
        }

        /** {@inheritDoc} */
        @Override
        public boolean isConnected() {
            return !closed;
        }

        /** {@inheritDoc} */
        @Override
        public long connectionGeneration() {
            return connectionGeneration;
        }

        /** {@inheritDoc} */
        @Override
        public Instant lastConnectedAt() {
            return lastConnectedAt;
        }

        /** 向已注册订阅同步投递一条 Broker 消息。 */
        private void receive(String topic, byte[] payload) {
            MqttDeviceMessageHandler handler = subscriptions.entrySet().stream()
                    .filter(entry -> subscriptionMatches(entry.getKey(), topic))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(messageHandler);
            if (handler == null) {
                throw new IllegalStateException("尚未建立命令订阅");
            }
            handler.onMessage(topic, payload);
        }

        /** @return 测试所需的精确 Topic 或尾部 {@code #} 前缀匹配结果 */
        private boolean subscriptionMatches(String subscription, String topic) {
            return subscription.endsWith("#")
                    ? topic.startsWith(subscription.substring(0, subscription.length() - 1))
                    : subscription.equals(topic);
        }

        /** 返回已发布消息的稳定快照。 */
        private List<PublishedMessage> publishedMessages() {
            return List.copyOf(publishedMessages);
        }

        /** {@inheritDoc} */
        @Override
        public void close() {
            closed = true;
        }
    }

    /** 测试侧保留发布边界的完整可观察字段。 */
    private record PublishedMessage(String topic, String payload, int qos, boolean retained) {
    }
}
