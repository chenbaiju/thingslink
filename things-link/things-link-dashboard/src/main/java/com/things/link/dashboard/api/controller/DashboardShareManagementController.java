package com.things.link.dashboard.api.controller;

import com.things.link.dashboard.api.dto.request.CreateDashboardShareRequest;
import com.things.link.dashboard.api.dto.response.DashboardShareCreatedResponse;
import com.things.link.dashboard.api.dto.response.DashboardShareSummaryResponse;
import com.things.link.dashboard.api.support.DashboardApiAuthorization;
import com.things.link.dashboard.api.support.DashboardShareRequestParser;
import com.things.link.dashboard.application.publication.DashboardShareManagementService;
import com.things.link.shared.page.CursorPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/**
 * ADR0101分享管理入口，三个操作均要求dashboard_definition:manage。
 * 领域服务保留独立角色复核；此处不开放匿名读取，也不构造带secret的URL。
 */
@RestController
@Validated
@Tag(name = "看板分享管理", description = "签发有限只读分享、查询历史及不可恢复撤销")
@RequestMapping("/api/v1/projects/{projectId}/dashboards/{dashboardId}/shares")
public class DashboardShareManagementController {
    /** 首层Console项目权限守卫。 */
    private final DashboardApiAuthorization authorization;
    /** 包含原事务资格、幂等与审计的管理用例。 */
    private final DashboardShareManagementService service;
    /** 严格信封解析器保留重复字段和非法Unicode反例。 */
    private final DashboardShareRequestParser parser;

    /** 创建入口并要求所有运行依赖明确装配。 */
    public DashboardShareManagementController(DashboardApiAuthorization authorization,
            DashboardShareManagementService service, DashboardShareRequestParser parser) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.service = Objects.requireNonNull(service, "service");
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    /** 首次签发只返回一次secret；同key重试由领域安全冲突返回shareId。 */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "createDashboardShare", summary = "签发看板只读分享",
            description = "Idempotency-Key必填；首次返回secret，同key重试409/60052且详情仅含shareId")
    @ApiResponse(responseCode = "201", description = "首次签发成功；凭据只返回一次",
            content = @Content(schema = @Schema(implementation = DashboardShareCreatedResponse.class)))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = CreateDashboardShareRequest.class)))
    public ResponseEntity<DashboardShareCreatedResponse> createDashboardShare(
            @PathVariable UUID projectId, @PathVariable UUID dashboardId,
            @Parameter(required = true) @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody byte[] body) {
        authorization.requireManage(projectId);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(
                DashboardShareCreatedResponse.from(service.create(projectId, dashboardId, key, parser.parseCreate(body))));
    }

    /** 列出全部状态的安全摘要；ARCHIVED只读可用，不返回secret/hash/creator或完整scope。 */
    @GetMapping
    @Operation(operationId = "listDashboardShares", summary = "分页查询看板分享历史")
    public ResponseEntity<CursorPage<DashboardShareSummaryResponse>> listDashboardShares(
            @PathVariable UUID projectId, @PathVariable UUID dashboardId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
        authorization.requireManage(projectId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                service.page(projectId, dashboardId, cursor, limit).map(DashboardShareSummaryResponse::from));
    }

    /** 无body的不可恢复撤销；重复撤销同一事实成功且不追加审计。 */
    @PostMapping("/{shareId}/revoke")
    @Operation(operationId = "revokeDashboardShare", summary = "撤销看板只读分享")
    @ApiResponse(responseCode = "204", description = "已撤销；重复操作无变化")
    public ResponseEntity<Void> revokeDashboardShare(@PathVariable UUID projectId,
            @PathVariable UUID dashboardId, @PathVariable UUID shareId,
            HttpServletRequest request) throws IOException {
        authorization.requireManage(projectId);
        // 只读取一个字节即可证明违反无body合同，不缓存或绑定无意义的大正文。
        parser.requireNoBody(request.getInputStream().readNBytes(1));
        service.revoke(projectId, dashboardId, shareId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
