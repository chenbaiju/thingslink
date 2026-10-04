package com.things.link.simulator.application.ota;

import com.things.link.simulator.infrastructure.ota.FileOtaStateJournal;
import com.things.link.simulator.infrastructure.ota.HttpRangeArtifactSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 故障处置：首错安全暂停、有界下载失败预算、恢复被拒、取消安全点。
 *
 * <p>覆盖验收项 (c)(d)(e)(f)。全部场景使用推进式时钟，等待时间为零。</p>
 */
class OtaDeviceExecutionFaultTests {

    /** 每个用例独立的暂存目录。 */
    @TempDir
    Path dir;

    /** 单分片读取上限。 */
    private static final long CHUNK_BYTES = 1024L;

    /**
     * (c) 首分片失败 → SAFETY_PAUSED，稳定原因码，且不自动重试（取字节次数为 1）。
     */
    @Test
    void firstChunkFailurePausesWithoutAutomaticRetry() throws Exception {
        byte[] artifact = OtaTestFixtures.artifact(2048);
        try (OtaRangeServer server = new OtaRangeServer(artifact)) {
            OtaScenarioClock clock = new OtaScenarioClock(OtaTestFixtures.START);
            FileOtaStateJournal journal = new FileOtaStateJournal(dir.resolve("ota.log"));

            OtaExecutionResult result = new OtaDeviceExecution(
                    OtaTestFixtures.assignmentFor(server.artifactUri(), artifact), dir.resolve("artifact.bin"),
                    new HttpRangeArtifactSource(CHUNK_BYTES, OtaDownloadAssignment.MAX_ARTIFACT_SIZE),
                    clock, journal, OtaTestFixtures.BUDGET, 2,
                    OtaFaultPlan.builder().failFirstChunk().build()).run();

            assertThat(result.state()).isEqualTo(OtaStage.SAFETY_PAUSED);
            assertThat(result.reason()).isEqualTo(OtaReasonCode.FIRST_CHUNK_FAILED_SAFETY_PAUSE);
            assertThat(result.observedStage()).isEqualTo(OtaStage.DOWNLOADING);
            assertThat(result.fetchAttempts()).isEqualTo(1);
            assertThat(result.downloadedBytes()).isZero();
            assertThat(clock.sleeps()).isEmpty();
            assertThat(server.rangeRequests()).isEmpty();
            assertThat(journal.read()).extracting(OtaJournalRecord::stage)
                    .containsExactly(OtaStage.DISPATCHED, OtaStage.DOWNLOADING);
            assertThat(journal.read().getLast().reasonCode())
                    .isEqualTo(OtaReasonCode.FIRST_CHUNK_FAILED_SAFETY_PAUSE);
        }
    }

    /**
     * (d-1) 有界预算内，真实 503 抖动被重试，日志保持已确认进度，最终提交成功。
     */
    @Test
    void retriesTransientDownloadFailuresWithinBudget() throws Exception {
        byte[] artifact = OtaTestFixtures.artifact(2048);
        try (OtaRangeServer server = new OtaRangeServer(artifact)) {
            server.failNextRequests(2);
            OtaScenarioClock clock = new OtaScenarioClock(OtaTestFixtures.START);
            FileOtaStateJournal journal = new FileOtaStateJournal(dir.resolve("ota.log"));

            OtaExecutionResult result = new OtaDeviceExecution(
                    OtaTestFixtures.assignmentFor(server.artifactUri(), artifact), dir.resolve("artifact.bin"),
                    new HttpRangeArtifactSource(artifact.length, OtaDownloadAssignment.MAX_ARTIFACT_SIZE),
                    clock, journal, OtaTestFixtures.BUDGET, 2, OtaFaultPlan.NONE).run();

            assertThat(result.state()).isEqualTo(OtaStage.COMMITTED);
            assertThat(result.fetchAttempts()).isEqualTo(3);
            assertThat(clock.sleeps()).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(1));
            // 抖动不写日志：唯一的下载进度记录就是成功那一次，偏移没有被重置。
            assertThat(journal.read()).extracting(OtaJournalRecord::stage)
                    .containsExactly(OtaStage.DISPATCHED, OtaStage.DOWNLOADING, OtaStage.VERIFYING,
                            OtaStage.INSTALLING, OtaStage.REBOOTING, OtaStage.HEALTH_CHECKING,
                            OtaStage.CONFIRMING, OtaStage.COMMITTED);
            assertThat(journal.read()).extracting(OtaJournalRecord::reasonCode)
                    .containsOnly(OtaReasonCode.NONE);
            assertThat(journal.read().get(1).downloadedBytes()).isEqualTo(artifact.length);
        }
    }

    /**
     * (d-2) 超出有界预算 → FAILED，且绝不进入刷写。
     */
    @Test
    void exceedsDownloadFailureBudgetBecomesFailed() throws Exception {
        byte[] artifact = OtaTestFixtures.artifact(2048);
        try (OtaRangeServer server = new OtaRangeServer(artifact)) {
            server.failNextRequests(3);
            OtaScenarioClock clock = new OtaScenarioClock(OtaTestFixtures.START);
            FileOtaStateJournal journal = new FileOtaStateJournal(dir.resolve("ota.log"));

            OtaExecutionResult result = new OtaDeviceExecution(
                    OtaTestFixtures.assignmentFor(server.artifactUri(), artifact), dir.resolve("artifact.bin"),
                    new HttpRangeArtifactSource(artifact.length, OtaDownloadAssignment.MAX_ARTIFACT_SIZE),
                    clock, journal, OtaTestFixtures.BUDGET, 2, OtaFaultPlan.NONE).run();

            assertThat(result.state()).isEqualTo(OtaStage.FAILED);
            assertThat(result.reason()).isEqualTo(OtaReasonCode.DOWNLOAD_FAILURE_BUDGET_EXHAUSTED);
            assertThat(result.fetchAttempts()).isEqualTo(3);
            assertThat(result.downloadedBytes()).isZero();
            assertThat(journal.read()).extracting(OtaJournalRecord::stage)
                    .doesNotContain(OtaStage.INSTALLING, OtaStage.COMMITTED);
        }
    }

    /**
     * (e-1) 计划注入的恢复拒绝：保持暂停、保留已确认偏移、完全不发起新请求。
     */
    @Test
    void rejectedResumePlanStaysPausedAtConfirmedOffset() throws Exception {
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
            server.clearObservations();

            OtaExecutionResult result = new OtaDeviceExecution(assignment, staging, source,
                    new OtaScenarioClock(OtaTestFixtures.START.plusSeconds(30)), journal,
                    OtaTestFixtures.BUDGET, 2, OtaFaultPlan.builder().rejectResume().build()).run();

            assertThat(result.state()).isEqualTo(OtaStage.SAFETY_PAUSED);
            assertThat(result.reason()).isEqualTo(OtaReasonCode.RESUME_REJECTED);
            assertThat(result.downloadedBytes()).isEqualTo(CHUNK_BYTES);
            assertThat(result.fetchAttempts()).isZero();
            assertThat(server.rangeRequests()).isEmpty();
            assertThat(journal.read().getLast().downloadedBytes()).isEqualTo(CHUNK_BYTES);
        }
    }

    /**
     * (e-2) 真实传输拒绝续传（服务器忽略 Range 回 200）：仍停在已确认偏移，请求的是续传偏移而非 0。
     */
    @Test
    void serverIgnoringRangeStaysPausedAtConfirmedOffset() throws Exception {
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

            server.clearObservations();
            server.ignoreRange();
            OtaExecutionResult result = new OtaDeviceExecution(assignment, staging, source,
                    new OtaScenarioClock(OtaTestFixtures.START.plusSeconds(30)), journal,
                    OtaTestFixtures.BUDGET, 2, OtaFaultPlan.NONE).run();

            assertThat(server.rangeRequests()).containsExactly("bytes=1024-");
            assertThat(server.statuses()).containsExactly(200);
            assertThat(result.state()).isEqualTo(OtaStage.SAFETY_PAUSED);
            assertThat(result.reason()).isEqualTo(OtaReasonCode.RESUME_REJECTED);
            assertThat(result.downloadedBytes()).isEqualTo(CHUNK_BYTES);
            assertThat(journal.read().getLast().downloadedBytes()).isEqualTo(CHUNK_BYTES);
        }
    }

    /**
     * (f-1) 安装窗口打开后到达的取消被拒绝：上报真实阶段，而不是伪造 CANCELLED。
     */
    @Test
    void cancelAfterSafetyStageReportsRealStageInsteadOfFakeCancel() throws Exception {
        byte[] artifact = OtaTestFixtures.artifact(2048);
        try (OtaRangeServer server = new OtaRangeServer(artifact)) {
            OtaDownloadAssignment assignment = OtaTestFixtures.assignmentFor(server.artifactUri(), artifact);
            Path staging = dir.resolve("artifact.bin");
            FileOtaStateJournal journal = new FileOtaStateJournal(dir.resolve("ota.log"));
            HttpRangeArtifactSource source = new HttpRangeArtifactSource(
                    artifact.length, OtaDownloadAssignment.MAX_ARTIFACT_SIZE);

            OtaExecutionResult refused = new OtaDeviceExecution(assignment, staging, source,
                    new OtaScenarioClock(OtaTestFixtures.START), journal, OtaTestFixtures.BUDGET, 2,
                    OtaFaultPlan.builder().cancelAtSafePoint(OtaStage.INSTALLING).build()).run();

            assertThat(refused.state()).isNotEqualTo(OtaStage.CANCELLED);
            assertThat(refused.state()).isEqualTo(OtaStage.SAFETY_PAUSED);
            assertThat(refused.reason()).isEqualTo(OtaReasonCode.CANCEL_REFUSED_AFTER_SAFETY_STAGE);
            assertThat(refused.observedStage()).isEqualTo(OtaStage.INSTALLING);
            assertThat(refused.cancelAccepted()).isFalse();

            // 被拒绝的取消不会因为重启而变成干净取消，而是走「安装结果未知」。
            OtaExecutionResult afterRestart = new OtaDeviceExecution(assignment, staging, source,
                    new OtaScenarioClock(OtaTestFixtures.START.plusSeconds(10)), journal,
                    OtaTestFixtures.BUDGET, 2, OtaFaultPlan.NONE).run();
            assertThat(afterRestart.state()).isEqualTo(OtaStage.SAFETY_PAUSED);
            assertThat(afterRestart.reason()).isEqualTo(OtaReasonCode.INSTALL_OUTCOME_UNKNOWN);
            assertThat(afterRestart.observedStage()).isEqualTo(OtaStage.INSTALLING);
        }
    }

    /**
     * (f-2) 取消在更晚的安全阶段到达时，上报的是该阶段而不是统一写死 INSTALLING。
     */
    @Test
    void cancelAtConfirmingReportsConfirmingStage() throws Exception {
        byte[] artifact = OtaTestFixtures.artifact(2048);
        try (OtaRangeServer server = new OtaRangeServer(artifact)) {
            OtaExecutionResult result = new OtaDeviceExecution(
                    OtaTestFixtures.assignmentFor(server.artifactUri(), artifact), dir.resolve("artifact.bin"),
                    new HttpRangeArtifactSource(artifact.length, OtaDownloadAssignment.MAX_ARTIFACT_SIZE),
                    new OtaScenarioClock(OtaTestFixtures.START),
                    new FileOtaStateJournal(dir.resolve("ota.log")), OtaTestFixtures.BUDGET, 2,
                    OtaFaultPlan.builder().cancelAtSafePoint(OtaStage.CONFIRMING).build()).run();

            assertThat(result.state()).isEqualTo(OtaStage.SAFETY_PAUSED);
            assertThat(result.reason()).isEqualTo(OtaReasonCode.CANCEL_REFUSED_AFTER_SAFETY_STAGE);
            assertThat(result.observedStage()).isEqualTo(OtaStage.CONFIRMING);
            assertThat(result.transitions()).extracting(OtaJournalRecord::stage)
                    .doesNotContain(OtaStage.COMMITTED, OtaStage.CANCELLED);
        }
    }

    /**
     * (f-3) 取消在安全阶段之前（VERIFYING 边界）到达仍被接受，并且下载进度被保留。
     */
    @Test
    void cancelBeforeSafetyStageAfterDownloadIsAccepted() throws Exception {
        byte[] artifact = OtaTestFixtures.artifact(2048);
        try (OtaRangeServer server = new OtaRangeServer(artifact)) {
            OtaExecutionResult result = new OtaDeviceExecution(
                    OtaTestFixtures.assignmentFor(server.artifactUri(), artifact), dir.resolve("artifact.bin"),
                    new HttpRangeArtifactSource(artifact.length, OtaDownloadAssignment.MAX_ARTIFACT_SIZE),
                    new OtaScenarioClock(OtaTestFixtures.START),
                    new FileOtaStateJournal(dir.resolve("ota.log")), OtaTestFixtures.BUDGET, 2,
                    OtaFaultPlan.builder().cancelAtSafePoint(OtaStage.VERIFYING).build()).run();

            assertThat(result.state()).isEqualTo(OtaStage.CANCELLED);
            assertThat(result.reason()).isEqualTo(OtaReasonCode.CANCELLED_BEFORE_SAFETY_STAGE);
            assertThat(result.cancelAccepted()).isTrue();
            assertThat(result.downloadedBytes()).isEqualTo(artifact.length);
            assertThat(result.transitions()).extracting(OtaJournalRecord::stage)
                    .doesNotContain(OtaStage.INSTALLING, OtaStage.COMMITTED);
        }
    }
}
