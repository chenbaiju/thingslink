package com.things.link.enduser.api.dto.response;

import com.things.link.enduser.application.ResolvedWebAppApplication;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Objects;

/**
 * WebApp 登录前应用定位的最小公开响应。
 *
 * <p>S12-2a2b 明确禁止内部租户、项目、应用 UUID、授权与摘要穿透 HTTP；因此本类型只保留
 * 跳转登录页所需的三个稳定公开字段。</p>
 *
 * @param appKey 应用公开稳定键
 * @param displayName 当前不可变发布版本的展示名称
 * @param projectKey 项目公开稳定键
 */
@Schema(description = "WebApp登录前应用定位结果")
public record WebAppApplicationResolutionResponse(
        @Schema(description = "应用公开稳定键", requiredMode = Schema.RequiredMode.REQUIRED)
        String appKey,
        @Schema(description = "当前发布版本的展示名称", requiredMode = Schema.RequiredMode.REQUIRED)
        String displayName,
        @Schema(description = "项目公开稳定键", requiredMode = Schema.RequiredMode.REQUIRED)
        String projectKey) {

    /** 最小公开响应不接受空引用，防止内部投影缺陷生成不完整的200响应。 */
    public WebAppApplicationResolutionResponse {
        Objects.requireNonNull(appKey, "appKey");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(projectKey, "projectKey");
    }

    /**
     * 从应用层已重验的公开结果创建 HTTP 投影。
     *
     * @param resolved 已完成应用及项目交叉重验的结果
     * @return 仅含三个公开字段的响应
     */
    public static WebAppApplicationResolutionResponse from(ResolvedWebAppApplication resolved) {
        Objects.requireNonNull(resolved, "resolved");
        return new WebAppApplicationResolutionResponse(
                resolved.appKey(), resolved.displayName(), resolved.projectKey());
    }
}
