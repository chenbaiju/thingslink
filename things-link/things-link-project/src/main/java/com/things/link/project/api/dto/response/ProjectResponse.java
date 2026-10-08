package com.things.link.project.api.dto.response;

import com.things.link.project.domain.ProjectMembership;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 项目及当前用户在其中的角色。
 *
 * <p>把「我是什么角色」一并返回，前端才能决定显示哪些操作按钮，
 * 不必为每个项目再发一次请求。
 *
 * <p><b>不返回 tenantId。</b>它是计费归属，与调用方无关；而暴露它等于告诉每个
 * 协作者「这个项目属于哪个租户」，那是别人的组织信息。
 *
 * @param id        项目 ID
 * @param name      项目名称
 * @param description 项目描述；未填写时为空字符串
 * @param region    区域
 * @param timezone  IANA 项目时区
 * @param status    项目状态
 * @param myRole    当前用户在该项目中的角色
 * @param createdAt 创建时刻（RFC3339 UTC）
 * @param subscribedPlan 项目列表中的订阅档位；仅有效租户成员可见，不含价格和额度
 */
@Schema(description = "项目")
public record ProjectResponse(
        @Schema(description = "项目 ID") String id,
        @Schema(description = "项目名称", example = "厂区环境监测") String name,
        @Schema(description = "区域", example = "sh-1") String region,
        @Schema(description = "IANA 项目时区", example = "Asia/Shanghai") String timezone,
        @Schema(description = "全局短标识符，用于 MQTT Topic") String projectKey,
        @Schema(description = "状态", example = "ACTIVE") String status,
        @Schema(description = "我在该项目中的角色", example = "OWNER") String myRole,
        @Schema(description = "创建时刻") String createdAt,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(description = "项目归属租户的活状态订阅档位，仅我的项目列表且调用者为有效租户成员时提供；缺失不能视为免费版", nullable = true)
        PlanIdentityResponse subscribedPlan,
        @Schema(description = "项目描述，未填写时为空字符串", maxLength = 1000) String description) {

    /**
     * 由领域模型转换。
     *
     * @param membership 项目与角色
     * @return HTTP 响应
     */
    public static ProjectResponse from(ProjectMembership membership) {
        return new ProjectResponse(
                membership.project().id().toString(),
                membership.project().name(),
                membership.project().region(),
                membership.project().timezone(),
                membership.project().projectKey(),
                membership.project().status().name(),
                membership.role().name(),
                // 时间一律 RFC3339 UTC，格式化交给前端做。
                // 后端图省事返回 "2026-08-02 16:00:00" 的话，前端会直接展示它，
                // 跨时区就错，而且这个 bug 在本机永远复现不了（开发手册 2.2）
                membership.project().createdAt().toString(),
                membership.subscribedPlan() == null ? null : PlanIdentityResponse.from(membership.subscribedPlan()),
                membership.project().description());
    }

}
