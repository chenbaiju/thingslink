package com.things.link.simulator.application.ota;

import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 追加写的 OTA 尝试耐久记录，字段与平台合同对齐，便于后续机械接线。
 *
 * <p><b>为什么日志必须先于「分片可丢弃」落盘：</b>设备的下载进度只有两处事实——暂存区里的字节和
 * 日志里的 offset。断电后无法区分「字节已写入但没记日志」与「字节丢了」；因此实现顺序固定为
 * 「先写暂存区内容，再追加本条记录并 fsync，最后才在内存推进 offset」。这样日志永远不会声称拥有
 * 暂存区里不存在的字节，重启时若发现日志 offset 大于暂存区长度，就可以直接判 {@link
 * OtaReasonCode#STAGING_INCONSISTENT} 而绝不刷写。</p>
 *
 * <p>{@link #stage()} 始终是<b>设备真实处在的阶段</b>，而不是结论；结论由 {@link #reasonCode()} 的
 * {@link OtaReasonCode#conclusion()} 推导。这样同一个 {@code INSTALLING} 既可以是「正在刷写」，
 * 也可以是「刷写时掉电、结果未知」，重启判定不需要再猜。</p>
 *
 * <p>字段名刻意复用平台证据字段：{@code artifactSha256}/{@code artifactSize} 对应 ADR0131 的 19 字段
 * 证据，{@code manifestSha256} 对应 ADR0128/0131 的清单摘要，{@code attemptNo} 对应作业尝试号。</p>
 *
 * @param stage 设备真实处在的阶段
 * @param attemptNo 作业尝试号，与平台 {@code attemptNo} 同义
 * @param downloadedBytes 已耐久确认的 artifact 字节数
 * @param confirmedDigestSoFar 对已确认前缀计算出的 SHA-256（小写十六进制）；提交时它等于预期完整摘要
 * @param artifactSha256 预期完整 artifact 摘要（平台证据字段同名）
 * @param artifactSize 预期 artifact 总字节数（平台证据字段同名）
 * @param manifestSha256 已签名清单摘要，用于把记录绑定到唯一一次派发
 * @param recordedAt 记录时刻（模拟器时钟）
 * @param reasonCode 稳定原因码；正常推进为 {@link OtaReasonCode#NONE}
 * @param cancelAccepted 该记录是否对应一次被设备接受的干净取消
 */
public record OtaJournalRecord(
        OtaStage stage,
        int attemptNo,
        long downloadedBytes,
        String confirmedDigestSoFar,
        String artifactSha256,
        long artifactSize,
        String manifestSha256,
        Instant recordedAt,
        OtaReasonCode reasonCode,
        boolean cancelAccepted) {

    /** 摘要字段必须是完整的 64 位小写十六进制。 */
    private static final String SHA256_PATTERN = "[0-9a-f]{64}";

    /**
     * 校验不可变记录的唯一形态。
     *
     * <p>严格校验不是形式主义：这些值会跨进程重启读回，并在未来直接作为进度/提交报文的证据字段。
     * 如果允许 null 或半截摘要进入日志，重启判定就会在「看起来像成功」和「其实没有证据」之间摇摆。</p>
     */
    public OtaJournalRecord {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(confirmedDigestSoFar, "confirmedDigestSoFar");
        Objects.requireNonNull(artifactSha256, "artifactSha256");
        Objects.requireNonNull(manifestSha256, "manifestSha256");
        Objects.requireNonNull(recordedAt, "recordedAt");
        Objects.requireNonNull(reasonCode, "reasonCode");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo 必须为正整数");
        }
        if (downloadedBytes < 0L) {
            throw new IllegalArgumentException("downloadedBytes 不能为负数");
        }
        if (artifactSize < 1L) {
            throw new IllegalArgumentException("artifactSize 必须为正数");
        }
        if (downloadedBytes > artifactSize) {
            throw new IllegalArgumentException("已确认字节数不能超过 artifact 总长度");
        }
        if (!confirmedDigestSoFar.matches(SHA256_PATTERN) || !artifactSha256.matches(SHA256_PATTERN)
                || !manifestSha256.matches(SHA256_PATTERN)) {
            throw new IllegalArgumentException("摘要字段必须是 64 位小写十六进制");
        }
        if (downloadedBytes == 0L && !confirmedDigestSoFar.equals(OtaDigests.EMPTY_SHA256)) {
            throw new IllegalArgumentException("零字节前缀的摘要必须是空输入摘要");
        }
    }

    /**
     * 返回该记录的结论阶段，正常推进时为空。
     *
     * @return 结论阶段；仍在进行中时为 {@code null}
     */
    public OtaStage conclusion() {
        OtaStage conclusion = reasonCode.conclusion();
        if (conclusion != null) {
            return conclusion;
        }
        return stage.isTerminal() ? stage : null;
    }

    /**
     * 生成稳定的单行文本形式，供文件日志追加。
     *
     * @return 不含换行的定宽字段行
     */
    public String toLogLine() {
        return stage.name()
                + '|' + attemptNo
                + '|' + downloadedBytes
                + '|' + confirmedDigestSoFar
                + '|' + artifactSha256
                + '|' + artifactSize
                + '|' + manifestSha256
                + '|' + recordedAt.toEpochMilli()
                + '|' + reasonCode.name()
                + '|' + cancelAccepted;
    }

    /**
     * 解析 {@link #toLogLine()} 产生的单行文本。
     *
     * @param line 一行文本
     * @return 解析出的记录
     * @throws IllegalArgumentException 字段数、枚举或数字不合法时
     */
    public static OtaJournalRecord fromLogLine(String line) {
        String[] fields = line.split("\\|", -1);
        if (fields.length != 10) {
            throw new IllegalArgumentException("OTA 日志行必须包含 10 个字段: " + fields.length);
        }
        try {
            return new OtaJournalRecord(
                    OtaStage.valueOf(fields[0]),
                    Integer.parseInt(fields[1]),
                    Long.parseLong(fields[2]),
                    fields[3],
                    fields[4],
                    Long.parseLong(fields[5]),
                    fields[6],
                    Instant.ofEpochMilli(Long.parseLong(fields[7])),
                    OtaReasonCode.valueOf(fields[8]),
                    Boolean.parseBoolean(fields[9]));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("OTA 日志行不合法", failure);
        }
    }

    /**
     * 判断前缀摘要是否与给定前缀的完整摘要一致。
     *
     * @param fullDigestOfPrefix 对暂存区前缀重新计算出的完整 SHA-256
     * @return {@code true} 表示日志与暂存区前缀一致
     */
    boolean matchesPrefixDigest(String fullDigestOfPrefix) {
        return confirmedDigestSoFar.equals(fullDigestOfPrefix);
    }

    /** 把字节数组摘要格式化为小写十六进制。 */
    static String hex(byte[] digest) {
        return HexFormat.of().formatHex(digest);
    }
}
