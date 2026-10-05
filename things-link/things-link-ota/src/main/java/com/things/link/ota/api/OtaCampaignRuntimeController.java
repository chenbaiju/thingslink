package com.things.link.ota.api;

import com.things.link.ota.api.support.OtaAuthorization;
import com.things.link.ota.application.OtaCampaignRuntimeService;
import com.things.link.ota.application.OtaCanonicalJson;
import com.things.link.ota.domain.OtaCampaignRuntimeErrorCode;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 真实管理身份下的运行命令；不开放设备HTTP或执行期取消。 */
@RestController
@Tag(name = "OTA活动运行", description = "启动、暂停、恢复与持久运行事实")
@RequestMapping("/api/v1/projects/{projectId}/ota/campaigns/{campaignId}")
public class OtaCampaignRuntimeController {
    /** OTA部署权限守卫。 */ private final OtaAuthorization authorization;
    /** 运行控制事务。 */ private final OtaCampaignRuntimeService service;
    /** 有界闭集JSON，保留重复字段信息。 */ private final OtaCanonicalJson json = new OtaCanonicalJson();

    /** 注入权限和业务边界。 */
    public OtaCampaignRuntimeController(OtaAuthorization authorization, OtaCampaignRuntimeService service) {
        this.authorization = authorization;
        this.service = service;
    }
    /**
     * 读取当前执行事实，不返回URL或租约。
     *
     * @param projectId 接口指定的项目标识
     * @param campaignId 升级活动标识
     * @return 当前接口的操作结果，响应结构见 {@code ResponseEntity<OtaCampaignExecutionResponse>}
     */
    @GetMapping("/execution")
    @Operation(operationId = "getOtaCampaignExecution", summary = "读取OTA活动执行事实", description = "读取当前执行事实，不返回URL或租约。")
    public ResponseEntity<OtaCampaignExecutionResponse> find(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId, @io.swagger.v3.oas.annotations.Parameter(description = "升级活动标识") @PathVariable UUID campaignId) {
        manage(projectId);
        return response(service.find(projectId, campaignId));
    }
    /**
     * 到达冻结排程时间后启动第一批，实际准入由后台事务执行。
     *
     * @param projectId 接口指定的项目标识
     * @param campaignId 升级活动标识
     * @param key 本次操作的幂等键，用于识别重复提交
     * @param body 原始请求体字节，由当前接口按请求契约解析
     * @return 当前接口的操作结果，响应结构见 {@code ResponseEntity<OtaCampaignExecutionResponse>}
     */
    @PostMapping(value = "/starting", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "startOtaCampaign", summary = "启动OTA活动", description = "到达冻结排程时间后启动第一批，实际准入由后台事务执行。")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = Starting.class)))
    public ResponseEntity<OtaCampaignExecutionResponse> start(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId, @io.swagger.v3.oas.annotations.Parameter(description = "升级活动标识") @PathVariable UUID campaignId,
            @Parameter(required = true, description = "本次操作的幂等键，用于识别重复提交") @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody byte[] body) {
        manage(projectId);
        var fields = fields(body, Set.of("expectedRevision"));
        return response(service.start(projectId, campaignId, key, fields.get("expectedRevision")));
    }
    /**
     * 关闭新准入，不撤回已受理通知或中断刷写。
     *
     * @param projectId 接口指定的项目标识
     * @param campaignId 升级活动标识
     * @param key 本次操作的幂等键，用于识别重复提交
     * @param body 原始请求体字节，由当前接口按请求契约解析
     * @return 当前接口的操作结果，响应结构见 {@code ResponseEntity<OtaCampaignExecutionResponse>}
     */
    @PostMapping(value = "/pauses", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "pauseOtaCampaign", summary = "暂停OTA活动", description = "关闭新准入，不撤回已受理通知或中断刷写。")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = Change.class)))
    public ResponseEntity<OtaCampaignExecutionResponse> pause(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId, @io.swagger.v3.oas.annotations.Parameter(description = "升级活动标识") @PathVariable UUID campaignId,
            @Parameter(required = true, description = "本次操作的幂等键，用于识别重复提交") @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody byte[] body) {
        manage(projectId);
        var fields = fields(body, Set.of("expectedRevision", "reason"));
        return response(service.pause(projectId, campaignId, key, fields.get("expectedRevision"), fields.get("reason")));
    }
    /**
     * 重新验真安全条件，人工原因不能覆盖安全暂停。
     *
     * @param projectId 接口指定的项目标识
     * @param campaignId 升级活动标识
     * @param key 本次操作的幂等键，用于识别重复提交
     * @param body 原始请求体字节，由当前接口按请求契约解析
     * @return 当前接口的操作结果，响应结构见 {@code ResponseEntity<OtaCampaignExecutionResponse>}
     */
    @PostMapping(value = "/resumptions", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "resumeOtaCampaign", summary = "恢复OTA活动", description = "重新验真安全条件，人工原因不能覆盖安全暂停。")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = Change.class)))
    public ResponseEntity<OtaCampaignExecutionResponse> resume(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId, @io.swagger.v3.oas.annotations.Parameter(description = "升级活动标识") @PathVariable UUID campaignId,
            @Parameter(required = true, description = "本次操作的幂等键，用于识别重复提交") @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody byte[] body) {
        manage(projectId);
        var fields = fields(body, Set.of("expectedRevision", "reason"));
        return response(service.resume(projectId, campaignId, key, fields.get("expectedRevision"), fields.get("reason")));
    }
    /** 所有成功运行响应禁缓存。 */
    private static ResponseEntity<OtaCampaignExecutionResponse> response(com.things.link.ota.domain.OtaCampaignRuntime value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(OtaCampaignExecutionResponse.from(value));
    }
    /** 真实角色先于正文解析，服务仍独立复核权限。 */
    private void manage(UUID project) {
        if (!authorization.mayDeploy(project)) throw new BusinessException(OtaCampaignRuntimeErrorCode.FORBIDDEN);
    }
    /** 精确必填字符串闭集，语义交由服务核验。 */
    private Map<String, String> fields(byte[] body, Set<String> expected) {
        try {
            var parsed = json.parseObject(body);
            if (!parsed.keySet().equals(expected) || parsed.values().stream().anyMatch(value -> !(value instanceof String))) {
                throw new IllegalArgumentException();
            }
            var result = new java.util.LinkedHashMap<String, String>();
            parsed.forEach((key, value) -> result.put(key, (String) value));
            return Map.copyOf(result);
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException(CommonErrorCode.MALFORMED_REQUEST);
        }
    }
    /** 启动仅携带当前CAS修订。
     * @param expectedRevision 状态修订
     */
    @Schema(name = "OtaCampaignStartingRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Starting(@Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "0|[1-9][0-9]{0,18}") String expectedRevision) { }
    /** 暂停恢复必须提供真实理由。
     * @param expectedRevision 状态修订
     * @param reason 操作原因
     */
    @Schema(name = "OtaCampaignChangeRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Change(@Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "0|[1-9][0-9]{0,18}") String expectedRevision,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 256) String reason) { }
}
