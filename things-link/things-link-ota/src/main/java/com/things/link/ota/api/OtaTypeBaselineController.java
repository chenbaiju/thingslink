package com.things.link.ota.api;

import com.things.link.ota.api.support.OtaAuthorization;
import com.things.link.ota.application.OtaTypeBaselineService;
import com.things.link.ota.domain.OtaTypeBaselineErrorCode;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.page.CursorPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 类型基线管理接口只登记受控来源，不开放设备自报或活动派发。 */
@RestController
@Validated
@RequestMapping("/api/v1/projects/{projectId}/ota/device-types/{deviceTypeId}/baseline")
public class OtaTypeBaselineController {
    /** 同事务登记与当前元数据校验。 */
    private final OtaTypeBaselineService service;
    /** OTA部署权限守卫，应用层仍复验锁后权限。 */
    private final OtaAuthorization authorization;
    /** 只允许修订的严格信封。 */
    private final OtaTypeBaselineRequestParser parser;

    /** 显式构造应用边界，不直接访问设备或基线仓储。 */
    public OtaTypeBaselineController(OtaTypeBaselineService service, OtaAuthorization authorization,
            OtaTypeBaselineRequestParser parser) {
        this.service = service;
        this.authorization = authorization;
        this.parser = parser;
    }

    /** 管理元数据不缓存，不能作为当前设备资格凭证。 */
    @GetMapping
    @Operation(operationId = "getOtaTypeBaseline", summary = "读取OTA类型基线登记")
    public ResponseEntity<OtaTypeBaselineResponse> read(@PathVariable UUID projectId, @PathVariable UUID deviceTypeId) {
        return response(service.read(projectId, deviceTypeId));
    }

    /**
     * 成员读取类型基线的不可变版本历史，最新在前；空历史返回空页，父类型不可见仍404。
     *
     * <p>与上方管理读取共用路径族但字段面严格更窄：{@code ota:read}只要求项目成员，
     * 响应不含只由启动配置接受的受控能力配置（见{@link OtaTypeBaselineVersionResponse}）。
     */
    @GetMapping("/versions")
    @Operation(operationId = "listOtaTypeBaselineVersions", summary = "OTA类型基线版本历史",
            description = "仅当前项目成员；父类型不存在或跨项目为404/70031；不暴露受控能力配置")
    public ResponseEntity<CursorPage<OtaTypeBaselineVersionResponse>> versions(@PathVariable UUID projectId,
            @PathVariable UUID deviceTypeId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        authorization.requireRead(projectId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.history(projectId, deviceTypeId, cursor, limit)
                        .map(OtaTypeBaselineVersionResponse::from));
    }

    /** 当前管理者按精确类型登记部署配置，成功重复键保持公共完成墓碑10014。 */
    @PostMapping(value = "/registrations", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "registerOtaTypeBaseline", summary = "登记受控OTA类型基线")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            content = @Content(schema = @Schema(implementation = OtaTypeBaselineRequestParser.Registration.class)))
    public ResponseEntity<OtaTypeBaselineResponse> register(@PathVariable UUID projectId,
            @PathVariable UUID deviceTypeId,
            @Parameter(required = true) @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody byte[] body) {
        if (!authorization.mayDeploy(projectId))
            throw new BusinessException(OtaTypeBaselineErrorCode.FORBIDDEN);
        return response(service.register(projectId, deviceTypeId, key, parser.parse(body).expectedRevision()));
    }

    /** 统一typed投影和禁止缓存的管理响应。 */
    private static ResponseEntity<OtaTypeBaselineResponse> response(OtaTypeBaselineService.Snapshot snapshot) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(OtaTypeBaselineResponse.from(snapshot));
    }
}
