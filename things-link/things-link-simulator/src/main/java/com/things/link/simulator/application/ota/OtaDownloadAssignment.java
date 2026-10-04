package com.things.link.simulator.application.ota;

import java.net.URI;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次设备侧 OTA 尝试的不可变身份，字段取自平台合同，便于后续机械接线。
 *
 * <p>字段来源：{@code authorizationId}/{@code jobId}/{@code attemptNo}/{@code manifestSha256}/
 * {@code downloadUrl} 来自 ADR0129 的下载授权响应；{@code artifactSha256}/{@code artifactSize}
 * 来自 ADR0131 的 19 字段进度证据（设备必须回显的目标 artifact 身份）。
 * 本片<b>不</b>包含签名、公钥或清单正文：模拟器不持有受控签名器，也不应伪造已验签的发布。</p>
 *
 * @param authorizationId 封存的下载授权标识
 * @param jobId 平台作业标识
 * @param attemptNo 作业尝试号，必须为正整数
 * @param manifestSha256 已签名清单摘要（64 位小写十六进制）
 * @param artifactSha256 预期完整 artifact 摘要（64 位小写十六进制）
 * @param artifactSize 预期 artifact 总字节数，遵循 ADR0131 的 1..67108864 上限
 * @param downloadUrl 短期下载地址；只允许 http/https，本机回环明文仅供受控测试
 */
public record OtaDownloadAssignment(
        UUID authorizationId,
        UUID jobId,
        int attemptNo,
        String manifestSha256,
        String artifactSha256,
        long artifactSize,
        URI downloadUrl) {

    /** ADR0131 冻结的 artifact 尺寸上限。 */
    public static final long MAX_ARTIFACT_SIZE = 67108864L;

    /** 摘要字段必须是完整的 64 位小写十六进制。 */
    private static final String SHA256_PATTERN = "[0-9a-f]{64}";

    /**
     * 校验身份字段；非法组合必须在取字节之前失败，而不是在下发授权之后才发现。
     */
    public OtaDownloadAssignment {
        Objects.requireNonNull(authorizationId, "authorizationId");
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(manifestSha256, "manifestSha256");
        Objects.requireNonNull(artifactSha256, "artifactSha256");
        Objects.requireNonNull(downloadUrl, "downloadUrl");
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo 必须为正整数");
        }
        if (!manifestSha256.matches(SHA256_PATTERN) || !artifactSha256.matches(SHA256_PATTERN)) {
            throw new IllegalArgumentException("摘要必须是 64 位小写十六进制");
        }
        if (artifactSize < 1L || artifactSize > MAX_ARTIFACT_SIZE) {
            throw new IllegalArgumentException("artifactSize 必须位于 1.." + MAX_ARTIFACT_SIZE);
        }
        String scheme = downloadUrl.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("downloadUrl 只允许 http/https");
        }
        if (downloadUrl.getHost() == null) {
            throw new IllegalArgumentException("downloadUrl 必须包含主机名");
        }
    }
}
