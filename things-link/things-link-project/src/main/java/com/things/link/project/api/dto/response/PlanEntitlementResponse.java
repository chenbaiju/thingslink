package com.things.link.project.api.dto.response;

import com.things.link.project.domain.plan.PlanEntitlement;
import io.swagger.v3.oas.annotations.media.Schema;

/** ADR0163：不可变目录的能力声明，不是当前账号运行授权或部署资格。 */
@Schema(description = "目录能力声明；enabled仅表示目录包含，不表示运行授权，语义由enforcement明确")
public record PlanEntitlementResponse(
        @Schema(description = "冻结 capability code", example = "OTA") String code,
        @Schema(description = "该修订版目录是否声明包含；不是运行时权限", example = "false") boolean enabled,
        @Schema(description = "当前仅作目录声明，不承担运行门禁；旧响应缺失或未知值不得猜测",
                allowableValues = {"CATALOG_ONLY"}) String enforcement) {

    /** 保留修订版原声明，明确当前过渡语义；不读取或更改业务权限。 */
    public static PlanEntitlementResponse from(PlanEntitlement entitlement) {
        return new PlanEntitlementResponse(entitlement.code(), entitlement.enabled(), "CATALOG_ONLY");
    }
}
