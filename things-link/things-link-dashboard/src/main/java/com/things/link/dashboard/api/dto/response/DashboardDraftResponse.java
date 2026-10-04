package com.things.link.dashboard.api.dto.response;

import com.things.link.dashboard.domain.DashboardDraft;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Console看板草稿读取响应。
 *
 * <p>草稿revision是后续保存CAS令牌，按十进制字符串输出；content保持已校验的
 * {@code tc.dashboard/v1}对象树，不把服务端模型关系投影暴露为第二份可漂移事实。</p>
 *
 * @param dashboardId 草稿所属看板ID
 * @param content 完整看板草稿对象
 * @param revision 草稿CAS修订号的十进制字符串
 * @param updatedAt 最近保存时刻
 */
public record DashboardDraftResponse(
        UUID dashboardId,
        @Schema(types = {"object"}, description = "完整tc.dashboard/v1看板草稿对象")
        JsonNode content,
        String revision,
        Instant updatedAt) {

    /** 冻结响应内的JSON树，避免序列化前被调用方原位篡改。 */
    public DashboardDraftResponse {
        Objects.requireNonNull(dashboardId, "dashboardId");
        content = Objects.requireNonNull(content, "content").deepCopy();
        Objects.requireNonNull(revision, "revision");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /**
     * 返回隔离的草稿JSON树。
     *
     * @return 可安全序列化或修改的内容副本
     */
    @Override
    public JsonNode content() {
        return content.deepCopy();
    }

    /**
     * 将项目范围内的可见草稿事实投影为最小HTTP响应。
     *
     * @param draft 看板草稿事实
     * @return 不包含租户、项目、操作者或模型关系字段的响应
     */
    public static DashboardDraftResponse from(DashboardDraft draft) {
        Objects.requireNonNull(draft, "draft");
        return new DashboardDraftResponse(
                draft.dashboardId(),
                draft.content(),
                Long.toString(draft.revision()),
                draft.updatedAt());
    }
}
