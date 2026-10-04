package com.things.link.dashboard.api.dto.response;

import com.things.link.dashboard.domain.DashboardCatalogEntry;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 看板创建及同请求重放的不可变身份响应。
 *
 * <p>只投影创建后不再变化的字段，使领域按持久创建映射恢复响应时不需要保存响应快照。</p>
 *
 * @param id 看板内部ID
 * @param createdAt 创建时刻
 */
public record DashboardCreationResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt) {

    /**
     * 从首次创建或域幂等恢复的目录事实投影不可变身份。
     *
     * @param entry 看板目录事实
     * @return 不随后续改名、发布或草稿保存变化的创建响应
     */
    public static DashboardCreationResponse from(DashboardCatalogEntry entry) {
        Objects.requireNonNull(entry, "entry");
        return new DashboardCreationResponse(entry.id(), entry.createdAt());
    }
}
