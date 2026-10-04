package com.things.link.ota.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.things.link.ota.application.OtaDeviceEligibilityEvaluator.Reason;
import com.things.link.ota.application.OtaDeviceEligibilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 仅管理端当前资格快照，不是设备HTTP接入或可复用的下载令牌。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/ota/devices/{deviceId}/eligibility")
public class OtaDeviceEligibilityController {
    /** 真实管理权限与当前事实事务。 */ private final OtaDeviceEligibilityService service;

    /** 控制器不接受客户端覆盖报告或能力字段。 */
    public OtaDeviceEligibilityController(OtaDeviceEligibilityService service) { this.service = service; }

    /** 查询指定固件当前交集，不返回对象地址，成功响应禁止缓存。 */
    @GetMapping
    @Operation(operationId = "getOtaDeviceEligibility", summary = "查询DIRECT设备当前OTA资格",
            description = "仅OWNER/ADMIN；本次事务快照不代替未来下载或活动授权")
    public ResponseEntity<Response> check(@PathVariable UUID projectId, @PathVariable UUID deviceId,
            @RequestParam UUID firmwareId) {
        var result = service.check(projectId, deviceId, firmwareId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Response(result.eligible(),
                result.reason(), result.reportRevision() == null ? null : result.reportRevision().toString(),
                result.checkedAt()));
    }

    /** 四字段白名单响应，无报告时修订明确为null。
     * @param eligible 当前交集是否满足
     * @param reason 闭集资格原因
     * @param reportRevision 当前报告修订字符串或null
     * @param checkedAt 本次检查时刻
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(name = "OtaDeviceEligibilityResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Response(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean eligible,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Reason reason,
            @Schema(types = {"string", "null"}, pattern = "[1-9][0-9]*",
                    requiredMode = Schema.RequiredMode.REQUIRED) String reportRevision,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant checkedAt) { }
}
