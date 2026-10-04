package com.things.link.dashboard.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Objects;

/**
 * 以调用方读取的发布状态修订号执行看板生命周期写入的封闭请求。
 *
 * <p>S12-1e1c至1e1e的回滚、撤回和软删除只接收同一发布状态revision。该值保持字符串并由
 * 发布服务执行规范十进制Long校验，避免HTTP层把非规范文本提前折叠成相同数值；共享模型也避免
 * 三个动作产生形状相同却可独立漂移的外部Schema。</p>
 *
 * @param expectedPublicationRevision 调用方读取的发布状态revision字符串
 */
public record DashboardPublicationRevisionRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string",
                description = "发布状态CAS修订号的规范十进制字符串")
        String expectedPublicationRevision) {

    /** 保证解析完成后的封闭请求不携带缺失字段。 */
    public DashboardPublicationRevisionRequest {
        Objects.requireNonNull(expectedPublicationRevision, "expectedPublicationRevision");
    }
}
