package com.things.link.export.api.controller;

import com.things.link.export.api.dto.ProjectExportResponse;
import com.things.link.export.api.dto.ProjectExportDownloadUrlResponse;
import com.things.link.export.application.ProjectExportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 项目冻结期一致快照导出HTTP入口。 */
@Tag(name = "项目导出", description = "项目冻结期数据导出")
@RestController
@RequestMapping("/api/v1/projects/{projectId}/exports")
public class ProjectExportController {

    /** 导出任务用例。 */
    private final ProjectExportService exportService;

    /** @param exportService 导出任务用例 */
    public ProjectExportController(ProjectExportService exportService) {
        this.exportService = exportService;
    }

    /**
     * 建立或回读同代次非终态导出任务。
     * @param projectId 删除项目 ID
     * @return 202及异步任务状态
     */
    @Operation(summary = "请求项目数据导出", description = "建立或回读同代次非终态导出任务。")
    @PostMapping
    public ResponseEntity<ProjectExportResponse> request(@io.swagger.v3.oas.annotations.Parameter(description = "删除项目 ID") @PathVariable UUID projectId) {
        return ResponseEntity.accepted().body(ProjectExportResponse.from(exportService.request(projectId)));
    }

    /**
     * 找回当前有效OWNER本人在本删除代次申请的最新导出任务。
     * @param projectId 删除项目ID
     * @return 有任务时200及无对象键的状态，未申请时204；不隐式新建任务
     */
    @Operation(operationId = "latestProjectExport", summary = "找回最新项目导出任务", description = "复核当前OWNER与冻结窗口，返回本人在本代次请求的最新一条任务（含终态），无任务返回204。读取不创建任务、不消耗申请限流、不返回下载地址或对象键。")
    @ApiResponses({@ApiResponse(responseCode = "200", description = "找到最新任务",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ProjectExportResponse.class))),
            @ApiResponse(responseCode = "204", description = "本代次本人尚未申请任务", content = @Content)})
    @GetMapping("/latest")
    public ResponseEntity<ProjectExportResponse> latest(@io.swagger.v3.oas.annotations.Parameter(description = "删除项目ID") @PathVariable UUID projectId) {
        return exportService.latest(projectId).map(ProjectExportResponse::from)
                .map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * 查询导出任务状态。
     * @param projectId 删除项目 ID
     * @param exportId 导出任务 ID
     * @return 当前状态；5g2另行增加下载签发
     */
    @Operation(summary = "查询项目数据导出状态", description = "查询导出任务状态。")
    @GetMapping("/{exportId}")
    public ResponseEntity<ProjectExportResponse> get(@io.swagger.v3.oas.annotations.Parameter(description = "删除项目 ID") @PathVariable UUID projectId,
                                                     @io.swagger.v3.oas.annotations.Parameter(description = "导出任务 ID") @PathVariable UUID exportId) {
        return ResponseEntity.ok(ProjectExportResponse.from(exportService.get(projectId, exportId)));
    }

    /**
     * 在当前账号和OWNER事实权威复核后签发五分钟下载URL。
     * @param projectId 项目 ID
     * @param exportId 导出任务 ID
     * @return 仅含短时URL与截止时刻的响应
     */
    @Operation(summary = "签发项目导出下载地址", description = "在当前账号和OWNER事实权威复核后签发五分钟下载URL。")
    @PostMapping("/{exportId}/download-url")
    public ResponseEntity<ProjectExportDownloadUrlResponse> download(@io.swagger.v3.oas.annotations.Parameter(description = "项目 ID") @PathVariable UUID projectId,
                                                                      @io.swagger.v3.oas.annotations.Parameter(description = "导出任务 ID") @PathVariable UUID exportId) {
        return ResponseEntity.ok(ProjectExportDownloadUrlResponse.from(
                exportService.download(projectId, exportId)));
    }
}
