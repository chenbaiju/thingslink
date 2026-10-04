package com.things.link.project.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * ADR0076：后台清理的完整持久身份；调用方不可只凭projectId或旧token推进。
 * @param tenantId 项目持久计费归属
 * @param projectId 被领取项目
 * @param generation 不可恢复准入时的授权代次
 * @param stage 当前阶段
 * @param leaseToken 本次领取围栏
 * @param leaseUntil 数据库租约截止，仅用于观察，写入仍查数据库实际时钟
 * @param newlyAdmitted 是否首次从DELETING进入PURGING，决定是否写首次审计
 */
public record ProjectCleanupClaim(UUID tenantId, UUID projectId, long generation, String stage,
                                  UUID leaseToken, Instant leaseUntil, boolean newlyAdmitted) {

    /** 缺失或负身份不能流入后续领域清理端口。 */
    public ProjectCleanupClaim {
        Objects.requireNonNull(tenantId, "清理租户不得为空");
        Objects.requireNonNull(projectId, "清理项目不得为空");
        Objects.requireNonNull(stage, "清理阶段不得为空");
        Objects.requireNonNull(leaseToken, "清理token不得为空");
        Objects.requireNonNull(leaseUntil, "清理截止不得为空");
        if (generation < 0 || stage.isBlank()) {
            throw new IllegalArgumentException("清理代次或阶段无效");
        }
    }
}
