package com.things.link.project.application;

import com.things.link.project.domain.ResourcePackageStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次资源包支付成功事件的生效结果（S14-4a）。
 *
 * <p>{@code activated} 为 {@code false} 表示同一支付事件的幂等重放：包已存在，本次不写第二行、
 * 不再推进缓存版本、不重复审计。{@code status} 是包落库时的状态（订阅不在 ACTIVE/GRACE 时为
 * {@code PENDING}，等待 {@link TenantResourcePackageService#advance(Instant)} 激活）。
 *
 * @param packageId 资源包 ID
 * @param startsAt 包自身服务期起点（UTC）
 * @param endsAt 包自身服务期终点（UTC）
 * @param status 落库状态
 * @param activated 本次是否确实新建了包
 */
public record ResourcePackageActivation(
        UUID packageId,
        Instant startsAt,
        Instant endsAt,
        ResourcePackageStatus status,
        boolean activated) {

    /** 生效结果必须完整。 */
    public ResourcePackageActivation {
        Objects.requireNonNull(packageId, "资源包 ID 不得为空");
        Objects.requireNonNull(startsAt, "资源包起算时刻不得为空");
        Objects.requireNonNull(endsAt, "资源包到期时刻不得为空");
        Objects.requireNonNull(status, "资源包状态不得为空");
    }
}
