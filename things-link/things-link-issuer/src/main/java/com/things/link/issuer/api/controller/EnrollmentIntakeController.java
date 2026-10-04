package com.things.link.issuer.api.controller;

import com.things.link.entitlement.application.EnrollmentRequestV1;
import com.things.link.issuer.application.EnrollmentConflictException;
import com.things.link.issuer.application.EnrollmentIntakeService;
import com.things.link.issuer.application.EnrollmentQueue;
import com.things.link.issuer.application.EnrollmentQueueService;
import com.things.link.issuer.application.EnrollmentRegistry;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 发行方运营端的申请接收入口；此 Controller 不进入客户运行时依赖树。 */
@RestController
@RequestMapping("/api/v1/operations/self-hosted/enrollment-requests")
@Tag(name = "自部署申请", description = "运营人员接收待审申请；不审核租户或签发授权")
public class EnrollmentIntakeController {
    private final EnrollmentIntakeService intake;
    private final EnrollmentQueueService queue;

    public EnrollmentIntakeController(EnrollmentIntakeService intake, EnrollmentQueueService queue) {
        this.intake = intake;
        this.queue = queue;
    }

    @PostMapping(consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @Operation(summary = "登记自部署待审申请", description = "仅受控运营人员提交 V1 原始封套；来源头由操作者声明，不能代替传输证明")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "待审登记或同封套重复提交"),
            @ApiResponse(responseCode = "400", description = "来源头或签名封套无效"),
            @ApiResponse(responseCode = "401", description = "未登录"),
            @ApiResponse(responseCode = "403", description = "缺少受控运营身份"),
            @ApiResponse(responseCode = "409", description = "申请或部署身份冲突"),
            @ApiResponse(responseCode = "413", description = "封套超过 V1 长度上限")
    })
    public PendingRegistration receive(@RequestHeader("X-Enrollment-Source") String source,
                                       HttpServletRequest request) throws IOException {
        EnrollmentRegistry.Channel channel;
        try {
            channel = EnrollmentRegistry.Channel.valueOf(source);
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "申请来源无效");
        }
        byte[] envelope = request.getInputStream().readNBytes(EnrollmentRequestV1.MAX_ENVELOPE_BYTES + 1);
        if (envelope.length > EnrollmentRequestV1.MAX_ENVELOPE_BYTES) {
            throw new BusinessException(CommonErrorCode.PAYLOAD_TOO_LARGE, "申请封套过长");
        }
        try {
            var result = intake.receive(envelope, channel);
            return new PendingRegistration(result.requestId(), result.deploymentId(), result.tenantId(),
                    result.channel(), result.status(), result.receivedAt());
        } catch (EnrollmentConflictException conflict) {
            throw new BusinessException(CommonErrorCode.RESOURCE_STATE_CONFLICT, "申请身份冲突");
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException(CommonErrorCode.MALFORMED_REQUEST, "申请封套无效");
        }
    }

    /** 只返回最小待审事实；公钥及摘要不通过本入口回显。 */
    public record PendingRegistration(UUID requestId, UUID deploymentId, UUID tenantId,
                                      EnrollmentRegistry.Channel firstChannel, String status, Instant receivedAt) { }

    @GetMapping
    @Operation(summary = "查询自部署待审队列", description = "仅受控运营人员可查看；稳定倒序键集分页，不包含公钥或摘要")
    public List<EnrollmentQueue.Pending> pending(
            @RequestParam(defaultValue = "25") String limit,
            @RequestParam(required = false) String beforeReceivedAt,
            @RequestParam(required = false) String beforeRequestId) {
        int pageSize;
        Instant before = null;
        UUID beforeId = null;
        try {
            pageSize = Integer.parseInt(limit);
            if (pageSize < 1 || pageSize > 50 || (beforeReceivedAt == null) != (beforeRequestId == null)) {
                throw new IllegalArgumentException();
            }
            if (beforeReceivedAt != null) {
                before = Instant.parse(beforeReceivedAt);
                beforeId = UUID.fromString(beforeRequestId);
            }
        } catch (IllegalArgumentException | DateTimeException invalid) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "待审队列分页参数无效");
        }
        return queue.page(pageSize, before, beforeId);
    }
}
