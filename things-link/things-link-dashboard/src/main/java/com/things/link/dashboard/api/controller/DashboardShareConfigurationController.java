package com.things.link.dashboard.api.controller;

import com.things.link.dashboard.api.dto.response.DashboardShareConfigurationResponse;
import com.things.link.dashboard.api.support.DashboardApiAuthorization;
import com.things.link.dashboard.application.publication.DashboardShareConfigurationService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

/** S12-4d分享宿主配置只向管理者开放；GET不赋予签发权限也不生成secret。 */
@RestController
@Tag(name = "看板分享管理", description = "签发有限只读分享、查询历史及不可恢复撤销")
@RequestMapping("/api/v1/projects/{projectId}/dashboards/{dashboardId}/shares/configuration")
public class DashboardShareConfigurationController {
    /** HTTP首层dashboard_definition:manage守卫。 */
    private final DashboardApiAuthorization authorization;
    /** 保留服务层角色及RLS复核的读取用例。 */
    private final DashboardShareConfigurationService service;

    /** @param authorization 首层管理授权 @param service 配置读取编排 */
    public DashboardShareConfigurationController(DashboardApiAuthorization authorization,
            DashboardShareConfigurationService service) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.service = Objects.requireNonNull(service, "service");
    }

    /**
     * 读取看板分享运行配置。
     *
     * @param projectId 当前项目
     * @param dashboardId 当前看板
     * @param request 拒绝未声明query
     * @return 不可缓存的公开配置
     */
    @GetMapping
    @Operation(operationId = "getDashboardShareConfiguration", summary = "读取看板分享运行配置",
            description = "仅管理者可读；当前运行启用且受管宿主验证成功时提供精确Origin与稳定版本范围，其余字段全null。")
    public ResponseEntity<DashboardShareConfigurationResponse> configuration(@io.swagger.v3.oas.annotations.Parameter(description = "当前项目") @PathVariable UUID projectId,
            @io.swagger.v3.oas.annotations.Parameter(description = "当前看板") @PathVariable UUID dashboardId, HttpServletRequest request) {
        authorization.requireManage(projectId);
        if (request.getQueryString() != null) throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                DashboardShareConfigurationResponse.from(service.read(projectId, dashboardId)));
    }
}
