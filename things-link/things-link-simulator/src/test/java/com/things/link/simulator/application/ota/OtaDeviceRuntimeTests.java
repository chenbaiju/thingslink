package com.things.link.simulator.application.ota;

import com.things.link.ota.application.OtaCommitReceiptCodec;
import com.things.link.ota.application.OtaDownloadRequestCodec;
import com.things.link.ota.application.OtaDownloadResponseCodec;
import com.things.link.ota.application.OtaInstallStopOperationCodec;
import com.things.link.ota.application.OtaInstallStopOperationReportCodec;
import com.things.link.ota.application.OtaJobProgressCodec;
import com.things.link.simulator.api.dto.SimulationRequest;
import com.things.link.simulator.application.DeviceSimulator;
import com.things.link.simulator.application.MqttDeviceClient;
import com.things.link.simulator.application.MqttDeviceClientFactory;
import com.things.link.simulator.application.ota.contract.OtaDeviceAvailableNotificationCodec;
import com.things.link.simulator.application.ota.contract.OtaDeviceCommitPermitCodec;
import com.things.link.simulator.application.ota.contract.OtaDeviceDownloadResponseCodec;
import com.things.link.simulator.application.ota.contract.OtaDeviceInstallStopOperationCodec;
import com.things.link.simulator.application.ota.contract.OtaDeviceInstallStopStatusQueryCodec;
import com.things.link.simulator.application.ota.contract.OtaDeviceInstallStopStatusReportCodec;
import com.things.link.simulator.application.ota.contract.OtaDeviceTopics;
import com.things.link.simulator.application.ota.contract.OtaWireTestVectors;
import com.things.link.simulator.application.ota.session.OtaDeviceRuntime;
import com.things.link.simulator.application.ota.session.OtaSimulatorEvidence;
import com.things.link.simulator.infrastructure.ota.FileOtaStateJournal;
import com.things.link.simulator.infrastructure.ota.HttpRangeArtifactSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S13-4d-2b-1：设备侧 OTA 运行时接线与「停止先赢 / 安装先赢」耐久裁决。
 *
 * <p>所有用例都走真实生产路径：{@link DeviceSimulator} 的真实连接生命周期订阅、真实的 OTA 合同编解码器、
 * 真实文件日志与暂存区、真实本地 HTTP Range 服务器。被替换的只有「Broker」（共享假
 * {@link FakeMqttDeviceClient}）与时钟（{@link OtaScenarioClock}），因此没有任何真实等待。</p>
 *
 * <p>平台侧事实只以平台自己的编解码器为准：设备发出的下载申请与停止报告都用 {@code things-link-ota}
 * 的权威解码器复核（该依赖是 test scope，永不进入生产依赖树）。</p>
 */
class OtaDeviceRuntimeTests {

    /** 场景使用的项目短标识。 */
    private static final String PROJECT_KEY = "project_1";

    /** 场景使用的设备短标识。 */
    private static final String DEVICE_KEY = "device_1";

    /** 平台闭集允许的四个进度阶段。 */
    private static final Set<String> PROGRESS_STAGES =
            Set.of("VERIFYING", "INSTALLING", "REBOOTING", "HEALTH_CHECKING");

    /** 停止操作期限；取自冻结闭集内的合法 Unix 秒。 */
    private static final long STOP_EXPIRES_AT = 1_900_000_000L;

    /** 平台权威停止报告解码器；设备发出的报告必须能被它接受。 */
    private static final OtaInstallStopOperationReportCodec PLATFORM_STOP_REPORT =
            new OtaInstallStopOperationReportCodec();

    /** OTA artifact 单次取字节上限的实现默认值（1MiB）。 */
    private static final long DEFAULT_CHUNK_BYTES = 1L << 20;

    /** 每个用例独立的基目录。 */
    @TempDir
    Path dir;

    /** 被测模拟器；在用例内构造以便注入 OTA 配置。 */
    private DeviceSimulator simulator;

    /** 每个用例结束都停止，避免调度线程影响后续断言。 */
    @AfterEach
    void stopSimulator() {
        if (simulator != null) {
            simulator.stop();
        }
    }

    /**
     * (a) 真实生命周期 happy path：通知入站 → 下载申请出站 → 下载响应入站 → 真实 HTTP 取字节 →
     * 耐久日志落盘 → 平台闭集进度出站。
     */
    @Test
    void drivesFullOtaLifecycleThroughRealSubscriptionsAndHttpArtifact() throws Exception {
        byte[] artifact = OtaWireTestVectors.artifact(2048);
        try (OtaRangeServer server = new OtaRangeServer(artifact)) {
            Scenario scenario = startScenario(true);
            FakeMqttDeviceClient client = scenario.client();

            // 四个 OTA 下行 Topic 必须与既有的命令订阅共生，且都用 QoS 1。
            assertThat(client.handlers()).contains(
                    OtaDeviceTopics.forDevice(OtaDeviceTopics.AVAILABLE, PROJECT_KEY, DEVICE_KEY),
                    OtaDeviceTopics.forDevice(OtaDeviceTopics.DOWNLOAD_RESPONSE, PROJECT_KEY, DEVICE_KEY),
                    OtaDeviceTopics.forDevice(OtaDeviceTopics.INSTALL_STOP_OPERATION, PROJECT_KEY, DEVICE_KEY),
                    OtaDeviceTopics.forDevice(OtaDeviceTopics.INSTALL_STOP_STATUS_QUERY, PROJECT_KEY, DEVICE_KEY),
                    "tc/v1/project_1/device_1/down/command/#");
            assertThat(client.subscriptionQos().values()).containsOnly(1);

            // 首条 VERIFYING 冻结本次启动身份；安装前的三个阶段都必须保持它（ADR0131）。
            UUID frozenBootId = scenario.bootId();

            OtaWireTestVectors.SignedManifest manifest = OtaWireTestVectors.signedManifest(artifact);
            UUID jobId = UUID.randomUUID();
            client.deliver(OtaDeviceTopics.forDevice(OtaDeviceTopics.AVAILABLE, PROJECT_KEY, DEVICE_KEY),
                    availableNotification(jobId, manifest.sha256()));

            // 设备必须发出下载申请，且平台权威解码器能解开它的字节。
            List<FakeMqttDeviceClient.Publication> requests = publicationsOn(client,
                    OtaDeviceTopics.forDevice(OtaDeviceTopics.DOWNLOAD_REQUEST, PROJECT_KEY, DEVICE_KEY));
            assertThat(requests).hasSize(1);
            OtaDownloadRequestCodec.Request request =
                    new OtaDownloadRequestCodec().decode(requests.getFirst().payload()).value();
            assertThat(request.jobId()).isEqualTo(jobId);
            assertThat(request.attemptNo()).isEqualTo(1);
            assertThat(request.manifestSha256()).isEqualTo(manifest.sha256());

            OtaWireTestVectors.DownloadPair pair = OtaWireTestVectors.downloadResponse(manifest, request.requestId(),
                    jobId, 1, server.artifactUri(), true);
            byte[] responseBytes = new OtaDeviceDownloadResponseCodec(true).encode(pair.device());
            // 同一组值必须能被平台侧编解码器复核：设备收到的下行报文与平台发出的完全一致。
            assertThat(new OtaDownloadResponseCodec(true).decode(responseBytes).requestId())
                    .isEqualTo(request.requestId());
            client.deliver(OtaDeviceTopics.forDevice(OtaDeviceTopics.DOWNLOAD_RESPONSE, PROJECT_KEY, DEVICE_KEY),
                    responseBytes);

            // artifact 确实经真实 HTTP Range 取回，并逐字节写进暂存区；日志落在设备目录里。
            assertThat(server.rangeRequests()).containsExactly("bytes=0-");
            assertThat(Files.readAllBytes(scenario.stagingFile())).isEqualTo(artifact);
            assertThat(scenario.journalFile()).exists();

            List<OtaJournalRecord> records = scenario.journalRecords();
            assertThat(records).extracting(OtaJournalRecord::stage).containsSubsequence(
                    OtaStage.DISPATCHED, OtaStage.DOWNLOADING, OtaStage.VERIFYING, OtaStage.INSTALLING,
                    OtaStage.REBOOTING, OtaStage.COMMITTED);
            assertThat(records.getLast().conclusion()).isEqualTo(OtaStage.COMMITTED);
            assertThat(records.getLast().downloadedBytes()).isEqualTo(artifact.length);

            // 进度只上报平台闭集阶段；默认不声明健康硬件事实，因此停在 REBOOTING。
            List<FakeMqttDeviceClient.Publication> progressPublishes = publicationsOn(client,
                    OtaDeviceTopics.forDevice(OtaDeviceTopics.PROGRESS, PROJECT_KEY, DEVICE_KEY));
            List<String> stages = new ArrayList<>();
            for (FakeMqttDeviceClient.Publication publication : progressPublishes) {
                assertThat(publication.qos()).isEqualTo(1);
                assertThat(publication.retained()).isFalse();
                OtaJobProgressCodec.Progress progress = new OtaJobProgressCodec().decode(publication.payload()).value();
                assertThat(PROGRESS_STAGES).contains(progress.stage());
                stages.add(progress.stage());
                assertThat(progress.jobId()).isEqualTo(jobId);
                assertThat(progress.authorizationId()).isEqualTo(pair.authorizationId());
                assertThat(progress.evidence().artifactSha256()).isEqualTo(OtaWireTestVectors.sha256Hex(artifact));
                assertThat(progress.evidence().artifactSize()).isEqualTo(artifact.length);
                assertThat(progress.evidence().selfTestPassed()).isFalse();
                assertThat(progress.evidence().watchdogHealthy()).isFalse();
                // 默认不声明健康：没有 HEALTH_CHECKING 帧，但 REBOOTING 仍必须保持 VERIFYING 冻结的原身份。
                assertThat(progress.bootId()).as("安装前三个阶段的启动身份必须等于首条VERIFYING冻结的原身份")
                        .isEqualTo(frozenBootId);
            }
            assertThat(stages).containsExactly("VERIFYING", "INSTALLING", "REBOOTING");
            // 安装已走到终态COMMITTED：设备确实越过了重启边界，持久化启动身份必须已轮换。
            assertThat(scenario.bootId()).as("完成安装后持久化启动身份必须轮换为重启后的新会话")
                    .isNotEqualTo(frozenBootId);
        }
    }

    /**
     * (a)(b)(c) ADR0131 的真实重启边界（登记债 D-156）：安装后的 {@code HEALTH_CHECKING} 必须携带
     * <b>新</b> bootId，且同一目录上的新运行时复用这个最新持久化取值。
     *
     * <p>为什么必须用 {@code assertHealth=true}：默认策略不声明健康硬件事实，运行时不会发
     * {@code HEALTH_CHECKING} 帧，也就无法证明「健康阶段带新身份」这条 ADR0131 不变量。这里显式声明
     * 健康事实，让运行时自己走完整重启边界，而不是由测试代发一帧（旧的 S13-4d-2b-2b-2 做法）。</p>
     *
     * <p>本用例同时覆盖三个可证伪点：(a) 完成安装后持久化启动身份恰好轮换一次；(b) 平台权威解码器
     * 读出的 {@code INSTALLING}/{@code REBOOTING} 帧仍是首条冻结的原身份，而 {@code HEALTH_CHECKING}
     * 帧是新身份；(c) 同一设备目录上的全新运行时复用最后一次持久化身份，不会因为没有新安装而再生成
     * 第三个会话。</p>
     */
    @Test
    void rotatesBootSessionAtRealRebootBoundaryAndReusesLatestPersistedIdentity() throws Exception {
        byte[] artifact = OtaWireTestVectors.artifact(2048);
        try (OtaRangeServer server = new OtaRangeServer(artifact)) {
            Scenario scenario = startScenario(true, new OtaScenarioClock(OtaTestFixtures.START),
                    HttpRangeArtifactSource::new, OtaFaultPlan.NONE, true);
            FakeMqttDeviceClient client = scenario.client();
            UUID frozenBootId = scenario.bootId();
            OtaWireTestVectors.SignedManifest manifest = OtaWireTestVectors.signedManifest(artifact);
            UUID jobId = UUID.randomUUID();
            client.deliver(OtaDeviceTopics.forDevice(OtaDeviceTopics.AVAILABLE, PROJECT_KEY, DEVICE_KEY),
                    availableNotification(jobId, manifest.sha256()));
            UUID requestId = new OtaDownloadRequestCodec().decode(publicationsOn(client,
                    OtaDeviceTopics.forDevice(OtaDeviceTopics.DOWNLOAD_REQUEST, PROJECT_KEY, DEVICE_KEY))
                    .getFirst().payload()).value().requestId();
            OtaWireTestVectors.DownloadPair pair = OtaWireTestVectors.downloadResponse(manifest, requestId, jobId, 1,
                    server.artifactUri(), true);
            // 假客户端在同一线程同步回调：deliver 返回时设备已完成执行与新会话轮换。
            client.deliver(OtaDeviceTopics.forDevice(OtaDeviceTopics.DOWNLOAD_RESPONSE, PROJECT_KEY, DEVICE_KEY),
                    new OtaDeviceDownloadResponseCodec(true).encode(pair.device()));
            assertThat(scenario.journalRecords().getLast().conclusion()).as("设备必须先走到耐久终态COMMITTED")
                    .isEqualTo(OtaStage.COMMITTED);

            List<OtaJobProgressCodec.Progress> progress = decodedProgress(client);
            assertThat(progress).extracting(OtaJobProgressCodec.Progress::stage)
                    .as("声明健康事实后平台闭集内四个阶段必须都被上报")
                    .containsExactly("VERIFYING", "INSTALLING", "REBOOTING", "HEALTH_CHECKING");

            UUID rotatedBootId = scenario.bootId();
            assertThat(rotatedBootId).as("完成安装并越过真实重启边界后，持久化启动身份必须变成新会话")
                    .isNotEqualTo(frozenBootId);
            assertThat(progress).extracting(OtaJobProgressCodec.Progress::bootId)
                    .as("VERIFYING/INSTALLING/REBOOTING保持冻结原身份，只有HEALTH_CHECKING携带新身份"
                            + "（启动身份必须恰好轮换一次）")
                    .containsExactly(frozenBootId, frozenBootId, frozenBootId, rotatedBootId);
            OtaJobProgressCodec.Progress health = progress.getLast();
            assertThat(health.evidence().selfTestPassed()).as("健康阶段必须声明自检通过").isTrue();
            assertThat(health.evidence().watchdogHealthy()).as("健康阶段必须声明看门狗健康").isTrue();
            assertThat(health.evidence().activeSlot()).as("健康阶段的活动槽必须已切到目标槽B").isEqualTo("B");

            // (c) 同一目录上的全新运行时只复用已持久化的最新身份，绝不因为“重启”而再生成一个会话。
            OtaDeviceRuntime fresh = new OtaDeviceRuntime(PROJECT_KEY, DEVICE_KEY, new FakeMqttDeviceClient(),
                    scenario.deviceDirectory(), OtaSimulatorEvidence.DEFAULT, false, true,
                    new OtaScenarioClock(OtaTestFixtures.START), HttpRangeArtifactSource::new, OtaFaultPlan.NONE);
            assertThat(fresh.bootId()).as("同一设备目录上的新运行时必须复用持久化的最新启动身份")
                    .isEqualTo(rotatedBootId);
        }
    }

    /**
     * S13-4d-2b-2b-4（关闭 D-155）：设备只在消费到匹配的平台提交许可后产生真实提交回执；
     * 任何身份、启动会话、期限或目标元组不符都必须零发布。
     *
     * <p><b>为什么这是运行时级证据而不是编解码器测试：</b>回执里的 {@code permitId}、{@code bootId}、
     * 授权/清单与 {@code committedSecurityVersion} 必须来自「真实下载 → 真实安装 → 真实重启 →
     * 消费真实许可」这条链，任何一环缺失都会让平台看到的提交变成伪造。这里先让设备经真实 HTTP Range
     * 走完整条链到耐久 {@code COMMITTED}，再用平台权威回执解码器复核设备真实发出的字节。</p>
     *
     * <p><b>为什么每条负例都必须零新增：</b>设备一旦对不匹配的许可也发回执，平台就会把一次并未发生的
     * 安装采用为成功。因此每一条拒绝条件都用同一个发送前/发送后的回执计数断言，绝不放宽为「多半不会发」。</p>
     *
     * @throws Exception 真实 HTTP/文件任一步失败时抛出
     */
    @Test
    void publishesCommitReceiptOnlyForPermitMatchingDurableCommit() throws Exception {
        byte[] artifact = OtaWireTestVectors.artifact(2048);
        String artifactSha256 = OtaWireTestVectors.sha256Hex(artifact);
        try (OtaRangeServer server = new OtaRangeServer(artifact)) {
            Scenario scenario = startScenario(true, new OtaScenarioClock(OtaTestFixtures.START),
                    HttpRangeArtifactSource::new, OtaFaultPlan.NONE, true);
            FakeMqttDeviceClient client = scenario.client();
            OtaWireTestVectors.SignedManifest manifest = OtaWireTestVectors.signedManifest(artifact);
            UUID jobId = UUID.randomUUID();

            client.deliver(OtaDeviceTopics.forDevice(OtaDeviceTopics.AVAILABLE, PROJECT_KEY, DEVICE_KEY),
                    availableNotification(jobId, manifest.sha256()));
            UUID requestId = new OtaDownloadRequestCodec().decode(publicationsOn(client,
                    OtaDeviceTopics.forDevice(OtaDeviceTopics.DOWNLOAD_REQUEST, PROJECT_KEY, DEVICE_KEY))
                    .getFirst().payload()).value().requestId();
            OtaWireTestVectors.DownloadPair pair = OtaWireTestVectors.downloadResponse(manifest, requestId, jobId, 1,
                    server.artifactUri(), true);
            client.deliver(OtaDeviceTopics.forDevice(OtaDeviceTopics.DOWNLOAD_RESPONSE, PROJECT_KEY, DEVICE_KEY),
                    new OtaDeviceDownloadResponseCodec(true).encode(pair.device()));

            // 前置：设备必须真的越过安装后重启边界到达耐久COMMITTED，启动身份必须已轮换。
            assertThat(scenario.journalRecords().getLast().conclusion())
                    .as("提交回执的前置是设备已耐久走到COMMITTED，而不是下载成功").isEqualTo(OtaStage.COMMITTED);
            UUID committedBootId = scenario.bootId();

            UUID permitId = UUID.randomUUID();
            client.deliver(OtaDeviceTopics.forDevice(OtaDeviceTopics.COMMIT_PERMIT, PROJECT_KEY, DEVICE_KEY),
                    commitPermit(permitId, jobId, 1, pair.authorizationId(), manifest.sha256(), committedBootId,
                            1L, artifactSha256, "B", 1_900_000_000L));

            List<OtaCommitReceiptCodec.Receipt> receipts = commitReceipts(client);
            assertThat(receipts).as("匹配的提交许可必须产生恰一条真实提交回执").hasSize(1);
            OtaCommitReceiptCodec.Receipt receipt = receipts.getFirst();
            assertThat(receipt.permitId()).as("回执必须引用被消费的许可，而不是设备自造一个")
                    .isEqualTo(permitId);
            assertThat(receipt.jobId()).as("回执必须绑定设备已提交的作业").isEqualTo(jobId);
            assertThat(receipt.attemptNo()).isEqualTo(1);
            assertThat(receipt.authorizationId()).as("回执必须回显设备记忆的平台授权")
                    .isEqualTo(pair.authorizationId());
            assertThat(receipt.manifestSha256()).as("回执必须回显设备记忆的清单摘要")
                    .isEqualTo(manifest.sha256());
            assertThat(receipt.bootId()).as("回执必须携带设备当前（安装后）启动身份").isEqualTo(committedBootId);
            assertThat(receipt.receiptId()).as("回执身份必须由设备本次新生成").isNotEqualTo(permitId);
            assertThat(receipt.evidence().committedSecurityVersion())
                    .as("提交回执必须声明已提交安全版本等于目标安全版本")
                    .isEqualTo(receipt.evidence().securityVersion());
            assertThat(receipt.evidence().artifactSha256()).isEqualTo(artifactSha256);
            assertThat(receipt.evidence().targetSlot()).isEqualTo("B");
            assertThat(receipt.evidence().selfTestPassed()).as("已提交证据必须声明安装后自检通过").isTrue();

            // 逐条拒绝条件：每次投递后回执总数都不得增加。
            assertNoNewCommitReceipt(client, 1, "许可指向不同作业",
                    commitPermit(UUID.randomUUID(), UUID.randomUUID(), 1, pair.authorizationId(),
                            manifest.sha256(), committedBootId, 1L, artifactSha256, "B", 1_900_000_000L));
            assertNoNewCommitReceipt(client, 1, "许可指向不同授权",
                    commitPermit(UUID.randomUUID(), jobId, 1, UUID.randomUUID(), manifest.sha256(),
                            committedBootId, 1L, artifactSha256, "B", 1_900_000_000L));
            assertNoNewCommitReceipt(client, 1, "许可尝试号与已提交尝试不符",
                    commitPermit(UUID.randomUUID(), jobId, 2, pair.authorizationId(), manifest.sha256(),
                            committedBootId, 1L, artifactSha256, "B", 1_900_000_000L));
            assertNoNewCommitReceipt(client, 1, "许可清单摘要与已提交尝试不符",
                    commitPermit(UUID.randomUUID(), jobId, 1, pair.authorizationId(),
                            OtaWireTestVectors.sha256Hex("other-manifest".getBytes(StandardCharsets.UTF_8)),
                            committedBootId, 1L, artifactSha256, "B", 1_900_000_000L));
            assertNoNewCommitReceipt(client, 1, "设备从未记住该尝试的授权",
                    commitPermit(UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID(),
                            OtaWireTestVectors.sha256Hex("never-downloaded".getBytes(StandardCharsets.UTF_8)),
                            committedBootId, 1L, artifactSha256, "B", 1_900_000_000L));
            assertNoNewCommitReceipt(client, 1, "许可启动会话不是设备当前会话",
                    commitPermit(UUID.randomUUID(), jobId, 1, pair.authorizationId(), manifest.sha256(),
                            UUID.randomUUID(), 1L, artifactSha256, "B", 1_900_000_000L));
            assertNoNewCommitReceipt(client, 1, "许可已过期",
                    commitPermit(UUID.randomUUID(), jobId, 1, pair.authorizationId(), manifest.sha256(),
                            committedBootId, 1L, artifactSha256, "B", 1L));
            assertNoNewCommitReceipt(client, 1, "许可目标安全版本与已安装版本不符",
                    commitPermit(UUID.randomUUID(), jobId, 1, pair.authorizationId(), manifest.sha256(),
                            committedBootId, 2L, artifactSha256, "B", 1_900_000_000L));
            assertNoNewCommitReceipt(client, 1, "许可artifact摘要与已安装镜像不符",
                    commitPermit(UUID.randomUUID(), jobId, 1, pair.authorizationId(), manifest.sha256(),
                            committedBootId, 1L,
                            OtaWireTestVectors.sha256Hex("other-artifact".getBytes(StandardCharsets.UTF_8)),
                            "B", 1_900_000_000L));
            assertNoNewCommitReceipt(client, 1, "许可目标槽位与已安装目标槽不符",
                    commitPermit(UUID.randomUUID(), jobId, 1, pair.authorizationId(), manifest.sha256(),
                            committedBootId, 1L, artifactSha256, "A", 1_900_000_000L));
        }
    }

    /** 投递一条许可，并断言设备没有新增任何提交回执。 */
    private static void assertNoNewCommitReceipt(FakeMqttDeviceClient client, int expected, String condition,
            byte[] permit) {
        client.deliver(OtaDeviceTopics.forDevice(OtaDeviceTopics.COMMIT_PERMIT, PROJECT_KEY, DEVICE_KEY), permit);
        assertThat(commitReceipts(client)).as("拒绝条件[" + condition + "]下设备不得发布任何提交回执")
                .hasSize(expected);
    }

    /** 用平台权威回执解码器读取设备真实发出的提交回执。 */
    private static List<OtaCommitReceiptCodec.Receipt> commitReceipts(FakeMqttDeviceClient client) {
        return publicationsOn(client, OtaDeviceTopics.forDevice(OtaDeviceTopics.COMMIT_RECEIPT, PROJECT_KEY,
                DEVICE_KEY))
                .stream()
                .map(publication -> new OtaCommitReceiptCodec().decode(publication.payload()).value())
                .toList();
    }

    /** 构造平台可解码的提交许可字节。 */
    private static byte[] commitPermit(UUID permitId, UUID jobId, int attemptNo, UUID authorizationId,
            String manifestSha256, UUID bootId, long securityVersion, String artifactSha256, String targetSlot,
            long expiresAt) {
        return OtaDeviceCommitPermitCodec.encode(new OtaDeviceCommitPermitCodec.Permit(
                OtaDeviceCommitPermitCodec.CONTRACT_VERSION, permitId, jobId, attemptNo, authorizationId,
                manifestSha256, bootId, securityVersion, artifactSha256, targetSlot, expiresAt));
    }

    /** (b) 默认关闭：不订阅任何 OTA Topic，也不写任何 OTA 目录；既有上报行为不变。 */
    @Test
    void leavesOtaUnwiredWhenDisabledByDefault() throws Exception {        Scenario scenario = startScenario(false);
        FakeMqttDeviceClient client = scenario.client();

        assertThat(client.handlers()).containsExactly("tc/v1/project_1/device_1/down/command/#");
        assertThat(client.handlers()).noneMatch(topic -> topic.contains("/ota/"));
        assertThat(Files.exists(scenario.otaRoot())).isFalse();

        awaitPropertyReport(client);
        assertThat(client.publications()).allSatisfy(publication ->
                assertThat(publication.topic()).doesNotContain("/ota/"));
    }

    /** (c) 停止先赢：未进入任何安全阶段时停止，发布 STOPPED 报告且此后再无进度。 */
    @Test
    void stopWinsBeforeSafetyStageAndSilencesFurtherProgress() throws Exception {
        byte[] artifact = OtaWireTestVectors.artifact(2048);
        try (OtaRangeServer server = new OtaRangeServer(artifact)) {
            Scenario scenario = startScenario(true);
            FakeMqttDeviceClient client = scenario.client();
            OtaWireTestVectors.SignedManifest manifest = OtaWireTestVectors.signedManifest(artifact);
            UUID jobId = UUID.randomUUID();
            client.deliver(OtaDeviceTopics.forDevice(OtaDeviceTopics.AVAILABLE, PROJECT_KEY, DEVICE_KEY),
                    availableNotification(jobId, manifest.sha256()));
            // 下载响应尚未送达：设备没有任何安全阶段记录，停止必须赢。
            OtaDeviceInstallStopOperationCodec.Decoded operation = stopOperation(jobId, manifest.sha256());

            client.deliver(OtaDeviceTopics.forDevice(OtaDeviceTopics.INSTALL_STOP_OPERATION, PROJECT_KEY, DEVICE_KEY),
                    operation.canonical());

            OtaInstallStopOperationReportCodec.Report report = stopReports(client).getFirst();
            assertThat(report.status()).isEqualTo("STOPPED");
            assertThat(report.operationId()).isEqualTo(operation.value().operationId());
            assertThat(report.operationSha256()).isEqualTo(operation.sha256());
            assertThat(report.manifestSha256()).isEqualTo(manifest.sha256());
            assertThat(report.evidence().stopOperations()).hasSize(1);
            assertThat(report.evidence().stopOperations().getFirst().operationId())
                    .isEqualTo(operation.value().operationId());
            assertThat(report.evidence().stopOperations().getFirst().state()).isEqualTo("STOPPED");
            assertThat(report.evidence().stopOperations().getFirst().acceptedRevision())
                    .isLessThanOrEqualTo(report.evidence().journalRevision());
            assertThat(report.evidence().installOperations()).isEmpty();
            assertThat(report.evidence().writeState()).isEqualTo("QUIESCENT");

            // 停止先赢必须落到耐久日志，且此后不再有任何进度上报、也不再取字节。
            assertThat(scenario.journalRecords().getLast().reasonCode())
                    .isEqualTo(OtaReasonCode.CANCELLED_BEFORE_SAFETY_STAGE);
            assertThat(scenario.journalRecords().getLast().cancelAccepted()).isTrue();
            assertThat(publicationsOn(client, OtaDeviceTopics.forDevice(OtaDeviceTopics.PROGRESS, PROJECT_KEY,
                    DEVICE_KEY))).isEmpty();
            assertThat(server.rangeRequests()).isEmpty();
        }
    }

    /** (d) 安装先赢：安全阶段已经打开后到达的停止不得声称干净取消。 */
    @Test
    void installWinsAfterSafetyStageAndNeverClaimsCleanCancellation() throws Exception {
        byte[] artifact = OtaWireTestVectors.artifact(2048);
        try (OtaRangeServer server = new OtaRangeServer(artifact)) {
            Scenario scenario = startScenario(true);
            FakeMqttDeviceClient client = scenario.client();
            OtaWireTestVectors.SignedManifest manifest = OtaWireTestVectors.signedManifest(artifact);
            UUID jobId = UUID.randomUUID();
            client.deliver(OtaDeviceTopics.forDevice(OtaDeviceTopics.AVAILABLE, PROJECT_KEY, DEVICE_KEY),
                    availableNotification(jobId, manifest.sha256()));
            UUID requestId = new OtaDownloadRequestCodec().decode(publicationsOn(client,
                    OtaDeviceTopics.forDevice(OtaDeviceTopics.DOWNLOAD_REQUEST, PROJECT_KEY, DEVICE_KEY))
                    .getFirst().payload()).value().requestId();
            OtaWireTestVectors.DownloadPair pair = OtaWireTestVectors.downloadResponse(manifest, requestId, jobId, 1,
                    server.artifactUri(), true);
            client.deliver(OtaDeviceTopics.forDevice(OtaDeviceTopics.DOWNLOAD_RESPONSE, PROJECT_KEY, DEVICE_KEY),
                    new OtaDeviceDownloadResponseCodec(true).encode(pair.device()));

            // 设备已经越过 INSTALLING：日志里存在安全阶段，停止只能报告安装先赢。
            assertThat(scenario.journalRecords()).extracting(OtaJournalRecord::stage)
                    .contains(OtaStage.INSTALLING, OtaStage.REBOOTING);
            OtaDeviceInstallStopOperationCodec.Decoded operation = stopOperation(jobId, manifest.sha256());

            client.deliver(OtaDeviceTopics.forDevice(OtaDeviceTopics.INSTALL_STOP_OPERATION, PROJECT_KEY, DEVICE_KEY),
                    operation.canonical());

            OtaInstallStopOperationReportCodec.Report report = stopReports(client).getFirst();
            assertThat(report.status()).isEqualTo("INSTALL_WON");
            assertThat(report.evidence().installOperations()).hasSize(1);
            assertThat(report.evidence().installOperations().getFirst().authorizationId())
                    .isEqualTo(pair.authorizationId());
            assertThat(report.evidence().installOperations().getFirst().state()).isEqualTo("INSTALL_ACCEPTED");
            assertThat(report.evidence().stopOperations()).isEmpty();
            assertThat(report.evidence().writeState()).isEqualTo("WRITING");
            assertThat(scenario.journalRecords().getLast().reasonCode())
                    .isEqualTo(OtaReasonCode.CANCEL_REFUSED_AFTER_SAFETY_STAGE);
            assertThat(scenario.journalRecords().getLast().cancelAccepted()).isFalse();
        }
    }

    /**
     * (e) 同一设备目录内重启：必须从耐久日志续传，而不是从零重新下载；启动身份的语义按 ADR0131
     * （登记债 D-156）分成两段。
     *
     * <p><b>为什么第二次运行开始时 bootId 必须与第一次相同：</b>第一次运行在 {@code DOWNLOADING}
     * 掉电，日志从未越过安装后重启边界，因此持久化的启动身份没有被轮换。新版运行时只在
     * {@code REBOOTING} 帧发出之后、{@code HEALTH_CHECKING} 证据构造之前轮换身份，所以「重启进程但没有
     * 发生新安装」只会复用同一目录里持久化的最新取值，而不会凭空生成第三个会话。</p>
     *
     * <p><b>为什么恢复执行结束后 bootId 必须变化：</b>第二次运行从已确认偏移续传并最终走到
     * {@code COMMITTED}，途中真实越过了安装后重启边界，因此持久化身份恰好轮换一次；这正是旧语义
     * （跨重启恒等）错误、新语义（重启边界轮换）正确的地方。</p>
     */
    @Test
    void resumesFromJournalAfterRestartInSameDeviceDirectory() throws Exception {
        byte[] artifact = OtaWireTestVectors.artifact(2048);
        try (OtaRangeServer server = new OtaRangeServer(artifact)) {
            // 第一次运行：注入下载阶段掉电，状态机在第一个分片后停机并保留已确认偏移与暂存前缀。
            OtaScenarioClock firstClock = new OtaScenarioClock(OtaTestFixtures.START);
            Scenario first = startScenario(true, firstClock, () -> prefixOnlySource(server, 1024),
                    OtaFaultPlan.builder().powerLossAt(OtaStage.DOWNLOADING).build());
            // 第一次运行开始时的持久化身份；后续断言必须与这个快照比较，而不是与会随文件变化的读取比较。
            UUID originBootId = first.bootId();
            OtaWireTestVectors.SignedManifest manifest = OtaWireTestVectors.signedManifest(artifact);
            UUID jobId = UUID.randomUUID();
            first.client().deliver(OtaDeviceTopics.forDevice(OtaDeviceTopics.AVAILABLE, PROJECT_KEY, DEVICE_KEY),
                    availableNotification(jobId, manifest.sha256()));
            UUID requestId = new OtaDownloadRequestCodec().decode(publicationsOn(first.client(),
                    OtaDeviceTopics.forDevice(OtaDeviceTopics.DOWNLOAD_REQUEST, PROJECT_KEY, DEVICE_KEY))
                    .getFirst().payload()).value().requestId();
            OtaWireTestVectors.DownloadPair pair = OtaWireTestVectors.downloadResponse(manifest, requestId, jobId, 1,
                    server.artifactUri(), true);
            first.client().deliver(OtaDeviceTopics.forDevice(OtaDeviceTopics.DOWNLOAD_RESPONSE, PROJECT_KEY,
                    DEVICE_KEY), new OtaDeviceDownloadResponseCodec(true).encode(pair.device()));

            // 掉电点已耐久记录：日志停在 DOWNLOADING，暂存区只含已确认前缀。
            assertThat(server.rangeRequests()).containsExactly("bytes=0-");
            OtaJournalRecord interrupted = first.journalRecords().getLast();
            assertThat(interrupted.stage()).isEqualTo(OtaStage.DOWNLOADING);
            assertThat(interrupted.reasonCode()).isEqualTo(OtaReasonCode.POWER_LOST);
            assertThat(interrupted.downloadedBytes()).isEqualTo(1024L);
            assertThat(Files.size(first.stagingFile())).isEqualTo(1024L);

            // 第二次运行：同一设备目录、新的模拟器对象（等价于同目录重启），端口换回真实实现。
            simulator.stop();
            server.clearObservations();
            OtaScenarioClock secondClock = new OtaScenarioClock(OtaTestFixtures.START.plusSeconds(60));
            Scenario second = startScenario(true, secondClock, HttpRangeArtifactSource::new, OtaFaultPlan.NONE);
            assertThat(second.journalFile()).isEqualTo(first.journalFile());
            // 掉电发生在下载阶段，尚未越过重启边界：新运行时只复用第一次已持久化的启动身份。
            assertThat(second.bootId()).as("未发生新安装的同目录重启必须复用持久化的最新启动身份")
                    .isEqualTo(originBootId);
            second.client().deliver(OtaDeviceTopics.forDevice(OtaDeviceTopics.AVAILABLE, PROJECT_KEY, DEVICE_KEY),
                    availableNotification(jobId, manifest.sha256()));
            // D-161：同目录重启在 start() 时已经按耐久受理记录自己重投过一次同一尝试的下载申请，
            // 随后到达的 available 通知再发一次；本用例继续用后者构造响应，证明两条触发路径都不破坏续传。
            List<FakeMqttDeviceClient.Publication> resumedRequests = publicationsOn(second.client(),
                    OtaDeviceTopics.forDevice(OtaDeviceTopics.DOWNLOAD_REQUEST, PROJECT_KEY, DEVICE_KEY));
            assertThat(resumedRequests).as("同目录重启必须先自己重投一次同一尝试的下载申请，再接受新的available通知")
                    .hasSize(2);
            UUID resumedRequestId = new OtaDownloadRequestCodec()
                    .decode(resumedRequests.getLast().payload()).value().requestId();
            assertThat(resumedRequestId).isNotEqualTo(requestId);
            OtaWireTestVectors.DownloadPair resumedPair = OtaWireTestVectors.downloadResponse(manifest,
                    resumedRequestId, jobId, 1, server.artifactUri(), true);
            second.client().deliver(OtaDeviceTopics.forDevice(OtaDeviceTopics.DOWNLOAD_RESPONSE, PROJECT_KEY,
                    DEVICE_KEY), new OtaDeviceDownloadResponseCodec(true).encode(resumedPair.device()));

            // 恢复执行必须从日志已确认的偏移继续，而不是从 0 重来；暂存区最终等于完整 artifact。
            assertThat(server.rangeRequests()).containsExactly("bytes=1024-");
            assertThat(Files.readAllBytes(second.stagingFile())).isEqualTo(artifact);
            assertThat(second.journalRecords().getLast().conclusion()).isEqualTo(OtaStage.COMMITTED);
            assertThat(second.journalRecords()).extracting(OtaJournalRecord::stage)
                    .containsSubsequence(OtaStage.DOWNLOADING, OtaStage.VERIFYING, OtaStage.INSTALLING);
            // 恢复执行越过了真实重启边界：持久化启动身份必须在恢复期间恰好轮换一次。
            assertThat(second.bootId()).as("恢复执行完成安装后，持久化启动身份必须轮换为重启后的新会话")
                    .isNotEqualTo(originBootId);
        }
    }

    /**
     * D-161 设备半：同目录重启只在「耐久受理记录存在且耐久日志显示该尝试尚未收束」时重投下载申请。
     *
     * <p>四个真实边界逐一验证：①掉电于下载阶段后重启，{@code start()} 在没有任何 available 通知前就发出
     * 一次同一 (jobId/attemptNo/manifestSha256)、新 requestId 的下载申请；②平台回包仍回显耐久受理记录里的
     * 原 requestId 时，设备从耐久偏移续传并走到 {@code COMMITTED}；③尝试已收束后重启不再发报文；
     * ④删除耐久受理记录后重启同样不发报文（fail-closed）。</p>
     */
    @Test
    void requestsAcceptedResumeOnRestartOnlyWhileAttemptUnfinished() throws Exception {
        byte[] artifact = OtaWireTestVectors.artifact(2048);
        try (OtaRangeServer server = new OtaRangeServer(artifact)) {
            String requestTopic = OtaDeviceTopics.forDevice(OtaDeviceTopics.DOWNLOAD_REQUEST, PROJECT_KEY, DEVICE_KEY);
            OtaWireTestVectors.SignedManifest manifest = OtaWireTestVectors.signedManifest(artifact);
            UUID jobId = UUID.randomUUID();

            // ① 第一次运行：下载阶段掉电，耐久日志停在 DOWNLOADING/POWER_LOST 且暂存区只含已确认前缀。
            Scenario first = startScenario(true, new OtaScenarioClock(OtaTestFixtures.START),
                    () -> prefixOnlySource(server, 1024),
                    OtaFaultPlan.builder().powerLossAt(OtaStage.DOWNLOADING).build());
            first.client().deliver(OtaDeviceTopics.forDevice(OtaDeviceTopics.AVAILABLE, PROJECT_KEY, DEVICE_KEY),
                    availableNotification(jobId, manifest.sha256()));
            UUID firstRequestId = new OtaDownloadRequestCodec()
                    .decode(publicationsOn(first.client(), requestTopic).getFirst().payload()).value().requestId();
            OtaWireTestVectors.DownloadPair firstPair = OtaWireTestVectors.downloadResponse(manifest, firstRequestId,
                    jobId, 1, server.artifactUri(), true);
            first.client().deliver(OtaDeviceTopics.forDevice(OtaDeviceTopics.DOWNLOAD_RESPONSE, PROJECT_KEY, DEVICE_KEY),
                    new OtaDeviceDownloadResponseCodec(true).encode(firstPair.device()));
            assertThat(first.journalRecords().getLast().reasonCode()).isEqualTo(OtaReasonCode.POWER_LOST);
            assertThat(first.journalRecords().getLast().downloadedBytes()).isEqualTo(1024L);

            // ② 同目录重启：start() 必须在收到任何 available 通知之前就重投同一尝试。
            simulator.stop();
            server.clearObservations();
            Scenario second = startScenario(true, new OtaScenarioClock(OtaTestFixtures.START.plusSeconds(60)),
                    HttpRangeArtifactSource::new, OtaFaultPlan.NONE);
            List<FakeMqttDeviceClient.Publication> resumeRequests = publicationsOn(second.client(), requestTopic);
            assertThat(resumeRequests).as("耐久受理记录存在且尝试未收束时，重启必须自己重投一次下载申请")
                    .hasSize(1);
            OtaDownloadRequestCodec.Request resume = new OtaDownloadRequestCodec()
                    .decode(resumeRequests.getFirst().payload()).value();
            assertThat(resume.jobId()).isEqualTo(jobId);
            assertThat(resume.attemptNo()).isEqualTo(1);
            assertThat(resume.manifestSha256()).isEqualTo(manifest.sha256());
            assertThat(resume.requestId()).as("重投只生成新的请求标识，尝试身份字段保持不变")
                    .isNotEqualTo(firstRequestId);

            // 平台重投后回包仍是同一份身份（同 authorizationId/requestId/清单），设备必须从已确认偏移续传并收束。
            second.client().deliver(OtaDeviceTopics.forDevice(OtaDeviceTopics.DOWNLOAD_RESPONSE, PROJECT_KEY, DEVICE_KEY),
                    new OtaDeviceDownloadResponseCodec(true).encode(firstPair.device()));
            assertThat(server.rangeRequests()).containsExactly("bytes=1024-");
            assertThat(second.journalRecords().getLast().conclusion()).isEqualTo(OtaStage.COMMITTED);

            // ③ 尝试已收束后重启：不得再重投。
            simulator.stop();
            Scenario third = startScenario(true, new OtaScenarioClock(OtaTestFixtures.START.plusSeconds(120)),
                    HttpRangeArtifactSource::new, OtaFaultPlan.NONE);
            assertThat(publicationsOn(third.client(), requestTopic))
                    .as("耐久日志已收束的尝试不得被重启放大成新的下载申请").isEmpty();

            // ④ 没有耐久受理记录（等价于从未受理过）：同样不得发出任何申请。
            simulator.stop();
            Files.deleteIfExists(third.deviceDirectory().resolve("accepted-download"));
            Scenario fourth = startScenario(true, new OtaScenarioClock(OtaTestFixtures.START.plusSeconds(180)),
                    HttpRangeArtifactSource::new, OtaFaultPlan.NONE);
            assertThat(publicationsOn(fourth.client(), requestTopic))
                    .as("缺少耐久受理记录时必须fail-closed，绝不凭空申请升级").isEmpty();
        }
    }

    /** (d-2) 只读状态查询：必须按同一份耐久日志重放安装先赢结论，且不改写任何设备状态。 */
    @Test
    void readOnlyStatusQueryReplaysInstallWonDecisionFromJournal() throws Exception {
        byte[] artifact = OtaWireTestVectors.artifact(2048);
        try (OtaRangeServer server = new OtaRangeServer(artifact)) {
            Scenario scenario = startScenario(true);
            FakeMqttDeviceClient client = scenario.client();
            OtaWireTestVectors.SignedManifest manifest = OtaWireTestVectors.signedManifest(artifact);
            UUID jobId = UUID.randomUUID();
            client.deliver(OtaDeviceTopics.forDevice(OtaDeviceTopics.AVAILABLE, PROJECT_KEY, DEVICE_KEY),
                    availableNotification(jobId, manifest.sha256()));
            UUID requestId = new OtaDownloadRequestCodec().decode(publicationsOn(client,
                    OtaDeviceTopics.forDevice(OtaDeviceTopics.DOWNLOAD_REQUEST, PROJECT_KEY, DEVICE_KEY))
                    .getFirst().payload()).value().requestId();
            OtaWireTestVectors.DownloadPair pair = OtaWireTestVectors.downloadResponse(manifest, requestId, jobId, 1,
                    server.artifactUri(), true);
            client.deliver(OtaDeviceTopics.forDevice(OtaDeviceTopics.DOWNLOAD_RESPONSE, PROJECT_KEY, DEVICE_KEY),
                    new OtaDeviceDownloadResponseCodec(true).encode(pair.device()));
            OtaDeviceInstallStopOperationCodec.Decoded operation = stopOperation(jobId, manifest.sha256());
            client.deliver(OtaDeviceTopics.forDevice(OtaDeviceTopics.INSTALL_STOP_OPERATION, PROJECT_KEY, DEVICE_KEY),
                    operation.canonical());
            List<OtaJournalRecord> afterOperationReport = scenario.journalRecords();
            // 平台先发停止操作，随后用只读查询确认既有结论（ADR0137 的 UNKNOWN 对账路径）。
            UUID queryId = UUID.randomUUID();
            byte[] queryBytes = OtaDeviceInstallStopStatusQueryCodec.encode(
                    new OtaDeviceInstallStopStatusQueryCodec.Query(
                            OtaDeviceInstallStopStatusQueryCodec.CONTRACT_VERSION, queryId, UUID.randomUUID(),
                            operation.value().operationId(), jobId, 1, manifest.sha256(), operation.sha256(),
                            STOP_EXPIRES_AT));

            client.deliver(OtaDeviceTopics.forDevice(OtaDeviceTopics.INSTALL_STOP_STATUS_QUERY, PROJECT_KEY,
                    DEVICE_KEY), queryBytes);

            List<OtaDeviceInstallStopStatusReportCodec.Report> reports = publicationsOn(client,
                    OtaDeviceTopics.forDevice(OtaDeviceTopics.INSTALL_STOP_STATUS_REPORT, PROJECT_KEY, DEVICE_KEY))
                    .stream()
                    .map(publication -> OtaDeviceInstallStopStatusReportCodec.decode(publication.payload()).value())
                    .toList();
            assertThat(reports).hasSize(1);
            OtaDeviceInstallStopStatusReportCodec.Report report = reports.getFirst();
            assertThat(report.queryId()).isEqualTo(queryId);
            assertThat(report.status()).isEqualTo("INSTALL_WON");
            assertThat(report.operationId()).isEqualTo(operation.value().operationId());
            assertThat(report.operationSha256()).isEqualTo(operation.sha256());
            assertThat(report.evidence().installOperations()).hasSize(1);
            assertThat(report.evidence().installOperations().getFirst().authorizationId())
                    .isEqualTo(pair.authorizationId());
            // 查询是只读的：它只追加一条观察记录，不推进任何设备状态。
            assertThat(scenario.journalRecords()).hasSize(afterOperationReport.size() + 1);
            assertThat(scenario.journalRecords().getLast().reasonCode())
                    .isEqualTo(OtaReasonCode.CANCEL_REFUSED_AFTER_SAFETY_STAGE);
        }
    }

    /**
     * 构造一个只回请求区间前缀的端口：真实下载第一段后返回，让执行在分片边界继续循环。
     *
     * <p>这不是协议违例：{@code OtaArtifactSource.Chunk} 的载荷本来就允许是完整对象的尾部被按分片上限
     * 截断的一段。截断后执行会带着已耐久的新偏移再次取字节，因此配合下载阶段掉电注入，就能在确定偏移
     * 上留下「暂存区 + 日志 + 无结论」的真实中断现场。</p>
     *
     * @param server 真实 Range 服务器
     * @param prefixBytes 第一次必须只取回的前缀字节数
     * @return 只回一次前缀的端口
     */
    private static OtaArtifactSource prefixOnlySource(OtaRangeServer server, int prefixBytes) {
        return new OtaArtifactSource() {

            /** 真实 HTTP Range 端口。 */
            private final OtaArtifactSource delegate = new HttpRangeArtifactSource();

            /** 已经回过的前缀。 */
            private byte[] prefix;

            /** {@inheritDoc} */
            @Override
            public Chunk fetch(String uri, long startOffset, long expectedLength, Duration budget) {
                if (prefix == null) {
                    Chunk chunk = delegate.fetch(uri, startOffset, expectedLength, budget);
                    int length = (int) Math.min(prefixBytes, chunk.payload().length);
                    prefix = java.util.Arrays.copyOf(chunk.payload(), length);
                    return new Chunk(prefix, chunk.contentRangeAccepted(), chunk.contentRangeStart(),
                            chunk.totalLength());
                }
                // 第二次取字节本就发生在掉电边界之后；返回同一前缀即可，执行已不会再消费它。
                return new Chunk(prefix, true, startOffset, expectedLength);
            }
        };
    }

    /** 构造平台可解码的停止操作字节。 */
    private static OtaDeviceInstallStopOperationCodec.Decoded stopOperation(UUID jobId, String manifestSha256) {
        return OtaDeviceInstallStopOperationCodec.decode(OtaDeviceInstallStopOperationCodec.encode(
                new OtaDeviceInstallStopOperationCodec.Operation(OtaDeviceInstallStopOperationCodec.CONTRACT_VERSION,
                        UUID.randomUUID(), UUID.randomUUID(), jobId, 1, manifestSha256, List.of(),
                        OtaTestFixtures.sha256Hex("stop-baseline"), 7L, STOP_EXPIRES_AT)));
    }

    /** 构造平台可解码的可升级通知字节。 */
    private static byte[] availableNotification(UUID jobId, String manifestSha256) {
        return OtaDeviceAvailableNotificationCodec.encode(
                new OtaDeviceAvailableNotificationCodec.Notification(
                        OtaDeviceAvailableNotificationCodec.CONTRACT_VERSION, UUID.randomUUID(), UUID.randomUUID(),
                        jobId, UUID.randomUUID(), 1, manifestSha256, Instant.parse("2026-09-12T12:00:00Z")));
    }

    /** 取某个 Topic 上的全部发布。 */
    private static List<FakeMqttDeviceClient.Publication> publicationsOn(FakeMqttDeviceClient client, String topic) {
        return client.publications().stream().filter(publication -> publication.topic().equals(topic)).toList();
    }

    /**
     * 用<b>平台</b>停止报告解码器读取设备发出的报告字节：设备侧合同必须能被平台权威解码器接受。
     *
     * <p>设备侧解码器只用于本地断言；这里刻意换成平台解码器，避免「只有自己解得开自己」的假通过。</p>
     *
     * @param client 假客户端
     * @return 平台解码出的报告列表
     */
    private static List<OtaInstallStopOperationReportCodec.Report> stopReports(FakeMqttDeviceClient client) {
        return publicationsOn(client,
                OtaDeviceTopics.forDevice(OtaDeviceTopics.INSTALL_STOP_OPERATION_REPORT, PROJECT_KEY, DEVICE_KEY))
                .stream()
                .map(publication -> PLATFORM_STOP_REPORT.decode(publication.payload()).value())
                .toList();
    }

    /**
     * 用<b>平台</b>进度解码器读出设备上报的完整进度，按真实进度序号排序。
     *
     * <p>刻意不用设备侧解码器：平台权威解码器接受才算设备合同正确，避免「只有自己解得开自己」。</p>
     *
     * @param client 假客户端
     * @return 平台解码出的进度列表
     */
    private static List<OtaJobProgressCodec.Progress> decodedProgress(FakeMqttDeviceClient client) {
        return publicationsOn(client, OtaDeviceTopics.forDevice(OtaDeviceTopics.PROGRESS, PROJECT_KEY, DEVICE_KEY))
                .stream()
                .map(publication -> new OtaJobProgressCodec().decode(publication.payload()).value())
                .sorted(Comparator.comparingLong(OtaJobProgressCodec.Progress::progressSeq))
                .toList();
    }

    /** 等待既有的周期属性上报出现。 */
    private static void awaitPropertyReport(FakeMqttDeviceClient client) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(2);
        while (Instant.now().isBefore(deadline)) {
            if (client.publications().stream().anyMatch(publication ->
                    publication.topic().endsWith("/up/property/report"))) {
                return;
            }
            Thread.sleep(5L);
        }
        throw new AssertionError("默认关闭 OTA 时既有属性上报未发生");
    }

    /** 以 OTA 开关启动模拟器，取字节端口使用真实 HTTP Range 实现且不注入故障。 */
    private Scenario startScenario(boolean otaEnabled) throws Exception {
        return startScenario(otaEnabled, new OtaScenarioClock(OtaTestFixtures.START),
                HttpRangeArtifactSource::new, OtaFaultPlan.NONE);
    }

    /**
     * 以 OTA 开关、时钟、取字节端口工厂与故障计划启动模拟器，默认不声明健康事实。
     *
     * @param otaEnabled 是否启用 OTA 接线
     * @param clock 推进式假时钟
     * @param artifactSourceFactory 取字节端口工厂
     * @param faultPlan 下一次下载执行注入的故障计划
     * @return 可断言场景
     */
    private Scenario startScenario(boolean otaEnabled, OtaScenarioClock clock,
                                   Supplier<OtaArtifactSource> artifactSourceFactory, OtaFaultPlan faultPlan)
            throws Exception {
        return startScenario(otaEnabled, clock, artifactSourceFactory, faultPlan, false);
    }

    /**
     * 以 OTA 开关、时钟、取字节端口工厂、故障计划与健康声明策略启动模拟器。
     *
     * @param otaEnabled 是否启用 OTA 接线
     * @param clock 推进式假时钟
     * @param artifactSourceFactory 取字节端口工厂
     * @param faultPlan 下一次下载执行注入的故障计划
     * @param assertHealth 是否允许声明健康/自检事实
     * @return 可断言场景
     */
    private Scenario startScenario(boolean otaEnabled, OtaScenarioClock clock,
                                   Supplier<OtaArtifactSource> artifactSourceFactory, OtaFaultPlan faultPlan,
                                   boolean assertHealth) throws Exception {
        Path manifestDir = dir.resolve("manifest");
        Path otaDir = dir.resolve("ota");
        RecordingClientFactory factory = new RecordingClientFactory();
        DeviceSimulator.OtaRuntimeConfig otaConfig = new DeviceSimulator.OtaRuntimeConfig();
        otaConfig.setEnabled(otaEnabled);
        otaConfig.setDirectory(otaDir);
        // 本地真实 HTTP artifact 服务器只能绑定回环明文地址；这是受控测试的显式例外。
        otaConfig.setAllowInsecureLoopback(true);
        otaConfig.setArtifactSourceFactory(artifactSourceFactory);
        otaConfig.setFaultPlan(faultPlan);
        otaConfig.setAssertHealth(assertHealth);
        simulator = new DeviceSimulator(factory, new ObjectMapper(), manifestDir, "1.0.0", otaConfig, clock);
        simulator.start(new SimulationRequest("tcp://localhost:1883", PROJECT_KEY,
                List.of(new SimulationRequest.DeviceCredential(DEVICE_KEY, "secret")), 60, false));
        return new Scenario(factory.client(), otaDir.resolve(DEVICE_KEY));
    }

    /** 单个场景的可断言事实：假客户端与设备目录。 */
    private record Scenario(FakeMqttDeviceClient client, Path deviceDirectory) {

        /** @return 每设备 OTA 目录的基目录 */
        private Path otaRoot() {
            return deviceDirectory.getParent();
        }

        /** @return 本设备暂存区绝对路径 */
        private Path stagingFile() {
            return deviceDirectory.resolve("staging.bin").toAbsolutePath();
        }

        /** @return 本设备耐久日志绝对路径 */
        private Path journalFile() {
            return deviceDirectory.resolve("ota.log").toAbsolutePath();
        }

        /** @return 从耐久日志读出的全部记录 */
        private List<OtaJournalRecord> journalRecords() {
            return new FileOtaStateJournal(journalFile()).read();
        }

        /** @return 耐久启动身份 */
        private UUID bootId() {
            try {
                return UUID.fromString(Files.readString(deviceDirectory.resolve("boot-id")).trim());
            } catch (Exception failure) {
                throw new IllegalStateException("启动身份读取失败", failure);
            }
        }
    }

    /** 只创建并记录单设备假客户端的工厂。 */
    private static final class RecordingClientFactory implements MqttDeviceClientFactory {

        /** 已创建的客户端。 */
        private final List<FakeMqttDeviceClient> clients = new ArrayList<>();

        /** {@inheritDoc} */
        @Override
        public MqttDeviceClient connect(String brokerUri, String projectKey, String deviceKey, String accessToken) {
            FakeMqttDeviceClient client = new FakeMqttDeviceClient();
            clients.add(client);
            return client;
        }

        /** @return 最后创建的客户端 */
        private FakeMqttDeviceClient client() {
            return clients.getLast();
        }
    }
}
