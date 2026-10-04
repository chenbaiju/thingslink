package com.things.link.simulator.infrastructure.ota;

import com.things.link.ota.application.OtaDownloadRequestCodec;
import com.things.link.ota.application.OtaDownloadResponseCodec;
import com.things.link.ota.application.OtaInstallStopOperationCodec;
import com.things.link.ota.application.OtaInstallStopOperationReportCodec;
import com.things.link.ota.application.OtaJobProgressCodec;
import com.things.link.ota.application.OtaNotificationCodec;
import com.things.link.simulator.application.MqttDeviceClient;
import com.things.link.simulator.application.ota.OtaFaultPlan;
import com.things.link.simulator.application.ota.OtaJournalRecord;
import com.things.link.simulator.application.ota.OtaRangeServer;
import com.things.link.simulator.application.ota.OtaReasonCode;
import com.things.link.simulator.application.ota.OtaStage;
import com.things.link.simulator.application.ota.contract.OtaDeviceTopics;
import com.things.link.simulator.application.ota.contract.OtaWireTestVectors;
import com.things.link.simulator.application.ota.session.OtaDeviceRuntime;
import com.things.link.simulator.application.ota.session.OtaSimulatorEvidence;
import com.things.link.simulator.infrastructure.mqtt.PahoMqttDeviceClientFactory;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S13-4d-2b-2a：在<b>真实 MQTT Broker</b> 上钉住已交付的设备侧 OTA 接线。
 *
 * <p><b>为什么必须换掉假客户端：</b>{@code OtaDeviceRuntimeTests} 用共享假客户端证明了状态机与耐久裁决，
 * 但假客户端既不会经过真实 TCP 连接与 Paho 网络线程，也不会经过 Broker 的订阅/转发与 QoS 1 PUBACK。
 * 「回调线程约束」「真实投递顺序」「下行字节确实来自平台权威编码器」都只有真实链路才能证伪，
 * 所以本类刻意不复用任何假客户端。</p>
 *
 * <p><b>真实到哪一层：</b>真实 {@code eclipse-mosquitto:2.0.22} 容器（随机宿主端口、仅该端口匿名）、
 * 真实 {@link PahoMqttDeviceClientFactory} 建出的设备连接、真实本地 {@code HttpServer} 的 HTTP Range
 * 取字节、真实每设备临时目录里的 {@code staging.bin}/{@code ota.log}，以及真实墙钟。</p>
 *
 * <p><b>测试侧「平台」不是平台实现：</b>本地没有受控签名器，无法产生真实的批次、作业、下载授权与
 * 提交许可，因此平台侧派发<b>明确不在本片范围内</b>。本类里的下行报文由一个测试侧的第二个真实 Paho
 * 客户端扮演：它用 {@code things-link-ota} 的平台权威编解码器编码下行、解码设备上行，只证明
 * 「设备侧接线在真实 Broker 上能按平台线格式对话」，不产生任何「平台已派发」的事实。</p>
 */
class OtaDeviceRuntimeBrokerTests {

    /** 与本地 deploy MQTT 协议代际一致的真实 Broker；测试配置只在随机映射端口允许匿名。 */
    private static final DockerImageName MOSQUITTO_IMAGE =
            DockerImageName.parse("eclipse-mosquitto:2.0.22");

    /** 场景项目短标识；必须匹配 OTA Topic 标识符字符集。 */
    private static final String PROJECT_KEY = "ota_broker_project";

    /** 测试设备 Access Token；匿名 Broker 不校验，取值只为让真实连接选项完整。 */
    private static final String ACCESS_TOKEN = "ota-broker-token";

    /** 关闭匿名访问的平台合同只允许 attemptNo=1。 */
    private static final int ATTEMPT_NO = 1;

    /** 可升级通知的原始阶段期限；固定值让下行字节可复现。 */
    private static final Instant NOTIFICATION_DEADLINE = Instant.parse("2026-09-12T12:00:00Z");

    /** 停止操作期限；取自平台闭集内的合法 Unix 秒。 */
    private static final long STOP_EXPIRES_AT = 1_900_000_000L;

    /** 被测 artifact；4096 字节足以产生真实 HTTP Range 往返，又远小于单分片上限。 */
    private static final byte[] ARTIFACT = OtaWireTestVectors.artifact(4096);

    /** 平台权威下载申请解码器；设备发出的字节必须能被它接受。 */
    private static final OtaDownloadRequestCodec PLATFORM_DOWNLOAD_REQUEST = new OtaDownloadRequestCodec();

    /** 平台权威进度解码器；设备发出的进度必须能被它接受。 */
    private static final OtaJobProgressCodec PLATFORM_JOB_PROGRESS = new OtaJobProgressCodec();

    /** 平台权威停止操作编解码器。 */
    private static final OtaInstallStopOperationCodec PLATFORM_STOP_OPERATION = new OtaInstallStopOperationCodec();

    /** 平台权威停止报告解码器。 */
    private static final OtaInstallStopOperationReportCodec PLATFORM_STOP_REPORT =
            new OtaInstallStopOperationReportCodec();

    /** 平台权威可升级通知编码器。 */
    private static final OtaNotificationCodec PLATFORM_NOTIFICATION = new OtaNotificationCodec();

    /** 单次断言的最长等待；真实链路正常在毫秒级收敛，超时即失败而不是挂住。 */
    private static final Duration BROKER_TIMEOUT = Duration.ofSeconds(20);

    /** 整个类共用一台真实 Broker，避免每个用例重复启动容器拖长真实链路回归。 */
    private static GenericContainer<?> broker;

    /** 每个用例独立的临时基目录；设备目录、暂存区与耐久日志都落在这里。 */
    @TempDir
    Path dir;

    /** 启动一次真实 Broker；配置与 S7 故障验收完全一致，只在随机端口放开匿名。 */
    @BeforeAll
    static void startBroker() {
        broker = new GenericContainer<>(MOSQUITTO_IMAGE)
                .withExposedPorts(1883)
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("mosquitto-s7.conf"),
                        "/mosquitto/config/mosquitto.conf")
                .waitingFor(Wait.forListeningPort())
                .withStartupTimeout(Duration.ofSeconds(30));
        broker.start();
    }

    /** 关闭真实 Broker；容器不跨类复用，避免残留会话影响其它测试。 */
    @AfterAll
    static void stopBroker() {
        if (broker != null && broker.isRunning()) {
            broker.stop();
        }
    }

    /**
     * 场景 A：平台权威编码的通知经真实 Broker 下行，设备必须在真实 Broker 上发布平台可解码的下载申请。
     */
    @Test
    void publishesDownloadRequestThroughRealBrokerForPlatformEncodedNotification() throws Exception {
        OtaWireTestVectors.SignedManifest manifest = OtaWireTestVectors.signedManifest(ARTIFACT);
        UUID jobId = UUID.randomUUID();
        try (DeviceUnderTest device = startDevice("ota_available_device")) {
            byte[] notification = availableNotification(jobId, manifest);
            device.platform().publish(OtaNotificationCodec.topic(PROJECT_KEY, device.deviceKey()), notification);

            String requestTopic =
                    OtaDeviceTopics.forDevice(OtaDeviceTopics.DOWNLOAD_REQUEST, PROJECT_KEY, device.deviceKey());
            awaitBroker("设备应在真实 Broker 上发布 up/ota/download/request", BROKER_TIMEOUT,
                    () -> !messagesOn(device.platform(), requestTopic).isEmpty());

            BrokerMessage delivered = messagesOn(device.platform(), requestTopic).getFirst();
            assertThat(delivered.qos()).isEqualTo(1);
            assertThat(delivered.retained()).isFalse();
            OtaDownloadRequestCodec.Request request =
                    PLATFORM_DOWNLOAD_REQUEST.decode(delivered.payload()).value();
            // 合同版本是平台闭集字段；设备必须原样使用平台版本串而不是自造。
            assertThat(request.contractVersion()).isEqualTo("tc-ota-download-request/v1");
            assertThat(request.requestId()).isNotNull();
            assertThat(request.jobId()).isEqualTo(jobId);
            assertThat(request.attemptNo()).isEqualTo(ATTEMPT_NO);
            assertThat(request.manifestSha256()).isEqualTo(manifest.sha256());
        }
    }

    /**
     * 场景 B：平台权威编码的下载响应经真实 Broker 下行，设备用真实 HTTP Range 取字节并上报
     * {@code VERIFYING}/{@code INSTALLING}/{@code REBOOTING} 进度，耐久日志落到 {@code COMMITTED}。
     *
     * <p><b>启动身份的真实语义（ADR0131，登记债 D-156）：</b>设备完成安装并重启后必须换新会话，
     * 因此安装前三帧携带 {@code VERIFYING} 冻结的原身份，而越过重启边界后持久化的 {@code boot-id}
     * 必须变为另一个值；本用例在真实 Broker 上同时钉住这两件事。</p>
     */
    @Test
    void downloadsVerifiesInstallsAndCommitsThroughRealBrokerAndRealHttpArtifact() throws Exception {
        OtaWireTestVectors.SignedManifest manifest = OtaWireTestVectors.signedManifest(ARTIFACT);
        UUID jobId = UUID.randomUUID();
        try (OtaRangeServer server = new OtaRangeServer(ARTIFACT);
             DeviceUnderTest device = startDevice("ota_download_device")) {
            // 首条 VERIFYING 冻结本次启动身份；安装边界之前的所有帧都必须保持它。
            UUID frozenBootId = device.runtime().bootId();
            device.platform().publish(OtaNotificationCodec.topic(PROJECT_KEY, device.deviceKey()),
                    availableNotification(jobId, manifest));

            String requestTopic =
                    OtaDeviceTopics.forDevice(OtaDeviceTopics.DOWNLOAD_REQUEST, PROJECT_KEY, device.deviceKey());
            awaitBroker("设备应先发布下载申请", BROKER_TIMEOUT,
                    () -> !messagesOn(device.platform(), requestTopic).isEmpty());
            UUID requestId = PLATFORM_DOWNLOAD_REQUEST
                    .decode(messagesOn(device.platform(), requestTopic).getFirst().payload()).value().requestId();

            OtaWireTestVectors.DownloadPair pair = OtaWireTestVectors.downloadResponse(manifest, requestId, jobId,
                    ATTEMPT_NO, server.artifactUri(), true);
            // 本地真实测试服务器只能绑定回环明文地址，因此设备侧与测试侧平台编码器都必须显式允许该例外。
            byte[] responseBytes = new OtaDownloadResponseCodec(true).encode(pair.platform());
            assertThat(new OtaDownloadResponseCodec(true).decode(responseBytes).requestId()).isEqualTo(requestId);
            device.platform().publish(OtaDownloadResponseCodec.topic(PROJECT_KEY, device.deviceKey()), responseBytes);

            String progressTopic =
                    OtaDeviceTopics.forDevice(OtaDeviceTopics.PROGRESS, PROJECT_KEY, device.deviceKey());
            awaitBroker("设备应经真实 Broker 上报 VERIFYING/INSTALLING/REBOOTING 三个阶段", BROKER_TIMEOUT,
                    () -> progressStages(device.platform(), progressTopic)
                            .containsAll(List.of("VERIFYING", "INSTALLING", "REBOOTING")));

            List<OtaJobProgressCodec.Progress> progress = progressOn(device.platform(), progressTopic);
            assertThat(progress).extracting(OtaJobProgressCodec.Progress::stage)
                    .containsExactly("VERIFYING", "INSTALLING", "REBOOTING");
            assertThat(progress).extracting(OtaJobProgressCodec.Progress::progressSeq)
                    .containsExactly(1L, 2L, 3L);
            for (OtaJobProgressCodec.Progress stage : progress) {
                assertThat(stage.jobId()).isEqualTo(jobId);
                assertThat(stage.attemptNo()).isEqualTo(ATTEMPT_NO);
                assertThat(stage.authorizationId()).isEqualTo(pair.authorizationId());
                assertThat(stage.bootId()).as("安装前三个阶段必须保持首条VERIFYING冻结的原启动身份")
                        .isEqualTo(frozenBootId);
                assertThat(stage.evidence().artifactSha256()).isEqualTo(OtaWireTestVectors.sha256Hex(ARTIFACT));
                assertThat(stage.evidence().artifactSize()).isEqualTo(ARTIFACT.length);
                // 模拟器没有被显式告知健康事实，因此任何阶段都不得声称自检或看门狗通过。
                assertThat(stage.evidence().selfTestPassed()).isFalse();
                assertThat(stage.evidence().watchdogHealthy()).isFalse();
            }
            assertThat(progress.get(0).evidence().verification()).isEqualTo("NOT_STARTED");
            assertThat(progress.get(1).evidence().verification()).isEqualTo("PASSED");
            assertThat(progress.get(2).evidence().verification()).isEqualTo("PASSED");

            // artifact 确实经真实 HTTP Range 取回，并逐字节写进真实暂存区文件。
            assertThat(server.rangeRequests()).isNotEmpty();
            assertThat(server.rangeRequests().getFirst()).isEqualTo("bytes=0-");
            assertThat(Files.readAllBytes(device.runtime().stagingFile())).isEqualTo(ARTIFACT);

            List<OtaJournalRecord> records = journalRecords(device.runtime());
            assertThat(records).extracting(OtaJournalRecord::stage).containsSubsequence(
                    OtaStage.DISPATCHED, OtaStage.DOWNLOADING, OtaStage.VERIFYING, OtaStage.INSTALLING,
                    OtaStage.REBOOTING, OtaStage.COMMITTED);
            assertThat(records.getLast().conclusion()).isEqualTo(OtaStage.COMMITTED);
            assertThat(records.getLast().downloadedBytes()).isEqualTo(ARTIFACT.length);

            // 设备越过真实重启边界后持久化启动身份必须轮换；这是 ADR0131 要求 HEALTH_CHECKING 携带
            // 新 bootId 的设备侧前提，旧行为（构造时生成一次并跨重启恒等）在本断言下会失败。
            awaitBroker("安装越过真实重启边界后设备必须轮换持久化启动身份", BROKER_TIMEOUT,
                    () -> !device.runtime().bootId().equals(frozenBootId));
            assertThat(device.runtime().bootId()).as("重启边界轮换后的启动身份必须不同于安装前冻结值")
                    .isNotEqualTo(frozenBootId);
        }
    }

    /**
     * 场景 C-1：尚未进入任何安全阶段时到达的停止命令必须赢，且报告经真实 Broker 上行。
     */
    @Test
    void stopBeforeSafetyStageWinsThroughRealBroker() throws Exception {
        OtaWireTestVectors.SignedManifest manifest = OtaWireTestVectors.signedManifest(ARTIFACT);
        UUID jobId = UUID.randomUUID();
        try (DeviceUnderTest device = startDevice("ota_stop_before_device")) {
            device.platform().publish(OtaNotificationCodec.topic(PROJECT_KEY, device.deviceKey()),
                    availableNotification(jobId, manifest));
            String requestTopic =
                    OtaDeviceTopics.forDevice(OtaDeviceTopics.DOWNLOAD_REQUEST, PROJECT_KEY, device.deviceKey());
            awaitBroker("设备应先发布下载申请，证明作业已在途", BROKER_TIMEOUT,
                    () -> !messagesOn(device.platform(), requestTopic).isEmpty());

            OtaInstallStopOperationCodec.Operation operation = stopOperation(jobId, manifest.sha256());
            byte[] operationBytes = PLATFORM_STOP_OPERATION.encode(operation);
            String operationSha256 = PLATFORM_STOP_OPERATION.decode(operationBytes).sha256();
            String reportTopic = OtaDeviceTopics.forDevice(
                    OtaDeviceTopics.INSTALL_STOP_OPERATION_REPORT, PROJECT_KEY, device.deviceKey());
            device.platform().publish(
                    OtaDeviceTopics.forDevice(OtaDeviceTopics.INSTALL_STOP_OPERATION, PROJECT_KEY,
                            device.deviceKey()), operationBytes);

            awaitBroker("设备应经真实 Broker 上报停止操作报告", BROKER_TIMEOUT,
                    () -> !messagesOn(device.platform(), reportTopic).isEmpty());
            OtaInstallStopOperationReportCodec.Report report = PLATFORM_STOP_REPORT
                    .decode(messagesOn(device.platform(), reportTopic).getFirst().payload()).value();
            assertThat(report.status()).isEqualTo("STOPPED");
            assertThat(report.operationId()).isEqualTo(operation.operationId());
            assertThat(report.jobId()).isEqualTo(jobId);
            assertThat(report.attemptNo()).isEqualTo(ATTEMPT_NO);
            assertThat(report.operationSha256()).isEqualTo(operationSha256);
            assertThat(report.manifestSha256()).isEqualTo(manifest.sha256());
            assertThat(report.bootId()).isEqualTo(device.runtime().bootId());
            assertThat(report.evidence().stopBaselineSha256()).isEqualTo(operation.stopBaselineSha256());
            assertThat(report.evidence().stopOperations()).hasSize(1);
            assertThat(report.evidence().stopOperations().getFirst().operationId())
                    .isEqualTo(operation.operationId());
            assertThat(report.evidence().stopOperations().getFirst().state()).isEqualTo("STOPPED");
            assertThat(report.evidence().stopOperations().getFirst().acceptedRevision())
                    .isLessThanOrEqualTo(report.evidence().journalRevision());
            assertThat(report.evidence().installOperations()).isEmpty();
            assertThat(report.evidence().writeState()).isEqualTo("QUIESCENT");

            assertThat(journalRecords(device.runtime()).getLast().reasonCode())
                    .isEqualTo(OtaReasonCode.CANCELLED_BEFORE_SAFETY_STAGE);
            // 停止先赢后设备只发报告、不再发任何进度。
            assertThat(progressOn(device.platform(), OtaDeviceTopics.forDevice(
                    OtaDeviceTopics.PROGRESS, PROJECT_KEY, device.deviceKey()))).isEmpty();
        }
    }

    /**
     * 场景 C-2：执行已经越过 {@code INSTALLING} 后到达的停止命令不得声称干净取消，必须报告安装先赢，
     * 并携带平台真实下发过的下载授权（而不是设备自造的身份）。
     */
    @Test
    void stopAfterInstallingReportsInstallWonThroughRealBroker() throws Exception {
        OtaWireTestVectors.SignedManifest manifest = OtaWireTestVectors.signedManifest(ARTIFACT);
        UUID jobId = UUID.randomUUID();
        try (OtaRangeServer server = new OtaRangeServer(ARTIFACT);
             DeviceUnderTest device = startDevice("ota_stop_after_device")) {
            device.platform().publish(OtaNotificationCodec.topic(PROJECT_KEY, device.deviceKey()),
                    availableNotification(jobId, manifest));
            String requestTopic =
                    OtaDeviceTopics.forDevice(OtaDeviceTopics.DOWNLOAD_REQUEST, PROJECT_KEY, device.deviceKey());
            awaitBroker("设备应先发布下载申请", BROKER_TIMEOUT,
                    () -> !messagesOn(device.platform(), requestTopic).isEmpty());
            UUID requestId = PLATFORM_DOWNLOAD_REQUEST
                    .decode(messagesOn(device.platform(), requestTopic).getFirst().payload()).value().requestId();

            OtaWireTestVectors.DownloadPair pair = OtaWireTestVectors.downloadResponse(manifest, requestId, jobId,
                    ATTEMPT_NO, server.artifactUri(), true);
            device.platform().publish(OtaDownloadResponseCodec.topic(PROJECT_KEY, device.deviceKey()),
                    new OtaDownloadResponseCodec(true).encode(pair.platform()));

            String progressTopic =
                    OtaDeviceTopics.forDevice(OtaDeviceTopics.PROGRESS, PROJECT_KEY, device.deviceKey());
            awaitBroker("设备应上报 INSTALLING，证明安全阶段已打开", BROKER_TIMEOUT,
                    () -> progressStages(device.platform(), progressTopic).contains("INSTALLING"));
            assertThat(journalRecords(device.runtime())).extracting(OtaJournalRecord::stage)
                    .contains(OtaStage.INSTALLING);

            OtaInstallStopOperationCodec.Operation operation = stopOperation(jobId, manifest.sha256());
            byte[] operationBytes = PLATFORM_STOP_OPERATION.encode(operation);
            String operationSha256 = PLATFORM_STOP_OPERATION.decode(operationBytes).sha256();
            String reportTopic = OtaDeviceTopics.forDevice(
                    OtaDeviceTopics.INSTALL_STOP_OPERATION_REPORT, PROJECT_KEY, device.deviceKey());
            device.platform().publish(
                    OtaDeviceTopics.forDevice(OtaDeviceTopics.INSTALL_STOP_OPERATION, PROJECT_KEY,
                            device.deviceKey()), operationBytes);

            awaitBroker("设备应经真实 Broker 上报停止操作报告", BROKER_TIMEOUT,
                    () -> !messagesOn(device.platform(), reportTopic).isEmpty());
            OtaInstallStopOperationReportCodec.Report report = PLATFORM_STOP_REPORT
                    .decode(messagesOn(device.platform(), reportTopic).getFirst().payload()).value();
            assertThat(report.status()).isEqualTo("INSTALL_WON");
            assertThat(report.operationId()).isEqualTo(operation.operationId());
            assertThat(report.operationSha256()).isEqualTo(operationSha256);
            assertThat(report.manifestSha256()).isEqualTo(manifest.sha256());
            assertThat(report.evidence().writeState()).isEqualTo("WRITING");
            assertThat(report.evidence().stopOperations()).isEmpty();
            assertThat(report.evidence().installOperations()).hasSize(1);
            assertThat(report.evidence().installOperations().getFirst().authorizationId())
                    .isEqualTo(pair.authorizationId());
            assertThat(report.evidence().installOperations().getFirst().state()).isEqualTo("INSTALL_ACCEPTED");

            assertThat(journalRecords(device.runtime()).getLast().reasonCode())
                    .isEqualTo(OtaReasonCode.CANCEL_REFUSED_AFTER_SAFETY_STAGE);
        }
    }

    /**
     * 建立单台设备的真实接线：真实工厂建连、真实运行时订阅、真实文件目录，并接上测试侧平台端。
     *
     * <p>构造失败必须回收已建连接，否则一个订阅失败会留下占用 Broker 的裸连接，污染后续用例。</p>
     *
     * @param deviceKey 本用例独占的设备短标识
     * @return 可关闭的真实设备场景
     */
    private DeviceUnderTest startDevice(String deviceKey) throws Exception {
        PahoMqttDeviceClientFactory factory = new PahoMqttDeviceClientFactory();
        MqttDeviceClient client = null;
        PlatformSideClient platform = null;
        try {
            client = factory.connect(brokerUri(), PROJECT_KEY, deviceKey, ACCESS_TOKEN);
            // 每设备独立目录：真实 staging.bin 与 ota.log 都落在这里；回环明文例外只服务本地真实 HTTP 服务器。
            OtaDeviceRuntime runtime = new OtaDeviceRuntime(PROJECT_KEY, deviceKey, client,
                    dir.resolve("ota").resolve(deviceKey), OtaSimulatorEvidence.DEFAULT, false, true,
                    new SystemOtaClock(), HttpRangeArtifactSource::new, OtaFaultPlan.NONE);
            runtime.start();
            platform = new PlatformSideClient(brokerUri(), PROJECT_KEY, deviceKey);
            return new DeviceUnderTest(deviceKey, factory, client, runtime, platform);
        } catch (Exception failure) {
            if (platform != null) {
                platform.close();
            }
            if (client != null) {
                client.close();
            }
            factory.shutdown();
            throw failure;
        }
    }

    /** @return 真实 Broker 的随机宿主端口地址 */
    private static String brokerUri() {
        return "tcp://" + broker.getHost() + ":" + broker.getMappedPort(1883);
    }

    /** 构造平台权威编码的可升级通知字节。 */
    private static byte[] availableNotification(UUID jobId, OtaWireTestVectors.SignedManifest manifest) {
        return PLATFORM_NOTIFICATION.encode(new OtaNotificationCodec.Notification("tc-ota-available/v1",
                UUID.randomUUID(), UUID.randomUUID(), jobId, manifest.firmwareId(), ATTEMPT_NO,
                manifest.sha256(), NOTIFICATION_DEADLINE));
    }

    /** 构造平台权威编码的停止操作；空授权列表是平台合同允许的取值。 */
    private static OtaInstallStopOperationCodec.Operation stopOperation(UUID jobId, String manifestSha256) {
        return new OtaInstallStopOperationCodec.Operation("tc-ota-install-stop-operation/v1", UUID.randomUUID(),
                UUID.randomUUID(), jobId, ATTEMPT_NO, manifestSha256, List.of(),
                OtaWireTestVectors.sha256Hex("ota-broker-stop-baseline".getBytes(StandardCharsets.UTF_8)),
                7L, STOP_EXPIRES_AT);
    }

    /** 从耐久日志读回全部记录；调用点都在设备停止写入之后，因此不会观察到半行。 */
    private static List<OtaJournalRecord> journalRecords(OtaDeviceRuntime runtime) {
        return new FileOtaStateJournal(runtime.journalFile()).read();
    }

    /** 取测试侧平台端在某个 Topic 上真实收到的全部下行报文。 */
    private static List<BrokerMessage> messagesOn(PlatformSideClient platform, String topic) {
        return platform.received().stream().filter(message -> message.topic().equals(topic)).toList();
    }

    /** 用平台权威解码器读出设备上报的阶段名，按真实进度序号排序。 */
    private static List<String> progressStages(PlatformSideClient platform, String progressTopic) {
        return messagesOn(platform, progressTopic).stream()
                .map(message -> PLATFORM_JOB_PROGRESS.decode(message.payload()).value().stage())
                .toList();
    }

    /** 用平台权威解码器读出设备上报的完整进度，按真实进度序号排序。 */
    private static List<OtaJobProgressCodec.Progress> progressOn(PlatformSideClient platform, String progressTopic) {
        return messagesOn(platform, progressTopic).stream()
                .map(message -> PLATFORM_JOB_PROGRESS.decode(message.payload()).value())
                .sorted(Comparator.comparingLong(OtaJobProgressCodec.Progress::progressSeq))
                .toList();
    }

    /**
     * 有界等待真实 Broker 上的可见事实。
     *
     * <p>不使用固定 sleep：条件成立即返回；超时抛 {@link AssertionError} 并带上等待目标，绝不永久挂住。</p>
     *
     * @param description 等待的设备/平台可见事实
     * @param timeout 最长等待
     * @param condition 轮询条件
     * @throws InterruptedException 线程被中断时
     */
    private static void awaitBroker(String description, Duration timeout, BooleanSupplier condition)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20L);
        }
        if (condition.getAsBoolean()) {
            return;
        }
        throw new AssertionError("真实 Broker 未在 " + timeout.toSeconds() + " 秒内满足: " + description);
    }

    /** 一条经真实 Broker 投递到测试侧平台端的报文；payload 防御复制。 */
    private record BrokerMessage(String topic, byte[] payload, int qos, boolean retained) {

        /** 冻结字节数组，避免后续读取被 Paho 复用缓冲影响。 */
        private BrokerMessage {
            payload = payload.clone();
        }

        /** @return 独立字节副本 */
        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }

    /**
     * 测试侧扮演的「平台」：第二个真实 Paho 客户端，订阅设备 {@code up/ota/#} 并发布平台下行字节。
     *
     * <p>它<b>不是</b> ThingsLink 平台实现：本地没有受控签名器，平台侧派发不在本片范围内。这里只使用
     * 平台权威编解码器编解码，因此任何字段/版本漂移都会以解码失败的形式暴露，而不是靠测试自洽蒙混。</p>
     */
    private static final class PlatformSideClient implements AutoCloseable {

        /** 真实 Paho 同步客户端：QoS 1 发布在返回前等 PUBACK，订阅在返回前等 SUBACK。 */
        private final MqttClient client;

        /** 按到达顺序记录的设备上行报文。 */
        private final List<BrokerMessage> received = new CopyOnWriteArrayList<>();

        /**
         * 建连并订阅本设备全部 {@code up/ota/#} 上行。
         *
         * @param brokerUri 真实 Broker 地址
         * @param projectKey 项目短标识
         * @param deviceKey 设备短标识
         * @throws MqttException 建连或订阅失败时
         */
        PlatformSideClient(String brokerUri, String projectKey, String deviceKey) throws MqttException {
            this.client = new MqttClient(brokerUri, "ota-test-platform-" + UUID.randomUUID(),
                    new MemoryPersistence());
            MqttConnectOptions options = new MqttConnectOptions();
            options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
            client.connect(options);
            client.subscribe("tc/v1/" + projectKey + "/" + deviceKey + "/up/ota/#", 1,
                    (topic, message) -> received.add(
                            new BrokerMessage(topic, message.getPayload(), message.getQos(), message.isRetained())));
        }

        /**
         * 以 QoS 1 发布平台下行字节；同步方法返回时 Broker 已回 PUBACK。
         *
         * @param topic 完整下行 Topic
         * @param payload 平台权威编码字节
         * @throws MqttException 发布失败时
         */
        void publish(String topic, byte[] payload) throws MqttException {
            MqttMessage message = new MqttMessage(payload);
            message.setQos(1);
            client.publish(topic, message);
        }

        /** @return 已真实收到的设备上行快照 */
        List<BrokerMessage> received() {
            return List.copyOf(received);
        }

        /** 有界断开并释放网络资源。 */
        @Override
        public void close() {
            try {
                if (client.isConnected()) {
                    client.disconnect(1_000);
                }
                client.close();
            } catch (MqttException exception) {
                throw new IllegalStateException("测试侧平台客户端关闭失败", exception);
            }
        }
    }

    /**
     * 单台设备在真实 Broker 上的完整接线；关闭时先停测试侧平台端，再关设备连接。
     *
     * <p>{@link OtaDeviceRuntime} 没有独立生命周期方法：它的订阅挂在底层连接上，关闭客户端即停止运行时。</p>
     */
    private static final class DeviceUnderTest implements AutoCloseable {

        /** 本场景设备短标识。 */
        private final String deviceKey;

        /** 真实连接工厂；关闭时终止共享重连与消息执行器。 */
        private final PahoMqttDeviceClientFactory factory;

        /** 真实设备连接。 */
        private final MqttDeviceClient client;

        /** 已接线的设备运行时。 */
        private final OtaDeviceRuntime runtime;

        /** 测试侧平台端。 */
        private final PlatformSideClient platform;

        /**
         * @param deviceKey 设备短标识
         * @param factory 真实连接工厂
         * @param client 真实设备连接
         * @param runtime 已接线的设备运行时
         * @param platform 测试侧平台端
         */
        DeviceUnderTest(String deviceKey, PahoMqttDeviceClientFactory factory, MqttDeviceClient client,
                        OtaDeviceRuntime runtime, PlatformSideClient platform) {
            this.deviceKey = deviceKey;
            this.factory = factory;
            this.client = client;
            this.runtime = runtime;
            this.platform = platform;
        }

        /** @return 设备短标识 */
        String deviceKey() {
            return deviceKey;
        }

        /** @return 已接线的设备运行时 */
        OtaDeviceRuntime runtime() {
            return runtime;
        }

        /** @return 测试侧平台端 */
        PlatformSideClient platform() {
            return platform;
        }

        /** 关闭测试侧平台端、设备连接与连接工厂共享线程。 */
        @Override
        public void close() {
            platform.close();
            client.close();
            factory.shutdown();
        }
    }
}
