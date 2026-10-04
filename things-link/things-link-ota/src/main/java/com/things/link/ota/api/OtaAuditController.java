package com.things.link.ota.api;

import com.things.link.ota.api.support.OtaAuthorization;
import com.things.link.ota.domain.OtaAuditErrorCode;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.page.CursorPage;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.audit.AuditQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/** OTA审计只读入口：先做角色守卫，再把动作命名空间固定收窄到 {@code ota.}。 */
@RestController
@Validated
@Tag(name = "OTA审计", description = "项目OTA审计时间线")
@RequestMapping("/api/v1/projects/{projectId}/ota/audits")
public class OtaAuditController {

    /**
     * 允许的精确动作编码形态。
     *
     * <p>为什么在前缀之外还要校验一次：前缀固定为 {@code ota.} 后，若 {@code action}
     * 可以是任意字符串，调用方就能用 {@code action=project.member.invited} 把查询
     * 借道到其他模块 —— 前缀与精确条件同时生效反而成了绕过收窄的入口。
     */
    private static final Pattern OTA_ACTION = Pattern.compile("^ota\\.[a-z0-9_.]+$");

    /** 只读审计查询服务。 */
    private final AuditQueryService audits;
    /** OTA部署权限守卫。 */
    private final OtaAuthorization authorization;
    /** jsonb 详情解析器。 */
    private final ObjectMapper objectMapper;

    /**
     * 显式注入只读查询与权限依赖。
     *
     * @param audits       审计只读查询服务
     * @param authorization OTA 权限守卫
     * @param objectMapper jsonb 详情解析器
     */
    public OtaAuditController(AuditQueryService audits, OtaAuthorization authorization, ObjectMapper objectMapper) {
        this.audits = audits;
        this.authorization = authorization;
        this.objectMapper = objectMapper;
    }

    /**
     * 读取本项目 OTA 审计时间线，最新在前。
     *
     * @param projectId 项目标识
     * @param cursor    游标，可为空表示首页
     * @param limit     每页条数，1..100，默认 20
     * @param action    精确动作编码，可为空；必须是 {@code ota.} 命名空间
     * @return 游标分页的审计时间线
     */
    @GetMapping
    @Operation(operationId = "listOtaAudits", summary = "OTA审计时间线")
    public ResponseEntity<CursorPage<OtaAuditResponse>> list(@PathVariable UUID projectId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) String action) {
        // 权限优先于查询参数校验，不向无权调用方泄露动作命名空间的校验细节。
        if (!authorization.mayDeploy(projectId)) {
            throw new BusinessException(OtaAuditErrorCode.FORBIDDEN);
        }
        if (action != null && !OTA_ACTION.matcher(action).matches()) {
            throw new BusinessException(CommonErrorCode.MALFORMED_REQUEST);
        }
        UUID tenantId = TenantContext.require().tenantId();
        // 始终传 "ota."：这是本项目能看到其他模块审计事实的唯一入口，不能由客户端提供前缀。
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(audits.page(tenantId, projectId, "ota.", action, cursor, limit)
                        .map(value -> OtaAuditResponse.from(value, objectMapper)));
    }
}
