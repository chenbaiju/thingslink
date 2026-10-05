package com.things.link.integration.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.things.link.integration.application.ApiKeyManagementService;
import com.things.link.integration.application.ApiKeyView;
import com.things.link.integration.domain.IntegrationErrorCode;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.page.CursorPage;
import com.things.link.shared.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** ADR0170：真实Console管理入口；API Key不是管理身份，响应禁止缓存。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/api-keys")
@Tag(name="公开集成管理",description="Console项目API Key管理；不提供公开设备API")
public class ApiKeyController {
    private final ApiKeyManagementService service;
    private final ProjectService projects;
    private final boolean enabled;
    public ApiKeyController(ApiKeyManagementService service,ProjectService projects,
            @Value("${things-link.integration.api-key.enabled:false}") boolean enabled){this.service=service;this.projects=projects;this.enabled=enabled;}
    /**
     * 签发项目API Key，仅首次返回秘密。
     *
     * @param projectId 接口指定的项目标识
     * @param request 本次操作的请求数据，结构见 {@code CreateKey}
     * @param http 原始 HTTP 请求，供头部、查询参数及身份校验使用
     * @return 当前接口的操作结果，响应结构见 {@code ResponseEntity<IssuedKey>}
     */
    @PostMapping @Operation(operationId="issueProjectApiKey",summary="签发项目API Key，仅首次返回秘密", description = "签发项目API Key，仅首次返回秘密。")
    public ResponseEntity<IssuedKey> issue(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,@Valid @RequestBody CreateKey request,HttpServletRequest http){
        Identity identity=identity(projectId,http);
        var result=service.issue(identity.tenant(),projectId,identity.actor(),request.operationId(),request.spec());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(IssuedKey.from(result));
    }
    /**
     * 原子轮换并立即撤销旧Key。
     *
     * @param projectId 接口指定的项目标识
     * @param keyId API 密钥记录标识，不是密钥明文
     * @param request 本次操作的请求数据，结构见 {@code CreateKey}
     * @param http 原始 HTTP 请求，供头部、查询参数及身份校验使用
     * @return 当前接口的操作结果，响应结构见 {@code ResponseEntity<IssuedKey>}
     */
    @PostMapping("/{keyId}/rotate") @Operation(operationId="rotateProjectApiKey",summary="原子轮换并立即撤销旧Key", description = "原子轮换并立即撤销旧Key。")
    public ResponseEntity<IssuedKey> rotate(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,@io.swagger.v3.oas.annotations.Parameter(description = "API 密钥记录标识，不是密钥明文") @PathVariable UUID keyId,@Valid @RequestBody CreateKey request,HttpServletRequest http){
        Identity identity=identity(projectId,http);
        var result=service.rotate(identity.tenant(),projectId,identity.actor(),request.operationId(),keyId,request.spec());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(IssuedKey.from(result));
    }
    /**
     * 撤销项目API Key。
     *
     * @param projectId 接口指定的项目标识
     * @param keyId API 密钥记录标识，不是密钥明文
     * @param request 本次操作的请求数据，结构见 {@code RevokeKey}
     * @param http 原始 HTTP 请求，供头部、查询参数及身份校验使用
     * @return 当前接口的操作结果，响应结构见 {@code ResponseEntity<ApiKeyView>}
     */
    @PostMapping("/{keyId}/revoke") @Operation(operationId="revokeProjectApiKey",summary="撤销项目API Key", description = "撤销项目API Key。")
    public ResponseEntity<ApiKeyView> revoke(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,@io.swagger.v3.oas.annotations.Parameter(description = "API 密钥记录标识，不是密钥明文") @PathVariable UUID keyId,@Valid @RequestBody RevokeKey request,HttpServletRequest http){
        Identity identity=identity(projectId,http);
        return noStore(service.revoke(identity.tenant(),projectId,identity.actor(),request.operationId(),keyId));
    }
    /**
     * 分页读取Key脱敏元数据。
     *
     * @param projectId 接口指定的项目标识
     * @param cursor 可选分页游标，继续读取上一页后的记录
     * @param limit 分页条数，具体边界由当前接口校验
     * @param http 原始 HTTP 请求，供头部、查询参数及身份校验使用
     * @return 符合条件的记录页及后续分页游标
     */
    @GetMapping @Operation(operationId="listProjectApiKeys",summary="分页读取Key脱敏元数据", description = "分页读取Key脱敏元数据。")
    public ResponseEntity<CursorPage<ApiKeyView>> list(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,@io.swagger.v3.oas.annotations.Parameter(description = "可选分页游标，继续读取上一页后的记录") @RequestParam(required=false) String cursor,
            @io.swagger.v3.oas.annotations.Parameter(description = "分页条数，具体边界由当前接口校验") @RequestParam(defaultValue="20") int limit,HttpServletRequest http){
        Identity identity=identity(projectId,http);
        return noStore(service.list(identity.tenant(),projectId,identity.actor(),cursor,limit));
    }
    /**
     * 读取Key脱敏元数据。
     *
     * @param projectId 接口指定的项目标识
     * @param keyId API 密钥记录标识，不是密钥明文
     * @param http 原始 HTTP 请求，供头部、查询参数及身份校验使用
     * @return 当前接口的操作结果，响应结构见 {@code ResponseEntity<ApiKeyView>}
     */
    @GetMapping("/{keyId}") @Operation(operationId="getProjectApiKey",summary="读取Key脱敏元数据", description = "读取Key脱敏元数据。")
    public ResponseEntity<ApiKeyView> get(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,@io.swagger.v3.oas.annotations.Parameter(description = "API 密钥记录标识，不是密钥明文") @PathVariable UUID keyId,HttpServletRequest http){
        Identity identity=identity(projectId,http);return noStore(service.get(identity.tenant(),projectId,identity.actor(),keyId));
    }
    /**
     * 查询原操作结果，不恢复秘密。
     *
     * @param projectId 接口指定的项目标识
     * @param operationId 管理操作标识，用于定位幂等回执
     * @param http 原始 HTTP 请求，供头部、查询参数及身份校验使用
     * @return 当前接口的操作结果，响应结构见 {@code ResponseEntity<ApiKeyView>}
     */
    @GetMapping("/operations/{operationId}") @Operation(operationId="recoverProjectApiKeyOperation",summary="查询原操作结果，不恢复秘密", description = "查询原操作结果，不恢复秘密。")
    public ResponseEntity<ApiKeyView> recover(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,@io.swagger.v3.oas.annotations.Parameter(description = "管理操作标识，用于定位幂等回执") @PathVariable UUID operationId,HttpServletRequest http){
        Identity identity=identity(projectId,http);return noStore(service.recover(identity.tenant(),projectId,identity.actor(),operationId));
    }
    private Identity identity(UUID project,HttpServletRequest request){
        if(request.getHeader("X-Api-Key")!=null)throw new BusinessException(IntegrationErrorCode.MANAGE_FORBIDDEN);
        var scope=TenantContext.current().orElseThrow(()->new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));
        if(!project.equals(scope.projectId()))throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        if(!projects.requireRoleInProject(project).canManageMembers())throw new BusinessException(IntegrationErrorCode.MANAGE_FORBIDDEN);
        if(!enabled)throw new BusinessException(IntegrationErrorCode.NOT_ENABLED);
        return new Identity(projects.requireProjectTenant(project),scope.accountId());
    }
    private static <T> ResponseEntity<T> noStore(T value){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);}
    private record Identity(UUID tenant,UUID actor){}
    /** 操作身份独立于HTTP幂等键，客户端需在未知结果时保留原身份查询。 */
    public record CreateKey(@NotNull UUID operationId,@NotBlank @Size(max=80) String name,
                            @NotEmpty @Size(max=3) List<@NotBlank String> scopes,
                            @NotEmpty @Size(max=32) List<@NotBlank @Size(max=64) String> ipCidrs,@NotNull Instant expiresAt){
        ApiKeyManagementService.Spec spec(){return new ApiKeyManagementService.Spec(name,scopes,ipCidrs,expiresAt);}
    }
    public record RevokeKey(@NotNull UUID operationId){}
    /** 回放省略secret字段，toString同样不暴露首次秘密。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IssuedKey(ApiKeyView key,String secret,boolean replayed){
        static IssuedKey from(ApiKeyManagementService.Result result){return new IssuedKey(result.key(),result.secret(),result.replayed());}
        @Override public String toString(){return "IssuedKey[key="+key.id()+", secret=REDACTED]";}
    }
}
