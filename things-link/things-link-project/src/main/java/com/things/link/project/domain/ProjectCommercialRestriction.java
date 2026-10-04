package com.things.link.project.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 一条生效中的商业受限项目记录（S14-3c，S14-0 P4）。
 *
 * <p>只承载恢复时必需的最小事实：受限记录身份、归属租户、被限制项目与触发订阅。
 * 恢复动作据此把项目从 {@code ARCHIVED} 恢复为 {@code ACTIVE}，并写审计。
 *
 * @param id 台账行 ID
 * @param tenantId 项目归属租户
 * @param projectId 被限制的项目
 * @param subscriptionId 触发受限的订阅行
 * @param restrictedAt 受限时刻
 */
public record ProjectCommercialRestriction(
        UUID id,
        UUID tenantId,
        UUID projectId,
        UUID subscriptionId,
        Instant restrictedAt) {

    /** 台账事实必须有身份、归属与触发订阅。 */
    public ProjectCommercialRestriction {
        Objects.requireNonNull(id, "台账行 ID 不得为空");
        Objects.requireNonNull(tenantId, "归属租户 ID 不得为空");
        Objects.requireNonNull(projectId, "项目 ID 不得为空");
        Objects.requireNonNull(subscriptionId, "触发订阅 ID 不得为空");
        Objects.requireNonNull(restrictedAt, "受限时刻不得为空");
    }
}
