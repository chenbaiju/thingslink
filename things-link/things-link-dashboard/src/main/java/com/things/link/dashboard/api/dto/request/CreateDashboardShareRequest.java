package com.things.link.dashboard.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/**
 * ADR0101分享签发的封闭HTTP合同；实际解析保留原始JSON的重复键与类型边界。
 * @param dashboardVersionId 不可变看板版本
 * @param expectedDashboardPublicationRevision 管理界面观察的规范正十进制发布代次
 * @param expiresInSeconds 有限有效秒数
 * @param refererPolicy 显式选择的来源附加检查策略
 * @param hostCompatibility 当前受管宿主的有限版本区间
 * @param variables 全部设备变量的有限候选范围
 */
public record CreateDashboardShareRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID dashboardVersionId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", pattern = "[1-9][0-9]*")
        String expectedDashboardPublicationRevision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "300", maximum = "86400", defaultValue = "3600")
        int expiresInSeconds,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"NONE", "HOST_ORIGIN"}, defaultValue = "HOST_ORIGIN")
        String refererPolicy,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) HostCompatibility hostCompatibility,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<VariableScope> variables) {

    /**
     * 有限宿主版本区间；不接受任意外部宿主或来源白名单。
     * @param minInclusive 包含的最低规范版本
     * @param maxExclusive 不包含的最高规范版本
     */
    public record HostCompatibility(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String minInclusive,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String maxExclusive) { }

    /**
     * 单个设备变量候选；所有变量设备并集最多二十个。
     * @param variableKey 精确Schema变量键
     * @param deviceIds 此变量的完整候选设备集合
     */
    public record VariableScope(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String variableKey,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<UUID> deviceIds) { }
}
