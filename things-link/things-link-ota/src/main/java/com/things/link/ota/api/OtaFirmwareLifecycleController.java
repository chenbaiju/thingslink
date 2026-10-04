package com.things.link.ota.api;

import com.things.link.ota.api.support.OtaAuthorization;
import com.things.link.ota.application.OtaFirmwareLifecycleService;
import com.things.link.ota.domain.OtaFirmwareLifecycleErrorCode;
import com.things.link.shared.error.BusinessException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 固件退役/撤销HTTP管理入口，不提供物理删除或客户端操作者字段。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/ota/firmwares/{firmwareId}")
public class OtaFirmwareLifecycleController {
    /** 同事务状态和审计。 */
    private final OtaFirmwareLifecycleService service;
    /** OTA部署权限守卫。 */
    private final OtaAuthorization authorization;
    /** 严格二字段正文。 */
    private final OtaFirmwareLifecycleRequestParser parser;
    /** 显式注入应用端口。 */
    public OtaFirmwareLifecycleController(OtaFirmwareLifecycleService service, OtaAuthorization authorization,
                                          OtaFirmwareLifecycleRequestParser parser) {
        this.service = service; this.authorization = authorization; this.parser = parser;
    }
    /** 当前管理成员读取不可缓存的安全生命周期记录。 */
    @GetMapping("/lifecycle")
    @Operation(operationId = "getOtaFirmwareLifecycle", summary = "读取OTA固件生命周期记录")
    public ResponseEntity<OtaFirmwareLifecycleResponse> read(@PathVariable UUID projectId, @PathVariable UUID firmwareId) {
        return response(OtaFirmwareLifecycleResponse.from(service.read(projectId, firmwareId)));
    }
    /** READY单向退役，重复成功键返回公共完成墓碑10014。 */
    @PostMapping(value = "/deprecations", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "deprecateOtaFirmware", summary = "退役OTA固件")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            content = @Content(schema = @Schema(implementation = OtaFirmwareLifecycleRequestParser.Change.class)))
    public ResponseEntity<OtaFirmwareLifecycleResponse> deprecate(@PathVariable UUID projectId, @PathVariable UUID firmwareId,
            @Parameter(required = true) @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody byte[] body) {
        manage(projectId);
        var request = parser.parse(body);
        return response(OtaFirmwareLifecycleResponse.from(service.deprecate(projectId, firmwareId, key,
                request.expectedRevision(), request.reason())));
    }
    /** READY/DEPRECATED单向撤销，不接受已终态再次修改事实。 */
    @PostMapping(value = "/revocations", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "revokeOtaFirmware", summary = "撤销OTA固件")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            content = @Content(schema = @Schema(implementation = OtaFirmwareLifecycleRequestParser.Change.class)))
    public ResponseEntity<OtaFirmwareLifecycleResponse> revoke(@PathVariable UUID projectId, @PathVariable UUID firmwareId,
            @Parameter(required = true) @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody byte[] body) {
        manage(projectId);
        var request = parser.parse(body);
        return response(OtaFirmwareLifecycleResponse.from(service.revoke(projectId, firmwareId, key,
                request.expectedRevision(), request.reason())));
    }
    /** 普通成员在正文解析前拒绝，应用服务仍会在项目锁后再次复核。 */
    private void manage(UUID project) {
        if (!authorization.mayDeploy(project))
            throw new BusinessException(OtaFirmwareLifecycleErrorCode.FORBIDDEN);
    }
    /** 成功读取和变更都禁止缓存，不影响公共幂等完成墓碑。 */
    private static ResponseEntity<OtaFirmwareLifecycleResponse> response(OtaFirmwareLifecycleResponse body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
