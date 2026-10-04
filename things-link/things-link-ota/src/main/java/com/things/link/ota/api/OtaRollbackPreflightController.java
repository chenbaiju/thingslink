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
@RestController
@RequestMapping("/api/v1/projects/{projectId}/ota/campaigns/{campaignId}/jobs/{jobId}/rollback-preflight")
public class OtaRollbackPreflightController {
    /** 同事务授权与动态重验。 */ private final OtaRollbackPreflightReadService service;
    /** 注入真实读取边界。 */
    public OtaRollbackPreflightController(OtaRollbackPreflightReadService service){this.service=service;}
    /** 成功读取禁缓存，始终明确不具执行权。 */
    @GetMapping
    @Operation(operationId="getOtaRollbackPreflight",summary="读取OTA回退准备观察与当前资格")
    public ResponseEntity<OtaRollbackPreflightResponse> find(@PathVariable UUID projectId,
            @PathVariable UUID campaignId,@PathVariable UUID jobId){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(OtaRollbackPreflightResponse.from(service.find(projectId,campaignId,jobId)));
    }
}
