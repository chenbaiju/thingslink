package com.things.link.project.domain;

/**
 * 租户资源包的生命周期状态（S14-4a，S14-0 P6）。
 *
 * <p>时间推进只产生两个转换：{@code PENDING → ACTIVE}（订阅回到 ACTIVE/GRACE 且包到达起点）与
 * {@code ACTIVE → EXPIRED}（到达自身 {@code ends_at}）。{@code CANCELLED}/{@code REFUNDED}
 * 由退款或人工动作推进，本片只预留取值并在 {@code CHECK} 中封闭闭集，不发明退款语义（属 S14-5）。
 *
 * <p>只有 {@link #ACTIVE} 且处于自身窗口内的包参与「有效权益 = 基础档 + 包求和」的合成。
 */
public enum ResourcePackageStatus {

    /** 已支付但订阅不在 ACTIVE/GRACE，等待订阅恢复后生效。 */
    PENDING,

    /** 生效中；是否处于自身窗口内由 {@code starts_at/ends_at} 另行判定。 */
    ACTIVE,

    /** 已到期（{@code ends_at} 已过），贡献为 0 且不可再回到 ACTIVE。 */
    EXPIRED,

    /** 已取消（本片只预留；取消/退款动作属 S14-5）。 */
    CANCELLED,

    /** 已退款（本片只预留；退款动作属 S14-5）。 */
    REFUNDED
}
