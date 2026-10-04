package com.things.link.dashboard.api.dto.response;

import com.things.link.dashboard.domain.ApplicationDraft;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Console应用草稿读取响应。
 *
 * <p>草稿revision是后续保存CAS令牌，按十进制字符串输出；content保持已校验的
 * {@code tc.application/v1}对象树，不转换成不完整的手写字段DTO。</p>
 *
 * @param applicationId 草稿所属应用ID
 * @param content 完整应用草稿对象
 * @param revision 草稿CAS修订号的十进制字符串
 * @param updatedAt 最近保存时刻
 */
public record ApplicationDraftResponse(
        UUID applicationId,
        JsonNode content,
        String revision,
        Instant updatedAt) {

    /** 冻结响应内的JSON树，避免序列化前被调用方原位篡改。 */
    public ApplicationDraftResponse {
        Objects.requireNonNull(applicationId, "applicationId");
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
     * @param draft 应用草稿事实
     * @return 不包含租户、项目或操作者字段的响应
     */
    public static ApplicationDraftResponse from(ApplicationDraft draft) {
        Objects.requireNonNull(draft, "draft");
        return new ApplicationDraftResponse(
                draft.applicationId(),
                draft.content(),
                Long.toString(draft.revision()),
                draft.updatedAt());
    }
}
