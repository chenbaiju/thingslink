package com.things.link.ota.api;

import com.things.link.ota.api.support.OtaAuthorization;
import com.things.link.ota.application.OtaPublicationService;
import com.things.link.ota.domain.OtaPublicationErrorCode;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.page.CursorPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
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

/** 创建持久发布尝试，不提供本地成功signer或公开对象能力。 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "OTA 固件发布", description = "受控发布尝试与审计结果")
@RestController
@Validated
@RequestMapping("/api/v1/projects/{projectId}/ota/firmwares/{firmwareId}/publications")
public class OtaPublicationController {
    /** 短事务资格与持久尝试。 */ private final OtaPublicationService service;
    /** OTA权限守卫。 */ private final OtaAuthorization authorization;
    /** 完整封闭信封解析。 */ private final OtaPublicationRequestParser parser;
    /** 显式依赖，不把网络签名放在控制器数据库事务内。 */
    public OtaPublicationController(OtaPublicationService service, OtaAuthorization authorization, OtaPublicationRequestParser parser) {
        this.service = service; this.authorization = authorization; this.parser = parser;
    }
    /**
     * 202仅说明接受尝试；无真实受控适配器时领域返回503且不新建成功状态。
     *
     * @param projectId 接口指定的项目标识
     * @param firmwareId 固件标识
     * @param key 本次操作的幂等键，用于识别重复提交
     * @param body 原始请求体字节，由当前接口按请求契约解析
     * @return 当前接口的操作结果，响应结构见 {@code ResponseEntity<OtaPublicationResponse>}
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "createOtaPublication", summary = "建立OTA固件发布尝试", description = "202仅说明接受尝试；无真实受控适配器时领域返回503且不新建成功状态。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "已建立持久尝试，需GET查询结果",
            content = @Content(schema = @Schema(implementation = OtaPublicationResponse.class)))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            content = @Content(schema = @Schema(implementation = OtaPublicationRequestParser.CreateBody.class)))
    public ResponseEntity<OtaPublicationResponse> create(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId, @io.swagger.v3.oas.annotations.Parameter(description = "固件标识") @PathVariable UUID firmwareId,
            @Parameter(required = true, description = "本次操作的幂等键，用于识别重复提交") @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody byte[] body) {
        if (!authorization.mayDeploy(projectId)) throw new BusinessException(OtaPublicationErrorCode.FORBIDDEN);
        var request = parser.parse(body);
        var result = OtaPublicationResponse.from(service.create(projectId, firmwareId, key, request.expectedRevision(),
                request.uploadSessionId(), request.manifest()));
        return ResponseEntity.accepted().location(URI.create("/api/v1/projects/" + projectId + "/ota/firmwares/"
                + firmwareId + "/publications/" + result.id())).body(result);
    }
    /**
     * 精确父资源读取，不输出内部签名正文或凭据。
     *
     * @param projectId 接口指定的项目标识
     * @param firmwareId 固件标识
     * @param publicationId 固件发布尝试标识
     * @return 当前接口的操作结果，响应结构见 {@code OtaPublicationResponse}
     */
    @GetMapping("/{publicationId}")
    @Operation(operationId = "getOtaPublication", summary = "读取OTA固件发布尝试", description = "精确父资源读取，不输出内部签名正文或凭据。")
    public OtaPublicationResponse find(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId, @io.swagger.v3.oas.annotations.Parameter(description = "固件标识") @PathVariable UUID firmwareId,
                                       @io.swagger.v3.oas.annotations.Parameter(description = "固件发布尝试标识") @PathVariable UUID publicationId) {
        authorization.requireRead(projectId);
        return OtaPublicationResponse.from(service.find(projectId, firmwareId, publicationId));
    }
    /**
     * 成员读取固件内发布尝试历史，最新在前；空历史返回空页，父固件不存在仍404。
     *
     * @param projectId 接口指定的项目标识
     * @param firmwareId 固件标识
     * @param cursor 可选分页游标，继续读取上一页后的记录
     * @param limit 分页条数，具体边界由当前接口校验
     * @return 符合条件的记录页及后续分页游标
     */
    @GetMapping
    @Operation(operationId = "listOtaPublications", summary = "固件发布尝试历史", description = "成员读取固件内发布尝试历史，最新在前；空历史返回空页，父固件不存在仍404。")
    public ResponseEntity<CursorPage<OtaPublicationResponse>> list(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,
            @io.swagger.v3.oas.annotations.Parameter(description = "固件标识") @PathVariable UUID firmwareId,
            @io.swagger.v3.oas.annotations.Parameter(description = "可选分页游标，继续读取上一页后的记录") @RequestParam(required = false) String cursor,
            @io.swagger.v3.oas.annotations.Parameter(description = "分页条数，具体边界由当前接口校验") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        authorization.requireRead(projectId);
        return ResponseEntity.ok(service.history(projectId, firmwareId, cursor, limit)
                .map(OtaPublicationResponse::from));
    }
}
