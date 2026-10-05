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
@io.swagger.v3.oas.annotations.tags.Tag(name = "OTA 固件生命周期", description = "冻结、撤销及生命周期事实")
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
    /**
     * 当前管理成员读取不可缓存的安全生命周期记录。
     *
     * @param projectId 接口指定的项目标识
     * @param firmwareId 固件标识
     * @return 当前接口的操作结果，响应结构见 {@code ResponseEntity<OtaFirmwareLifecycleResponse>}
     */
    @GetMapping("/lifecycle")
    @Operation(operationId = "getOtaFirmwareLifecycle", summary = "读取OTA固件生命周期记录", description = "当前管理成员读取不可缓存的安全生命周期记录。")
    public ResponseEntity<OtaFirmwareLifecycleResponse> read(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId, @io.swagger.v3.oas.annotations.Parameter(description = "固件标识") @PathVariable UUID firmwareId) {
        return response(OtaFirmwareLifecycleResponse.from(service.read(projectId, firmwareId)));
    }
    /**
     * READY单向退役，重复成功键返回公共完成墓碑10014。
     *
     * @param projectId 接口指定的项目标识
     * @param firmwareId 固件标识
     * @param key 本次操作的幂等键，用于识别重复提交
     * @param body 原始请求体字节，由当前接口按请求契约解析
     * @return 当前接口的操作结果，响应结构见 {@code ResponseEntity<OtaFirmwareLifecycleResponse>}
     */
    @PostMapping(value = "/deprecations", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "deprecateOtaFirmware", summary = "退役OTA固件", description = "READY单向退役，重复成功键返回公共完成墓碑10014。")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            content = @Content(schema = @Schema(implementation = OtaFirmwareLifecycleRequestParser.Change.class)))
    public ResponseEntity<OtaFirmwareLifecycleResponse> deprecate(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId, @io.swagger.v3.oas.annotations.Parameter(description = "固件标识") @PathVariable UUID firmwareId,
            @Parameter(required = true, description = "本次操作的幂等键，用于识别重复提交") @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody byte[] body) {
        manage(projectId);
        var request = parser.parse(body);
        return response(OtaFirmwareLifecycleResponse.from(service.deprecate(projectId, firmwareId, key,
                request.expectedRevision(), request.reason())));
    }
    /**
     * READY/DEPRECATED单向撤销，不接受已终态再次修改事实。
     *
     * @param projectId 接口指定的项目标识
     * @param firmwareId 固件标识
     * @param key 本次操作的幂等键，用于识别重复提交
     * @param body 原始请求体字节，由当前接口按请求契约解析
     * @return 当前接口的操作结果，响应结构见 {@code ResponseEntity<OtaFirmwareLifecycleResponse>}
     */
    @PostMapping(value = "/revocations", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "revokeOtaFirmware", summary = "撤销OTA固件", description = "READY/DEPRECATED单向撤销，不接受已终态再次修改事实。")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            content = @Content(schema = @Schema(implementation = OtaFirmwareLifecycleRequestParser.Change.class)))
    public ResponseEntity<OtaFirmwareLifecycleResponse> revoke(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId, @io.swagger.v3.oas.annotations.Parameter(description = "固件标识") @PathVariable UUID firmwareId,
            @Parameter(required = true, description = "本次操作的幂等键，用于识别重复提交") @RequestHeader(value = "Idempotency-Key", required = false) String key,
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
