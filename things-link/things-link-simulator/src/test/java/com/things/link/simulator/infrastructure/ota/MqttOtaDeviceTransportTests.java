package com.things.link.simulator.infrastructure.ota;

import com.things.link.ota.application.OtaDownloadRequestCodec;
import com.things.link.ota.application.OtaJobProgressCodec;
import com.things.link.simulator.application.ota.FakeMqttDeviceClient;
import com.things.link.simulator.application.ota.OtaArtifactSource;
import com.things.link.simulator.application.ota.OtaClock;
import com.things.link.simulator.application.ota.OtaStateJournal;
import com.things.link.simulator.application.ota.OtaStage;
import com.things.link.simulator.application.ota.contract.OtaDeviceAvailableNotificationCodec;
import com.things.link.simulator.application.ota.contract.OtaDeviceDownloadResponseCodec;
import com.things.link.simulator.application.ota.contract.OtaDeviceJobProgressCodec;
import com.things.link.simulator.application.ota.contract.OtaDeviceTopics;
import com.things.link.simulator.application.ota.contract.OtaDeviceTransport;
import com.things.link.simulator.application.ota.contract.OtaWireTestVectors;
import com.things.link.simulator.application.ota.session.OtaDeviceSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 传输适配器测试：用共享假 {@link FakeMqttDeviceClient} 证明 Topic 替换、QoS 1、非 retained、入站路由与失败上报。
 *
 * <p>端到端场景只走真实生产代码路径：通知入站 → 下载申请出站 → 下载响应入站 → 设备状态机执行 →
 * 进程阶段进度出站。测试替身只替换「Broker 与 artifact 服务器」，不替换编解码与状态机。</p>
 */
@DisplayName("S13-4d-2a MQTT OTA 设备传输适配器")
class MqttOtaDeviceTransportTests {

    /** 端到端场景使用的项目短标识。 */
    private static final String PROJECT_KEY = "ota_test";

    /** 端到端场景使用的设备短标识。 */
    private static final String DEVICE_KEY = "device_test";

    /** 端到端场景使用的固定启动身份。 */
    private static final UUID BOOT_ID = UUID.fromString("01920000-0000-7000-8000-0000000000ff");

    /** 平台闭集允许的四个进度阶段。 */
    private static final Set<String> PROGRESS_STAGES =
            Set.of("VERIFYING", "INSTALLING", "REBOOTING", "HEALTH_CHECKING");

    /** 每个用例独立的暂存目录。 */
    @TempDir
    Path dir;

    /** Topic 替换必须精确，QoS 1 且非 retained 必须由适配器固定。 */
    @Test
    void substitutesTopicsAndPublishesQosOneNonRetained() {
        FakeMqttDeviceClient client = new FakeMqttDeviceClient();
        OtaDeviceTransport transport = new MqttOtaDeviceTransport(client);
        String progressTopic = OtaDeviceTopics.forDevice(OtaDeviceTopics.PROGRESS, PROJECT_KEY, DEVICE_KEY);

        assertThat(progressTopic).isEqualTo("tc/v1/ota_test/device_test/up/ota/progress");
        transport.publish(progressTopic, new byte[] {1, 2, 3});

        assertThat(client.publications()).hasSize(1);
        FakeMqttDeviceClient.Publication publication = client.publications().getFirst();
        assertThat(publication.topic()).isEqualTo(progressTopic);
        assertThat(publication.qos()).isEqualTo(1);
        assertThat(publication.retained()).isFalse();
        assertThat(publication.payload()).containsExactly(1, 2, 3);

        List<String> delivered = new ArrayList<>();
        transport.subscribe(progressTopic, (topic, payload) -> delivered.add(topic + ":" + payload.length));
        assertThat(client.subscriptionQos().get(progressTopic)).isEqualTo(1);
        client.deliver(progressTopic, new byte[] {9});
        assertThat(delivered).containsExactly(progressTopic + ":1");
    }

    /** 含斜杠或通配符的标识符不能借 Topic 拼接逃逸到别的设备。 */
    @Test
    void rejectsUnsafeTopicSegments() {
        assertThatThrownBy(() -> OtaDeviceTopics.forDevice(OtaDeviceTopics.PROGRESS, "proj/other", DEVICE_KEY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OtaDeviceTopics.forDevice(OtaDeviceTopics.PROGRESS, PROJECT_KEY, "dev+"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OtaDeviceTopics.forDevice("tc/v1/x/y/up/ota/unknown", PROJECT_KEY, DEVICE_KEY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(OtaDeviceTopics.matches(OtaDeviceTopics.PROGRESS, PROJECT_KEY, DEVICE_KEY,
                "tc/v1/ota_test/device_test/up/ota/progress")).isTrue();
        assertThat(OtaDeviceTopics.matches(OtaDeviceTopics.PROGRESS, PROJECT_KEY, DEVICE_KEY,
                "tc/v1/ota_test/other_device/up/ota/progress")).isFalse();
    }

    /** 交付失败必须由返回的 future 上报，不能被吞掉或改写为成功。 */
    @Test
    void surfacesPublishFailureInsteadOfSwallowingIt() {
        FakeMqttDeviceClient client = new FakeMqttDeviceClient();
        client.failNextPublish(new IllegalStateException("PUBACK 前断开"));
        OtaDeviceTransport transport = new MqttOtaDeviceTransport(client);
        String topic = OtaDeviceTopics.forDevice(OtaDeviceTopics.PROGRESS, PROJECT_KEY, DEVICE_KEY);

        CompletableFuture<Void> receipt = transport.publish(topic, new byte[] {1});

        assertThat(receipt.isCompletedExceptionally()).isTrue();
        assertThatThrownBy(receipt::join).hasRootCauseInstanceOf(IllegalStateException.class);
    }

    /** 入站平台报文必须被路由到状态机，并按平台闭集发出进度报文。 */
    @Test
    void inboundPlatformPayloadDrivesExecutionAndPublishesProgress() throws Exception {
        FakeMqttDeviceClient client = new FakeMqttDeviceClient();
        OtaDeviceTransport transport = new MqttOtaDeviceTransport(client);
        byte[] artifact = OtaWireTestVectors.artifact(2048);
        OtaWireTestVectors.SignedManifest manifest = OtaWireTestVectors.signedManifest(artifact);
        OtaStateJournal journal = new FileOtaStateJournal(dir.resolve("ota.log"));
        OtaDeviceSession session = new OtaDeviceSession(PROJECT_KEY, DEVICE_KEY, transport, fixedClock(),
                dir.resolve("artifact.bin"), inMemorySource(artifact), journal,
                new FileOtaAcceptedDownloadResponseStore(dir.resolve("accepted-download")), progressReporter());

        session.start();
        String availableTopic = OtaDeviceTopics.forDevice(OtaDeviceTopics.AVAILABLE, PROJECT_KEY, DEVICE_KEY);
        String responseTopic = OtaDeviceTopics.forDevice(OtaDeviceTopics.DOWNLOAD_RESPONSE, PROJECT_KEY, DEVICE_KEY);
        assertThat(client.handlers()).containsExactlyInAnyOrder(availableTopic, responseTopic);
        assertThat(client.subscriptionQos().get(availableTopic)).isEqualTo(1);
        assertThat(client.subscriptionQos().get(responseTopic)).isEqualTo(1);

        UUID jobId = UUID.randomUUID();
        byte[] notification = OtaDeviceAvailableNotificationCodec.encode(
                new OtaDeviceAvailableNotificationCodec.Notification(
                        OtaDeviceAvailableNotificationCodec.CONTRACT_VERSION, UUID.randomUUID(), UUID.randomUUID(),
                        jobId, UUID.randomUUID(), 1, manifest.sha256(), Instant.parse("2026-09-12T12:00:00Z")));
        client.deliver(availableTopic, notification);

        // 授权（可升级通知）入站后设备必须发出下载申请。
        String requestTopic = OtaDeviceTopics.forDevice(OtaDeviceTopics.DOWNLOAD_REQUEST, PROJECT_KEY, DEVICE_KEY);
        assertThat(client.topics()).containsExactly(requestTopic);
        OtaDownloadRequestCodec.Request request = new OtaDownloadRequestCodec()
                .decode(client.publications().getFirst().payload()).value();
        assertThat(request.jobId()).isEqualTo(jobId);
        assertThat(request.manifestSha256()).isEqualTo(manifest.sha256());

        // 平台下载响应入站 → 状态机执行 → 进度出站。
        OtaWireTestVectors.DownloadPair pair = OtaWireTestVectors.downloadResponse(manifest, request.requestId(),
                jobId, 1, URI.create("https://storage.example/object"));
        byte[] response = new OtaDeviceDownloadResponseCodec().encode(pair.device());
        client.deliver(responseTopic, response);

        List<FakeMqttDeviceClient.Publication> progressPublications = client.publications().stream()
                .filter(publication -> publication.topic()
                        .equals(OtaDeviceTopics.forDevice(OtaDeviceTopics.PROGRESS, PROJECT_KEY, DEVICE_KEY)))
                .toList();
        assertThat(progressPublications).hasSize(4);
        assertThat(progressPublications).extracting(FakeMqttDeviceClient.Publication::qos).containsOnly(1);
        assertThat(progressPublications).extracting(FakeMqttDeviceClient.Publication::retained).containsOnly(false);

        List<String> stages = new ArrayList<>();
        for (FakeMqttDeviceClient.Publication publication : progressPublications) {
            OtaJobProgressCodec.Progress progress = new OtaJobProgressCodec().decode(publication.payload()).value();
            stages.add(progress.stage());
            assertThat(progress.jobId()).isEqualTo(jobId);
            assertThat(progress.authorizationId()).isEqualTo(pair.authorizationId());
            assertThat(progress.evidence().artifactSha256()).isEqualTo(OtaWireTestVectors.sha256Hex(artifact));
        }
        assertThat(stages).containsExactly("VERIFYING", "INSTALLING", "REBOOTING", "HEALTH_CHECKING");
        // 单分片下载在假时钟下应当走到 COMMITTED；这是状态机结论，不是平台事实。
        assertThat(journal.read().getLast().conclusion()).isEqualTo(OtaStage.COMMITTED);
    }

    /** 没有申请过的下载响应必须被拒绝，设备不能凭一份结构合法的授权执行升级。 */
    @Test
    void unsolicitedDownloadResponseIsRejected() throws Exception {
        FakeMqttDeviceClient client = new FakeMqttDeviceClient();
        OtaDeviceSession session = new OtaDeviceSession(PROJECT_KEY, DEVICE_KEY, new MqttOtaDeviceTransport(client),
                fixedClock(), dir.resolve("artifact.bin"), inMemorySource(OtaWireTestVectors.artifact(64)),
                new FileOtaStateJournal(dir.resolve("ota.log")),
                new FileOtaAcceptedDownloadResponseStore(dir.resolve("accepted-download")), progressReporter());
        session.start();
        OtaWireTestVectors.SignedManifest manifest = OtaWireTestVectors.signedManifest(OtaWireTestVectors.artifact(64));
        byte[] response = new OtaDeviceDownloadResponseCodec()
                .encode(OtaWireTestVectors.downloadResponse(manifest, UUID.randomUUID(), UUID.randomUUID(), 1,
                        URI.create("https://storage.example/object")).device());
        String responseTopic = OtaDeviceTopics.forDevice(OtaDeviceTopics.DOWNLOAD_RESPONSE, PROJECT_KEY, DEVICE_KEY);

        assertThatThrownBy(() -> client.deliver(responseTopic, response))
                .isInstanceOf(IllegalStateException.class);
    }

    /** 固定时钟：端到端场景不需要真实等待。 */
    private static OtaClock fixedClock() {
        return new OtaClock() {
            @Override
            public Instant now() {
                return OtaWireTestVectors.EXPIRES_AT.minusSeconds(3600);
            }

            @Override
            public void sleep(Duration duration) {
                // 单分片下载不需要退避；真实退避行为由既有状态机测试覆盖。
            }
        };
    }

    /** 内存 artifact 端口：一次返回剩余全部字节，并如实报告 Content-Range。 */
    private static OtaArtifactSource inMemorySource(byte[] artifact) {
        return (uri, startOffset, expectedLength, budget) -> new OtaArtifactSource.Chunk(
                Arrays.copyOfRange(artifact, Math.toIntExact(startOffset), artifact.length), true, startOffset,
                artifact.length);
    }

    /** 只上报平台闭集阶段；其余阶段返回 {@code null} 表示本策略不上报。 */
    private static OtaDeviceSession.ProgressReporter progressReporter() {
        return (transition, result, progressSeq) -> {
            String stage = transition.stage().name();
            if (!PROGRESS_STAGES.contains(stage)) {
                return null;
            }
            return new OtaDeviceJobProgressCodec.Progress(OtaDeviceJobProgressCodec.CONTRACT_VERSION,
                    result.jobId(), result.attemptNo(), progressSeq, result.authorizationId(),
                    result.manifestSha256(), stage, BOOT_ID,
                    OtaWireTestVectors.evidenceForStage(stage, result.artifactSha256(), result.artifactSize()));
        };
    }

}
