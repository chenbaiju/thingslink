package com.things.link.dashboard.api.controller;

import com.things.link.dashboard.api.dto.request.CreateDashboardRequest;
import com.things.link.dashboard.api.dto.request.DashboardPublicationRevisionRequest;
import com.things.link.dashboard.api.dto.request.PublishDashboardVersionRequest;
import com.things.link.dashboard.api.dto.request.RenameDashboardRequest;
import com.things.link.dashboard.api.dto.request.SaveDashboardDraftRequest;
import com.things.link.dashboard.api.dto.response.DashboardCatalogResponse;
import com.things.link.dashboard.api.dto.response.DashboardCreationResponse;
import com.things.link.dashboard.api.dto.response.DashboardDraftResponse;
import com.things.link.dashboard.api.dto.response.DashboardVersionResponse;
import com.things.link.dashboard.api.dto.response.DashboardVersionSummaryResponse;
import com.things.link.dashboard.api.support.DashboardApiAuthorization;
import com.things.link.dashboard.api.support.DashboardManagementRequestParser;
import com.things.link.dashboard.api.support.DashboardPublicationRequestParser;
import com.things.link.dashboard.application.DashboardManagementService;
import com.things.link.dashboard.application.publication.DashboardPublicationService;
import com.things.link.shared.page.CursorPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Objects;
import java.util.UUID;

/**
 * Console看板目录、草稿与不可变版本历史的管理HTTP入口。
 *
 * <p>S12-1d3开放目录、详情和草稿读取；S12-1d4增加幂等创建、改名与草稿保存；
 * S12-1e1a增加轻量版本分页与精确版本详情，S12-1e1b至1e1e开放发布、精确历史版本回滚、撤回与软删除入口。
 * 每个入口先执行对应的{@code dashboard_definition:read}或
 * {@code dashboard_definition:manage}守卫，再调用保留二次成员与角色校验的看板用例。</p>
 *
 * <p>全部{@link Operation#operationId()}显式冻结生成客户端名称：三个基础读取沿用S12-1d3已发布值，
 * 写入及版本读取使用唯一看板语义名称。删除这些值会因Application控制器存在同名Java方法而反向改写
 * 已发布的应用客户端操作名。</p>
 */
@Tag(name = "看板定义", description = "Console中的看板目录、草稿与不可变版本管理")
@Validated
@RestController
@RequestMapping("/api/v1/projects/{projectId}/dashboards")
public class DashboardManagementController {

    /** 看板目录、草稿与版本历史管理用例。 */
    private final DashboardManagementService service;

    /** HTTP边界的项目成员首层守卫。 */
    private final DashboardApiAuthorization authorization;

    /** 在DTO绑定前保留重复键和content原始字节的严格信封解析器。 */
    private final DashboardManagementRequestParser requestParser;

    /** 在DTO绑定前严格解析发布生命周期信封的独立解析器。 */
    private final DashboardPublicationRequestParser publicationRequestParser;

    /** 在原子事务内重建资格并改变看板发布状态的受控用例。 */
    private final DashboardPublicationService publicationService;

    /**
     * 创建看板管理HTTP入口。
     *
     * @param service 看板管理用例
     * @param authorization HTTP首层授权守卫
     * @param requestParser 写入信封原始JSON解析器
     * @param publicationRequestParser 发布生命周期信封原始JSON解析器
     * @param publicationService 看板受控发布生命周期用例
     */
    public DashboardManagementController(
            DashboardManagementService service,
            DashboardApiAuthorization authorization,
            DashboardManagementRequestParser requestParser,
            DashboardPublicationRequestParser publicationRequestParser,
            DashboardPublicationService publicationService) {
        this.service = Objects.requireNonNull(service, "service");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.requestParser = Objects.requireNonNull(requestParser, "requestParser");
        this.publicationRequestParser = Objects.requireNonNull(
                publicationRequestParser, "publicationRequestParser");
        this.publicationService = Objects.requireNonNull(publicationService, "publicationService");
    }

    /**
     * 以领域幂等身份创建未发布看板与revision为0的初始草稿。
     *
     * @param projectId 当前选定的项目ID
     * @param idempotencyKey 客户端生成且在当前项目创建范围内唯一的幂等键
     * @param body 未绑定的原始JSON信封
     * @return 首次创建或相同请求恢复得到的不可变看板身份
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "createDashboard", summary = "创建看板",
            description = "Idempotency-Key必填；相同请求恢复原看板身份；新增按所属租户有效额度准入，已满409/60059，额度不可用503/50048")
    @ApiResponse(
            responseCode = "201",
            description = "首次创建或相同请求恢复成功",
            headers = @Header(
                    name = "Location",
                    description = "稳定的看板详情资源位置",
                    schema = @Schema(types = {"string"}, format = "uri")),
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = DashboardCreationResponse.class)))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = CreateDashboardRequest.class)))
    public ResponseEntity<DashboardCreationResponse> createDashboard(
            @PathVariable UUID projectId,
            @Parameter(required = true)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody byte[] body) {
        authorization.requireManage(projectId);
        CreateDashboardRequest request = requestParser.parseCreate(body);
        DashboardCreationResponse response = DashboardCreationResponse.from(service.createIdempotent(
                projectId, idempotencyKey, request.managementName(), request.content()));
        URI location = URI.create("/api/v1/projects/%s/dashboards/%s".formatted(projectId, response.id()));
        return ResponseEntity.created(location).body(response);
    }

    /**
     * 按最近更新时间与ID的稳定键集顺序列出未删除看板。
     *
     * @param projectId 当前选定的项目ID
     * @param cursor 上一页返回的不透明游标；首页为空
     * @param limit 单页数量，默认50，允许1至200
     * @return 不包含软删除看板的目录页
     */
    @GetMapping
    @Operation(operationId = "listDashboards", summary = "看板目录分页",
            description = "使用不透明游标读取当前项目的未删除看板")
    public ResponseEntity<CursorPage<DashboardCatalogResponse>> list(
            @PathVariable UUID projectId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        authorization.requireRead(projectId);
        return ResponseEntity.ok(service.list(projectId, cursor, limit).map(DashboardCatalogResponse::from));
    }

    /**
     * 读取当前项目内一个可见看板的目录事实。
     *
     * @param projectId 当前选定的项目ID
     * @param dashboardId 看板内部ID
     * @return 看板目录响应
     */
    @GetMapping("/{dashboardId}")
    @Operation(operationId = "find_1", summary = "看板目录详情",
            description = "读取当前项目内的单个未删除看板")
    public ResponseEntity<DashboardCatalogResponse> find(
            @PathVariable UUID projectId,
            @PathVariable UUID dashboardId) {
        authorization.requireRead(projectId);
        return ResponseEntity.ok(DashboardCatalogResponse.from(service.find(projectId, dashboardId)));
    }

    /**
     * 读取当前项目内一个可见看板的完整草稿。
     *
     * @param projectId 当前选定的项目ID
     * @param dashboardId 看板内部ID
     * @return 包含完整Schema对象与CAS修订号的草稿响应
     */
    @GetMapping("/{dashboardId}/draft")
    @Operation(operationId = "getDraft_1", summary = "看板草稿详情",
            description = "读取当前项目内未删除看板的可变草稿")
    public ResponseEntity<DashboardDraftResponse> getDraft(
            @PathVariable UUID projectId,
            @PathVariable UUID dashboardId) {
        authorization.requireRead(projectId);
        return ResponseEntity.ok(DashboardDraftResponse.from(service.getDraft(projectId, dashboardId)));
    }

    /**
     * 按版本号倒序读取当前项目内一个可见看板的轻量发布历史。
     *
     * @param projectId 当前选定的项目ID
     * @param dashboardId 看板内部ID
     * @param cursor 上一页返回的不透明版本号游标；首页为空
     * @param limit 单页数量，默认50，允许1至200
     * @return 不包含完整Schema和内部归属的版本摘要页
     */
    @GetMapping("/{dashboardId}/versions")
    @Operation(operationId = "listDashboardVersions", summary = "看板历史版本分页",
            description = "按版本号倒序读取未删除看板的轻量不可变版本元数据")
    public ResponseEntity<CursorPage<DashboardVersionSummaryResponse>> listDashboardVersions(
            @PathVariable UUID projectId,
            @PathVariable UUID dashboardId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        authorization.requireRead(projectId);
        return ResponseEntity.ok(service.listVersions(projectId, dashboardId, cursor, limit)
                .map(DashboardVersionSummaryResponse::from));
    }

    /**
     * 读取当前项目内一个可见看板的精确不可变版本详情。
     *
     * @param projectId 当前选定的项目ID
     * @param dashboardId 看板内部ID
     * @param versionId 精确看板版本ID
     * @return 包含完整Schema与派生需求的版本详情
     */
    @GetMapping("/{dashboardId}/versions/{versionId}")
    @Operation(operationId = "getDashboardVersion", summary = "看板历史版本详情",
            description = "读取未删除看板的精确不可变版本Schema与派生需求")
    public ResponseEntity<DashboardVersionResponse> getDashboardVersion(
            @PathVariable UUID projectId,
            @PathVariable UUID dashboardId,
            @PathVariable UUID versionId) {
        authorization.requireRead(projectId);
        return ResponseEntity.ok(DashboardVersionResponse.from(
                service.getVersion(projectId, dashboardId, versionId)));
    }

    /**
     * 以草稿和发布状态双revision把当前草稿封存为新不可变版本。
     *
     * @param projectId 当前选定的项目ID
     * @param dashboardId 看板内部ID
     * @param idempotencyKey 已由公共过滤器消费的可选幂等键，仅保留参数以公开OpenAPI合同
     * @param body 未绑定的原始双revision JSON信封
     * @return 新版本详情及其稳定精确读取位置
     */
    @PostMapping(value = "/{dashboardId}/versions", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "publishDashboardVersion", summary = "发布看板新版本",
            description = "在同一事务内以双revision重取草稿、重验当前资格并追加不可变版本")
    @ApiResponse(
            responseCode = "201",
            description = "新不可变版本发布成功",
            headers = @Header(
                    name = "Location",
                    description = "新看板版本的精确管理读取位置",
                    schema = @Schema(types = {"string"}, format = "uri")),
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = DashboardVersionResponse.class)))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = PublishDashboardVersionRequest.class)))
    public ResponseEntity<DashboardVersionResponse> publishDashboardVersion(
            @PathVariable UUID projectId,
            @PathVariable UUID dashboardId,
            @Parameter(description = "可选公共写幂等键；重放完成结果时按公共墓碑合同处理", required = false)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody byte[] body) {
        authorization.requireManage(projectId);
        PublishDashboardVersionRequest request = publicationRequestParser.parsePublish(body);
        DashboardVersionResponse response = DashboardVersionResponse.from(publicationService.publish(
                projectId,
                dashboardId,
                request.expectedDraftRevision(),
                request.expectedPublicationRevision()));
        URI location = URI.create("/api/v1/projects/%s/dashboards/%s/versions/%s"
                .formatted(projectId, dashboardId, response.id()));
        return ResponseEntity.created(location).body(response);
    }

    /**
     * 把发布指针切回重新通过当前资格的指定不可变历史版本。
     *
     * @param projectId 当前选定的项目ID
     * @param dashboardId 看板内部ID
     * @param versionId 目标历史版本ID
     * @param idempotencyKey 已由公共过滤器消费的可选幂等键，仅保留参数以公开OpenAPI合同
     * @param body 未绑定的原始publicationRevision JSON信封
     * @return 未复制或改写的目标版本详情
     */
    @PostMapping(
            value = "/{dashboardId}/versions/{versionId}/rollback",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "rollbackDashboardVersion", summary = "回滚看板历史版本",
            description = "重验目标不可变版本的当前资格后只切换发布指针")
    @ApiResponse(
            responseCode = "200",
            description = "发布指针已切换到指定历史版本",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = DashboardVersionResponse.class)))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = DashboardPublicationRevisionRequest.class)))
    public ResponseEntity<DashboardVersionResponse> rollbackDashboardVersion(
            @PathVariable UUID projectId,
            @PathVariable UUID dashboardId,
            @PathVariable UUID versionId,
            @Parameter(description = "可选公共写幂等键；重放完成结果时按公共墓碑合同处理", required = false)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody byte[] body) {
        authorization.requireManage(projectId);
        DashboardPublicationRevisionRequest request = publicationRequestParser.parsePublicationRevision(body);
        return ResponseEntity.ok(DashboardVersionResponse.from(publicationService.rollback(
                projectId,
                dashboardId,
                versionId,
                request.expectedPublicationRevision())));
    }

    /**
     * 清空当前发布指针并保留全部不可变看板版本。
     *
     * @param projectId 当前选定的项目ID
     * @param dashboardId 看板内部ID
     * @param idempotencyKey 已由公共过滤器消费的可选幂等键，仅保留参数以公开OpenAPI合同
     * @param body 未绑定的原始publicationRevision JSON信封
     * @return 无响应正文或资源位置的204结果
     */
    @PostMapping(value = "/{dashboardId}/withdraw", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "withdrawDashboardPublication", summary = "撤回看板当前发布",
            description = "只清空当前发布指针并保留全部不可变版本")
    @ApiResponse(responseCode = "204", description = "当前看板发布已撤回")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = DashboardPublicationRevisionRequest.class)))
    public ResponseEntity<Void> withdrawDashboardPublication(
            @PathVariable UUID projectId,
            @PathVariable UUID dashboardId,
            @Parameter(description = "可选公共写幂等键；重放完成结果时按公共墓碑合同处理", required = false)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody byte[] body) {
        authorization.requireManage(projectId);
        DashboardPublicationRevisionRequest request = publicationRequestParser.parsePublicationRevision(body);
        publicationService.withdraw(projectId, dashboardId, request.expectedPublicationRevision());
        return ResponseEntity.noContent().build();
    }

    /**
     * 一次性软删除看板目录并永久保留草稿与不可变版本历史。
     *
     * @param projectId 当前选定的项目ID
     * @param dashboardId 看板内部ID
     * @param idempotencyKey 已由公共过滤器消费的可选幂等键，仅保留参数以公开OpenAPI合同
     * @param body 未绑定的原始publicationRevision JSON信封
     * @return 无响应正文或资源位置的204结果
     */
    @PostMapping(value = "/{dashboardId}/soft-delete", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "softDeleteDashboard", summary = "软删除看板",
            description = "清空当前发布指针并永久隐藏目录，草稿与不可变历史保留至项目清理")
    @ApiResponse(responseCode = "204", description = "看板已软删除")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = DashboardPublicationRevisionRequest.class)))
    public ResponseEntity<Void> softDeleteDashboard(
            @PathVariable UUID projectId,
            @PathVariable UUID dashboardId,
            @Parameter(description = "可选公共写幂等键；重放完成结果时按公共墓碑合同处理", required = false)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody byte[] body) {
        authorization.requireManage(projectId);
        DashboardPublicationRevisionRequest request = publicationRequestParser.parsePublicationRevision(body);
        publicationService.softDelete(projectId, dashboardId, request.expectedPublicationRevision());
        return ResponseEntity.noContent().build();
    }

    /**
     * 修改当前项目内一个可见看板的Console管理名称。
     *
     * @param projectId 当前选定的项目ID
     * @param dashboardId 看板内部ID
     * @param body 未绑定的原始JSON信封
     * @return 改名后的看板目录响应
     */
    @PatchMapping(value = "/{dashboardId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "renameDashboard", summary = "修改看板管理名称",
            description = "仅修改Console目录名称，不改发布内容")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = RenameDashboardRequest.class)))
    public ResponseEntity<DashboardCatalogResponse> rename(
            @PathVariable UUID projectId,
            @PathVariable UUID dashboardId,
            @RequestBody byte[] body) {
        authorization.requireManage(projectId);
        RenameDashboardRequest request = requestParser.parseRename(body);
        return ResponseEntity.ok(DashboardCatalogResponse.from(
                service.rename(projectId, dashboardId, request.managementName())));
    }

    /**
     * 以调用方读取的revision执行看板草稿CAS保存。
     *
     * @param projectId 当前选定的项目ID
     * @param dashboardId 看板内部ID
     * @param body 未绑定的原始JSON信封
     * @return 保存后revision恰好加一的完整草稿响应
     */
    @PutMapping(value = "/{dashboardId}/draft", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "saveDashboardDraft", summary = "保存看板草稿",
            description = "保留content原始JSON字节并使用expectedRevision执行CAS")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = SaveDashboardDraftRequest.class)))
    public ResponseEntity<DashboardDraftResponse> saveDraft(
            @PathVariable UUID projectId,
            @PathVariable UUID dashboardId,
            @RequestBody byte[] body) {
        authorization.requireManage(projectId);
        SaveDashboardDraftRequest request = requestParser.parseSaveDraft(body);
        return ResponseEntity.ok(DashboardDraftResponse.from(
                service.saveDraft(projectId, dashboardId, request.expectedRevision(), request.content())));
    }
}
