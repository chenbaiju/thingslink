package com.things.link.project.api.controller;

import com.things.link.project.api.dto.response.PlanResponse;
import com.things.link.project.application.plan.PlanCatalogService;
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

import java.util.List;

/**
 * 平台套餐目录接口（S14-1a 列表，S14-1c 单档与参考价）。
 *
 * <p><b>授权选择：</b>目录是平台全局只读目录，没有租户列，也不属于任何项目；本仓库当前
 * 没有平台管理员角色或平台守卫（{@code /api/v1/admin} 之类的入口尚未交付），因此这里使用
 * 现有最接近的守卫 —— IAM 主过滤链的兜底 {@code anyRequest().authenticated()}，
 * 即「任意已登录账号可读」，不额外要求租户或已选项目，也不放行匿名访问。
 * 这与架构文档 §6「租户成员只能读本租户套餐摘要」不冲突：本接口只返回公开报价，
 * 不含订单、成交价、租户 ID 或支付渠道。
 *
 * <p>响应不使用 {@code null} 表达「不限」：数值维度是原始类型，未定价档位不返回成交价字段。
 * 参考价（{@code referencePriceCents}）是商业架构 §2 的快照，与成交价分离，不改变销售状态。
 */
@RestController
@RequestMapping("/api/v1/plans")
@Tag(name = "套餐目录", description = "平台四档套餐的公开报价、参考价、冻结额度与功能权益")
public class PlanCatalogController {

    /** 目录只读用例。 */
    private final PlanCatalogService planCatalogService;

    /**
     * @param planCatalogService 目录只读用例
     */
    public PlanCatalogController(PlanCatalogService planCatalogService) {
        this.planCatalogService = planCatalogService;
    }

    /**
     * 列出当前生效的平台套餐目录。
     *
     * @return 按定价页展示顺序排列的四档快照
     */
    @GetMapping
    @Operation(summary = "平台套餐目录",
            description = "返回四档套餐当前生效修订版的公开报价、参考价、冻结数值维度与功能权益。"
                    + "未定价档位没有成交价字段；参考价只作展示，不改变销售状态；"
                    + "未交付能力以 enabled=false 与额度 0 区分。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "成功"),
            @ApiResponse(responseCode = "401", description = "未认证",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<List<PlanResponse>> list() {
        return ResponseEntity.ok(planCatalogService.catalog().stream()
                .map(PlanResponse::from)
                .toList());
    }

    /**
     * 读取单个套餐当前生效的修订版快照。
     *
     * @param planCode 稳定套餐编码，如 {@code STANDARD}
     * @return 该套餐的当前修订版、冻结数值维度与功能权益
     */
    @GetMapping("/{planCode}")
    @Operation(summary = "单个套餐详情",
            description = "返回该套餐当前生效修订版的报价、参考价、冻结数值维度与功能权益。"
                    + "DISABLED 权益保留且不携带数值额度；未知编码返回 404，不用 200 空对象代替。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "成功"),
            @ApiResponse(responseCode = "401", description = "未认证",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "套餐编码不存在",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<PlanResponse> detail(@PathVariable String planCode) {
        return ResponseEntity.ok(PlanResponse.from(planCatalogService.plan(planCode)));
    }
}
