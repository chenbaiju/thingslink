package com.things.link.dashboard.api.dto.response;

import com.things.link.dashboard.api.dto.request.CreateDashboardShareRequest.HostCompatibility;
import com.things.link.dashboard.application.DashboardShareContext;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * ADR0101 context的八字段闭集，不暴露tenant/project、secret/hash或签发账号。
 * @param shareId 已验证分享身份
 * @param dashboardId 稳定看板身份
 * @param dashboardVersionId 冻结的完整版本
 * @param dashboardVersionNumber 精确十进制版本号
 * @param expiresAt 数据库确定的到期时间
 * @param historyAnchorAt 本轮历史的数据库时间锚点
 * @param hostCompatibility 签发时冻结的有限宿主范围
 * @param variableScopes 每个设备变量已冻结的候选集合
 */
public record DashboardShareContextResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID shareId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID dashboardId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID dashboardVersionId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^[1-9][0-9]{0,18}$") String dashboardVersionNumber,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant expiresAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant historyAnchorAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) HostCompatibility hostCompatibility,
        @NotNull @ArraySchema(maxItems = 20, arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
        List<VariableScopeResponse> variableScopes) {
    /** 冻结列表引用，不能在序列化期间修改已授权的变量集合。 */
    public DashboardShareContextResponse {
        variableScopes = List.copyOf(variableScopes);
    }

    /** 仅投影对匿名页面必要的字段，不序列化内部principal或token事实。 */
    public static DashboardShareContextResponse from(DashboardShareContext source) {
        return new DashboardShareContextResponse(source.shareId(), source.dashboardId(), source.dashboardVersionId(),
                Long.toString(source.dashboardVersionNumber()), source.expiresAt(), source.historyAnchorAt(),
                new HostCompatibility(source.hostCompatibility().get("minInclusive").stringValue(),
                        source.hostCompatibility().get("maxExclusive").stringValue()),
                source.variableScopes().stream().map(scope ->
                        new VariableScopeResponse(scope.variableKey(), scope.deviceIds())).toList());
    }

    /**
     * 候选按变量表达，不能把不同变量的设备和属性任意叉乘。
     * @param variableKey Schema设备变量键
     * @param deviceIds 该变量的冻结候选设备，所有变量并集最多二十台
     */
    @Schema(name = "DashboardShareVariableScopeResponse")
    public record VariableScopeResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String variableKey,
            @NotNull @ArraySchema(minItems = 1, maxItems = 20, arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<UUID> deviceIds) {
        /** 防止外部集合改写capability已声明的候选。 */
        public VariableScopeResponse { deviceIds = List.copyOf(deviceIds); }
    }
}
