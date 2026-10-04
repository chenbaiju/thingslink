package com.things.link.project.application;

import com.things.link.project.domain.ResourcePackageStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次人工调整的写入结果（S14-4b）。
 *
 * <p>{@code created} 为 {@code false} 表示同一幂等键的重放：返回既有调整，本次不写第二行、
 * 不重复审计、不推进缓存版本。{@code status} 是落库状态（订阅不在 ACTIVE/GRACE 时为
 * {@code PENDING}，等待订阅恢复后由 {@link TenantResourcePackageService#advance(Instant)} 激活）。
 *
 * @param adjustmentId 调整行 ID（资源包事实的一个来源）
 * @param status 落库状态
 * @param startsAt 调整生效起点（UTC）
 * @param endsAt 调整生效终点（UTC）
 * @param created 本次是否确实新建了调整
 */
public record EntitlementAdjustmentResult(
        UUID adjustmentId,
        ResourcePackageStatus status,
        Instant startsAt,
        Instant endsAt,
        boolean created) {

    /** 结果必须完整，半截结果无法用于幂等回执。 */
    public EntitlementAdjustmentResult {
        Objects.requireNonNull(adjustmentId, "调整 ID 不得为空");
        Objects.requireNonNull(status, "调整状态不得为空");
        Objects.requireNonNull(startsAt, "调整起算时刻不得为空");
        Objects.requireNonNull(endsAt, "调整到期时刻不得为空");
    }
}
