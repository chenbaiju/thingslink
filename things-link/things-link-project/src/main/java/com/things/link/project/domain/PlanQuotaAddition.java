package com.things.link.project.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * 一条租户可见的扩容/调整事实（S14-4c）：资源包与人工调整的只读溯源行。
 *
 * <p>它回答「租户的额度为什么比套餐冻结值高」：{@code source} 区分客户购买
 * （{@link ResourcePackageSource#PURCHASE}）与运营人工调整
 * （{@link ResourcePackageSource#OPERATION_ADJUSTMENT}），{@code amount} 是它在自身窗口内
 * 为该维度追加的额度。
 *
 * <p><b>只暴露租户该看的东西。</b>人工调整的 {@code reason} 对租户可见（他有理由知道额度
 * 从哪来），但<b>操作人账号不在这里</b>：那是平台内部人员的身份，属于最小披露而不是租户契约。
 * 购买包没有原因，{@code reason} 为空。
 *
 * <p>{@code effectiveNow} 是服务端按「状态为 {@code ACTIVE} 且此刻处于自身
 * {@code [startsAt, endsAt)} 窗口内」算出的结论，避免每个消费端各自猜测一份时间语义。
 *
 * @param source 来源：购买或运营人工调整
 * @param dimensionCode 被追加额度的冻结维度编码
 * @param amount 该维度追加的额度，恒为正
 * @param unit 额度单位（运行时单位，如 {@code COUNT}/{@code BYTE}）
 * @param window 计量窗口
 * @param startsAt 自身服务期起点（UTC，含）
 * @param endsAt 自身服务期终点（UTC，不含）
 * @param status 生命周期状态
 * @param effectiveNow 此刻是否确实参与有效权益合成
 * @param reason 人工调整原因；购买包为 {@code null}
 */
public record PlanQuotaAddition(
        ResourcePackageSource source,
        String dimensionCode,
        long amount,
        String unit,
        String window,
        Instant startsAt,
        Instant endsAt,
        ResourcePackageStatus status,
        boolean effectiveNow,
        String reason) {

    /** 溯源行必须完整自洽：来源与原因同生共死，窗口必须是正区间。 */
    public PlanQuotaAddition {
        Objects.requireNonNull(source, "扩容来源不得为空");
        Objects.requireNonNull(dimensionCode, "扩容维度编码不得为空");
        Objects.requireNonNull(unit, "扩容单位不得为空");
        Objects.requireNonNull(window, "扩容窗口不得为空");
        Objects.requireNonNull(startsAt, "扩容起算时刻不得为空");
        Objects.requireNonNull(endsAt, "扩容到期时刻不得为空");
        Objects.requireNonNull(status, "扩容状态不得为空");
        if (amount <= 0) {
            throw new IllegalArgumentException("扩容额度必须为正数");
        }
        if (!endsAt.isAfter(startsAt)) {
            throw new IllegalArgumentException("扩容到期时刻必须晚于起算时刻");
        }
        if ((source == ResourcePackageSource.OPERATION_ADJUSTMENT) != (reason != null)) {
            throw new IllegalArgumentException("人工调整必须携带原因，购买包不得携带原因");
        }
    }

    /**
     * 由一条资源包事实映射出溯源行（只读面的唯一映射入口）。
     *
     * <p>{@code effectiveNow} 与合成规则逐字一致：只有 {@code ACTIVE} 且处于自身窗口内的行
     * 才真正参与有效权益；{@code PENDING}、未来起点的 {@code ACTIVE} 行都不参与。
     *
     * @param row 资源包或人工调整事实
     * @param now 结论时刻（UTC）
     * @return 租户可见的溯源行
     */
    public static PlanQuotaAddition from(TenantResourcePackage row, Instant now) {
        Objects.requireNonNull(row, "资源包事实不得为空");
        Objects.requireNonNull(now, "结论时刻不得为空");
        boolean effectiveNow = row.status() == ResourcePackageStatus.ACTIVE
                && !row.startsAt().isAfter(now)
                && row.endsAt().isAfter(now);
        return new PlanQuotaAddition(row.source(), row.dimensionCode(), row.amount(), row.unit(),
                row.window(), row.startsAt(), row.endsAt(), row.status(), effectiveNow,
                row.source() == ResourcePackageSource.OPERATION_ADJUSTMENT
                        ? row.adjustment().reason() : null);
    }
}
