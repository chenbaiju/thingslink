package com.things.link.simulator.application.ota;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 设备本地耐久记下的「已接纳下载响应」身份，用于区分「我此前确实接纳过的重投递」与「未申请的响应」。
 *
 * <p><b>为什么不能只靠内存里的待处理申请：</b>下载响应是 QoS 1 报文，Broker 可能在设备已经执行完一次
 * 之后再次投递同一份字节；进程重启后内存里的待处理申请更是全部丢失。若设备只认内存，就会把
 * 「自己此前明确接纳过的响应的重复投递」当成未申请响应拒绝，从而永远无法在掉电后从耐久偏移续传。</p>
 *
 * <p><b>本记录只保存身份，不保存授权材料：</b>这里没有下载地址、签名、公钥或清单正文，只有
 * {@code jobId}/{@code requestId}/{@code authorizationId}/{@code attemptNo}/{@code manifestSha256}/
 * {@code artifactSha256}/{@code artifactSize} 这七个身份字段。重投递必须与它们逐字段一致才可能被接纳，
 * 而真正的取字节仍由调用方新收到的响应（含地址与清单）驱动；本记录绝不代替平台授权。</p>
 *
 * <p><b>格式版本是设备本地事实：</b>{@link #CONTRACT_VERSION} 只描述本文件的字节格式，不进入任何平台合同。
 * 版本不符时读取方必须按「没有记录」处理并失败关闭，而不是猜测旧格式含义。</p>
 *
 * @param contractVersion 设备本地记录格式版本，必须等于 {@link #CONTRACT_VERSION}
 * @param jobId 平台作业标识
 * @param requestId 设备为本次下载生成的请求标识
 * @param authorizationId 平台封存的下载授权标识
 * @param attemptNo 作业尝试号，必须为正整数
 * @param manifestSha256 已签名清单摘要（64 位小写十六进制）
 * @param artifactSha256 清单声明的目标 artifact 摘要（64 位小写十六进制）
 * @param artifactSize 清单声明的目标 artifact 总字节数
 * @param recordedAt 身份耐久落盘时刻（模拟器时钟）
 */
public record OtaAcceptedDownloadResponse(
        String contractVersion,
        UUID jobId,
        UUID requestId,
        UUID authorizationId,
        int attemptNo,
        String manifestSha256,
        String artifactSha256,
        long artifactSize,
        Instant recordedAt) {

    /** 设备本地记录格式版本；与平台合同无关，只用于拒绝未知字节格式。 */
    public static final String CONTRACT_VERSION = "tc-ota-device-accepted-download/v1";

    /** 摘要字段必须是完整的 64 位小写十六进制。 */
    private static final String SHA256_PATTERN = "[0-9a-f]{64}";

    /**
     * 校验不可变记录的唯一形态。
     *
     * <p>这些字段会跨进程重启读回并直接决定「是否允许再次执行」，因此任何缺失、版本不符或摘要形态
     * 不合法都必须在构造期失败；读取方再把构造失败转换成「没有记录」，保证失败关闭而不是崩溃。</p>
     */
    public OtaAcceptedDownloadResponse {
        Objects.requireNonNull(contractVersion, "contractVersion");
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(authorizationId, "authorizationId");
        Objects.requireNonNull(manifestSha256, "manifestSha256");
        Objects.requireNonNull(artifactSha256, "artifactSha256");
        Objects.requireNonNull(recordedAt, "recordedAt");
        if (!CONTRACT_VERSION.equals(contractVersion)) {
            throw new IllegalArgumentException("未知的设备本地已接纳下载响应记录版本: " + contractVersion);
        }
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo 必须为正整数");
        }
        if (artifactSize < 1L || artifactSize > OtaDownloadAssignment.MAX_ARTIFACT_SIZE) {
            throw new IllegalArgumentException("artifactSize 必须位于 1.." + OtaDownloadAssignment.MAX_ARTIFACT_SIZE);
        }
        if (!manifestSha256.matches(SHA256_PATTERN) || !artifactSha256.matches(SHA256_PATTERN)) {
            throw new IllegalArgumentException("摘要字段必须是 64 位小写十六进制");
        }
    }

    /**
     * 从一次已构造的下载赋值与设备请求标识生成记录。
     *
     * @param assignment 本次响应对应的下载赋值
     * @param requestId 设备为本次下载生成的请求标识
     * @param recordedAt 耐久落盘时刻
     * @return 待写入的身份记录
     */
    public static OtaAcceptedDownloadResponse from(OtaDownloadAssignment assignment, UUID requestId,
                                                   Instant recordedAt) {
        Objects.requireNonNull(assignment, "assignment");
        return new OtaAcceptedDownloadResponse(CONTRACT_VERSION, assignment.jobId(), requestId,
                assignment.authorizationId(), assignment.attemptNo(), assignment.manifestSha256(),
                assignment.artifactSha256(), assignment.artifactSize(), recordedAt);
    }

    /**
     * 判断新收到的响应是否与本次已接纳身份逐字段一致。
     *
     * <p>只有七个身份字段全部相等才算「同一份被接纳过的响应」；任何一项不同都说明这是另一次派发或
     * 被篡改的响应，调用方必须失败关闭。</p>
     *
     * @param assignment 新收到响应构造出的下载赋值
     * @param incomingRequestId 新收到响应携带的请求标识
     * @return {@code true} 表示与本次耐久接纳完全一致
     */
    public boolean matches(OtaDownloadAssignment assignment, UUID incomingRequestId) {
        Objects.requireNonNull(assignment, "assignment");
        return jobId.equals(assignment.jobId())
                && requestId.equals(incomingRequestId)
                && authorizationId.equals(assignment.authorizationId())
                && attemptNo == assignment.attemptNo()
                && manifestSha256.equals(assignment.manifestSha256())
                && artifactSha256.equals(assignment.artifactSha256())
                && artifactSize == assignment.artifactSize();
    }

    /**
     * 生成稳定的单行文本形式，供设备本地小文件写入。
     *
     * @return 不含换行的九个分隔字段
     */
    public String toLogLine() {
        return contractVersion
                + '|' + jobId
                + '|' + requestId
                + '|' + authorizationId
                + '|' + attemptNo
                + '|' + manifestSha256
                + '|' + artifactSha256
                + '|' + artifactSize
                + '|' + recordedAt.toEpochMilli();
    }

    /**
     * 解析 {@link #toLogLine()} 产生的单行文本。
     *
     * @param line 一行文本
     * @return 解析出的记录
     * @throws IllegalArgumentException 字段数、版本、枚举、UUID 或数字不合法时
     */
    public static OtaAcceptedDownloadResponse fromLogLine(String line) {
        String[] fields = line.split("\\|", -1);
        if (fields.length != 9) {
            throw new IllegalArgumentException("设备本地下载响应记录必须包含 9 个字段: " + fields.length);
        }
        try {
            return new OtaAcceptedDownloadResponse(
                    fields[0],
                    UUID.fromString(fields[1]),
                    UUID.fromString(fields[2]),
                    UUID.fromString(fields[3]),
                    Integer.parseInt(fields[4]),
                    fields[5],
                    fields[6],
                    Long.parseLong(fields[7]),
                    Instant.ofEpochMilli(Long.parseLong(fields[8])));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("设备本地下载响应记录不合法", failure);
        }
    }
}
