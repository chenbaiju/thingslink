package com.things.link.dashboard.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Objects;

/**
 * 把当前应用草稿发布为新不可变版本的封闭请求。
 *
 * <p>两个revision保持字符串并由发布服务执行规范十进制Long校验；HTTP解析器只负责保证外层对象
 * 精确包含这两个非null字符串，不能提前把多个文本表示折叠成同一数值。</p>
 *
 * @param expectedDraftRevision 调用方读取的草稿revision字符串
 * @param expectedPublicationRevision 调用方读取的发布状态revision字符串
 */
public record PublishApplicationVersionRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string",
                description = "草稿CAS修订号的规范十进制字符串")
        String expectedDraftRevision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string",
                description = "发布状态CAS修订号的规范十进制字符串")
        String expectedPublicationRevision) {

    /** 保证解析完成后的封闭请求不携带缺失字段。 */
    public PublishApplicationVersionRequest {
        Objects.requireNonNull(expectedDraftRevision, "expectedDraftRevision");
        Objects.requireNonNull(expectedPublicationRevision, "expectedPublicationRevision");
    }
}
