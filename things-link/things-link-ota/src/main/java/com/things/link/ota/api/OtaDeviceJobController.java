package com.things.link.ota.api;

import com.things.link.ota.api.support.OtaAuthorization;
import com.things.link.ota.application.OtaDeviceJobService;
import com.things.link.shared.page.CursorPage;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备维度OTA作业只读集合，与资格端点相邻但不改变其语义。
 *
 * <p>只读且成员可读：{@code ota:read} 单一授权源是 {@link OtaAuthorization#requireRead(UUID)}；
 * 返回既有作业摘要白名单，不含租户、租约令牌、规范计划或资格报告。
 */
@RestController
@Validated
@RequestMapping("/api/v1/projects/{projectId}/ota/devices/{deviceId}/jobs")
public class OtaDeviceJobController {
    /** 成员读取与父设备存在性。 */ private final OtaDeviceJobService service;
    /** OTA读取权限守卫。 */ private final OtaAuthorization authorization;

    /** 明确边界依赖。 */
    public OtaDeviceJobController(OtaDeviceJobService service, OtaAuthorization authorization) {
        this.service = service; this.authorization = authorization;
    }

    /** 成员读取设备内全部作业，最新在前；空历史返回空页，父设备不存在仍404。 */
    @GetMapping
    @Operation(operationId = "listOtaDeviceJobs", summary = "OTA设备作业游标分页",
            description = "仅当前项目成员；父设备不存在或跨项目为404/30020")
    public ResponseEntity<CursorPage<OtaDeviceJobSummaryResponse>> list(@PathVariable UUID projectId,
            @PathVariable UUID deviceId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        authorization.requireRead(projectId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.history(projectId, deviceId, cursor, limit).map(OtaDeviceJobSummaryResponse::from));
    }
}
