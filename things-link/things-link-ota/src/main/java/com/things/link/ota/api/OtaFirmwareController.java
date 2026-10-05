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
    /**
     * 创建或按领域映射恢复同一未取消草稿。
     *
     * @param projectId 接口指定的项目标识
     * @param key 本次操作的幂等键，用于识别重复提交
     * @param body 原始请求体字节，由当前接口按请求契约解析
     * @return 当前接口的操作结果，响应结构见 {@code ResponseEntity<OtaFirmwareResponse>}
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "createOtaFirmware", summary = "创建OTA固件草稿", description = "创建或按领域映射恢复同一未取消草稿。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201",
            description = "创建或恢复原始草稿成功", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = OtaFirmwareResponse.class)))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = OtaFirmwareRequestParser.CreateRequest.class)))
    public ResponseEntity<OtaFirmwareResponse> create(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,
            @Parameter(required = true, description = "本次操作的幂等键，用于识别重复提交") @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody byte[] body) {
        requireManage(projectId);
        OtaFirmwareRequestParser.CreateRequest request = parser.create(body);
        OtaFirmwareResponse result = OtaFirmwareResponse.from(service.createIdempotent(projectId, key,
                request.deviceTypeId(), request.thingModelVersionId(), request.firmwareVersion()));
        return ResponseEntity.created(URI.create("/api/v1/projects/" + projectId + "/ota/firmwares/" + result.id()))
                .body(result);
    }
    /**
     * 成员读取草稿及取消终态，默认每页20项。
     *
     * @param projectId 接口指定的项目标识
     * @param cursor 可选分页游标，继续读取上一页后的记录
     * @param limit 分页条数，具体边界由当前接口校验
     * @return 符合条件的记录页及后续分页游标
     */
    @GetMapping
    @Operation(operationId = "listOtaFirmwares", summary = "OTA固件游标分页", description = "成员读取草稿及取消终态，默认每页20项。")
    public ResponseEntity<CursorPage<OtaFirmwareResponse>> list(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,
            @io.swagger.v3.oas.annotations.Parameter(description = "可选分页游标，继续读取上一页后的记录") @RequestParam(required = false) String cursor,
            @io.swagger.v3.oas.annotations.Parameter(description = "分页条数，具体边界由当前接口校验") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        authorization.requireRead(projectId);
        return ResponseEntity.ok(service.list(projectId, cursor, limit).map(OtaFirmwareResponse::from));
    }
    /**
     * 精确项目读取，不向跨项目请求区分真实存在性。
     *
     * @param projectId 接口指定的项目标识
     * @param firmwareId 固件标识
     * @return 当前接口的操作结果，响应结构见 {@code ResponseEntity<OtaFirmwareResponse>}
     */
    @GetMapping("/{firmwareId}")
    @Operation(operationId = "getOtaFirmware", summary = "OTA固件详情", description = "精确项目读取，不向跨项目请求区分真实存在性。")
    public ResponseEntity<OtaFirmwareResponse> find(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId, @io.swagger.v3.oas.annotations.Parameter(description = "固件标识") @PathVariable UUID firmwareId) {
        authorization.requireRead(projectId);
        return ResponseEntity.ok(OtaFirmwareResponse.from(service.find(projectId, firmwareId)));
    }
    /**
     * 取消采用必填公共写幂等及领域修订终态规则。
     *
     * @param projectId 接口指定的项目标识
     * @param firmwareId 固件标识
     * @param key 本次操作的幂等键，用于识别重复提交
     * @param body 原始请求体字节，由当前接口按请求契约解析
     * @return 当前接口的操作结果，响应结构见 {@code ResponseEntity<OtaFirmwareResponse>}
     */
    @PostMapping(value = "/{firmwareId}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "cancelOtaFirmware", summary = "取消OTA固件草稿", description = "取消采用必填公共写幂等及领域修订终态规则。")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = OtaFirmwareRequestParser.CancelRequest.class)))
    public ResponseEntity<OtaFirmwareResponse> cancel(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId, @io.swagger.v3.oas.annotations.Parameter(description = "固件标识") @PathVariable UUID firmwareId,
            @Parameter(required = true, description = "本次操作的幂等键，用于识别重复提交") @RequestHeader(value = "Idempotency-Key", required = false) String key,
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
