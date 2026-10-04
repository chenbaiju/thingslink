package com.things.link.simulator.application.ota;

/**
 * OTA 尝试的稳定原因码，是「结论」的唯一来源。
 *
 * <p>日志只记录设备真实处在的阶段，终态/暂停由原因码推导（{@link #conclusion()}）。这样做的原因是：
 * 同一阶段既可能是正常推进，也可能是安全暂停（例如 {@link OtaStage#INSTALLING} 既表示正在刷写，
 * 也表示掉电时刷写结果未知）。把结论放进原因码，可以让重启后的判定只依赖一条不可变记录，
 * 而不需要猜测「上一次到底走到哪」。</p>
 *
 * <p>原因码必须稳定：平台侧对账、测试断言和后续 MQTT 接线都会按名字匹配，因此只允许新增、
 * 不允许改名。</p>
 */
public enum OtaReasonCode {

    /** 正常推进，没有异常结论。 */
    NONE,

    /** 注入/观测到掉电，设备在 {@code stage} 上停止。 */
    POWER_LOST,

    /** 第一个分片就失败：按首错安全暂停处理，不自动重试。 */
    FIRST_CHUNK_FAILED_SAFETY_PAUSE,

    /** 平台/传输明确拒绝从已确认偏移续传；保持暂停，绝不从零悄悄重来。 */
    RESUME_REJECTED,

    /** 有界下载失败预算耗尽。 */
    DOWNLOAD_FAILURE_BUDGET_EXHAUSTED,

    /** 下载总预算耗尽。 */
    DOWNLOAD_DEADLINE_EXCEEDED,

    /** artifact 超过允许的最大尺寸。 */
    ARTIFACT_OVERSIZED,

    /** 服务器声明的 artifact 总长度与预期不一致。 */
    ARTIFACT_SIZE_MISMATCH,

    /** 暂存区内容与日志记录的前缀摘要不一致（可能被截断或篡改）。 */
    STAGING_INCONSISTENT,

    /** 完整 SHA-256 与预期摘要不符；绝不进入刷写。 */
    DIGEST_MISMATCH,

    /** 重启后发现安装窗口已经打开且结果未知：不重新刷写，交回平台对账。 */
    INSTALL_OUTCOME_UNKNOWN,

    /** 取消在安装窗口打开前被接受。 */
    CANCELLED_BEFORE_SAFETY_STAGE,

    /** 取消在安装窗口打开后才到达：拒绝伪造取消，如实上报真实阶段。 */
    CANCEL_REFUSED_AFTER_SAFETY_STAGE;

    /**
     * 把原因码映射为结论阶段。
     *
     * @return 结论阶段；{@link #NONE} 返回 {@code null} 表示尝试仍在进行中
     */
    public OtaStage conclusion() {
        return switch (this) {
            case NONE -> null;
            case CANCELLED_BEFORE_SAFETY_STAGE -> OtaStage.CANCELLED;
            case DIGEST_MISMATCH, ARTIFACT_OVERSIZED, ARTIFACT_SIZE_MISMATCH, STAGING_INCONSISTENT,
                 DOWNLOAD_FAILURE_BUDGET_EXHAUSTED, DOWNLOAD_DEADLINE_EXCEEDED -> OtaStage.FAILED;
            case POWER_LOST, FIRST_CHUNK_FAILED_SAFETY_PAUSE, RESUME_REJECTED,
                 INSTALL_OUTCOME_UNKNOWN, CANCEL_REFUSED_AFTER_SAFETY_STAGE -> OtaStage.SAFETY_PAUSED;
        };
    }

    /**
     * 判断该原因产生的暂停是否落在安全阶段上（必须由平台对账，不能由设备自行重试）。
     *
     * @param stage 记录该原因时设备所处的阶段
     * @return {@code true} 表示这是「安装结果未知」类的暂停
     */
    public boolean isSafetyStagePause(OtaStage stage) {
        OtaStage conclusion = conclusion();
        return stage != null && stage.isSafetyStage()
                && (conclusion == null || conclusion == OtaStage.SAFETY_PAUSED);
    }
}
