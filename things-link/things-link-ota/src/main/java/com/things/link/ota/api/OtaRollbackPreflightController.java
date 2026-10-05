package com.things.link.ota.api;

import com.things.link.ota.application.OtaRollbackPreflightReadService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 仅管理读取安全准备观察，不开放设备HTTP或回退动作。 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "OTA 回退准备", description = "读取回退观察与当前资格")
@RestController
@RequestMapping("/api/v1/projects/{projectId}/ota/campaigns/{campaignId}/jobs/{jobId}/rollback-preflight")
public class OtaRollbackPreflightController {
    /** 同事务授权与动态重验。 */ private final OtaRollbackPreflightReadService service;
    /** 注入真实读取边界。 */
    public OtaRollbackPreflightController(OtaRollbackPreflightReadService service){this.service=service;}
    /**
     * 成功读取禁缓存，始终明确不具执行权。
     *
     * @param projectId 接口指定的项目标识
     * @param campaignId 升级活动标识
     * @param jobId 批量任务标识
     * @return 当前接口的操作结果，响应结构见 {@code ResponseEntity<OtaRollbackPreflightResponse>}
     */
    @GetMapping
    @Operation(operationId="getOtaRollbackPreflight",summary="读取OTA回退准备观察与当前资格", description = "成功读取禁缓存，始终明确不具执行权。")
    public ResponseEntity<OtaRollbackPreflightResponse> find(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,
            @io.swagger.v3.oas.annotations.Parameter(description = "升级活动标识") @PathVariable UUID campaignId,@io.swagger.v3.oas.annotations.Parameter(description = "批量任务标识") @PathVariable UUID jobId){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(OtaRollbackPreflightResponse.from(service.find(projectId,campaignId,jobId)));
    }
}
