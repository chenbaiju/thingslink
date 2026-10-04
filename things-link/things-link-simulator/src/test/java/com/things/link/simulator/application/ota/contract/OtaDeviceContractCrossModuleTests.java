package com.things.link.simulator.application.ota.contract;

import com.things.link.ota.application.OtaCommitPermitCodec;
import com.things.link.ota.application.OtaCommitReceiptCodec;
import com.things.link.ota.application.OtaDownloadRequestCodec;
import com.things.link.ota.application.OtaDownloadResponseCodec;
import com.things.link.ota.application.OtaHealthCodec;
import com.things.link.ota.application.OtaInstallStopOperationCodec;
import com.things.link.ota.application.OtaInstallStopOperationReportCodec;
import com.things.link.ota.application.OtaInstallStopStatusQueryCodec;
import com.things.link.ota.application.OtaInstallStopStatusReportCodec;
import com.things.link.ota.application.OtaJobProgressCodec;
import com.things.link.ota.application.OtaNotificationCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨模块线合同测试：设备侧编码器与平台权威编解码器必须逐字节一致。
 *
 * <p>这是本片存在的理由。任何字段名、版本串、排序、转义、数字宽度或 Base64 形态的漂移，
 * 都会在这里以字节不等或平台解码异常的形式失败，而不是等到真实联调时表现为设备被平台静默拒绝。</p>
 *
 * <p>平台解码器只接受真实签名，因此下载响应用测试内即时生成的 Ed25519 私钥现签；私钥不落盘。</p>
 */
@DisplayName("S13-4d-2a 设备侧线合同与平台编解码器跨模块一致")
class OtaDeviceContractCrossModuleTests {

    /** 平台下载申请解码器。 */
    private static final OtaDownloadRequestCodec PLATFORM_REQUEST = new OtaDownloadRequestCodec();

    /** 平台进度解码器。 */
    private static final OtaJobProgressCodec PLATFORM_PROGRESS = new OtaJobProgressCodec();

    /** 平台健康解码器。 */
    private static final OtaHealthCodec PLATFORM_HEALTH = new OtaHealthCodec();

    /** 平台停止操作编解码器。 */
    private static final OtaInstallStopOperationCodec PLATFORM_STOP_OPERATION = new OtaInstallStopOperationCodec();

    /** 平台停止操作报告编解码器。 */
    private static final OtaInstallStopOperationReportCodec PLATFORM_STOP_REPORT =
            new OtaInstallStopOperationReportCodec();

    /** 平台停止状态报告编解码器。 */
    private static final OtaInstallStopStatusReportCodec PLATFORM_STATUS_REPORT =
            new OtaInstallStopStatusReportCodec();

    /** 平台停止状态查询编解码器。 */
    private static final OtaInstallStopStatusQueryCodec PLATFORM_STATUS_QUERY =
            new OtaInstallStopStatusQueryCodec();

    /** 平台下载响应编解码器（默认只允许 HTTPS，与设备侧默认一致）。 */
    private static final OtaDownloadResponseCodec PLATFORM_RESPONSE = new OtaDownloadResponseCodec();

    /** 平台提交许可编解码器（唯一普通文本许可合同）。 */
    private static final OtaCommitPermitCodec PLATFORM_COMMIT_PERMIT = new OtaCommitPermitCodec();

    /** 平台提交回执解码器（平台只公开解码，规范字节由解码结果给出）。 */
    private static final OtaCommitReceiptCodec PLATFORM_COMMIT_RECEIPT = new OtaCommitReceiptCodec();

    /**
     * 下载申请：设备编码 → 平台解码接受、值相等、规范字节逐字节相同；平台规范字节 → 设备解码相等。
     */
    @Test
    void downloadRequestBytesAreIdenticalAndMutuallyDecodable() {
        OtaDeviceDownloadRequestCodec.Request request = new OtaDeviceDownloadRequestCodec.Request(
                OtaDeviceDownloadRequestCodec.CONTRACT_VERSION, UUID.randomUUID(), UUID.randomUUID(), 2,
                OtaWireTestVectors.sha256Hex("manifest".getBytes(StandardCharsets.UTF_8)));

        byte[] deviceBytes = OtaDeviceDownloadRequestCodec.encode(request);
        OtaDownloadRequestCodec.Decoded platformDecoded = PLATFORM_REQUEST.decode(deviceBytes);

        assertThat(platformDecoded.value().contractVersion()).isEqualTo(request.contractVersion());
        assertThat(platformDecoded.value().requestId()).isEqualTo(request.requestId());
        assertThat(platformDecoded.value().jobId()).isEqualTo(request.jobId());
        assertThat(platformDecoded.value().attemptNo()).isEqualTo(request.attemptNo());
        assertThat(platformDecoded.value().manifestSha256()).isEqualTo(request.manifestSha256());
        // 平台对同一字段值的规范字节必须与设备侧完全相同。
        assertThat(platformDecoded.canonical()).isEqualTo(deviceBytes);

        OtaDeviceDownloadRequestCodec.Request deviceDecoded =
                OtaDeviceDownloadRequestCodec.decode(platformDecoded.canonical()).value();
        assertThat(deviceDecoded).isEqualTo(request);
    }

    /**
     * 可升级通知：设备编码 = 平台编码；平台编码结果能被设备解码回同一身份。
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={1,2,11})
    void availableNotificationBytesAreIdenticalAndMutuallyDecodable(int attemptNo) {
        OtaDeviceAvailableNotificationCodec.Notification notification =
                new OtaDeviceAvailableNotificationCodec.Notification(
                        OtaDeviceAvailableNotificationCodec.CONTRACT_VERSION, UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID(), UUID.randomUUID(), attemptNo,
                        OtaWireTestVectors.sha256Hex("manifest".getBytes(StandardCharsets.UTF_8)),
                        Instant.parse("2026-09-12T12:00:00Z"));

        byte[] deviceBytes = OtaDeviceAvailableNotificationCodec.encode(notification);
        OtaNotificationCodec.Notification platformDecoded = new OtaNotificationCodec().decode(deviceBytes);

        assertThat(platformDecoded.eventId()).isEqualTo(notification.eventId());
        assertThat(platformDecoded.campaignId()).isEqualTo(notification.campaignId());
        assertThat(platformDecoded.jobId()).isEqualTo(notification.jobId());
        assertThat(platformDecoded.firmwareId()).isEqualTo(notification.firmwareId());
        assertThat(platformDecoded.attemptNo()).isEqualTo(notification.attemptNo());
        assertThat(platformDecoded.manifestSha256()).isEqualTo(notification.manifestSha256());
        assertThat(platformDecoded.deadlineAt()).isEqualTo(notification.deadlineAt());
        assertThat(new OtaNotificationCodec().encode(platformDecoded)).isEqualTo(deviceBytes);
        assertThat(OtaDeviceAvailableNotificationCodec.decode(
                new OtaNotificationCodec().encode(platformDecoded)).value()).isEqualTo(notification);
    }

    /**
     * 执行进度：平台闭集的四个阶段都必须逐字节一致，且平台能解回完全相同的证据。
     */
    @Test
    void jobProgressBytesAreIdenticalForEveryClosedStage() {
        String artifactSha256 = OtaWireTestVectors.sha256Hex("artifact".getBytes(StandardCharsets.UTF_8));
        long artifactSize = 4096L;
        UUID jobId = UUID.randomUUID();
        UUID authorizationId = UUID.randomUUID();
        UUID bootId = UUID.randomUUID();
        String manifestSha256 =
                OtaWireTestVectors.sha256Hex("manifest".getBytes(StandardCharsets.UTF_8));
        long sequence = 0L;
        for (String stage : List.of("VERIFYING", "INSTALLING", "REBOOTING", "HEALTH_CHECKING")) {
            sequence++;
            OtaDeviceJobProgressCodec.Progress progress = new OtaDeviceJobProgressCodec.Progress(
                    OtaDeviceJobProgressCodec.CONTRACT_VERSION, jobId, 1, sequence, authorizationId, manifestSha256,
                    stage, bootId, OtaWireTestVectors.evidenceForStage(stage, artifactSha256, artifactSize));

            byte[] deviceBytes = OtaDeviceJobProgressCodec.encode(progress);
            OtaJobProgressCodec.Decoded platformDecoded = PLATFORM_PROGRESS.decode(deviceBytes);

            assertThat(platformDecoded.value().stage()).isEqualTo(stage);
            assertThat(platformDecoded.value().jobId()).isEqualTo(jobId);
            assertThat(platformDecoded.value().progressSeq()).isEqualTo(sequence);
            assertThat(platformDecoded.value().bootId()).isEqualTo(bootId);
            assertThat(platformDecoded.value().evidence().artifactSha256()).isEqualTo(artifactSha256);
            assertThat(platformDecoded.value().evidence().artifactSize()).isEqualTo(artifactSize);
            assertThat(platformDecoded.value().evidence().verification())
                    .isEqualTo(progress.evidence().verification());
            assertThat(platformDecoded.value().evidence().selfTestPassed())
                    .isEqualTo(progress.evidence().selfTestPassed());
            assertThat(platformDecoded.canonical()).isEqualTo(deviceBytes);
            assertThat(OtaDeviceJobProgressCodec.decode(platformDecoded.canonical()).value()).isEqualTo(progress);
        }
    }

    /**
     * 健康确认：设备编码 = 平台规范字节；平台规范字节能被设备解码回同一证据与健康时长。
     */
    @Test
    void healthBytesAreIdenticalAndMutuallyDecodable() {
        UUID jobId = UUID.randomUUID();
        UUID authorizationId = UUID.randomUUID();
        UUID bootId = UUID.randomUUID();
        OtaDeviceHealthCodec.HealthEvidence evidence = new OtaDeviceHealthCodec.HealthEvidence(
                OtaWireTestVectors.evidenceForStage("HEALTH_CHECKING",
                        OtaWireTestVectors.sha256Hex("artifact".getBytes(StandardCharsets.UTF_8)),
                        2048L),
                90_000L, 60_000L);
        OtaDeviceHealthCodec.Health health = new OtaDeviceHealthCodec.Health(OtaDeviceHealthCodec.CONTRACT_VERSION,
                jobId, 1, authorizationId,
                OtaWireTestVectors.sha256Hex("manifest".getBytes(StandardCharsets.UTF_8)),
                3L, bootId, evidence);

        byte[] deviceBytes = OtaDeviceHealthCodec.encode(health);
        OtaHealthCodec.Decoded platformDecoded = PLATFORM_HEALTH.decode(deviceBytes);

        assertThat(platformDecoded.value().jobId()).isEqualTo(jobId);
        assertThat(platformDecoded.value().healthSeq()).isEqualTo(3L);
        assertThat(platformDecoded.value().evidence().uptimeMillis()).isEqualTo(90_000L);
        assertThat(platformDecoded.value().evidence().healthyForMillis()).isEqualTo(60_000L);
        assertThat(platformDecoded.value().evidence().target().artifactSha256())
                .isEqualTo(evidence.target().artifactSha256());
        assertThat(platformDecoded.canonical()).isEqualTo(deviceBytes);
        assertThat(OtaDeviceHealthCodec.decode(platformDecoded.canonical()).value()).isEqualTo(health);
    }

    /**
     * 安装前停止操作：设备编码 = 平台编码；空授权列表也必须一致。
     */
    @Test
    void installStopOperationBytesAreIdenticalIncludingEmptyAuthorizationList() {
        for (List<UUID> authorizationIds : List.of(List.<UUID>of(), List.of(UUID.randomUUID()))) {
            OtaDeviceInstallStopOperationCodec.Operation operation =
                    new OtaDeviceInstallStopOperationCodec.Operation(
                            OtaDeviceInstallStopOperationCodec.CONTRACT_VERSION, UUID.randomUUID(), UUID.randomUUID(),
                            UUID.randomUUID(), 1,
                            OtaWireTestVectors.sha256Hex("manifest".getBytes(StandardCharsets.UTF_8)),
                            authorizationIds,
                            OtaWireTestVectors.sha256Hex("baseline".getBytes(StandardCharsets.UTF_8)),
                            4L, 1_800_000_000L);

            byte[] deviceBytes = OtaDeviceInstallStopOperationCodec.encode(operation);
            OtaInstallStopOperationCodec.Decoded platformDecoded = PLATFORM_STOP_OPERATION.decode(deviceBytes);

            assertThat(platformDecoded.value().authorizationIds()).isEqualTo(authorizationIds);
            assertThat(platformDecoded.value().expiresAt()).isEqualTo(1_800_000_000L);
            assertThat(platformDecoded.canonical()).isEqualTo(deviceBytes);
            assertThat(PLATFORM_STOP_OPERATION.encode(platformDecoded.value())).isEqualTo(deviceBytes);
            assertThat(OtaDeviceInstallStopOperationCodec.decode(platformDecoded.canonical()).value())
                    .isEqualTo(operation);
        }
    }

    /**
     * 安装前停止操作报告：设备编码 = 平台编码，证据字段与顺序完全一致。
     */
    @Test
    void installStopOperationReportBytesAreIdentical() {
        UUID operationId = UUID.randomUUID();
        UUID bootId = UUID.randomUUID();
        String operationSha256 =
                OtaWireTestVectors.sha256Hex("operation".getBytes(StandardCharsets.UTF_8));
        OtaDeviceInstallStopReportCodec.Report report = new OtaDeviceInstallStopReportCodec.Report(
                OtaDeviceInstallStopReportCodec.CONTRACT_VERSION, operationId, UUID.randomUUID(), 1,
                OtaWireTestVectors.sha256Hex("manifest".getBytes(StandardCharsets.UTF_8)),
                operationSha256, UUID.randomUUID(), 9L, bootId, "STOPPED",
                OtaWireTestVectors.stopEvidence(operationId, operationSha256, bootId));

        byte[] deviceBytes = OtaDeviceInstallStopReportCodec.encode(report);
        OtaInstallStopOperationReportCodec.Decoded platformDecoded = PLATFORM_STOP_REPORT.decode(deviceBytes);

        assertThat(platformDecoded.value().status()).isEqualTo("STOPPED");
        assertThat(platformDecoded.value().reportSeq()).isEqualTo(9L);
        assertThat(platformDecoded.value().evidence().stopOperations()).hasSize(1);
        assertThat(platformDecoded.value().evidence().stopOperations().getFirst().acceptedRevision()).isEqualTo(5L);
        assertThat(platformDecoded.value().evidence().writeState()).isEqualTo("QUIESCENT");
        assertThat(platformDecoded.canonical()).isEqualTo(deviceBytes);
        assertThat(PLATFORM_STOP_REPORT.encode(platformDecoded.value())).isEqualTo(deviceBytes);
        assertThat(OtaDeviceInstallStopReportCodec.decode(platformDecoded.canonical()).value()).isEqualTo(report);
    }

    /**
     * 安装前停止状态报告：设备编码 = 平台编码，查询身份参与规范字节。
     */
    @Test
    void installStopStatusReportBytesAreIdentical() {
        UUID operationId = UUID.randomUUID();
        UUID bootId = UUID.randomUUID();
        String operationSha256 =
                OtaWireTestVectors.sha256Hex("operation".getBytes(StandardCharsets.UTF_8));
        OtaDeviceInstallStopStatusReportCodec.Report report = new OtaDeviceInstallStopStatusReportCodec.Report(
                OtaDeviceInstallStopStatusReportCodec.CONTRACT_VERSION, operationId, UUID.randomUUID(), 1,
                OtaWireTestVectors.sha256Hex("manifest".getBytes(StandardCharsets.UTF_8)),
                operationSha256, UUID.randomUUID(), 11L, bootId, "INSTALL_WON",
                OtaWireTestVectors.stopEvidence(operationId, operationSha256, bootId),
                UUID.randomUUID(), UUID.randomUUID());

        byte[] deviceBytes = OtaDeviceInstallStopStatusReportCodec.encode(report);
        OtaInstallStopStatusReportCodec.Decoded platformDecoded = PLATFORM_STATUS_REPORT.decode(deviceBytes);

        assertThat(platformDecoded.value().status()).isEqualTo("INSTALL_WON");
        assertThat(platformDecoded.value().queryId()).isEqualTo(report.queryId());
        assertThat(platformDecoded.value().queryNonce()).isEqualTo(report.queryNonce());
        assertThat(platformDecoded.canonical()).isEqualTo(deviceBytes);
        assertThat(PLATFORM_STATUS_REPORT.encode(platformDecoded.value())).isEqualTo(deviceBytes);
        assertThat(OtaDeviceInstallStopStatusReportCodec.decode(platformDecoded.canonical()).value())
                .isEqualTo(report);
    }

    /**
     * 安装前停止状态查询：设备编码 = 平台编码；平台查询字节也必须能被设备解码。
     */
    @Test
    void installStopStatusQueryBytesAreIdentical() {
        OtaDeviceInstallStopStatusQueryCodec.Query query = new OtaDeviceInstallStopStatusQueryCodec.Query(
                OtaDeviceInstallStopStatusQueryCodec.CONTRACT_VERSION, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 1,
                OtaWireTestVectors.sha256Hex("manifest".getBytes(StandardCharsets.UTF_8)),
                OtaWireTestVectors.sha256Hex("operation".getBytes(StandardCharsets.UTF_8)), 1_800_000_000L);

        byte[] deviceBytes = OtaDeviceInstallStopStatusQueryCodec.encode(query);
        OtaInstallStopStatusQueryCodec.Decoded platformDecoded = PLATFORM_STATUS_QUERY.decode(deviceBytes);

        assertThat(platformDecoded.value().queryId()).isEqualTo(query.queryId());
        assertThat(platformDecoded.value().queryNonce()).isEqualTo(query.queryNonce());
        assertThat(platformDecoded.value().operationId()).isEqualTo(query.operationId());
        assertThat(platformDecoded.value().attemptNo()).isEqualTo(1);
        assertThat(platformDecoded.value().expiresAt()).isEqualTo(1_800_000_000L);
        assertThat(platformDecoded.canonical()).isEqualTo(deviceBytes);
        assertThat(PLATFORM_STATUS_QUERY.encode(platformDecoded.value())).isEqualTo(deviceBytes);
        assertThat(OtaDeviceInstallStopStatusQueryCodec.decode(platformDecoded.canonical()).value())
                .isEqualTo(query);
    }

    /**
     * 下载响应：设备编码 = 平台编码（含真实 Ed25519 签名与公钥），并且双向可解。
     */
    @Test
    void downloadResponseBytesAreIdenticalForRealSignature() throws Exception {
        byte[] artifact = OtaWireTestVectors.artifact(3072);
        OtaWireTestVectors.SignedManifest manifest = OtaWireTestVectors.signedManifest(artifact);
        OtaWireTestVectors.DownloadPair pair = OtaWireTestVectors.downloadResponse(manifest, UUID.randomUUID(),
                UUID.randomUUID(), 1, URI.create("https://storage.example/object?signature=secret"));

        byte[] deviceBytes = new OtaDeviceDownloadResponseCodec().encode(pair.device());
        byte[] platformBytes = PLATFORM_RESPONSE.encode(pair.platform());

        assertThat(deviceBytes).isEqualTo(platformBytes);

        OtaDownloadResponseCodec.Response platformDecoded = PLATFORM_RESPONSE.decode(deviceBytes);
        assertThat(platformDecoded.authorizationId()).isEqualTo(pair.device().authorizationId());
        assertThat(platformDecoded.requestId()).isEqualTo(pair.device().requestId());
        assertThat(platformDecoded.campaignId()).isEqualTo(pair.device().campaignId());
        assertThat(platformDecoded.jobId()).isEqualTo(pair.device().jobId());
        assertThat(platformDecoded.firmwareId()).isEqualTo(pair.device().firmwareId());
        assertThat(platformDecoded.attemptNo()).isEqualTo(pair.device().attemptNo());
        assertThat(platformDecoded.manifestSha256()).isEqualTo(pair.device().manifestSha256());
        assertThat(platformDecoded.manifest()).isEqualTo(pair.device().manifest());
        assertThat(platformDecoded.signature()).isEqualTo(pair.device().signature());
        assertThat(platformDecoded.publicKeySpki()).isEqualTo(pair.device().publicKeySpki());
        assertThat(platformDecoded.keyFingerprint()).isEqualTo(pair.device().keyFingerprint());
        assertThat(platformDecoded.downloadUrl()).isEqualTo(pair.device().downloadUrl());
        assertThat(platformDecoded.expiresAt()).isEqualTo(pair.device().expiresAt());

        OtaDeviceDownloadResponseCodec.Response deviceDecoded =
                new OtaDeviceDownloadResponseCodec().decode(platformBytes).value();
        assertThat(deviceDecoded.manifest()).isEqualTo(pair.device().manifest());
        assertThat(deviceDecoded.signature()).isEqualTo(pair.device().signature());
        assertThat(deviceDecoded.publicKeySpki()).isEqualTo(pair.device().publicKeySpki());
        assertThat(deviceDecoded.manifestArtifactSha256()).isEqualTo(OtaWireTestVectors.sha256Hex(artifact));
        assertThat(deviceDecoded.manifestArtifactSize()).isEqualTo(artifact.length);
    }

    /**
     * 提交许可：设备编码 = 平台编码；平台规范字节能被设备解码回同一许可。
     *
     * <p>许可正文由平台一次性编码并持久、之后只按原字节重传，因此两侧编码必须逐字节一致，
     * 否则设备会读到一份平台并未签发的许可。</p>
     */
    @Test
    void commitPermitBytesAreIdenticalAndMutuallyDecodable() {
        OtaDeviceCommitPermitCodec.Permit permit = new OtaDeviceCommitPermitCodec.Permit(
                OtaDeviceCommitPermitCodec.CONTRACT_VERSION,
                UUID.fromString("01920000-0000-7000-8000-000000000101"),
                UUID.fromString("01920000-0000-7000-8000-000000000102"), 1,
                UUID.fromString("01920000-0000-7000-8000-000000000103"),
                OtaWireTestVectors.sha256Hex("manifest".getBytes(StandardCharsets.UTF_8)),
                UUID.fromString("01920000-0000-7000-8000-000000000104"), 7L,
                OtaWireTestVectors.sha256Hex("artifact".getBytes(StandardCharsets.UTF_8)), "B", 1_800_000_000L);

        byte[] deviceBytes = OtaDeviceCommitPermitCodec.encode(permit);
        OtaCommitPermitCodec.Decoded platformDecoded = PLATFORM_COMMIT_PERMIT.decode(deviceBytes);

        assertThat(platformDecoded.value().permitId()).isEqualTo(permit.permitId());
        assertThat(platformDecoded.value().jobId()).isEqualTo(permit.jobId());
        assertThat(platformDecoded.value().attemptNo()).isEqualTo(1);
        assertThat(platformDecoded.value().authorizationId()).isEqualTo(permit.authorizationId());
        assertThat(platformDecoded.value().manifestSha256()).isEqualTo(permit.manifestSha256());
        assertThat(platformDecoded.value().bootId()).isEqualTo(permit.bootId());
        assertThat(platformDecoded.value().securityVersion()).isEqualTo(7L);
        assertThat(platformDecoded.value().artifactSha256()).isEqualTo(permit.artifactSha256());
        assertThat(platformDecoded.value().targetSlot()).isEqualTo("B");
        assertThat(platformDecoded.value().expiresAt()).isEqualTo(1_800_000_000L);
        assertThat(platformDecoded.canonical()).isEqualTo(deviceBytes);
        // 平台权威编码器对同一许可必须产出与设备完全相同的规范字节。
        assertThat(PLATFORM_COMMIT_PERMIT.encode(platformDecoded.value())).isEqualTo(deviceBytes);
        assertThat(OtaDeviceCommitPermitCodec.decode(platformDecoded.canonical()).value()).isEqualTo(permit);
    }

    /**
     * 提交回执：设备编码 = 平台规范字节；平台权威解码器重建的秘密字节必须与设备发出的完全一致。
     *
     * <p>平台只公开回执解码器（许可由平台签发，回执只由设备产生），因此字节一致性以
     * 「平台解码后重新规范化得到的规范字节」为准：设备编码、平台对设备字节的规范化结果、
     * 以及平台对同一逻辑事实非规范写法的规范化结果三者必须完全相同。</p>
     */
    @Test
    void commitReceiptBytesAreIdenticalAndMutuallyDecodable() {
        String artifactSha256 = OtaWireTestVectors.sha256Hex("artifact".getBytes(StandardCharsets.UTF_8));
        OtaDeviceCommitReceiptCodec.Receipt receipt = new OtaDeviceCommitReceiptCodec.Receipt(
                OtaDeviceCommitReceiptCodec.CONTRACT_VERSION,
                UUID.fromString("01920000-0000-7000-8000-000000000201"), 1,
                UUID.fromString("01920000-0000-7000-8000-000000000202"),
                OtaWireTestVectors.sha256Hex("manifest".getBytes(StandardCharsets.UTF_8)),
                UUID.fromString("01920000-0000-7000-8000-000000000203"),
                UUID.fromString("01920000-0000-7000-8000-000000000204"),
                UUID.fromString("01920000-0000-7000-8000-000000000205"),
                OtaWireTestVectors.committedEvidence(artifactSha256, 4096L));

        byte[] deviceBytes = OtaDeviceCommitReceiptCodec.encode(receipt);
        OtaCommitReceiptCodec.Decoded platformDecoded = PLATFORM_COMMIT_RECEIPT.decode(deviceBytes);

        assertThat(platformDecoded.value().jobId()).isEqualTo(receipt.jobId());
        assertThat(platformDecoded.value().attemptNo()).isEqualTo(1);
        assertThat(platformDecoded.value().authorizationId()).isEqualTo(receipt.authorizationId());
        assertThat(platformDecoded.value().manifestSha256()).isEqualTo(receipt.manifestSha256());
        assertThat(platformDecoded.value().receiptId()).isEqualTo(receipt.receiptId());
        assertThat(platformDecoded.value().permitId()).isEqualTo(receipt.permitId());
        assertThat(platformDecoded.value().bootId()).isEqualTo(receipt.bootId());
        assertThat(platformDecoded.value().evidence().artifactSha256()).isEqualTo(artifactSha256);
        assertThat(platformDecoded.value().evidence().committedSecurityVersion())
                .as("提交回执的已提交安全版本必须等于目标安全版本")
                .isEqualTo(platformDecoded.value().evidence().securityVersion());
        // 设备字节必须恰好等于平台权威解码器重新规范化后的规范字节。
        assertThat(platformDecoded.canonical()).isEqualTo(deviceBytes);
        // 同一逻辑事实的非规范写法经平台规范化后也必须回到设备编码字节。
        byte[] padded = (" " + new String(deviceBytes, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        assertThat(PLATFORM_COMMIT_RECEIPT.decode(padded).canonical()).isEqualTo(deviceBytes);
        assertThat(OtaDeviceCommitReceiptCodec.decode(platformDecoded.canonical()).value()).isEqualTo(receipt);
    }
}
