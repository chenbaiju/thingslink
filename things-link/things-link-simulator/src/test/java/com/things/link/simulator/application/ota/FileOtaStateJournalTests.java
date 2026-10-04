package com.things.link.simulator.application.ota;

import com.things.link.simulator.infrastructure.ota.FileOtaStateJournal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实文件日志的耐久读写契约：追加顺序、重启可读、坏行拒绝。
 *
 * <p>恢复逻辑只看最后一条记录，因此「重启后读到的顺序与字段完全一致」是本片所有断电场景的前提。</p>
 */
class FileOtaStateJournalTests {

    /** 每个用例独立的暂存目录。 */
    @TempDir
    Path dir;

    /** 固定 artifact 摘要。 */
    private static final String ARTIFACT_SHA = OtaTestFixtures.sha256Hex("artifact");

    /** 固定清单摘要。 */
    private static final String MANIFEST_SHA = OtaTestFixtures.sha256Hex("manifest");

    /**
     * 首次运行时文件还不存在，读取必须返回空列表而不是异常。
     */
    @Test
    void returnsEmptyBeforeFirstAppend() {
        FileOtaStateJournal journal = new FileOtaStateJournal(dir.resolve("nested/ota.log"));

        assertThat(journal.read()).isEmpty();
    }

    /**
     * 追加的记录在重新打开日志后按顺序完整读回。
     */
    @Test
    void appendsAndReadsRecordsInOrder() {
        Path file = dir.resolve("ota.log");
        FileOtaStateJournal writer = new FileOtaStateJournal(file);
        OtaJournalRecord dispatched = record(OtaStage.DISPATCHED, 0L, OtaDigests.EMPTY_SHA256,
                OtaReasonCode.NONE, false);
        OtaJournalRecord downloading = record(OtaStage.DOWNLOADING, 1024L,
                OtaTestFixtures.sha256Hex(OtaTestFixtures.artifact(1024)), OtaReasonCode.NONE, false);
        OtaJournalRecord paused = record(OtaStage.DOWNLOADING, 1024L,
                OtaTestFixtures.sha256Hex(OtaTestFixtures.artifact(1024)), OtaReasonCode.POWER_LOST, false);
        writer.append(dispatched);
        writer.append(downloading);
        writer.append(paused);

        FileOtaStateJournal reader = new FileOtaStateJournal(file);

        assertThat(reader.read()).containsExactly(dispatched, downloading, paused);
        assertThat(reader.read().getLast().downloadedBytes()).isEqualTo(1024L);
        assertThat(reader.file()).isEqualTo(file.toAbsolutePath());
    }

    /**
     * 一行一条记录：三行文本恰好对应三条记录。
     */
    @Test
    void writesOneLinePerRecord() throws Exception {
        Path file = dir.resolve("ota.log");
        FileOtaStateJournal journal = new FileOtaStateJournal(file);
        journal.append(record(OtaStage.DISPATCHED, 0L, OtaDigests.EMPTY_SHA256, OtaReasonCode.NONE, false));
        journal.append(record(OtaStage.VERIFYING, 2048L, ARTIFACT_SHA, OtaReasonCode.DIGEST_MISMATCH, false));

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);

        assertThat(lines).hasSize(2);
        assertThat(lines.getFirst()).startsWith("DISPATCHED|1|0|");
        assertThat(lines.getLast()).endsWith("|DIGEST_MISMATCH|false");
    }

    /**
     * 日志损坏时必须直接失败：宁可停在「拒绝刷写」，也不能跳过坏行继续。
     */
    @Test
    void rejectsMalformedLine() throws Exception {
        Path file = dir.resolve("ota.log");
        Files.writeString(file, "这不是合法记录\n", StandardCharsets.UTF_8);

        FileOtaStateJournal journal = new FileOtaStateJournal(file);

        assertThatThrownBy(journal::read)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OTA 日志行");
    }

    /**
     * 枚举名与原因码必须能在跨进程重开后精确还原。
     */
    @Test
    void roundTripsStageAndReasonCode() {
        Path file = dir.resolve("ota.log");
        FileOtaStateJournal journal = new FileOtaStateJournal(file);
        OtaJournalRecord paused = record(OtaStage.INSTALLING, 2048L, ARTIFACT_SHA, OtaReasonCode.POWER_LOST, false);
        journal.append(paused);

        OtaJournalRecord restored = journal.read().getFirst();

        assertThat(restored.stage()).isEqualTo(OtaStage.INSTALLING);
        assertThat(restored.reasonCode()).isEqualTo(OtaReasonCode.POWER_LOST);
        assertThat(restored.conclusion()).isEqualTo(OtaStage.SAFETY_PAUSED);
    }

    /** 构造一条合法记录。 */
    private static OtaJournalRecord record(OtaStage stage, long offset, String digest, OtaReasonCode reason,
                                           boolean cancelAccepted) {
        return new OtaJournalRecord(stage, 1, offset, digest, ARTIFACT_SHA, 2048L, MANIFEST_SHA,
                Instant.parse("2026-09-12T00:00:00Z"), reason, cancelAccepted);
    }
}
