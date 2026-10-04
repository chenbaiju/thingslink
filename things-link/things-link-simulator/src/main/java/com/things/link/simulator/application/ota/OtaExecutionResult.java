package com.things.link.simulator.application.ota;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次设备执行的不可变结果：既是状态机返回值，也是未来进度/提交报文的证据来源。
 *
 * <p>刻意区分 {@link #state()} 与 {@link #observedStage()}：{@code state} 是结论（可能为
 * {@link OtaStage#SAFETY_PAUSED}），{@code observedStage} 是设备真实处在的阶段。
 * 取消请求在安装窗口打开后到达时，设备既不能宣称 {@link OtaStage#CANCELLED}（那是伪造），
 * 也不能假装自己仍在下载；正确做法是上报真实阶段并保留平台对账责任（ADR0137 的
 * {@code INSTALL_WON}/{@code RECOVERY_REQUIRED} 语义）。</p>
 *
 * <p>{@link #confirmedDigest()} 在提交成功时等于 {@link #artifactSha256()}，可以直接作为 ADR0131
 * 证据里的设备观察值；在暂停/失败时它是「已确认前缀」的摘要，不能冒充完整 artifact 摘要。</p>
 *
 * @param state 结论阶段
 * @param observedStage 设备真实处在的阶段（最后一条日志的阶段）
 * @param reason 稳定原因码
 * @param authorizationId 下载授权标识
 * @param jobId 平台作业标识
 * @param attemptNo 作业尝试号
 * @param manifestSha256 已签名清单摘要
 * @param artifactSha256 预期完整 artifact 摘要
 * @param artifactSize 预期 artifact 总字节数
 * @param downloadedBytes 本次执行结束时已耐久确认的字节数
 * @param confirmedDigest 已确认前缀的 SHA-256（小写十六进制）
 * @param fetchAttempts 本次执行对 artifact 端口发起的取字节次数（含失败次数）
 * @param downloadFailures 本次执行中未超过预算的可重试下载失败次数
 * @param cancelAccepted 本次执行是否接受了一次干净取消
 * @param transitions 本次执行新写入日志的转换，顺序与写入顺序一致
 * @param startedAt 执行开始时刻
 * @param finishedAt 执行结束时刻
 */
public record OtaExecutionResult(
        OtaStage state,
        OtaStage observedStage,
        OtaReasonCode reason,
        UUID authorizationId,
        UUID jobId,
        int attemptNo,
        String manifestSha256,
        String artifactSha256,
        long artifactSize,
        long downloadedBytes,
        String confirmedDigest,
        int fetchAttempts,
        int downloadFailures,
        boolean cancelAccepted,
        List<OtaJournalRecord> transitions,
        Instant startedAt,
        Instant finishedAt) {

    /**
     * 冻结列表并校验必备字段。
     */
    public OtaExecutionResult {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(authorizationId, "authorizationId");
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(manifestSha256, "manifestSha256");
        Objects.requireNonNull(artifactSha256, "artifactSha256");
        Objects.requireNonNull(confirmedDigest, "confirmedDigest");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(finishedAt, "finishedAt");
        transitions = List.copyOf(transitions);
    }

    /**
     * 判断设备是否声称镜像已经完整可信。
     *
     * @return {@code true} 仅当结论为 {@link OtaStage#COMMITTED} 且前缀摘要等于预期完整摘要
     */
    public boolean claimsArtifactVerified() {
        return state == OtaStage.COMMITTED && confirmedDigest.equals(artifactSha256);
    }
}
