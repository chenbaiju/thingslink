package com.things.link.issuer.api.controller;

import com.things.link.issuer.application.EnrollmentReviewConflictException;
import com.things.link.issuer.application.EnrollmentReviewNotFoundException;
import com.things.link.issuer.application.EnrollmentReviewService;
import com.things.link.issuer.application.EnrollmentReviewService.ReviewDetail;
import com.things.link.issuer.application.EnrollmentReviewService.ReviewInput;
import com.things.link.issuer.application.EnrollmentReviewService.ReviewProgress;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 发行方专用审核事实入口；审核就绪也不会在此签发授权。 */
@RestController
@RequestMapping("/api/v1/operations/self-hosted/enrollment-requests/{requestId}/review")
@Tag(name = "自部署申请审核", description = "独立审核者核验待审材料并留下只追加事实")
public class EnrollmentReviewController {
    private final EnrollmentReviewService reviews;

    public EnrollmentReviewController(EnrollmentReviewService reviews) {
        this.reviews = reviews;
    }

    /**
     * 读取待审申请的核验摘要。
     * 仅审核者可读取申请摘要和核验进度；不返回原始公钥或组织材料
     *
     * @param requestId 自部署登记申请标识
     * @return 当前接口的操作结果，响应结构见 {@code ReviewDetail}
     */
    @GetMapping
    @Operation(summary = "读取待审申请的核验摘要", description = "仅审核者可读取申请摘要和核验进度；不返回原始公钥或组织材料")
    public ReviewDetail detail(@io.swagger.v3.oas.annotations.Parameter(description = "自部署登记申请标识") @PathVariable UUID requestId) {
        try {
            return reviews.detail(requestId);
        } catch (EnrollmentReviewNotFoundException absent) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        } catch (EnrollmentReviewConflictException conflict) {
            throw new BusinessException(CommonErrorCode.RESOURCE_STATE_CONFLICT, "申请审核事实冲突");
        }
    }

    /**
     * 追加申请核验事实。
     * 审核者须独立核对部署、租户、双摘要、组织材料及档位；此操作不签发授权
     *
     * @param requestId 自部署登记申请标识
     * @param input 待追加的申请核验事实
     * @return 当前接口的操作结果，响应结构见 {@code ReviewProgress}
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "追加申请核验事实", description = "审核者须独立核对部署、租户、双摘要、组织材料及档位；此操作不签发授权")
    public ReviewProgress attest(@io.swagger.v3.oas.annotations.Parameter(description = "自部署登记申请标识") @PathVariable UUID requestId, @RequestBody ReviewInput input) {
        try {
            return reviews.attest(requestId, input);
        } catch (EnrollmentReviewNotFoundException absent) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        } catch (EnrollmentReviewConflictException conflict) {
            throw new BusinessException(CommonErrorCode.RESOURCE_STATE_CONFLICT, "申请审核事实冲突");
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "审核输入无效");
        }
    }
}
