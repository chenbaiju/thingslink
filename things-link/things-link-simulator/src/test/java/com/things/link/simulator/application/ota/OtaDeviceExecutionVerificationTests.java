package com.things.link.simulator.application.ota;

import com.things.link.simulator.infrastructure.ota.FileOtaStateJournal;
import com.things.link.simulator.infrastructure.ota.HttpRangeArtifactSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * (g) 判定性拒绝：artifact 超限、声明长度不符、完整摘要不符、暂存区与日志不一致。
 *
 * <p>共性断言是「绝不进入刷写」：{@link OtaStage#INSTALLING} 及其后续阶段不得出现，
 * 因为一旦进入安全阶段，设备就无法再宣称自己没动过槽位。</p>
 */
class OtaDeviceExecutionVerificationTests {

    /** 每个用例独立的暂存目录。 */
    @TempDir
    Path dir;

    /** 单分片读取上限。 */
    private static final long CHUNK_BYTES = 1024L;

    /**
     * 服务器声明的 artifact 总长度超过实现允许的最大尺寸 → FAILED，绝不刷写。
     */
    @Test
    void rejectsOversizedArtifactByServerDeclaredTotal() throws Exception {
        byte[] artifact = OtaTestFixtures.artifact(8192);
        try (OtaRangeServer server = new OtaRangeServer(artifact)) {
            OtaDownloadAssignment assignment = new OtaDownloadAssignment(UUID.randomUUID(), UUID.randomUUID(),
                    1, OtaTestFixtures.sha256Hex("manifest"), OtaTestFixtures.sha256Hex(artifact, 4096),
                    4096L, server.artifactUri());
            FileOtaStateJournal journal = new FileOtaStateJournal(dir.resolve("ota.log"));

            OtaExecutionResult result = new OtaDeviceExecution(assignment, dir.resolve("artifact.bin"),
                    new HttpRangeArtifactSource(CHUNK_BYTES, 4096L),
                    new OtaScenarioClock(OtaTestFixtures.START), journal, OtaTestFixtures.BUDGET, 2,
                    OtaFaultPlan.NONE).run();

            assertThat(result.state()).isEqualTo(OtaStage.FAILED);
            assertThat(result.reason()).isEqualTo(OtaReasonCode.ARTIFACT_OVERSIZED);
            assertThat(result.downloadedBytes()).isZero();
            assertThat(result.fetchAttempts()).isEqualTo(1);
            assertThat(server.rangeRequests()).containsExactly("bytes=0-");
            assertThat(journal.read()).extracting(OtaJournalRecord::stage)
                    .doesNotContain(OtaStage.INSTALLING, OtaStage.COMMITTED);
        }
    }

    /**
     * 预期长度本身就超过实现上限时，在发起任何网络请求之前就拒绝。
     */
    @Test
    void rejectsExpectedLengthAboveSourceLimitBeforeFetching() throws Exception {
        byte[] artifact = OtaTestFixtures.artifact(8192);
        try (OtaRangeServer server = new OtaRangeServer(artifact)) {
            OtaDownloadAssignment assignment = OtaTestFixtures.assignmentFor(server.artifactUri(), artifact);
            FileOtaStateJournal journal = new FileOtaStateJournal(dir.resolve("ota.log"));

            OtaExecutionResult result = new OtaDeviceExecution(assignment, dir.resolve("artifact.bin"),
                    new HttpRangeArtifactSource(CHUNK_BYTES, 4096L),
                    new OtaScenarioClock(OtaTestFixtures.START), journal, OtaTestFixtures.BUDGET, 2,
                    OtaFaultPlan.NONE).run();

            assertThat(result.state()).isEqualTo(OtaStage.FAILED);
            assertThat(result.reason()).isEqualTo(OtaReasonCode.ARTIFACT_OVERSIZED);
            assertThat(result.fetchAttempts()).isEqualTo(1);
            assertThat(server.rangeRequests()).isEmpty();
        }
    }

    /**
     * Content-Range 声明的总长度与预期不符 → FAILED，绝不刷写。
     */
    @Test
    void rejectsArtifactSizeMismatch() throws Exception {
        byte[] artifact = OtaTestFixtures.artifact(3072);
        try (OtaRangeServer server = new OtaRangeServer(artifact)) {
            OtaDownloadAssignment assignment = new OtaDownloadAssignment(UUID.randomUUID(), UUID.randomUUID(),
                    1, OtaTestFixtures.sha256Hex("manifest"), OtaTestFixtures.sha256Hex(artifact, 2048),
                    2048L, server.artifactUri());
            FileOtaStateJournal journal = new FileOtaStateJournal(dir.resolve("ota.log"));

            OtaExecutionResult result = new OtaDeviceExecution(assignment, dir.resolve("artifact.bin"),
                    new HttpRangeArtifactSource(CHUNK_BYTES, OtaDownloadAssignment.MAX_ARTIFACT_SIZE),
                    new OtaScenarioClock(OtaTestFixtures.START), journal, OtaTestFixtures.BUDGET, 2,
                    OtaFaultPlan.NONE).run();

            assertThat(result.state()).isEqualTo(OtaStage.FAILED);
            assertThat(result.reason()).isEqualTo(OtaReasonCode.ARTIFACT_SIZE_MISMATCH);
            assertThat(journal.read()).extracting(OtaJournalRecord::stage)
                    .doesNotContain(OtaStage.INSTALLING, OtaStage.COMMITTED);
        }
    }

    /**
     * 完整 SHA-256 与预期不符 → FAILED，明确记录 VERIFYING 失败且绝不刷写。
     */
    @Test
    void rejectsDigestMismatchWithoutFlashing() throws Exception {
        byte[] served = OtaTestFixtures.otherArtifact(2048);
        byte[] expected = OtaTestFixtures.artifact(2048);
        try (OtaRangeServer server = new OtaRangeServer(served)) {
            OtaDownloadAssignment assignment = new OtaDownloadAssignment(UUID.randomUUID(), UUID.randomUUID(),
                    1, OtaTestFixtures.sha256Hex("manifest"), OtaTestFixtures.sha256Hex(expected),
                    expected.length, server.artifactUri());
            FileOtaStateJournal journal = new FileOtaStateJournal(dir.resolve("ota.log"));

            OtaExecutionResult result = new OtaDeviceExecution(assignment, dir.resolve("artifact.bin"),
                    new HttpRangeArtifactSource(CHUNK_BYTES, OtaDownloadAssignment.MAX_ARTIFACT_SIZE),
                    new OtaScenarioClock(OtaTestFixtures.START), journal, OtaTestFixtures.BUDGET, 2,
                    OtaFaultPlan.NONE).run();

            assertThat(result.state()).isEqualTo(OtaStage.FAILED);
            assertThat(result.reason()).isEqualTo(OtaReasonCode.DIGEST_MISMATCH);
            assertThat(result.observedStage()).isEqualTo(OtaStage.VERIFYING);
            assertThat(result.claimsArtifactVerified()).isFalse();
            assertThat(result.transitions()).extracting(OtaJournalRecord::stage)
                    .contains(OtaStage.VERIFYING)
                    .doesNotContain(OtaStage.INSTALLING, OtaStage.REBOOTING, OtaStage.COMMITTED);
            assertThat(journal.read().getLast().reasonCode()).isEqualTo(OtaReasonCode.DIGEST_MISMATCH);
        }
    }

    /**
     * 暂存区前缀被篡改 → STAGING_INCONSISTENT，重启后拒绝刷写且不重新下载。
     */
    @Test
    void rejectsTamperedStagingPrefixAfterRestart() throws Exception {
        byte[] artifact = OtaTestFixtures.artifact(3072);
        try (OtaRangeServer server = new OtaRangeServer(artifact)) {
            OtaDownloadAssignment assignment = OtaTestFixtures.assignmentFor(server.artifactUri(), artifact);
            Path staging = dir.resolve("artifact.bin");
            FileOtaStateJournal journal = new FileOtaStateJournal(dir.resolve("ota.log"));
            HttpRangeArtifactSource source = new HttpRangeArtifactSource(
                    CHUNK_BYTES, OtaDownloadAssignment.MAX_ARTIFACT_SIZE);

            new OtaDeviceExecution(assignment, staging, source, new OtaScenarioClock(OtaTestFixtures.START),
                    journal, OtaTestFixtures.BUDGET, 2,
                    OtaFaultPlan.builder().powerLossAt(OtaStage.DOWNLOADING).build()).run();
            byte[] tampered = Files.readAllBytes(staging);
            tampered[0] = (byte) (tampered[0] ^ 0xFF);
            Files.write(staging, tampered);

            server.clearObservations();
            OtaExecutionResult result = new OtaDeviceExecution(assignment, staging, source,
                    new OtaScenarioClock(OtaTestFixtures.START.plusSeconds(20)), journal,
                    OtaTestFixtures.BUDGET, 2, OtaFaultPlan.NONE).run();

            assertThat(result.state()).isEqualTo(OtaStage.FAILED);
            assertThat(result.reason()).isEqualTo(OtaReasonCode.STAGING_INCONSISTENT);
            assertThat(result.fetchAttempts()).isZero();
            assertThat(server.rangeRequests()).isEmpty();
            assertThat(journal.read()).extracting(OtaJournalRecord::stage)
                    .doesNotContain(OtaStage.INSTALLING, OtaStage.COMMITTED);
        }
    }
}
