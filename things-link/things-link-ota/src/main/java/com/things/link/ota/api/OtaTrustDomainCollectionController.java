package com.things.link.ota.api;

import com.things.link.ota.api.support.OtaAuthorization;
import com.things.link.ota.application.OtaTrustService;
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
 * 项目信任域只读集合，与单域管理读取相邻但<b>不</b>提供任何密钥状态变更入口。
 *
 * <p>冻结的访问合同（S13-4e-8 / D-152③）：
 * <ul>
 *   <li><b>可读</b>：本项目信任域摘要（域名、修订、包版本/摘要、根Profile/指纹、当前ACTIVE发布键
 *       版本与指纹、时间戳）。逐键公开元数据在相邻的
 *       {@code GET .../trust-domains/{trustDomain}/keys}；</li>
 *   <li><b>永不暴露</b>：私钥材料、secret/token、规范包字节、签名、{@code policyHash}、
 *       {@code tenantId}/{@code projectId}，以及任何能改变密钥状态的字段或端点；</li>
 *   <li><b>授权</b>：与相邻OTA读端点共用同一{@link OtaAuthorization#requireRead(UUID)}单一来源，
 *       项目范围由RLS与{@code project_id}双重限定；无域的项目返回200空页而不是404。</li>
 * </ul>
 */
@RestController
@Validated
@RequestMapping("/api/v1/projects/{projectId}/ota/trust-domains")
public class OtaTrustDomainCollectionController {
    /** 只读域摘要与当前包公开投影。 */ private final OtaTrustService service;
    /** OTA读取权限守卫。 */ private final OtaAuthorization authorization;

    /** 显式构造依赖。 */
    public OtaTrustDomainCollectionController(OtaTrustService service, OtaAuthorization authorization) {
        this.service = service; this.authorization = authorization;
    }

    /** 成员读取本项目全部信任域，按域名升序；空集合返回空页。 */
    @GetMapping
    @Operation(operationId = "listOtaTrustDomains", summary = "OTA信任域游标分页",
            description = "仅当前项目成员；未知项目按不可见404；不含规范包、签名或私钥材料，也不提供状态变更")
    public ResponseEntity<CursorPage<OtaTrustDomainSummaryResponse>> list(@PathVariable UUID projectId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        authorization.requireRead(projectId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.domains(projectId, cursor, limit)
                        .map(value -> OtaTrustDomainSummaryResponse.from(value.state(), value.activeKey())));
    }
}
