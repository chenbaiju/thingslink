package com.things.link.dashboard.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * 创建Console看板的封闭请求。
 *
 * <p>content保留为原始UTF-8字节，供看板草稿合同在建树前检查重复键、大小与规范结构。</p>
 *
 * @param managementName Console目录管理名称，由看板服务执行Title语义校验
 * @param content 信封中原样截取的content对象UTF-8字节
 */
public record CreateDashboardRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Console目录管理名称")
        String managementName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, implementation = JsonNode.class,
                types = {"object"},
                description = "初始tc.dashboard/v1看板草稿对象")
        byte[] content) {

    /** 冻结原始content字节，防止解析后到领域校验前被修改。 */
    public CreateDashboardRequest {
        Objects.requireNonNull(managementName, "managementName");
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
