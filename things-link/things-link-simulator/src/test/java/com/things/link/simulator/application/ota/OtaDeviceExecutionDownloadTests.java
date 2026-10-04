package com.things.link.simulator.application.ota;

import com.things.link.simulator.infrastructure.ota.FileOtaStateJournal;
import com.things.link.simulator.infrastructure.ota.HttpRangeArtifactSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 断点续传与断电恢复：驱动真实 HTTP Range 实现，用真实文件日志与真实暂存区。
 *
 * <p>覆盖验收项 (a) 断点续传、(b) 断电恢复（下载阶段可续传；安装阶段绝不重新刷写）。</p>
 */
class OtaDeviceExecutionDownloadTests {

    /** 每个用例独立的暂存目录。 */
    @TempDir
    Path dir;

    /** 单分片读取上限；用它把「中断」固定在确定偏移上。 */
    private static final long CHUNK_BYTES = 1024L;

    /**
     * (a) 第一个执行在已确认偏移处断电，第二个执行必须从该偏移请求 Range，并且最终摘要匹配。
     */
    @Test
    void resumesFromJournalConfirmedOffsetAndMatchesFullDigest() throws Exception {
        byte[] artifact = OtaTestFixtures.artifact(3072);
        try (OtaRangeServer server = new OtaRangeServer(artifact)) {
            OtaDownloadAssignment assignment = OtaTestFixtures.assignmentFor(server.artifactUri(), artifact);
            Path staging = dir.resolve("artifact.bin");
            FileOtaStateJournal journal = new FileOtaStateJournal(dir.resolve("ota.log"));
            HttpRangeArtifactSource source = new HttpRangeArtifactSource(
                    CHUNK_BYTES, OtaDownloadAssignment.MAX_ARTIFACT_SIZE);

            OtaDeviceExecution interrupted = new OtaDeviceExecution(assignment, staging, source,
                    new OtaScenarioClock(OtaTestFixtures.START), journal, OtaTestFixtures.BUDGET, 2,
                    OtaFaultPlan.builder().powerLossAt(OtaStage.DOWNLOADING).build());
            OtaExecutionResult crashed = interrupted.run();

            assertThat(crashed.state()).isEqualTo(OtaStage.SAFETY_PAUSED);
            assertThat(crashed.reason()).isEqualTo(OtaReasonCode.POWER_LOST);
            assertThat(crashed.observedStage()).isEqualTo(OtaStage.DOWNLOADING);
            assertThat(crashed.downloadedBytes()).isEqualTo(CHUNK_BYTES);
            assertThat(crashed.confirmedDigest())
                    .isEqualTo(OtaTestFixtures.sha256Hex(artifact, (int) CHUNK_BYTES));
            assertThat(journal.read().getLast().downloadedBytes()).isEqualTo(CHUNK_BYTES);

            server.clearObservations();
            OtaDeviceExecution resumed = new OtaDeviceExecution(assignment, staging, source,
                    new OtaScenarioClock(OtaTestFixtures.START.plusSeconds(60)), journal,
                    OtaTestFixtures.BUDGET, 2, OtaFaultPlan.NONE);
            OtaExecutionResult completed = resumed.run();

            // 恢复执行的第一个请求必须从日志已确认的偏移开始，而不是从 0 重来。
            assertThat(server.rangeRequests()).containsExactly("bytes=1024-", "bytes=2048-");
            assertThat(completed.state()).isEqualTo(OtaStage.COMMITTED);
            assertThat(completed.downloadedBytes()).isEqualTo(3072L);
            assertThat(completed.confirmedDigest()).isEqualTo(OtaTestFixtures.sha256Hex(artifact));
            assertThat(completed.claimsArtifactVerified()).isTrue();
            assertThat(completed.transitions()).extracting(OtaJournalRecord::stage)
                    .containsExactly(OtaStage.DOWNLOADING, OtaStage.DOWNLOADING, OtaStage.VERIFYING,
                            OtaStage.INSTALLING, OtaStage.REBOOTING, OtaStage.HEALTH_CHECKING,
                            OtaStage.CONFIRMING, OtaStage.COMMITTED);
            assertThat(Files.readAllBytes(staging)).isEqualTo(artifact);
        }
    }

    /**
     * (b-1) 断电发生在下载阶段，且最后一个分片已经把 artifact 取完；重启后不得重新下载任何字节。
     */
    @Test
    void powerLossDuringDownloadResumesWithoutReDownloading() throws Exception {
        byte[] artifact = OtaTestFixtures.artifact(2048);
        try (OtaRangeServer server = new OtaRangeServer(artifact)) {
            OtaDownloadAssignment assignment = OtaTestFixtures.assignmentFor(server.artifactUri(), artifact);
            Path staging = dir.resolve("artifact.bin");
            FileOtaStateJournal journal = new FileOtaStateJournal(dir.resolve("ota.log"));
            HttpRangeArtifactSource source = new HttpRangeArtifactSource(
                    artifact.length, OtaDownloadAssignment.MAX_ARTIFACT_SIZE);

            OtaExecutionResult crashed = new OtaDeviceExecution(assignment, staging, source,
                    new OtaScenarioClock(OtaTestFixtures.START), journal, OtaTestFixtures.BUDGET, 2,
                    OtaFaultPlan.builder().powerLossAt(OtaStage.DOWNLOADING).build()).run();
            assertThat(crashed.state()).isEqualTo(OtaStage.SAFETY_PAUSED);
            assertThat(crashed.downloadedBytes()).isEqualTo(artifact.length);

            server.clearObservations();
            OtaExecutionResult resumedResult = new OtaDeviceExecution(assignment, staging, source,
                    new OtaScenarioClock(OtaTestFixtures.START.plusSeconds(30)), journal,
                    OtaTestFixtures.BUDGET, 2, OtaFaultPlan.NONE).run();

            assertThat(server.rangeRequests()).isEmpty();
            assertThat(resumedResult.fetchAttempts()).isZero();
            assertThat(resumedResult.state()).isEqualTo(OtaStage.COMMITTED);
            assertThat(resumedResult.claimsArtifactVerified()).isTrue();
        }
    }

    /**
     * (b-2) 断电发生在 INSTALLING：重启后只追加「安装结果未知」的诚实观察，不重新下载、不重新刷写。
     */
    @Test
    void powerLossDuringInstallingDoesNotReflashAndReportsUnknownOutcome() throws Exception {
        byte[] artifact = OtaTestFixtures.artifact(3072);
        try (OtaRangeServer server = new OtaRangeServer(artifact)) {
            OtaDownloadAssignment assignment = OtaTestFixtures.assignmentFor(server.artifactUri(), artifact);
            Path staging = dir.resolve("artifact.bin");
            FileOtaStateJournal journal = new FileOtaStateJournal(dir.resolve("ota.log"));
            HttpRangeArtifactSource source = new HttpRangeArtifactSource(
                    CHUNK_BYTES, OtaDownloadAssignment.MAX_ARTIFACT_SIZE);

            OtaExecutionResult crashed = new OtaDeviceExecution(assignment, staging, source,
                    new OtaScenarioClock(OtaTestFixtures.START), journal, OtaTestFixtures.BUDGET, 2,
                    OtaFaultPlan.builder().powerLossAt(OtaStage.INSTALLING).build()).run();

            assertThat(crashed.state()).isEqualTo(OtaStage.SAFETY_PAUSED);
            assertThat(crashed.reason()).isEqualTo(OtaReasonCode.POWER_LOST);
            assertThat(crashed.observedStage()).isEqualTo(OtaStage.INSTALLING);

            server.clearObservations();
            OtaExecutionResult afterRestart = new OtaDeviceExecution(assignment, staging, source,
                    new OtaScenarioClock(OtaTestFixtures.START.plusSeconds(5)), journal,
                    OtaTestFixtures.BUDGET, 2, OtaFaultPlan.NONE).run();

            assertThat(afterRestart.state()).isEqualTo(OtaStage.SAFETY_PAUSED);
            assertThat(afterRestart.reason()).isEqualTo(OtaReasonCode.INSTALL_OUTCOME_UNKNOWN);
            assertThat(afterRestart.observedStage()).isEqualTo(OtaStage.INSTALLING);
            assertThat(afterRestart.cancelAccepted()).isFalse();
            assertThat(afterRestart.claimsArtifactVerified()).isFalse();
            assertThat(afterRestart.fetchAttempts()).isZero();
            assertThat(server.rangeRequests()).isEmpty();
            // 唯一的新记录是「安装结果未知」观察；没有任何一次新的 INSTALLING 进入记录。
            assertThat(afterRestart.transitions()).hasSize(1);
            assertThat(afterRestart.transitions().getFirst().reasonCode())
                    .isEqualTo(OtaReasonCode.INSTALL_OUTCOME_UNKNOWN);
            assertThat(journal.read()).filteredOn(record -> record.stage() == OtaStage.INSTALLING
                            && record.reasonCode() == OtaReasonCode.NONE)
                    .hasSize(1);
            assertThat(journal.read()).extracting(OtaJournalRecord::stage)
                    .doesNotContain(OtaStage.COMMITTED);
        }
    }

    /**
     * 下载前的取消边界：取消在安装窗口打开之前到达，应被接受为干净取消。
     */
    @Test
    void cancelAtDownloadBoundaryIsAcceptedBeforeAnyFetch() throws Exception {
        byte[] artifact = OtaTestFixtures.artifact(2048);
        try (OtaRangeServer server = new OtaRangeServer(artifact)) {
            OtaDeviceExecution execution = new OtaDeviceExecution(
                    OtaTestFixtures.assignmentFor(server.artifactUri(), artifact), dir.resolve("artifact.bin"),
                    new HttpRangeArtifactSource(CHUNK_BYTES, OtaDownloadAssignment.MAX_ARTIFACT_SIZE),
                    new OtaScenarioClock(OtaTestFixtures.START),
                    new FileOtaStateJournal(dir.resolve("ota.log")), OtaTestFixtures.BUDGET, 2,
                    OtaFaultPlan.builder().cancelAtSafePoint(OtaStage.DOWNLOADING).build());

            OtaExecutionResult result = execution.run();

            assertThat(result.state()).isEqualTo(OtaStage.CANCELLED);
            assertThat(result.reason()).isEqualTo(OtaReasonCode.CANCELLED_BEFORE_SAFETY_STAGE);
            assertThat(result.cancelAccepted()).isTrue();
            assertThat(result.fetchAttempts()).isZero();
            assertThat(server.rangeRequests()).isEmpty();
        }
    }

    /**
     * 恢复执行不会因为重复调用而重复副作用：已提交后再次 run 必须幂等且不请求任何字节。
     */
    @Test
    void secondRunAfterCommitIsIdempotent() throws Exception {
        byte[] artifact = OtaTestFixtures.artifact(1024);
        try (OtaRangeServer server = new OtaRangeServer(artifact)) {
            OtaDownloadAssignment assignment = OtaTestFixtures.assignmentFor(server.artifactUri(), artifact);
            Path staging = dir.resolve("artifact.bin");
            FileOtaStateJournal journal = new FileOtaStateJournal(dir.resolve("ota.log"));
            HttpRangeArtifactSource source = new HttpRangeArtifactSource(
                    CHUNK_BYTES, OtaDownloadAssignment.MAX_ARTIFACT_SIZE);

            OtaExecutionResult first = new OtaDeviceExecution(assignment, staging, source,
                    new OtaScenarioClock(OtaTestFixtures.START), journal, OtaTestFixtures.BUDGET, 2,
                    OtaFaultPlan.NONE).run();
            assertThat(first.state()).isEqualTo(OtaStage.COMMITTED);

            server.clearObservations();
            List<OtaJournalRecord> before = journal.read();
            OtaExecutionResult second = new OtaDeviceExecution(assignment, staging, source,
                    new OtaScenarioClock(OtaTestFixtures.START.plusSeconds(10)), journal,
                    OtaTestFixtures.BUDGET, 2, OtaFaultPlan.NONE).run();

            assertThat(second.state()).isEqualTo(OtaStage.COMMITTED);
            assertThat(second.transitions()).isEmpty();
            assertThat(second.fetchAttempts()).isZero();
            assertThat(server.rangeRequests()).isEmpty();
            assertThat(journal.read()).isEqualTo(before);
        }
    }
}
