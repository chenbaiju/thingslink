package com.things.link.simulator.application.ota;

import com.things.link.simulator.infrastructure.ota.FileOtaAcceptedDownloadResponseStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 设备本地「已接纳下载响应身份」单槽存储的真实文件契约（债务 D-159 设备半边）。
 *
 * <p>覆盖两类必须证伪的行为：写入必须原子替换且跨重开可完整读回；文件缺失、为空、损坏、被截断或
 * 版本未知时都必须被当成「没有记录」失败关闭，读取本身绝不崩溃。</p>
 */
class FileOtaAcceptedDownloadResponseStoreTests {

    /** 每个用例独立的暂存目录。 */
    @TempDir
    Path dir;

    /** 固定作业身份。 */
    private static final UUID JOB_ID = UUID.fromString("11111111-1111-7111-8111-111111111111");

    /** 固定请求身份。 */
    private static final UUID REQUEST_ID = UUID.fromString("22222222-2222-7222-8222-222222222222");

    /** 固定授权身份。 */
    private static final UUID AUTHORIZATION_ID = UUID.fromString("33333333-3333-7333-8333-333333333333");

    /** 固定清单摘要。 */
    private static final String MANIFEST_SHA = OtaTestFixtures.sha256Hex("manifest");

    /** 固定 artifact 摘要。 */
    private static final String ARTIFACT_SHA = OtaTestFixtures.sha256Hex("artifact");

    /** 固定落盘时刻。 */
    private static final Instant RECORDED_AT = Instant.parse("2026-09-12T00:00:00Z");

    /** 首次写入前文件不存在，读取必须返回空而不是异常。 */
    @Test
    void returnsEmptyBeforeFirstWrite() {
        FileOtaAcceptedDownloadResponseStore store =
                new FileOtaAcceptedDownloadResponseStore(dir.resolve("nested/accepted-download"));

        assertThat(store.read()).isEmpty();
    }

    /** 重新打开后必须逐字段读回同一身份。 */
    @Test
    void roundTripsAcceptedIdentityAcrossReopen() {
        Path file = dir.resolve("accepted-download");
        OtaAcceptedDownloadResponse expected = record();
        new FileOtaAcceptedDownloadResponseStore(file).write(expected);

        OtaAcceptedDownloadResponse restored = new FileOtaAcceptedDownloadResponseStore(file).read().orElseThrow();

        assertThat(restored).isEqualTo(expected);
        assertThat(restored.contractVersion()).isEqualTo(OtaAcceptedDownloadResponse.CONTRACT_VERSION);
        assertThat(restored.recordedAt()).isEqualTo(RECORDED_AT);
    }

    /** 单槽覆盖写：第二次写入后只应读到新身份，且不留下临时文件。 */
    @Test
    void replacesPreviousIdentityWithNewerOne() throws Exception {
        Path file = dir.resolve("accepted-download");
        FileOtaAcceptedDownloadResponseStore store = new FileOtaAcceptedDownloadResponseStore(file);
        store.write(record());
        OtaAcceptedDownloadResponse newer = new OtaAcceptedDownloadResponse(
                OtaAcceptedDownloadResponse.CONTRACT_VERSION, JOB_ID, UUID.randomUUID(), AUTHORIZATION_ID, 2,
                MANIFEST_SHA, ARTIFACT_SHA, 4096L, RECORDED_AT);

        store.write(newer);

        assertThat(store.read()).contains(newer);
        assertThat(Files.readAllLines(file, StandardCharsets.UTF_8)).hasSize(1);
        assertThat(Files.exists(file.resolveSibling("accepted-download.tmp"))).isFalse();
    }

    /** 损坏内容必须被当成「没有记录」，而不是让读取崩溃或放行执行。 */
    @Test
    void treatsMalformedFileAsNoRecord() throws Exception {
        Path file = dir.resolve("accepted-download");
        Files.writeString(file, "这不是合法记录\n", StandardCharsets.UTF_8);

        assertThat(new FileOtaAcceptedDownloadResponseStore(file).read()).isEmpty();
    }

    /** 被截断的记录必须被当成「没有记录」。 */
    @Test
    void treatsTruncatedFileAsNoRecord() throws Exception {
        Path file = dir.resolve("accepted-download");
        Files.writeString(file, OtaAcceptedDownloadResponse.CONTRACT_VERSION + '|' + JOB_ID + '|' + REQUEST_ID,
                StandardCharsets.UTF_8);

        assertThat(new FileOtaAcceptedDownloadResponseStore(file).read()).isEmpty();
    }

    /** 摘要形态不合法的记录必须被当成「没有记录」。 */
    @Test
    void treatsInvalidDigestAsNoRecord() throws Exception {
        Path file = dir.resolve("accepted-download");
        String corrupted = record().toLogLine().replace(ARTIFACT_SHA, "not-a-digest");
        Files.writeString(file, corrupted + System.lineSeparator(), StandardCharsets.UTF_8);

        assertThat(new FileOtaAcceptedDownloadResponseStore(file).read()).isEmpty();
    }

    /** 未知本地格式版本必须被当成「没有记录」，绝不猜测旧格式含义。 */
    @Test
    void treatsUnknownFormatVersionAsNoRecord() throws Exception {
        Path file = dir.resolve("accepted-download");
        String unknownVersion = record().toLogLine()
                .replace(OtaAcceptedDownloadResponse.CONTRACT_VERSION, "tc-ota-device-accepted-download/v9");
        Files.writeString(file, unknownVersion + System.lineSeparator(), StandardCharsets.UTF_8);

        assertThat(new FileOtaAcceptedDownloadResponseStore(file).read()).isEmpty();
    }

    /** 空文件必须被当成「没有记录」。 */
    @Test
    void treatsEmptyFileAsNoRecord() throws Exception {
        Path file = dir.resolve("accepted-download");
        Files.writeString(file, "", StandardCharsets.UTF_8);

        assertThat(new FileOtaAcceptedDownloadResponseStore(file).read()).isEmpty();
    }

    /** 意外追加出第二行的文件无法证明是原记录，必须被当成「没有记录」。 */
    @Test
    void treatsMultipleLinesAsNoRecord() throws Exception {
        Path file = dir.resolve("accepted-download");
        Files.writeString(file, record().toLogLine() + System.lineSeparator() + record().toLogLine(),
                StandardCharsets.UTF_8);

        assertThat(new FileOtaAcceptedDownloadResponseStore(file).read()).isEmpty();
    }

    /** 构造一条身份完整的固定记录。 */
    private static OtaAcceptedDownloadResponse record() {
        return new OtaAcceptedDownloadResponse(OtaAcceptedDownloadResponse.CONTRACT_VERSION, JOB_ID, REQUEST_ID,
                AUTHORIZATION_ID, 1, MANIFEST_SHA, ARTIFACT_SHA, 2048L, RECORDED_AT);
    }
}
