package com.things.link.project.api.controller;

import com.things.link.project.api.dto.response.ProjectQuotaOverviewResponse;
import com.things.link.project.application.ProjectQuotaService;
import com.things.link.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 项目设置中的配额和日用量接口。
 *
 * <p>路径保留 projectId 以匹配项目设置路由，但它必须等于当前 JWT 已选项目；服务层拒绝
 * 不一致请求，配额投影则只从 {@code app_current_project()} 派生项目，双层避免路径篡改。
 *
 * <p><b>S14-2c 的套餐摘要沿用同一授权。</b>响应附带租户套餐摘要，但只有项目归属租户的
 * ACTIVE 成员才能读到（架构文档 §6）：跨租户协作者仍只看到既有共享池投影，不新增平台权限，
 * 也不新增第二条读取路径。
 */
@RestController
@RequestMapping("/api/v1/projects")
@Tag(name = "项目配额", description = "项目贡献与租户共享配额池的只读展示")
public class ProjectQuotaController {

    /** 项目配额只读用例。 */
    private final ProjectQuotaService projectQuotaService;

    /** @param projectQuotaService 项目配额只读用例 */
    public ProjectQuotaController(ProjectQuotaService projectQuotaService) {
        this.projectQuotaService = projectQuotaService;
    }

    /**
     * 读取当前已选项目的配额、UTC 日用量与租户套餐摘要。
     *
     * @param projectId 路径项目 ID；必须与当前令牌已选项目一致
     * @return 当前项目贡献、所属租户共享池，以及同一租户成员可见的套餐摘要
     */
    @GetMapping("/{projectId}/quota")
    @Operation(summary = "项目配额与日用量",
            description = "仅当前已选项目的成员可读取。返回当前项目贡献及所属租户共享池，不返回其他项目明细。"
                    + "同一租户成员还会得到套餐摘要（订阅档位、服务期、冻结额度与权益，参考价只作展示）；"
                    + "跨租户协作者只得到共享池投影，摘要字段整体省略。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "成功"),
            @ApiResponse(responseCode = "401", description = "未认证",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "项目不存在、不是成员或不是当前已选项目",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<ProjectQuotaOverviewResponse> get(@PathVariable UUID projectId) {
        return ResponseEntity.ok(ProjectQuotaOverviewResponse.from(projectQuotaService.get(projectId)));
    }
}
