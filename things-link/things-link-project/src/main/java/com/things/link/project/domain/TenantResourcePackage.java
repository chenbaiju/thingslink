package com.things.link.project.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 一行租户资源包事实（S14-4a，S14-0 P6；S14-4b 增加人工调整元数据）。
 *
 * <p>包是**独立权益**：{@code startsAt}/{@code endsAt} 只属于它自己，与基础订阅的服务期无关，
 * 不随订阅续期，也没有「跟随当前订阅指针」的隐式到期。{@code endsAt} 必填 —— 不存在无期限包。
 *
 * <p>购买包只带 {@code sourceOrderId}；运营人工调整（S14-4b）没有订单，带
 * {@link ResourcePackageAdjustment}（原因/操作人/幂等键）。两者在有效期与合成上完全同权，
 * 合成时只认 {@link ResourcePackageStatus#ACTIVE} 且处于自身窗口内的包；来源的差别只影响追溯，
 * 因此由紧凑构造器把「来源」与「来源元数据」绑成同生共死，禁止半真半假的行。
 *
 * @param id 包行 ID
 * @param tenantId 归属租户
 * @param dimensionCode 被扩容的冻结维度编码
 * @param amount 该包为维度增加的额度，恒为正
 * @param unit 额度单位
 * @param window 计量窗口
 * @param startsAt 包自身服务期起点（UTC）
 * @param endsAt 包自身服务期终点（UTC），必填
 * @param source 来源：购买或人工调整
 * @param sourceOrderId 来源订单 ID；购买必填、人工调整为空
 * @param adjustment 人工调整元数据；调整必填、购买为空
 * @param status 生命周期状态
 * @param revision 包行乐观锁版本
 */
public record TenantResourcePackage(
        UUID id,
        UUID tenantId,
        String dimensionCode,
        long amount,
        String unit,
        String window,
        Instant startsAt,
        Instant endsAt,
        ResourcePackageSource source,
        UUID sourceOrderId,
        ResourcePackageAdjustment adjustment,
        ResourcePackageStatus status,
        long revision) {

    /** 包事实必须完整且自洽，半截快照在重建时直接失败。 */
    public TenantResourcePackage {
        Objects.requireNonNull(id, "资源包 ID 不得为空");
        Objects.requireNonNull(tenantId, "资源包租户 ID 不得为空");
        Objects.requireNonNull(dimensionCode, "资源包维度编码不得为空");
        Objects.requireNonNull(unit, "资源包单位不得为空");
        Objects.requireNonNull(window, "资源包窗口不得为空");
        Objects.requireNonNull(startsAt, "资源包起算时刻不得为空");
        Objects.requireNonNull(endsAt, "资源包到期时刻不得为空");
        Objects.requireNonNull(source, "资源包来源不得为空");
        Objects.requireNonNull(status, "资源包状态不得为空");
        if (amount <= 0) {
            throw new IllegalArgumentException("资源包额度必须为正数");
        }
        if (!endsAt.isAfter(startsAt)) {
            throw new IllegalArgumentException("资源包到期时刻必须晚于起算时刻");
        }
        if ((source == ResourcePackageSource.PURCHASE) != (sourceOrderId != null)) {
            throw new IllegalArgumentException("购买包必须有来源订单，人工调整不得携带订单");
        }
        if ((source == ResourcePackageSource.OPERATION_ADJUSTMENT) != (adjustment != null)) {
            throw new IllegalArgumentException("人工调整必须携带原因/操作人/幂等键，购买包不得携带");
        }
    }
}
