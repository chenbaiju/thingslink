package com.things.link.simulator.application.ota;

/**
 * 设备侧 OTA 阶段，名称直接取自平台合同，避免自造状态。
 *
 * <p>这些名字来自 ADR0128/0129 的 {@code DISPATCHED}/{@code DOWNLOADING}、ADR0131 的
 * {@code VERIFYING}/{@code INSTALLING}/{@code REBOOTING}/{@code HEALTH_CHECKING}、ADR0132 的
 * {@code CONFIRMING}/{@code COMMITTED} 以及 ADR0137 的取消/停止语义。模拟器复用它们的文本，
 * 是为了让后续 MQTT 接线只做字段搬运（{@link #name()} 直接作为进度报文的 {@code stage}），
 * 而不是在设备端再维护一张容易和平台漂移的翻译表。</p>
 *
 * <p><b>为什么安全阶段绝不自动重启刷写：</b>{@link #INSTALLING}/{@link #REBOOTING}/
 * {@link #HEALTH_CHECKING}/{@link #CONFIRMING} 表示「安装窗口已经打开」。ADR0131 明确：
 * 缺少「设备未进入安装」的证明时，即便派发或下载超时也不能猜测可重试，必须保留
 * {@code RECOVERY_REQUIRED} 责任；ADR0137 明确安装先赢只能返回 {@code INSTALL_WON}。
 * 断电发生在这些阶段时，设备无法知道擦写/切槽是否已经发生，任何「重新来一遍」都可能把
 * 未完成的镜像二次写进槽位，因此本片只允许以「安装结果未知」暂停并交回平台对账。</p>
 *
 * <p>与之相对，{@link #VERIFYING} 不是安全阶段：摘要核对是只读且幂等的，重做不会改变硬件状态，
 * 所以断电后可以安全重做。</p>
 */
public enum OtaStage {

    /** 平台已派发该尝试，设备还没有取到任何字节。 */
    DISPATCHED,

    /** 正在从短期授权地址下载 artifact；支持按已确认偏移断点续传。 */
    DOWNLOADING,

    /** 正在核对完整 artifact 摘要；只读、幂等，不属于安全阶段。 */
    VERIFYING,

    /** 开始刷写，安装窗口已经打开；属于安全阶段。 */
    INSTALLING,

    /** 刷写后重启；属于安全阶段。 */
    REBOOTING,

    /** 重启后的自检与健康观察；属于安全阶段。 */
    HEALTH_CHECKING,

    /** 设备原子提交确认；属于安全阶段。 */
    CONFIRMING,

    /** 设备已提交（终态）。 */
    COMMITTED,

    /** 判定性失败（终态），例如摘要不符、artifact 超限；绝不进入刷写。 */
    FAILED,

    /** 安装窗口打开前接受的干净取消（终态）。 */
    CANCELLED,

    /** 安全暂停（终态）：掉电、首错、恢复被拒；发生在安全阶段时必须由平台对账。 */
    SAFETY_PAUSED;

    /**
     * 判断进入该阶段后断电是否可能已经改变了硬件状态。
     *
     * @return {@code true} 表示这是安全阶段，重启后不得自动重新开始刷写
     */
    public boolean isSafetyStage() {
        return this == INSTALLING || this == REBOOTING || this == HEALTH_CHECKING || this == CONFIRMING;
    }

    /**
     * 判断该阶段是否已经给出终态结论。
     *
     * @return {@code true} 表示该阶段不会再有后续设备动作
     */
    public boolean isTerminal() {
        return this == COMMITTED || this == FAILED || this == CANCELLED || this == SAFETY_PAUSED;
    }

    /**
     * 判断该阶段是否是设备真正处在的阶段（而非结论）。
     *
     * @return {@code true} 表示设备可以停在该阶段等待下一步
     */
    public boolean isDeviceStage() {
        return !isTerminal();
    }
}
