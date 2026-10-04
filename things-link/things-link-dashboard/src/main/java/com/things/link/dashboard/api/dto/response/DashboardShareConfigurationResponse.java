package com.things.link.dashboard.api.dto.response;

import com.things.link.dashboard.application.publication.DashboardShareConfiguration;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Console分享管理配置的精确HTTP投影，不返回部署路径、摘要或签发凭据。
 * @param available 分享运行和受管宿主同时可用
 * @param hostOrigin 已验证公开Origin或null
 * @param hostVersion 实际受管宿主版本或null
 * @param hostCompatibility 当前稳定版本范围或null
 */
public record DashboardShareConfigurationResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean available,
        @Schema(types = {"string", "null"}, requiredMode = Schema.RequiredMode.REQUIRED) String hostOrigin,
        @Schema(types = {"string", "null"}, requiredMode = Schema.RequiredMode.REQUIRED) String hostVersion,
        @Schema(types = {"object", "null"}, requiredMode = Schema.RequiredMode.REQUIRED)
        HostCompatibility hostCompatibility) {
    /** 分享配置仅允许当前合法稳定版本，独立DTO名避免改变签发请求的HostCompatibility定义。 */
    @Schema(name = "DashboardShareConfigurationHostCompatibility")
    public record HostCompatibility(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String minInclusive,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String maxExclusive) { }

    /** @param value 已确权配置事实 @return 不含内部字段的精确投影 */
    public static DashboardShareConfigurationResponse from(DashboardShareConfiguration value) {
        return new DashboardShareConfigurationResponse(value.available(), value.hostOrigin(), value.hostVersion(),
                value.hostCompatibility() == null ? null : new HostCompatibility(
                        value.hostCompatibility().minInclusive(), value.hostCompatibility().maxExclusive()));
    }
}
