package com.things.link.enduser.api.controller;

import com.things.link.enduser.api.support.AppJwtIdentity;
import com.things.link.enduser.application.PushInstallationService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/** 已认证安装关联资源，所有身份和会话组仅从已验签JWT和服务端事实确定。 */
@RestController
@io.swagger.v3.oas.annotations.tags.Tag(name="App 安装推送",description="本人会话关联的推送安装生命周期")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="appAccessBearer")
@RequestMapping("/api/v1/app/push-installations")
public class PushInstallationController {
    private final PushInstallationService service;
    public PushInstallationController(PushInstallationService service) { this.service=service; }
    /** 注册并返回当前安装绑定，不接收正文自报会话；token仅交给加密边界。
     * @param jwt 已认证身份
     * @param body 安装、白名单配置、token及期望版本
     * @return 当前绑定摘要；409需读回，401禁止重放 */
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200",description="安装注册成功，返回权威版本与随机回执"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="409",description="安装版本或会话归属冲突，必须先读回，错误码60065"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="503",description="平台实例或推送配置不可用，错误码60063或60067")
    })
    @PutMapping
    @Operation(operationId="registerAppPushInstallation",summary="注册或轮换会话关联安装",description="期望版本为非负十进制字符串，首次为0；CAS冲突60065/409。配置未启用60067/503；token不回显。")
    public ResponseEntity<PushInstallationService.Summary> register(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Registration body) {
        return response(service.register(AppJwtIdentity.tenantId(jwt),AppJwtIdentity.projectId(jwt),AppJwtIdentity.appUserId(jwt),AppJwtIdentity.sessionId(jwt),
                body.installationId(),body.channelConfigurationId(),body.providerToken(),body.expectedRevision(),body.registrationId()));
    }
    /** 读取本人最新安装摘要，不返回厂商token或跨账号事实。
     * @param jwt 已认证身份
     * @param installationId 本人安装UUID
     * @return 最新摘要及ETag，不存在60066/404；不含token */
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200",description="本人安装当前摘要"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="404",description="本人无该安装记录，错误码60066")
    })
    @GetMapping("/{installationId}")
    @Operation(operationId="getAppPushInstallation",summary="读取本人安装关联摘要",description="读取同一终端账号的最新安装版本；无记录60066/404，失效绑定返回REVOKED；不含厂商token。")
    public ResponseEntity<PushInstallationService.Summary> read(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID installationId) {
        return response(service.read(AppJwtIdentity.tenantId(jwt),AppJwtIdentity.projectId(jwt),AppJwtIdentity.appUserId(jwt),AppJwtIdentity.sessionId(jwt),installationId));
    }
    /** 按精确版本撤销本人当前会话组绑定；允许冻结项目减少安全能力。
     * @param jwt 已认证身份
     * @param installationId 本人安装UUID
     * @param match 带双引号的期望版本
     * @return 204；仅精确同组撤销，项目冻结不阻止安全撤销 */
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="204",description="精确绑定已撤销或已处于撤销状态")
    @DeleteMapping("/{installationId}")
    @Operation(operationId="revokeAppPushInstallation",summary="按版本撤销本人安装关联",description="If-Match必须为带双引号的十进制版本；仅此安全撤销允许冻结项目，不允许恢复或注册。")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID installationId,@RequestHeader("If-Match") String match) {
        if(!match.matches("\"(0|[1-9][0-9]{0,18})\""))throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
        service.revoke(AppJwtIdentity.tenantId(jwt),AppJwtIdentity.projectId(jwt),AppJwtIdentity.appUserId(jwt),AppJwtIdentity.sessionId(jwt),installationId,match.substring(1,match.length()-1));
        return ResponseEntity.noContent().build();
    }
    private ResponseEntity<PushInstallationService.Summary> response(PushInstallationService.Summary summary) {
        return ResponseEntity.ok().eTag(summary.revision()).cacheControl(org.springframework.http.CacheControl.noStore()).body(summary);
    }
    /** 登记正文不允许自报tenant/user/sessionGroup；token默认字符串输出必须脱敏。 */
    @Schema(name="RegisterAppPushInstallationRequest",description="安装注册正文，不含账号或会话归属")
    public record Registration(@NotNull UUID installationId,@NotNull UUID registrationId,@NotBlank @Size(max=64) String channelConfigurationId,
            @NotBlank @Size(max=4096) String providerToken,@NotBlank @Pattern(regexp="0|[1-9][0-9]{0,18}") String expectedRevision) {
        @Override public String toString() { return "Registration[providerToken=***]"; }
    }
}
