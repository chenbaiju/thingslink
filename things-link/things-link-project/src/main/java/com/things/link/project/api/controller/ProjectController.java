package com.things.link.project.api.controller;

import com.things.link.project.api.dto.request.CreateProjectRequest;
import com.things.link.project.api.dto.request.UpdateProjectRequest;
import com.things.link.project.api.dto.response.ProjectRecycleBinResponse;
import com.things.link.project.api.dto.response.ProjectResponse;
import com.things.link.project.application.ProjectRecoveryService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 项目接口。
 *
 * <p>创建与“我的项目”列表不做项目角色判定，因为它们发生在选定项目之前 ——
 * 那时还没有项目角色可言。回收站与恢复同样允许无项目令牌，但会按账号与保留OWNER事实
 * 单独授权；编辑和删除仍使用普通项目管理门禁（ADR 0012、ADR0073）。
 *
 * <p>它们的隔离完全靠 SQL 里的 {@code m.account_id = ?}：
 * {@code project} 与 {@code project_member} 两张表<b>豁免了 RLS</b>，
 * 数据库这一层不会兜底。
 */
@RestController
@RequestMapping("/api/v1/projects")
@Tag(name = "项目", description = "项目创建、编辑、删除、恢复与列表")
public class ProjectController {

    private final ProjectService projectService;
    /** 删除项目专用查询与恢复服务，普通项目服务继续隐藏DELETING事实。 */
    private final ProjectRecoveryService projectRecoveryService;

    /**
     * 创建项目控制器。
     * @param projectService 普通项目用例
     * @param projectRecoveryService 回收站与恢复用例
     */
    public ProjectController(ProjectService projectService, ProjectRecoveryService projectRecoveryService) {
        this.projectService = projectService;
        this.projectRecoveryService = projectRecoveryService;
    }

    /**
     * 列出我参与的项目。
     *
     * <p>结果<b>跨租户</b>：既有自己创建的，也有被邀请加入的、属于别人租户的项目。
     *
     * <p>暂不分页。项目数量是个位数到几十的量级，游标分页的复杂度在这里划不来；
     * 等出现「一个账号参与上百个项目」的真实场景再说 ——
     * 范围条件见 ARCHITECTURE_GAPS.md GAP-TODO-08： 若真要加，按开发手册的约定用游标分页，不要用 offset。
     *
     * @return 项目列表
     */
    @GetMapping
    @Operation(summary = "我的项目",
            description = "列出当前账号参与的全部项目及其中的角色。结果跨租户。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "成功"),
            @ApiResponse(responseCode = "401", description = "未认证",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<List<ProjectResponse>> listMine() {
        return ResponseEntity.ok(projectService.listMine().stream()
                .map(ProjectResponse::from)
                .toList());
    }

    /**
     * 列出当前账号仍以ACTIVE OWNER身份持有的删除项目，包含已过期但尚未清理的项目。
     * @return 回收站项目列表
     */
    @GetMapping("/recycle-bin")
    @Operation(summary = "项目回收站",
            description = "列出当前账号作为有效所有者持有的删除项目及恢复期限。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "成功"),
            @ApiResponse(responseCode = "401", description = "未认证",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<List<ProjectRecycleBinResponse>> listRecycleBin() {
        return ResponseEntity.ok(projectRecoveryService.listRecycleBin().stream()
                .map(ProjectRecycleBinResponse::from)
                .toList());
    }

    /**
     * 创建项目，创建者自动成为 OWNER。
     *
     * <p>S14-2b：自有项目数受有效套餐冻结的 {@code projects_max} 约束（FREE 为 1）；超限返回 50020。
     * 外部协作者身份属于他人租户，不占用本租户名额。
     *
     * @param request 创建请求
     * @return 新建的项目
     */
    @PostMapping
    @Operation(summary = "创建项目",
            description = "创建者自动成为该项目的 OWNER，每个项目有且只有一个 OWNER。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "创建成功"),
            @ApiResponse(responseCode = "400", description = "参数不合法",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "未认证",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "503", description = "项目额度暂不可用（50047），确认套餐配置后重试",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "429", description = "租户自有项目数已达套餐上限",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<ProjectResponse> create(@Valid @RequestBody CreateProjectRequest request) {
        return ResponseEntity.ok(ProjectResponse.from(
                projectService.create(request.name(), request.region(), request.timezone())));
    }

    /**
     * 修改项目名称。
     *
     * <p>区域不能在这里修改。创建时选定区域后，设备接入地址、数据存储位置都会从它
     * 派生，后续跨区域属于迁移流程。
     *
     * @param projectId 项目 ID
     * @param request   编辑请求
     * @return 修改后的项目
     */
    @PatchMapping("/{projectId}")
    @Operation(summary = "编辑项目",
            description = "仅允许项目 OWNER 修改项目名称；项目区域创建后不可变更。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "修改成功"),
            @ApiResponse(responseCode = "400", description = "参数不合法",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "未认证",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "不是项目所有者",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "项目不存在",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<ProjectResponse> update(
            @PathVariable UUID projectId,
            @Valid @RequestBody UpdateProjectRequest request) {
        return ResponseEntity.ok(ProjectResponse.from(
                projectService.updateName(projectId, request.name())));
    }

    /**
     * 删除项目。
     *
     * <p>只允许 OWNER 删除，且项目里必须只剩 OWNER 一人。若还有被邀请成员，
     * 需要先解除成员与项目的绑定。
     *
     * @param projectId 项目 ID
     * @return 无内容
     */
    @DeleteMapping("/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "删除项目",
            description = "仅允许项目 OWNER 删除；项目仍有其他成员时不能删除。")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "删除成功"),
            @ApiResponse(responseCode = "401", description = "未认证",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "403", description = "不是项目所有者",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "项目不存在",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "项目仍有其他成员",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public void delete(@PathVariable UUID projectId) {
        projectService.delete(projectId);
    }

    /**
     * 在删除后三十天内恢复项目；恢复保留删除时递增的生命周期代次。
     * @param projectId 待恢复项目ID
     * @return 恢复后的项目
     */
    @PostMapping("/{projectId}/restore")
    @Operation(operationId = "restoreProject",
            summary = "恢复项目",
            description = "仅当前有效所有者可在删除后三十天内恢复项目；旧项目凭据不会复活。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "恢复成功"),
            @ApiResponse(responseCode = "401", description = "未认证",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "项目不存在或不可见",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "项目恢复期限已过",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<ProjectResponse> restore(@PathVariable UUID projectId) {
        return ResponseEntity.ok(ProjectResponse.from(projectRecoveryService.restore(projectId)));
    }

}
