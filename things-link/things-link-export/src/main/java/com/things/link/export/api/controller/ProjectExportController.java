package com.things.link.export.api.controller;

import com.things.link.export.api.dto.ProjectExportResponse;
import com.things.link.export.api.dto.ProjectExportDownloadUrlResponse;
import com.things.link.export.application.ProjectExportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 项目冻结期一致快照导出HTTP入口。 */
@Tag(name = "Project Export", description = "项目冻结期数据导出")
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
    @Operation(summary = "请求项目数据导出")
    @PostMapping
    public ResponseEntity<ProjectExportResponse> request(@PathVariable UUID projectId) {
        return ResponseEntity.accepted().body(ProjectExportResponse.from(exportService.request(projectId)));
    }

    /**
     * 查询导出任务状态。
     * @param projectId 删除项目 ID
     * @param exportId 导出任务 ID
     * @return 当前状态；5g2另行增加下载签发
     */
    @Operation(summary = "查询项目数据导出状态")
    @GetMapping("/{exportId}")
    public ResponseEntity<ProjectExportResponse> get(@PathVariable UUID projectId,
                                                     @PathVariable UUID exportId) {
        return ResponseEntity.ok(ProjectExportResponse.from(exportService.get(projectId, exportId)));
    }

    /**
     * 在当前账号和OWNER事实权威复核后签发五分钟下载URL。
     * @param projectId 项目 ID
     * @param exportId 导出任务 ID
     * @return 仅含短时URL与截止时刻的响应
     */
    @Operation(summary = "签发项目导出下载地址")
    @PostMapping("/{exportId}/download-url")
    public ResponseEntity<ProjectExportDownloadUrlResponse> download(@PathVariable UUID projectId,
                                                                      @PathVariable UUID exportId) {
        return ResponseEntity.ok(ProjectExportDownloadUrlResponse.from(
                exportService.download(projectId, exportId)));
    }
}
