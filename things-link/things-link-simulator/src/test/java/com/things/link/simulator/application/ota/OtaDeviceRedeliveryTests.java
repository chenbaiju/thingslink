package com.things.link.simulator.application.ota;

import com.things.link.ota.application.OtaDownloadRequestCodec;
import com.things.link.simulator.application.ota.contract.OtaDeviceAvailableNotificationCodec;
import com.things.link.simulator.application.ota.contract.OtaDeviceDownloadResponseCodec;
import com.things.link.simulator.application.ota.contract.OtaDeviceTopics;
import com.things.link.simulator.application.ota.contract.OtaWireTestVectors;
import com.things.link.simulator.application.ota.session.OtaDeviceRuntime;
import com.things.link.simulator.application.ota.session.OtaSimulatorEvidence;
import com.things.link.simulator.infrastructure.ota.FileOtaAcceptedDownloadResponseStore;
import com.things.link.simulator.infrastructure.ota.FileOtaStateJournal;
import com.things.link.simulator.infrastructure.ota.HttpRangeArtifactSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 债务 D-159 设备半边：QoS 1 重复投递的下载响应必须能凭耐久接纳身份被识别。
 *
 * <p>全部用例走真实生产路径：真实 {@link OtaDeviceRuntime} 会话、真实 OTA 合同编解码器、真实文件日志与
 * 真实文件身份记录、真实本地 HTTP Range 服务器；被替换的只有 Broker（共享假 {@link FakeMqttDeviceClient}）
 * 与时钟（{@link OtaScenarioClock}）。因此「身份是耐久的」不是内存断言，而是重新构造运行时后仍成立。</p>
 *
 * <p>本片只证明设备半边：平台仍无法重新下发同一份下载响应（那半边保持未完成），因此这里所有重投递都由
 * 测试直接投递同一份字节，不代表平台具备重发能力，也不声称端到端恢复已完成。</p>
 */
class OtaDeviceRedeliveryTests {

    /** 场景使用的项目短标识。 */
    private static final String PROJECT_KEY = "project_1";

    /** 场景使用的设备短标识。 */
    private static final String DEVICE_KEY = "device_1";

    /** 设备可升级通知下行 Topic。 */
    private static final String AVAILABLE_TOPIC =
            OtaDeviceTopics.forDevice(OtaDeviceTopics.AVAILABLE, PROJECT_KEY, DEVICE_KEY);

    /** 下载申请上行 Topic。 */
    private static final String DOWNLOAD_REQUEST_TOPIC =
            OtaDeviceTopics.forDevice(OtaDeviceTopics.DOWNLOAD_REQUEST, PROJECT_KEY, DEVICE_KEY);

    /** 下载响应下行 Topic。 */
    private static final String DOWNLOAD_RESPONSE_TOPIC =
            OtaDeviceTopics.forDevice(OtaDeviceTopics.DOWNLOAD_RESPONSE, PROJECT_KEY, DEVICE_KEY);

    /** 进度上报上行 Topic。 */
    private static final String PROGRESS_TOPIC =
            OtaDeviceTopics.forDevice(OtaDeviceTopics.PROGRESS, PROJECT_KEY, DEVICE_KEY);

    /** 与既有运行时测试一致的场景起点。 */
    private static final Instant START = OtaTestFixtures.START;

    /** 每个用例独立的基目录。 */
    @TempDir
    Path dir;

    /** R1b：真实下载后安装中断，重启只观察未知，原响应重放也不产生第二次副作用。 */
    @ParameterizedTest
    @EnumSource(value = OtaStage.class, names = {"INSTALLING", "REBOOTING", "HEALTH_CHECKING", "CONFIRMING"})
    void startupObservesInstallationUncertaintyWithoutDownloading(OtaStage stage) throws Exception {
        byte[] artifact = OtaWireTestVectors.artifact(2048);
        try (OtaRangeServer server = new OtaRangeServer(artifact)) {
            Path directory = dir.resolve(DEVICE_KEY);
            FakeMqttDeviceClient firstClient = new FakeMqttDeviceClient();
            OtaDeviceRuntime first = startRuntime(directory, firstClient, new OtaScenarioClock(START),
                    HttpRangeArtifactSource::new, OtaFaultPlan.builder().powerLossAt(stage).build());
            var manifest = OtaWireTestVectors.signedManifest(artifact);
            UUID job = UUID.randomUUID();
            firstClient.deliver(AVAILABLE_TOPIC, availableNotification(job, manifest.sha256()));
            var pair = OtaWireTestVectors.downloadResponse(manifest, requestId(firstClient), job, 1,
                    server.artifactUri(), true);
            byte[] response = new OtaDeviceDownloadResponseCodec(true).encode(pair.device());
            firstClient.deliver(DOWNLOAD_RESPONSE_TOPIC, response);
            var journal = new FileOtaStateJournal(first.journalFile());
            assertThat(journal.read().getLast().stage()).isEqualTo(stage);
            assertThat(journal.read().getLast().reasonCode()).isEqualTo(OtaReasonCode.POWER_LOST);
            int before = journal.read().size();
            byte[] originalStaging = Files.readAllBytes(first.stagingFile());
            server.clearObservations();
            FakeMqttDeviceClient recoveredClient = new FakeMqttDeviceClient();
            OtaDeviceRuntime recovered = startRuntime(directory, recoveredClient,
                    new OtaScenarioClock(START.plusSeconds(3600)), HttpRangeArtifactSource::new, OtaFaultPlan.NONE);
            assertThat(journal.read()).hasSize(before + 1);
            assertThat(journal.read().getLast().reasonCode()).isEqualTo(OtaReasonCode.INSTALL_OUTCOME_UNKNOWN);
            assertThat(journal.read().getLast().conclusion()).isEqualTo(OtaStage.SAFETY_PAUSED);
            assertThat(recoveredClient.publications()).isEmpty();
            recovered.start();
            recoveredClient.deliver(DOWNLOAD_RESPONSE_TOPIC, response);
            startRuntime(directory, new FakeMqttDeviceClient(), new OtaScenarioClock(START.plusSeconds(7200)),
                    HttpRangeArtifactSource::new, OtaFaultPlan.NONE);
            assertThat(journal.read()).hasSize(before + 1);
            assertThat(server.rangeRequests()).isEmpty();
            assertThat(recoveredClient.publications()).isEmpty();
            assertThat(Files.readAllBytes(first.stagingFile())).isEqualTo(originalStaging);
        }
    }

    /** R1b：无POWER_LOST尾标也须识别硬退出；身份缺失/错配和损坏不得补造结论。 */
    @Test
    void startupRequiresMatchingDurableIdentityAndFailsClosedOnCorruptJournal() throws Exception {
        Path directory = dir.resolve(DEVICE_KEY);
        FakeMqttDeviceClient client = new FakeMqttDeviceClient();
        OtaDeviceRuntime runtime = startRuntime(directory, client, new OtaScenarioClock(START),
                HttpRangeArtifactSource::new, OtaFaultPlan.NONE);
        var identity = new OtaAcceptedDownloadResponse(OtaAcceptedDownloadResponse.CONTRACT_VERSION,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, "a".repeat(64), "b".repeat(64), 7, START);
        var store = new FileOtaAcceptedDownloadResponseStore(runtime.acceptedResponseFile());
        var journal = new FileOtaStateJournal(runtime.journalFile());
        var record = new OtaJournalRecord(OtaStage.INSTALLING, 1, 7, "b".repeat(64), "b".repeat(64), 7,
                "a".repeat(64), START, OtaReasonCode.NONE, false);
        journal.append(record);
        startRuntime(directory, new FakeMqttDeviceClient(), new OtaScenarioClock(START),
                HttpRangeArtifactSource::new, OtaFaultPlan.NONE);
        assertThat(journal.read()).containsExactly(record);
        store.write(new OtaAcceptedDownloadResponse(identity.contractVersion(), identity.jobId(), identity.requestId(),
                identity.authorizationId(), 2, identity.manifestSha256(), identity.artifactSha256(), 7, START));
        startRuntime(directory, new FakeMqttDeviceClient(), new OtaScenarioClock(START),
                HttpRangeArtifactSource::new, OtaFaultPlan.NONE);
        assertThat(journal.read()).containsExactly(record);
        store.write(identity);
        startRuntime(directory, new FakeMqttDeviceClient(), new OtaScenarioClock(START),
                HttpRangeArtifactSource::new, OtaFaultPlan.NONE);
        assertThat(journal.read().getLast().reasonCode()).isEqualTo(OtaReasonCode.INSTALL_OUTCOME_UNKNOWN);
        OtaStateJournal failedJournal = org.mockito.Mockito.mock(OtaStateJournal.class);
        org.mockito.Mockito.when(failedJournal.read()).thenReturn(List.of(record));
        org.mockito.Mockito.doThrow(new java.io.UncheckedIOException(new java.io.IOException("fsync failed")))
                .when(failedJournal).append(org.mockito.ArgumentMatchers.any());
        var transport = org.mockito.Mockito.mock(
                com.things.link.simulator.application.ota.contract.OtaDeviceTransport.class);
        var session = new com.things.link.simulator.application.ota.session.OtaDeviceSession(
                PROJECT_KEY, DEVICE_KEY, transport, new OtaScenarioClock(START), runtime.stagingFile(),
                new HttpRangeArtifactSource(), failedJournal, store, (transition, result, seq) -> null);
        assertThatThrownBy(session::observeInterruptedInstallation).isInstanceOf(java.io.UncheckedIOException.class);
        org.mockito.Mockito.verifyNoInteractions(transport);
        Files.writeString(runtime.journalFile(), "corrupt\n");
        FakeMqttDeviceClient corruptClient = new FakeMqttDeviceClient();
        assertThatThrownBy(() -> startRuntime(directory, corruptClient, new OtaScenarioClock(START),
                HttpRangeArtifactSource::new, OtaFaultPlan.NONE)).isInstanceOf(RuntimeException.class);
        assertThat(corruptClient.publications()).isEmpty();
    }

    /**
     * 同会话重投递：首次投递在 DOWNLOADING 掉电后，逐字节重投递同一份响应必须从耐久确认偏移续传并提交。
     *
     * @throws Exception 真实 HTTP/文件任一步失败时抛出
     */
    @Test
    void resumesByteIdenticalRedeliveryInSameSessionFromDurableOffset() throws Exception {
        byte[] artifact = OtaWireTestVectors.artifact(2048);
        try (OtaRangeServer server = new OtaRangeServer(artifact)) {
            Path deviceDirectory = dir.resolve(DEVICE_KEY);
            FakeMqttDeviceClient client = new FakeMqttDeviceClient();
            OtaDeviceRuntime runtime = startRuntime(deviceDirectory, client, new OtaScenarioClock(START),
                    () -> prefixFirstChunkOnly(server, 1024),
                    OtaFaultPlan.builder().powerLossAt(OtaStage.DOWNLOADING).build());
            OtaWireTestVectors.SignedManifest manifest = OtaWireTestVectors.signedManifest(artifact);
            UUID jobId = UUID.randomUUID();
            client.deliver(AVAILABLE_TOPIC, availableNotification(jobId, manifest.sha256()));
            UUID requestId = requestId(client);
            OtaWireTestVectors.DownloadPair pair = OtaWireTestVectors.downloadResponse(manifest, requestId, jobId, 1,
                    server.artifactUri(), true);
            byte[] response = new OtaDeviceDownloadResponseCodec(true).encode(pair.device());

            // 首次投递：真实 HTTP 只取回 1024 字节前缀后在 DOWNLOADING 掉电。
            client.deliver(DOWNLOAD_RESPONSE_TOPIC, response);
            OtaJournalRecord interrupted = journal(runtime).getLast();
            assertThat(interrupted.stage()).isEqualTo(OtaStage.DOWNLOADING);
            assertThat(interrupted.reasonCode()).isEqualTo(OtaReasonCode.POWER_LOST);
            assertThat(interrupted.downloadedBytes()).isEqualTo(1024L);
            // 身份必须在执行运行之前就耐久落盘：这里读的是真实文件，不是内存里的待处理申请。
            OtaAcceptedDownloadResponse accepted = accepted(runtime);
            assertThat(accepted.jobId()).isEqualTo(jobId);
            assertThat(accepted.requestId()).isEqualTo(requestId);
            assertThat(accepted.authorizationId()).isEqualTo(pair.authorizationId());
            assertThat(accepted.manifestSha256()).isEqualTo(manifest.sha256());
            assertThat(accepted.artifactSha256()).isEqualTo(OtaWireTestVectors.sha256Hex(artifact));
            assertThat(accepted.artifactSize()).isEqualTo(artifact.length);
            int journalBefore = journal(runtime).size();
            server.clearObservations();

            // 同会话内逐字节重投递：必须从耐久确认的 1024 偏移续传并走到 COMMITTED。
            client.deliver(DOWNLOAD_RESPONSE_TOPIC, response);

            assertThat(server.rangeRequests()).as("恢复执行的第一条 Range 必须从耐久确认偏移开始")
                    .containsExactly("bytes=1024-");
            assertThat(journal(runtime)).as("恢复执行必须继续追加日志").hasSizeGreaterThan(journalBefore);
            assertThat(journal(runtime).getLast().conclusion()).isEqualTo(OtaStage.COMMITTED);
            assertThat(Files.readAllBytes(runtime.stagingFile())).isEqualTo(artifact);
            assertThat(publicationsOn(client, PROGRESS_TOPIC))
                    .as("恢复执行必须按平台闭集上报 VERIFYING/INSTALLING/REBOOTING")
                    .hasSize(3);
        }
    }

    /**
     * 跨重启重投递：同一设备目录上的<b>新</b> {@link OtaDeviceRuntime} 只凭耐久记录就识别同一份响应，
     * 并从耐久偏移续传到 COMMITTED（证明身份是耐久的，不是内存的）。
     *
     * @throws Exception 真实 HTTP/文件任一步失败时抛出
     */
    @Test
    void resumesByteIdenticalRedeliveryAfterRestartOnSameDeviceDirectory() throws Exception {
        byte[] artifact = OtaWireTestVectors.artifact(2048);
        try (OtaRangeServer server = new OtaRangeServer(artifact)) {
            Path deviceDirectory = dir.resolve(DEVICE_KEY);
            FakeMqttDeviceClient firstClient = new FakeMqttDeviceClient();
            OtaDeviceRuntime first = startRuntime(deviceDirectory, firstClient, new OtaScenarioClock(START),
                    () -> prefixFirstChunkOnly(server, 1024),
                    OtaFaultPlan.builder().powerLossAt(OtaStage.DOWNLOADING).build());
            OtaWireTestVectors.SignedManifest manifest = OtaWireTestVectors.signedManifest(artifact);
            UUID jobId = UUID.randomUUID();
            firstClient.deliver(AVAILABLE_TOPIC, availableNotification(jobId, manifest.sha256()));
            UUID requestId = requestId(firstClient);
            OtaWireTestVectors.DownloadPair pair = OtaWireTestVectors.downloadResponse(manifest, requestId, jobId, 1,
                    server.artifactUri(), true);
            byte[] response = new OtaDeviceDownloadResponseCodec(true).encode(pair.device());

            // 第一次运行：在 DOWNLOADING 掉电，留下 1024 字节耐久前缀与耐久接纳身份。
            firstClient.deliver(DOWNLOAD_RESPONSE_TOPIC, response);
            assertThat(journal(first).getLast().reasonCode()).isEqualTo(OtaReasonCode.POWER_LOST);
            assertThat(journal(first).getLast().downloadedBytes()).isEqualTo(1024L);
            OtaAcceptedDownloadResponse durable = accepted(first);
            server.clearObservations();

            // 第二次运行：同一设备目录上的全新运行时，内存里没有任何待处理申请。
            FakeMqttDeviceClient secondClient = new FakeMqttDeviceClient();
            OtaDeviceRuntime second = startRuntime(deviceDirectory, secondClient,
                    new OtaScenarioClock(START.plusSeconds(60)), HttpRangeArtifactSource::new, OtaFaultPlan.NONE);
            assertThat(second.journalFile()).isEqualTo(first.journalFile());
            // 不重新投递 available：只有耐久身份记录能证明这份响应此前被接纳过。
            secondClient.deliver(DOWNLOAD_RESPONSE_TOPIC, response);

            assertThat(server.rangeRequests()).containsExactly("bytes=1024-");
            assertThat(journal(second).getLast().conclusion()).isEqualTo(OtaStage.COMMITTED);
            assertThat(Files.readAllBytes(second.stagingFile())).isEqualTo(artifact);
            assertThat(accepted(second)).as("重投递接纳的是同一份耐久身份，不得被改写").isEqualTo(durable);
        }
    }

    /**
     * 未申请响应：没有耐久接纳记录时，一份结构合法的下载响应仍必须被拒绝，且零日志写入、零取字节。
     *
     * @throws Exception 真实 HTTP 服务器启动失败时抛出
     */
    @Test
    void rejectsUnsolicitedResponseWithoutJournalWriteOrExecution() throws Exception {
        byte[] artifact = OtaWireTestVectors.artifact(2048);
        try (OtaRangeServer server = new OtaRangeServer(artifact)) {
            Path deviceDirectory = dir.resolve(DEVICE_KEY);
            FakeMqttDeviceClient client = new FakeMqttDeviceClient();
            OtaDeviceRuntime runtime = startRuntime(deviceDirectory, client, new OtaScenarioClock(START),
                    HttpRangeArtifactSource::new, OtaFaultPlan.NONE);
            OtaWireTestVectors.SignedManifest manifest = OtaWireTestVectors.signedManifest(artifact);
            byte[] response = new OtaDeviceDownloadResponseCodec(true).encode(
                    OtaWireTestVectors.downloadResponse(manifest, UUID.randomUUID(), UUID.randomUUID(), 1,
                            server.artifactUri(), true).device());

            assertThatThrownBy(() -> client.deliver(DOWNLOAD_RESPONSE_TOPIC, response))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("未申请");

            assertThat(journal(runtime)).isEmpty();
            assertThat(Files.exists(runtime.acceptedResponseFile())).isFalse();
            assertThat(server.rangeRequests()).isEmpty();
        }
    }

    /**
     * 身份不符的重投递：requestId 或 manifestSha256 与耐久接纳不一致时必须失败关闭，且不改日志、不执行。
     *
     * @throws Exception 真实 HTTP/文件任一步失败时抛出
     */
    @Test
    void rejectsRedeliveryWhoseIdentityDiffersFromDurableAcceptance() throws Exception {
        byte[] artifact = OtaWireTestVectors.artifact(2048);
        try (OtaRangeServer server = new OtaRangeServer(artifact)) {
            Path deviceDirectory = dir.resolve(DEVICE_KEY);
            FakeMqttDeviceClient client = new FakeMqttDeviceClient();
            OtaDeviceRuntime runtime = startRuntime(deviceDirectory, client, new OtaScenarioClock(START),
                    () -> prefixFirstChunkOnly(server, 1024),
                    OtaFaultPlan.builder().powerLossAt(OtaStage.DOWNLOADING).build());
            OtaWireTestVectors.SignedManifest manifest = OtaWireTestVectors.signedManifest(artifact);
            UUID jobId = UUID.randomUUID();
            client.deliver(AVAILABLE_TOPIC, availableNotification(jobId, manifest.sha256()));
            UUID requestId = requestId(client);
            OtaWireTestVectors.DownloadPair pair = OtaWireTestVectors.downloadResponse(manifest, requestId, jobId, 1,
                    server.artifactUri(), true);
            client.deliver(DOWNLOAD_RESPONSE_TOPIC, new OtaDeviceDownloadResponseCodec(true).encode(pair.device()));
            List<OtaJournalRecord> journalBefore = journal(runtime);
            int rangesBefore = server.rangeRequests().size();

            // (a) 同一 jobId 但 requestId（以及授权）不同。
            byte[] differentRequest = new OtaDeviceDownloadResponseCodec(true).encode(
                    OtaWireTestVectors.downloadResponse(manifest, UUID.randomUUID(), jobId, 1, server.artifactUri(),
                            true).device());
            assertThatThrownBy(() -> client.deliver(DOWNLOAD_RESPONSE_TOPIC, differentRequest))
                    .isInstanceOf(IllegalStateException.class);

            // (b) 同一 requestId 但清单摘要（以及 artifact 身份）不同。
            byte[] otherArtifact = OtaWireTestVectors.artifact(4096);
            OtaWireTestVectors.SignedManifest otherManifest = OtaWireTestVectors.signedManifest(otherArtifact);
            byte[] differentManifest = new OtaDeviceDownloadResponseCodec(true).encode(
                    OtaWireTestVectors.downloadResponse(otherManifest, requestId, jobId, 1, server.artifactUri(),
                            true).device());
            assertThatThrownBy(() -> client.deliver(DOWNLOAD_RESPONSE_TOPIC, differentManifest))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(journal(runtime)).as("被拒绝的重投递不得写入任何耐久日志").isEqualTo(journalBefore);
            assertThat(server.rangeRequests()).as("被拒绝的重投递不得取字节").hasSize(rangesBefore);
            assertThat(accepted(runtime).requestId()).as("耐久接纳身份不得被不匹配的响应改写").isEqualTo(requestId);
        }
    }

    /**
     * 终态重投递：尝试已耐久 COMMITTED 后重投同一份响应必须是幂等空操作——不抛异常、不写日志、不取字节、不再报进度。
     *
     * @throws Exception 真实 HTTP/文件任一步失败时抛出
     */
    @Test
    void treatsRedeliveryAfterCommitAsIdempotentNoOp() throws Exception {
        byte[] artifact = OtaWireTestVectors.artifact(2048);
        try (OtaRangeServer server = new OtaRangeServer(artifact)) {
            Path deviceDirectory = dir.resolve(DEVICE_KEY);
            FakeMqttDeviceClient client = new FakeMqttDeviceClient();
            OtaDeviceRuntime runtime = startRuntime(deviceDirectory, client, new OtaScenarioClock(START),
                    HttpRangeArtifactSource::new, OtaFaultPlan.NONE);
            OtaWireTestVectors.SignedManifest manifest = OtaWireTestVectors.signedManifest(artifact);
            UUID jobId = UUID.randomUUID();
            client.deliver(AVAILABLE_TOPIC, availableNotification(jobId, manifest.sha256()));
            UUID requestId = requestId(client);
            OtaWireTestVectors.DownloadPair pair = OtaWireTestVectors.downloadResponse(manifest, requestId, jobId, 1,
                    server.artifactUri(), true);
            byte[] response = new OtaDeviceDownloadResponseCodec(true).encode(pair.device());
            client.deliver(DOWNLOAD_RESPONSE_TOPIC, response);
            assertThat(journal(runtime).getLast().conclusion()).isEqualTo(OtaStage.COMMITTED);
            List<OtaJournalRecord> journalAfterCommit = journal(runtime);
            int rangesAfterCommit = server.rangeRequests().size();
            int progressAfterCommit = publicationsOn(client, PROGRESS_TOPIC).size();

            client.deliver(DOWNLOAD_RESPONSE_TOPIC, response);

            assertThat(journal(runtime)).as("终态重投递不得新增或改写耐久日志").isEqualTo(journalAfterCommit);
            assertThat(server.rangeRequests()).as("终态重投递不得再取字节").hasSize(rangesAfterCommit);
            assertThat(publicationsOn(client, PROGRESS_TOPIC)).as("终态重投递不得再发布进度")
                    .hasSize(progressAfterCommit);
        }
    }

    /**
     * 「身份已落盘但执行从未开始」：日志为空时重投递必须在空日志上正常开始执行，而不是失败关闭；
     * 这覆盖「写入身份之后、运行执行之前崩溃」这一窗口，且不会产生第二次副作用。
     *
     * @throws Exception 真实 HTTP/文件任一步失败时抛出
     */
    @Test
    void startsExecutionWhenAcceptanceRecordExistsButJournalIsEmpty() throws Exception {
        byte[] artifact = OtaWireTestVectors.artifact(2048);
        try (OtaRangeServer server = new OtaRangeServer(artifact)) {
            Path deviceDirectory = dir.resolve(DEVICE_KEY);
            FakeMqttDeviceClient client = new FakeMqttDeviceClient();
            OtaDeviceRuntime runtime = startRuntime(deviceDirectory, client, new OtaScenarioClock(START),
                    HttpRangeArtifactSource::new, OtaFaultPlan.NONE);
            OtaWireTestVectors.SignedManifest manifest = OtaWireTestVectors.signedManifest(artifact);
            UUID jobId = UUID.randomUUID();
            UUID requestId = UUID.randomUUID();
            OtaWireTestVectors.DownloadPair pair = OtaWireTestVectors.downloadResponse(manifest, requestId, jobId, 1,
                    server.artifactUri(), true);
            OtaDownloadAssignment assignment = new OtaDownloadAssignment(pair.device().authorizationId(), jobId, 1,
                    manifest.sha256(), OtaWireTestVectors.sha256Hex(artifact), artifact.length, server.artifactUri());
            new FileOtaAcceptedDownloadResponseStore(runtime.acceptedResponseFile())
                    .write(OtaAcceptedDownloadResponse.from(assignment, requestId, START));
            byte[] response = new OtaDeviceDownloadResponseCodec(true).encode(pair.device());

            // 内存里没有任何待处理申请，日志为空；只有耐久身份记录能识别这份响应。
            client.deliver(DOWNLOAD_RESPONSE_TOPIC, response);

            assertThat(server.rangeRequests()).containsExactly("bytes=0-");
            assertThat(journal(runtime).getLast().conclusion()).isEqualTo(OtaStage.COMMITTED);
        }
    }

    /** 用真实文件日志读取设备耐久记录。 */
    private static List<OtaJournalRecord> journal(OtaDeviceRuntime runtime) {
        return new FileOtaStateJournal(runtime.journalFile()).read();
    }

    /** 用真实文件存储读取设备耐久接纳身份。 */
    private static OtaAcceptedDownloadResponse accepted(OtaDeviceRuntime runtime) {
        return new FileOtaAcceptedDownloadResponseStore(runtime.acceptedResponseFile()).read().orElseThrow();
    }

    /** 启动一个订阅了 OTA 下行 Topic 的真实运行时。 */
    private static OtaDeviceRuntime startRuntime(Path deviceDirectory, FakeMqttDeviceClient client, OtaClock clock,
                                                 Supplier<OtaArtifactSource> artifactSourceFactory,
                                                 OtaFaultPlan faultPlan) {
        OtaDeviceRuntime runtime = new OtaDeviceRuntime(PROJECT_KEY, DEVICE_KEY, client, deviceDirectory,
                OtaSimulatorEvidence.DEFAULT, false, true, clock, artifactSourceFactory, faultPlan);
        runtime.start();
        return runtime;
    }

    /** 用平台权威解码器读出设备刚发出的下载申请标识。 */
    private static UUID requestId(FakeMqttDeviceClient client) {
        return new OtaDownloadRequestCodec().decode(publicationsOn(client, DOWNLOAD_REQUEST_TOPIC).getFirst().payload())
                .value().requestId();
    }

    /** 取某个 Topic 上的全部发布。 */
    private static List<FakeMqttDeviceClient.Publication> publicationsOn(FakeMqttDeviceClient client, String topic) {
        return client.publications().stream().filter(publication -> publication.topic().equals(topic)).toList();
    }

    /** 构造平台可解码的可升级通知字节。 */
    private static byte[] availableNotification(UUID jobId, String manifestSha256) {
        return OtaDeviceAvailableNotificationCodec.encode(
                new OtaDeviceAvailableNotificationCodec.Notification(
                        OtaDeviceAvailableNotificationCodec.CONTRACT_VERSION, UUID.randomUUID(), UUID.randomUUID(),
                        jobId, UUID.randomUUID(), 1, manifestSha256, Instant.parse("2026-09-12T12:00:00Z")));
    }

    /**
     * 只在第一次（offset 0）取字节时把真实 HTTP 结果截成前缀，之后完全委托真实 HTTP。
     *
     * <p>这样第一次执行会在确定的 1024 字节偏移上掉电，而恢复执行仍走真实 Range 请求——正是
     * 「第二条 Range 必须等于已确认前缀」这条断言所需要的传输行为。</p>
     *
     * @param server 真实 Range 服务器
     * @param prefixBytes 第一次必须只取回的前缀字节数
     * @return 只截断第一次取字节的端口
     */
    private static OtaArtifactSource prefixFirstChunkOnly(OtaRangeServer server, int prefixBytes) {
        return new OtaArtifactSource() {

            /** 真实 HTTP Range 端口。 */
            private final OtaArtifactSource delegate = new HttpRangeArtifactSource();

            /** 是否仍是第一次取字节。 */
            private boolean firstFetch = true;

            /** {@inheritDoc} */
            @Override
            public Chunk fetch(String uri, long startOffset, long expectedLength, Duration budget) {
                Chunk chunk = delegate.fetch(uri, startOffset, expectedLength, budget);
                if (firstFetch && startOffset == 0L) {
                    firstFetch = false;
                    int length = (int) Math.min(prefixBytes, chunk.payload().length);
                    return new Chunk(Arrays.copyOf(chunk.payload(), length), chunk.contentRangeAccepted(),
                            chunk.contentRangeStart(), chunk.totalLength());
                }
                return chunk;
            }
        };
    }
}
