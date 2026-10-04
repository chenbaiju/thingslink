package com.things.link.ota.api;

import com.things.link.ota.api.support.OtaAuthorization;
import com.things.link.ota.application.OtaFirmwareService;
import com.things.link.ota.domain.OtaFirmwareErrorCode;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.page.CursorPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
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

/** 固件草稿HTTP入口；先做角色守卫，再解析原始请求，领域内仍保留二次授权。 */
@RestController
@Validated
@Tag(name = "OTA固件", description = "未上传固件草稿与取消管理")
@RequestMapping("/api/v1/projects/{projectId}/ota/firmwares")
public class OtaFirmwareController {
    /** 同事务草稿生命周期服务。 */
    private final OtaFirmwareService service;
    /** OTA权限守卫。 */
    private final OtaAuthorization authorization;
    /** 保留重复字段信息的信封解析器。 */
    private final OtaFirmwareRequestParser parser;
    /** 显式注入HTTP边界依赖。 */
    public OtaFirmwareController(OtaFirmwareService service, OtaAuthorization authorization, OtaFirmwareRequestParser parser) {
        this.service = service;
        this.authorization = authorization;
        this.parser = parser;
    }
    /** 创建或按领域映射恢复同一未取消草稿。 */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "createOtaFirmware", summary = "创建OTA固件草稿")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201",
            description = "创建或恢复原始草稿成功", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = OtaFirmwareResponse.class)))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = OtaFirmwareRequestParser.CreateRequest.class)))
    public ResponseEntity<OtaFirmwareResponse> create(@PathVariable UUID projectId,
            @Parameter(required = true) @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody byte[] body) {
        requireManage(projectId);
        OtaFirmwareRequestParser.CreateRequest request = parser.create(body);
        OtaFirmwareResponse result = OtaFirmwareResponse.from(service.createIdempotent(projectId, key,
                request.deviceTypeId(), request.thingModelVersionId(), request.firmwareVersion()));
        return ResponseEntity.created(URI.create("/api/v1/projects/" + projectId + "/ota/firmwares/" + result.id()))
                .body(result);
    }
    /** 成员读取草稿及取消终态，默认每页20项。 */
    @GetMapping
    @Operation(operationId = "listOtaFirmwares", summary = "OTA固件游标分页")
    public ResponseEntity<CursorPage<OtaFirmwareResponse>> list(@PathVariable UUID projectId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        authorization.requireRead(projectId);
        return ResponseEntity.ok(service.list(projectId, cursor, limit).map(OtaFirmwareResponse::from));
    }
    /** 精确项目读取，不向跨项目请求区分真实存在性。 */
    @GetMapping("/{firmwareId}")
    @Operation(operationId = "getOtaFirmware", summary = "OTA固件详情")
    public ResponseEntity<OtaFirmwareResponse> find(@PathVariable UUID projectId, @PathVariable UUID firmwareId) {
        authorization.requireRead(projectId);
        return ResponseEntity.ok(OtaFirmwareResponse.from(service.find(projectId, firmwareId)));
    }
    /** 取消采用必填公共写幂等及领域修订终态规则。 */
    @PostMapping(value = "/{firmwareId}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "cancelOtaFirmware", summary = "取消OTA固件草稿")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = OtaFirmwareRequestParser.CancelRequest.class)))
    public ResponseEntity<OtaFirmwareResponse> cancel(@PathVariable UUID projectId, @PathVariable UUID firmwareId,
            @Parameter(required = true) @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody byte[] body) {
        requireManage(projectId);
        OtaFirmwareRequestParser.CancelRequest request = parser.cancel(body);
        return ResponseEntity.ok(OtaFirmwareResponse.from(service.cancel(projectId, firmwareId, key,
                request.expectedRevision())));
    }
    /** 非成员由项目服务拒绝，普通成员写入返回专用70002。 */
    private void requireManage(UUID projectId) {
        if (!authorization.mayDeploy(projectId)) {
            throw new BusinessException(OtaFirmwareErrorCode.FORBIDDEN);
        }
    }
}
