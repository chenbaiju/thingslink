package com.things.link.project.api.controller;

import com.things.link.project.api.dto.response.ProjectRegionResponse;
import com.things.link.project.application.ProjectRegionService;
import com.things.link.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 项目区域目录接口。
 *
 * <p>区域目录是平台基础设施能力，不是租户或项目里的业务资源，因此这里只开放
 * 只读列表给控制台消费。维护入口等平台运营后台再做。
 */
@RestController
@RequestMapping("/api/v1/project-regions")
@Tag(name = "项目区域", description = "项目区域目录")
public class ProjectRegionController {

    /** 区域目录用例。Controller 只负责编排 HTTP 契约，不直接查库。 */
    private final ProjectRegionService projectRegionService;

    /**
     * 创建区域目录 Controller。
     *
     * @param projectRegionService 区域目录用例
     */
    public ProjectRegionController(ProjectRegionService projectRegionService) {
        this.projectRegionService = projectRegionService;
    }

    /**
     * 列出可见区域。
     *
     * @return 区域列表
     */
    @GetMapping
    @Operation(summary = "项目区域目录",
            description = "列出平台可见的项目区域。前端只读消费，维护由后端配置/代码完成。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "成功"),
            @ApiResponse(responseCode = "401", description = "未认证",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<List<ProjectRegionResponse>> list() {
        return ResponseEntity.ok(projectRegionService.listVisible().stream()
                .map(ProjectRegionResponse::from)
                .toList());
    }

}
