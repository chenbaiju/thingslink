package com.things.link.project.api.dto.response;

import com.things.link.project.domain.plan.PlanIdentity;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 套餐的最小身份投影：稳定编码 + 展示名 + 修订版标识与序号。
 *
 * <p>它不含任何价格、额度或权益，只用来回答「是哪一档、哪一版」。租户套餐摘要用它分别
 * 表达「订阅锁定的档位」与「运行时实际绑定的档位」，读取方不得把二者互相冒充。
 *
 * @param code 稳定套餐编码
 * @param name 展示名称
 * @param revision 产品修订版标识
 * @param revisionNo 同一套餐的修订序号
 */
@Schema(description = "套餐最小身份投影；不含价格、额度或权益")
public record PlanIdentityResponse(
        @Schema(description = "稳定套餐编码", example = "FREE") String code,
        @Schema(description = "展示名称", example = "免费版") String name,
        @Schema(description = "产品修订版标识", example = "product-revision-1") String revision,
        @Schema(description = "同一套餐的修订序号", example = "1") int revisionNo) {

    /**
     * 领域身份转换为 HTTP 契约。
     *
     * @param identity 套餐身份
     * @return HTTP 身份投影
     */
    public static PlanIdentityResponse from(PlanIdentity identity) {
        return new PlanIdentityResponse(identity.code(), identity.name(),
                identity.revision(), identity.revisionNo());
    }
}
