package com.things.link.dashboard.api.controller;

import com.things.link.dashboard.api.dto.request.ApplicationPublicationRevisionRequest;
import com.things.link.dashboard.api.dto.request.CreateApplicationRequest;
import com.things.link.dashboard.api.dto.request.PublishApplicationVersionRequest;
import com.things.link.dashboard.api.dto.request.RenameApplicationRequest;
import com.things.link.dashboard.api.dto.request.SaveApplicationDraftRequest;
import com.things.link.dashboard.api.dto.response.ApplicationCatalogResponse;
import com.things.link.dashboard.api.dto.response.ApplicationCreationResponse;
import com.things.link.dashboard.api.dto.response.ApplicationDraftResponse;
import com.things.link.dashboard.api.dto.response.ApplicationVersionResponse;
import com.things.link.dashboard.api.dto.response.ApplicationVersionSummaryResponse;
import com.things.link.dashboard.api.support.ApplicationApiAuthorization;
import com.things.link.dashboard.api.support.ApplicationManagementRequestParser;
import com.things.link.dashboard.api.support.ApplicationPublicationRequestParser;
import com.things.link.dashboard.application.ApplicationManagementService;
import com.things.link.dashboard.application.publication.ApplicationPublicationService;
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
 * Console应用目录、草稿与不可变版本历史的管理HTTP入口。
 *
 * <p>S12-1d1接入列表、详情与草稿读取；S12-1d2增加幂等创建、改名与草稿保存；
 * S12-1e2b增加不可变历史版本列表与详情读取，S12-1e2c至1e2f开放发布、精确历史版本回滚、撤回与软删入口。
 * 每个入口先执行对应的{@code application:read}或{@code application:manage}守卫，
 * 再调用保留二次成员与角色校验的应用用例。</p>
 */
@Tag(name = "WebApp应用", description = "Console中的WebApp应用目录、草稿与不可变版本历史管理")
@Validated
@RestController
@RequestMapping("/api/v1/projects/{projectId}/applications")
public class ApplicationManagementController {

    /** 应用目录、草稿与不可变版本历史管理用例。 */
    private final ApplicationManagementService service;

    /** HTTP边界的项目成员首层守卫。 */
    private final ApplicationApiAuthorization authorization;

    /** 在DTO绑定前保留重复键和content原始字节的严格信封解析器。 */
    private final ApplicationManagementRequestParser requestParser;

    /** 在DTO绑定前严格解析应用发布生命周期信封的独立解析器。 */
    private final ApplicationPublicationRequestParser publicationRequestParser;

    /** 在原子事务内重建资格并改变应用发布状态的受控用例。 */
    private final ApplicationPublicationService publicationService;

    /**
     * 创建应用管理HTTP入口。
     *
     * @param service 应用管理用例
     * @param authorization HTTP首层授权守卫
     * @param requestParser 写入信封原始JSON解析器
     * @param publicationRequestParser 发布生命周期信封原始JSON解析器
     * @param publicationService 应用受控发布生命周期用例
     */
    public ApplicationManagementController(
            ApplicationManagementService service,
            ApplicationApiAuthorization authorization,
            ApplicationManagementRequestParser requestParser,
            ApplicationPublicationRequestParser publicationRequestParser,
            ApplicationPublicationService publicationService) {
        this.service = Objects.requireNonNull(service, "service");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.requestParser = Objects.requireNonNull(requestParser, "requestParser");
        this.publicationRequestParser = Objects.requireNonNull(
                publicationRequestParser, "publicationRequestParser");
        this.publicationService = Objects.requireNonNull(publicationService, "publicationService");
    }

    /**
     * 以领域幂等身份创建未发布应用与revision为0的初始草稿。
     *
     * @param projectId 当前选定的项目ID
     * @param idempotencyKey 客户端生成且在当前项目创建范围内唯一的幂等键
     * @param body 未绑定的原始JSON信封
     * @return 首次创建或相同请求恢复得到的不可变应用身份
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "创建应用", description = "Idempotency-Key必填；相同请求恢复原应用身份")
    @ApiResponse(
            responseCode = "201",
            description = "首次创建或相同请求恢复成功",
            headers = @Header(
                    name = "Location",
                    description = "稳定的应用详情资源位置",
                    schema = @Schema(types = {"string"}, format = "uri")),
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApplicationCreationResponse.class)))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = CreateApplicationRequest.class)))
    public ResponseEntity<ApplicationCreationResponse> create(
            @PathVariable UUID projectId,
            @Parameter(required = true)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody byte[] body) {
        authorization.requireManage(projectId);
        CreateApplicationRequest request = requestParser.parseCreate(body);
        ApplicationCreationResponse response = ApplicationCreationResponse.from(service.createIdempotent(
                projectId, idempotencyKey, request.managementName(), request.content()));
        URI location = URI.create("/api/v1/projects/%s/applications/%s".formatted(projectId, response.id()));
        return ResponseEntity.created(location).body(response);
    }

    /**
     * 按创建时刻与ID的稳定键集顺序列出可见应用。
     *
     * @param projectId 当前选定的项目ID
     * @param cursor 上一页返回的不透明游标；首页为空
     * @param limit 单页数量，默认50，允许1至200
     * @return 不包含软删除应用的目录页
     */
    @GetMapping
    @Operation(summary = "应用目录分页", description = "使用不透明游标读取当前项目的未删除应用")
    public ResponseEntity<CursorPage<ApplicationCatalogResponse>> list(
            @PathVariable UUID projectId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        authorization.requireRead(projectId);
        return ResponseEntity.ok(service.list(projectId, cursor, limit).map(ApplicationCatalogResponse::from));
    }

    /**
     * 读取当前项目内一个可见应用的目录事实。
     *
     * @param projectId 当前选定的项目ID
     * @param applicationId 应用内部ID
     * @return 应用目录响应
     */
    @GetMapping("/{applicationId}")
    @Operation(summary = "应用目录详情", description = "读取当前项目内的单个未删除应用")
    public ResponseEntity<ApplicationCatalogResponse> find(
            @PathVariable UUID projectId,
            @PathVariable UUID applicationId) {
        authorization.requireRead(projectId);
        return ResponseEntity.ok(ApplicationCatalogResponse.from(service.find(projectId, applicationId)));
    }

    /**
     * 读取当前项目内一个可见应用的完整草稿。
     *
     * @param projectId 当前选定的项目ID
     * @param applicationId 应用内部ID
     * @return 包含完整应用对象与CAS修订号的草稿响应
     */
    @GetMapping("/{applicationId}/draft")
    @Operation(summary = "应用草稿详情", description = "读取当前项目内未删除应用的可变草稿")
    public ResponseEntity<ApplicationDraftResponse> getDraft(
            @PathVariable UUID projectId,
            @PathVariable UUID applicationId) {
        authorization.requireRead(projectId);
        return ResponseEntity.ok(ApplicationDraftResponse.from(service.getDraft(projectId, applicationId)));
    }

    /**
     * 按版本号倒序读取当前项目内一个可见应用的轻量发布历史。
     *
     * @param projectId 当前选定的项目ID
     * @param applicationId 应用内部ID
     * @param cursor 上一页返回的不透明版本号游标；首页为空
     * @param limit 单页数量，默认50，允许1至200
     * @return 不包含完整快照和内部归属的版本摘要页
     */
    @GetMapping("/{applicationId}/versions")
    @Operation(operationId = "listApplicationVersions", summary = "应用历史版本分页",
            description = "按版本号倒序读取未删除应用的轻量不可变版本元数据")
    public ResponseEntity<CursorPage<ApplicationVersionSummaryResponse>> listApplicationVersions(
            @PathVariable UUID projectId,
            @PathVariable UUID applicationId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        authorization.requireRead(projectId);
        return ResponseEntity.ok(service.listVersions(projectId, applicationId, cursor, limit)
                .map(ApplicationVersionSummaryResponse::from));
    }

    /**
     * 读取当前项目内一个可见应用的精确不可变版本详情。
     *
     * @param projectId 当前选定的项目ID
     * @param applicationId 应用内部ID
     * @param versionId 精确应用版本ID
     * @return 包含完整冻结快照和摘要的版本详情
     */
    @GetMapping("/{applicationId}/versions/{versionId}")
    @Operation(operationId = "getApplicationVersion", summary = "应用历史版本详情",
            description = "读取未删除应用的精确不可变版本快照与摘要")
    public ResponseEntity<ApplicationVersionResponse> getApplicationVersion(
            @PathVariable UUID projectId,
            @PathVariable UUID applicationId,
            @PathVariable UUID versionId) {
        authorization.requireRead(projectId);
        return ResponseEntity.ok(ApplicationVersionResponse.from(
                service.getVersion(projectId, applicationId, versionId)));
    }

    /**
     * 以草稿和发布状态双revision把当前应用草稿封存为新不可变版本。
     *
     * @param projectId 当前选定的项目ID
     * @param applicationId 应用内部ID
     * @param idempotencyKey 已由公共过滤器消费的可选幂等键，仅保留参数以公开OpenAPI合同
     * @param body 未绑定的原始双revision JSON信封
     * @return 新版本详情及其稳定精确读取位置
     */
    @PostMapping(value = "/{applicationId}/versions", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "publishApplicationVersion", summary = "发布应用新版本",
            description = "在同一事务内以双revision重取草稿、重验精确看板引用与当前宿主资格并追加不可变版本")
    @ApiResponse(
            responseCode = "201",
            description = "新不可变应用版本发布成功",
            headers = @Header(
                    name = "Location",
                    description = "新应用版本的精确管理读取位置",
                    schema = @Schema(types = {"string"}, format = "uri")),
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApplicationVersionResponse.class)))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = PublishApplicationVersionRequest.class)))
    public ResponseEntity<ApplicationVersionResponse> publishApplicationVersion(
            @PathVariable UUID projectId,
            @PathVariable UUID applicationId,
            @Parameter(description = "可选公共写幂等键；重放完成结果时按公共墓碑合同处理", required = false)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody byte[] body) {
        authorization.requireManage(projectId);
        PublishApplicationVersionRequest request = publicationRequestParser.parsePublish(body);
        ApplicationVersionResponse response = ApplicationVersionResponse.from(publicationService.publish(
                projectId,
                applicationId,
                request.expectedDraftRevision(),
                request.expectedPublicationRevision()));
        URI location = URI.create("/api/v1/projects/%s/applications/%s/versions/%s"
                .formatted(projectId, applicationId, response.id()));
        return ResponseEntity.created(location).body(response);
    }

    /**
     * 把发布指针切回重新通过当前资格的指定不可变应用历史版本。
     *
     * @param projectId 当前选定的项目ID
     * @param applicationId 应用内部ID
     * @param versionId 目标历史版本ID
     * @param idempotencyKey 已由公共过滤器消费的可选幂等键，仅保留参数以公开OpenAPI合同
     * @param body 未绑定的原始publicationRevision JSON信封
     * @return 未复制或改写的目标版本详情
     */
    @PostMapping(
            value = "/{applicationId}/versions/{versionId}/rollback",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "rollbackApplicationVersion", summary = "回滚应用历史版本",
            description = "重验目标不可变版本的精确看板引用与当前宿主资格后只切换发布指针")
    @ApiResponse(
            responseCode = "200",
            description = "发布指针已切换到指定应用历史版本",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApplicationVersionResponse.class)))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApplicationPublicationRevisionRequest.class)))
    public ResponseEntity<ApplicationVersionResponse> rollbackApplicationVersion(
            @PathVariable UUID projectId,
            @PathVariable UUID applicationId,
            @PathVariable UUID versionId,
            @Parameter(description = "可选公共写幂等键；重放完成结果时按公共墓碑合同处理", required = false)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody byte[] body) {
        authorization.requireManage(projectId);
        ApplicationPublicationRevisionRequest request = publicationRequestParser.parsePublicationRevision(body);
        return ResponseEntity.ok(ApplicationVersionResponse.from(publicationService.rollback(
                projectId,
                applicationId,
                versionId,
                request.expectedPublicationRevision())));
    }

    /**
     * 清空当前应用发布指针并保留全部不可变版本及精确看板关系。
     *
     * @param projectId 当前选定的项目ID
     * @param applicationId 应用内部ID
     * @param idempotencyKey 已由公共过滤器消费的可选幂等键，仅保留参数以公开OpenAPI合同
     * @param body 未绑定的原始publicationRevision JSON信封
     * @return 无响应正文或资源位置的204结果
     */
    @PostMapping(value = "/{applicationId}/withdraw", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "withdrawApplicationPublication", summary = "撤回应用当前发布",
            description = "只清空当前发布指针并保留全部不可变版本及精确看板关系")
    @ApiResponse(responseCode = "204", description = "当前应用发布已撤回")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApplicationPublicationRevisionRequest.class)))
    public ResponseEntity<Void> withdrawApplicationPublication(
            @PathVariable UUID projectId,
            @PathVariable UUID applicationId,
            @Parameter(description = "可选公共写幂等键；重放完成结果时按公共墓碑合同处理", required = false)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody byte[] body) {
        authorization.requireManage(projectId);
        ApplicationPublicationRevisionRequest request = publicationRequestParser.parsePublicationRevision(body);
        publicationService.withdraw(projectId, applicationId, request.expectedPublicationRevision());
        return ResponseEntity.noContent().build();
    }

    /**
     * 永久隐藏应用目录并保留草稿、不可变版本及精确看板关系至项目清理。
     *
     * @param projectId 当前选定的项目ID
     * @param applicationId 应用内部ID
     * @param idempotencyKey 已由公共过滤器消费的可选幂等键，仅保留参数以公开OpenAPI合同
     * @param body 未绑定的原始publicationRevision JSON信封
     * @return 无响应正文或资源位置的204结果
     */
    @PostMapping(value = "/{applicationId}/soft-delete", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "softDeleteApplication", summary = "软删应用",
            description = "永久隐藏应用目录并保留草稿、不可变版本与精确看板关系至项目清理")
    @ApiResponse(responseCode = "204", description = "应用已软删并永久隐藏")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ApplicationPublicationRevisionRequest.class)))
    public ResponseEntity<Void> softDeleteApplication(
            @PathVariable UUID projectId,
            @PathVariable UUID applicationId,
            @Parameter(description = "可选公共写幂等键；重放完成结果时按公共墓碑合同处理", required = false)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody byte[] body) {
        authorization.requireManage(projectId);
        ApplicationPublicationRevisionRequest request = publicationRequestParser.parsePublicationRevision(body);
        publicationService.softDelete(projectId, applicationId, request.expectedPublicationRevision());
        return ResponseEntity.noContent().build();
    }

    /**
     * 修改当前项目内一个可见应用的Console管理名称。
     *
     * @param projectId 当前选定的项目ID
     * @param applicationId 应用内部ID
     * @param body 未绑定的原始JSON信封
     * @return 改名后的应用目录响应
     */
    @PatchMapping(value = "/{applicationId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "修改应用管理名称", description = "仅修改Console目录名称，不改发布内容")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = RenameApplicationRequest.class)))
    public ResponseEntity<ApplicationCatalogResponse> rename(
            @PathVariable UUID projectId,
            @PathVariable UUID applicationId,
            @RequestBody byte[] body) {
        authorization.requireManage(projectId);
        RenameApplicationRequest request = requestParser.parseRename(body);
        return ResponseEntity.ok(ApplicationCatalogResponse.from(
                service.rename(projectId, applicationId, request.managementName())));
    }

    /**
     * 以调用方读取的revision执行应用草稿CAS保存。
     *
     * @param projectId 当前选定的项目ID
     * @param applicationId 应用内部ID
     * @param body 未绑定的原始JSON信封
     * @return 保存后revision恰好加一的完整草稿响应
     */
    @PutMapping(value = "/{applicationId}/draft", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "保存应用草稿", description = "保留content原始JSON字节并使用expectedRevision执行CAS")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = SaveApplicationDraftRequest.class)))
    public ResponseEntity<ApplicationDraftResponse> saveDraft(
            @PathVariable UUID projectId,
            @PathVariable UUID applicationId,
            @RequestBody byte[] body) {
        authorization.requireManage(projectId);
        SaveApplicationDraftRequest request = requestParser.parseSaveDraft(body);
        return ResponseEntity.ok(ApplicationDraftResponse.from(
                service.saveDraft(projectId, applicationId, request.expectedRevision(), request.content())));
    }
}
