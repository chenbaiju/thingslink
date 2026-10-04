package com.things.link.ota.api;

import com.things.link.ota.api.support.OtaAuthorization;
import com.things.link.ota.application.OtaTrustService;
import com.things.link.ota.domain.OtaTrustErrorCode;
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

/** 根签名信任包管理；不接收根配置或私钥，不形成固件READY事实。 */
@RestController
@Validated
@RequestMapping("/api/v1/projects/{projectId}/ota/trust-domains/{trustDomain}")
public class OtaTrustController {
    /** 同事务状态演进及重新授权。 */ private final OtaTrustService service;
    /** OTA权限守卫。 */ private final OtaAuthorization authorization;
    /** 严格封闭信封。 */ private final OtaTrustRequestParser parser;
    /** 显式构造依赖。 */
    public OtaTrustController(OtaTrustService service, OtaAuthorization authorization, OtaTrustRequestParser parser) {
        this.service = service; this.authorization = authorization; this.parser = parser;
    }
    /** 当前成员可读登记摘要，不返回内部签名正文。 */
    @GetMapping
    @Operation(operationId = "getOtaTrustDomain", summary = "读取OTA信任域登记摘要")
    public OtaTrustResponse find(@PathVariable UUID projectId, @PathVariable String trustDomain) {
        authorization.requireRead(projectId);
        return OtaTrustResponse.from(service.find(projectId, trustDomain));
    }

    /**
     * 成员读取当前包的逐键<b>公开元数据</b>，按{@code keyVersion}升序。
     *
     * <p>与上方单域读取共用{@code ota:read}单一授权源：域不存在、跨项目或RLS不可见统一是
     * 404/70013；空包返回200空页。响应是{@link OtaTrustKeyResponse}白名单，只有
     * keyVersion/state/fingerprint/profile/有效期，<b>不含</b>SPKI、规范字节、签名或任何
     * 可改变密钥状态的字段；本片刻意不提供rotate/retire/import状态变更端点。
     */
    @GetMapping("/keys")
    @Operation(operationId = "listOtaTrustKeys", summary = "OTA信任域发布键公开元数据",
            description = "仅当前项目成员；未知或跨项目信任域为404/70013；不含SPKI、私钥材料或状态变更")
    public ResponseEntity<CursorPage<OtaTrustKeyResponse>> keys(@PathVariable UUID projectId,
            @PathVariable String trustDomain,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        authorization.requireRead(projectId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.keys(projectId, trustDomain, cursor, limit).map(OtaTrustKeyResponse::from));
    }

    /** 仅管理角色导入，公共幂等完成墓碑保持，不重复执行成功写。 */
    @PostMapping(value = "/bundles", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "importOtaTrustBundle", summary = "导入离线根签名OTA信任包")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            content = @Content(schema = @Schema(implementation = OtaTrustRequestParser.ImportBody.class)))
    public OtaTrustResponse importBundle(@PathVariable UUID projectId, @PathVariable String trustDomain,
            @Parameter(required = true) @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody byte[] body) {
        if (!authorization.mayDeploy(projectId)) {
            throw new BusinessException(OtaTrustErrorCode.FORBIDDEN);
        }
        var request = parser.parse(body);
        return OtaTrustResponse.from(service.importBundle(projectId, trustDomain, key,
                request.expectedRevision(), request.bundle(), request.signature()));
    }
}
