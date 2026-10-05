package com.things.link.ota.api;

import com.things.link.ota.api.support.OtaAuthorization;
import com.things.link.ota.application.OtaCampaignAdvancementService;
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
public class OtaCampaignAdvancementController {
    /** OTA部署权限守卫。 */ private final OtaAuthorization authorization;
    /** 运行控制事务。 */ private final OtaCampaignAdvancementService service;
    /** 有界闭集JSON，保留重复字段信息。 */ private final OtaCanonicalJson json = new OtaCanonicalJson();

    /** 注入权限和业务边界。 */
    public OtaCampaignAdvancementController(OtaAuthorization authorization, OtaCampaignAdvancementService service) {
        this.authorization = authorization;
        this.service = service;
    }
    /**
     * 当前人工计划成功批的唯一后继，完成同键返回公共墓碑。
     *
     * @param projectId 接口指定的项目标识
     * @param campaignId 升级活动标识
     * @param key 本次操作的幂等键，用于识别重复提交
     * @param body 原始请求体字节，由当前接口按请求契约解析
     * @return 当前接口的操作结果，响应结构见 {@code ResponseEntity<OtaCampaignExecutionResponse>}
     */
    @PostMapping(value = "/batch-advancements", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "advanceOtaCampaignBatch", summary = "人工放行OTA下一批", description = "当前人工计划成功批的唯一后继，完成同键返回公共墓碑。")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = Advancement.class)))
    public ResponseEntity<OtaCampaignExecutionResponse> advance(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId, @io.swagger.v3.oas.annotations.Parameter(description = "升级活动标识") @PathVariable UUID campaignId,
            @Parameter(required = true, description = "本次操作的幂等键，用于识别重复提交") @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody byte[] body) {
        manage(projectId);
        var fields = fields(body, Set.of("expectedRevision", "expectedBatchNumber", "reason"));
        return response(service.advance(projectId, campaignId, key, fields.get("expectedRevision"),
                fields.get("expectedBatchNumber"), fields.get("reason")));
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
    /** 人工放行不能改变原目标和冻结策略。
     * @param expectedRevision 活动当前修订
     * @param expectedBatchNumber 当前成功批号
     * @param reason 当前管理原因
     */
    @Schema(name = "OtaCampaignAdvancementRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
            requiredProperties = {"expectedRevision", "expectedBatchNumber", "reason"})
    public record Advancement(@Schema(pattern = "0|[1-9][0-9]{0,18}") String expectedRevision,
            @Schema(pattern = "[1-9][0-9]{0,2}|1000") String expectedBatchNumber,
            @Schema(minLength = 1, maxLength = 256) String reason) { }
}
