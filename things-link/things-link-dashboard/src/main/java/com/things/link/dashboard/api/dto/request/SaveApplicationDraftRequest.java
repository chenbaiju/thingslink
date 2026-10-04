package com.things.link.dashboard.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * 保存应用草稿的封闭请求。
 *
 * <p>content保留为原始UTF-8字节，不先绑定为JSON树，否则重复键与原始长度证据会丢失。</p>
 *
 * @param expectedRevision 调用方读到的草稿CAS修订号字符串
 * @param content 封中原样截取的content对象UTF-8字节
 */
public record SaveApplicationDraftRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string",
                description = "草稿CAS修订号的规范十进制字符串")
        String expectedRevision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, implementation = JsonNode.class,
                types = {"object"},
                description = "完整tc.application/v1应用草稿对象")
        byte[] content) {

    /** 冻结原始content字节，防止解析后到校验前被修改。 */
    public SaveApplicationDraftRequest {
        Objects.requireNonNull(expectedRevision, "expectedRevision");
        content = Objects.requireNonNull(content, "content").clone();
    }

    /**
     * 返回与请求对象内部状态隔离的原始content字节。
     *
     * @return content原始UTF-8字节副本
     */
    @Override
    public byte[] content() {
        return content.clone();
    }
}
