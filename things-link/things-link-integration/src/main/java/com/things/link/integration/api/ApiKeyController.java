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
    @PostMapping @Operation(operationId="issueProjectApiKey",summary="签发项目API Key，仅首次返回秘密")
    public ResponseEntity<IssuedKey> issue(@PathVariable UUID projectId,@Valid @RequestBody CreateKey request,HttpServletRequest http){
        Identity identity=identity(projectId,http);
        var result=service.issue(identity.tenant(),projectId,identity.actor(),request.operationId(),request.spec());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(IssuedKey.from(result));
    }
    @PostMapping("/{keyId}/rotate") @Operation(operationId="rotateProjectApiKey",summary="原子轮换并立即撤销旧Key")
    public ResponseEntity<IssuedKey> rotate(@PathVariable UUID projectId,@PathVariable UUID keyId,@Valid @RequestBody CreateKey request,HttpServletRequest http){
        Identity identity=identity(projectId,http);
        var result=service.rotate(identity.tenant(),projectId,identity.actor(),request.operationId(),keyId,request.spec());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(IssuedKey.from(result));
    }
    @PostMapping("/{keyId}/revoke") @Operation(operationId="revokeProjectApiKey",summary="撤销项目API Key")
    public ResponseEntity<ApiKeyView> revoke(@PathVariable UUID projectId,@PathVariable UUID keyId,@Valid @RequestBody RevokeKey request,HttpServletRequest http){
        Identity identity=identity(projectId,http);
        return noStore(service.revoke(identity.tenant(),projectId,identity.actor(),request.operationId(),keyId));
    }
    @GetMapping @Operation(operationId="listProjectApiKeys",summary="分页读取Key脱敏元数据")
    public ResponseEntity<CursorPage<ApiKeyView>> list(@PathVariable UUID projectId,@RequestParam(required=false) String cursor,
            @RequestParam(defaultValue="20") int limit,HttpServletRequest http){
        Identity identity=identity(projectId,http);
        return noStore(service.list(identity.tenant(),projectId,identity.actor(),cursor,limit));
    }
    @GetMapping("/{keyId}") @Operation(operationId="getProjectApiKey",summary="读取Key脱敏元数据")
    public ResponseEntity<ApiKeyView> get(@PathVariable UUID projectId,@PathVariable UUID keyId,HttpServletRequest http){
        Identity identity=identity(projectId,http);return noStore(service.get(identity.tenant(),projectId,identity.actor(),keyId));
    }
    @GetMapping("/operations/{operationId}") @Operation(operationId="recoverProjectApiKeyOperation",summary="查询原操作结果，不恢复秘密")
    public ResponseEntity<ApiKeyView> recover(@PathVariable UUID projectId,@PathVariable UUID operationId,HttpServletRequest http){
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
