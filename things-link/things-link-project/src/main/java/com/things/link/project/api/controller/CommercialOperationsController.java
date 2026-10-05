package com.things.link.project.api.controller;

import com.things.link.project.api.dto.request.CreateCommercialAdjustmentRequest;
import com.things.link.project.application.CommercialOperationsService;
import com.things.link.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/** Console认证加每次持久平台授权，项目OWNER不隐含运营权限。 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/adjustments")
@Tag(name="商业运营",description="受控平台运营审批；不代表销售或云容量资格")
@ApiResponses({
    @ApiResponse(responseCode="200",description="成功；数量及版本均为精确十进制字符串"),
    @ApiResponse(responseCode="400",description="参数或精确整数格式错误、50034维度未开放、50039有效期、50040元数据",
        content=@Content(schema=@Schema(implementation=ApiError.class))),
    @ApiResponse(responseCode="401",description="未认证",content=@Content(schema=@Schema(implementation=ApiError.class))),
    @ApiResponse(responseCode="403",description="50051缺少有效平台商业权限",content=@Content(schema=@Schema(implementation=ApiError.class))),
    @ApiResponse(responseCode="404",description="租户或50037调整不存在",content=@Content(schema=@Schema(implementation=ApiError.class))),
    @ApiResponse(responseCode="409",description="50041幂等冲突、50052审批版本变化、50036来源不符、50038不可撤销",
        content=@Content(schema=@Schema(implementation=ApiError.class))),
    @ApiResponse(responseCode="503",description="50048套餐投影不可用",content=@Content(schema=@Schema(implementation=ApiError.class)))
})
public class CommercialOperationsController {
    private final CommercialOperationsService operations;
    public CommercialOperationsController(CommercialOperationsService operations) { this.operations=operations; }
    /**
     * 读取目标租户调整审批摘要。
     *
     * @param tenantId 目标租户标识
     * @return 当前接口的操作结果，响应结构见 {@code CommercialOperationsService.Preview}
     */
    @GetMapping("/context") @Operation(summary="读取目标租户调整审批摘要", description = "读取目标租户调整审批摘要。")
    public CommercialOperationsService.Preview preview(@io.swagger.v3.oas.annotations.Parameter(description = "目标租户标识") @PathVariable UUID tenantId) { return operations.preview(tenantId); }
    /**
     * 提交有限期人工调整。
     * 精确字符串数量；可信操作者；先预览版本，未知结果按原键查询，不自动换键
     *
     * @param tenantId 目标租户标识
     * @param request 本次操作的请求数据，结构见 {@code CreateCommercialAdjustmentRequest}
     * @return 当前接口的操作结果，响应结构见 {@code CommercialOperationsService.Adjustment}
     */
    @PostMapping @Operation(summary="提交有限期人工调整",description="精确字符串数量；可信操作者；先预览版本，未知结果按原键查询，不自动换键")
    public CommercialOperationsService.Adjustment create(@io.swagger.v3.oas.annotations.Parameter(description = "目标租户标识") @PathVariable UUID tenantId,@Valid @RequestBody CreateCommercialAdjustmentRequest request) {
        return operations.create(tenantId,request.dimensionCode(),request.amount(),request.startsAt(),request.endsAt(),
                request.reason(),request.idempotencyKey(),request.expectedAssignmentVersion());
    }
    /**
     * 恢复原幂等键调整事实。
     * 包括已到期及已撤销，不重新授予
     *
     * @param tenantId 目标租户标识
     * @param key 本次操作的幂等键，用于识别重复提交
     * @return 当前接口的操作结果，响应结构见 {@code CommercialOperationsService.Adjustment}
     */
    @GetMapping("/by-key/{key}") @Operation(summary="恢复原幂等键调整事实",description="包括已到期及已撤销，不重新授予")
    public CommercialOperationsService.Adjustment byKey(@io.swagger.v3.oas.annotations.Parameter(description = "目标租户标识") @PathVariable UUID tenantId,@io.swagger.v3.oas.annotations.Parameter(description = "本次操作的幂等键，用于识别重复提交") @PathVariable String key) { return operations.byKey(tenantId,key); }
    /**
     * 撤销人工调整。
     * 仅人工ACTIVE/PENDING调整；重复撤销返回false，不重复审计
     *
     * @param tenantId 目标租户标识
     * @param adjustmentId 商业调整记录标识
     * @param request 本次操作的请求数据，结构见 {@code RevokeRequest}
     * @return 当前接口的操作结果，响应结构见 {@code RevokeResult}
     */
    @PostMapping("/{adjustmentId}/revoke") @Operation(summary="撤销人工调整",description="仅人工ACTIVE/PENDING调整；重复撤销返回false，不重复审计")
    public RevokeResult revoke(@io.swagger.v3.oas.annotations.Parameter(description = "目标租户标识") @PathVariable UUID tenantId,@io.swagger.v3.oas.annotations.Parameter(description = "商业调整记录标识") @PathVariable UUID adjustmentId,@Valid @RequestBody RevokeRequest request) {
        return new RevokeResult(operations.revoke(tenantId,adjustmentId,request.reason()));
    }
    public record RevokeRequest(@NotBlank @Size(max=512) String reason) { }
    public record RevokeResult(boolean revoked) { }
}
