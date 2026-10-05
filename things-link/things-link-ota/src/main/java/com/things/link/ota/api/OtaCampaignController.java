package com.things.link.ota.api;

import com.things.link.ota.api.support.OtaAuthorization;
import com.things.link.ota.application.OtaCampaignService;
import com.things.link.ota.application.OtaCanonicalJson;
import com.things.link.ota.domain.OtaCampaignErrorCode;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.page.CursorPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

/** 管理活动入口，权限优先于正文解析；不开放运行或下载路径。 */
@RestController
@Validated
@Tag(name = "OTA活动", description = "DIRECT活动计划、排程快照与未派发取消")
@RequestMapping("/api/v1/projects/{projectId}/ota/campaigns")
public class OtaCampaignController {
    /** 完整业务事务。 */ private final OtaCampaignService service;
    /** OTA部署权限守卫。 */ private final OtaAuthorization authorization;
    /** 保留重复键及未知字段信息。 */ private final OtaCanonicalJson json = new OtaCanonicalJson();
    /** 明确边界依赖。 */
    public OtaCampaignController(OtaCampaignService service, OtaAuthorization authorization) {
        this.service = service; this.authorization = authorization;
    }
    /**
     * 创建不可编辑草稿，未执行设备占位。
     *
     * @param projectId 接口指定的项目标识
     * @param key 本次操作的幂等键，用于识别重复提交
     * @param body 原始请求体字节，由当前接口按请求契约解析
     * @return 当前接口的操作结果，响应结构见 {@code ResponseEntity<OtaCampaignResponse>}
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "createOtaCampaign", summary = "创建DIRECT OTA活动草稿", description = "创建不可编辑草稿，未执行设备占位。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "草稿创建或恢复",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = OtaCampaignResponse.class)))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = OtaCampaignPlanBody.class)))
    public ResponseEntity<OtaCampaignResponse> create(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,
            @Parameter(required = true, description = "本次操作的幂等键，用于识别重复提交") @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody byte[] body) {
        manage(projectId);
        var result = OtaCampaignResponse.from(service.create(projectId, key, body));
        return ResponseEntity.created(URI.create("/api/v1/projects/" + projectId + "/ota/campaigns/" + result.id()))
                .cacheControl(CacheControl.noStore()).body(result);
    }
    /**
     * 返回同一事务中的计划与稳定作业快照。
     *
     * @param projectId 接口指定的项目标识
     * @param campaignId 升级活动标识
     * @return 当前接口的操作结果，响应结构见 {@code ResponseEntity<OtaCampaignResponse>}
     */
    @GetMapping("/{campaignId}")
    @Operation(operationId = "getOtaCampaign", summary = "读取OTA活动与冻结目标", description = "返回同一事务中的计划与稳定作业快照。")
    public ResponseEntity<OtaCampaignResponse> find(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId, @io.swagger.v3.oas.annotations.Parameter(description = "升级活动标识") @PathVariable UUID campaignId) {
        manage(projectId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(OtaCampaignResponse.from(service.find(projectId, campaignId)));
    }

    /**
     * 管理端活动列表：游标不透明且绑定项目，响应只含摘要白名单。
     *
     * @param projectId 接口指定的项目标识
     * @param cursor 可选分页游标，继续读取上一页后的记录
     * @param limit 分页条数，具体边界由当前接口校验
     * @return 符合条件的记录页及后续分页游标
     */
    @GetMapping
    @Operation(operationId = "listOtaCampaigns", summary = "OTA活动游标分页", description = "管理端活动列表：游标不透明且绑定项目，响应只含摘要白名单。")
    public ResponseEntity<CursorPage<OtaCampaignSummaryResponse>> list(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,
            @io.swagger.v3.oas.annotations.Parameter(description = "可选分页游标，继续读取上一页后的记录") @RequestParam(required = false) String cursor,
            @io.swagger.v3.oas.annotations.Parameter(description = "分页条数，具体边界由当前接口校验") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        manage(projectId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.list(projectId, cursor, limit).map(OtaCampaignSummaryResponse::from));
    }

    /**
     * 冻结批次只读投影；草稿活动返回空列表，未知与跨项目身份都是70034。
     *
     * @param projectId 接口指定的项目标识
     * @param campaignId 升级活动标识
     * @return 符合当前查询条件的结果列表
     */
    @GetMapping("/{campaignId}/batches")
    @Operation(operationId = "listOtaCampaignBatches", summary = "读取OTA活动冻结批次", description = "冻结批次只读投影；草稿活动返回空列表，未知与跨项目身份都是70034。")
    public ResponseEntity<List<OtaCampaignBatchResponse>> batches(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,
            @io.swagger.v3.oas.annotations.Parameter(description = "升级活动标识") @PathVariable UUID campaignId) {
        manage(projectId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.batches(projectId, campaignId).stream().map(OtaCampaignBatchResponse::from).toList());
    }

    /**
     * 活动内设备作业列表：游标不透明且绑定项目，响应只含运行状态机白名单。
     *
     * @param projectId 接口指定的项目标识
     * @param campaignId 升级活动标识
     * @param cursor 可选分页游标，继续读取上一页后的记录
     * @param limit 分页条数，具体边界由当前接口校验
     * @return 符合条件的记录页及后续分页游标
     */
    @GetMapping("/{campaignId}/jobs")
    @Operation(operationId = "listOtaCampaignJobs", summary = "OTA活动设备作业游标分页", description = "活动内设备作业列表：游标不透明且绑定项目，响应只含运行状态机白名单。")
    public ResponseEntity<CursorPage<OtaDeviceJobSummaryResponse>> jobs(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,
            @io.swagger.v3.oas.annotations.Parameter(description = "升级活动标识") @PathVariable UUID campaignId,
            @io.swagger.v3.oas.annotations.Parameter(description = "可选分页游标，继续读取上一页后的记录") @RequestParam(required = false) String cursor,
            @io.swagger.v3.oas.annotations.Parameter(description = "分页条数，具体边界由当前接口校验") @RequestParam(defaultValue = "100") @Min(1) @Max(200) int limit) {
        manage(projectId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                service.listJobs(projectId, campaignId, cursor, limit).map(OtaDeviceJobSummaryResponse::from));
    }

    /**
     * 单个作业与不可变转移时间线；未知、跨项目或跨活动身份都是70034。
     *
     * @param projectId 接口指定的项目标识
     * @param campaignId 升级活动标识
     * @param jobId 批量任务标识
     * @return 当前接口的操作结果，响应结构见 {@code ResponseEntity<OtaDeviceJobDetailResponse>}
     */
    @GetMapping("/{campaignId}/jobs/{jobId}")
    @Operation(operationId = "getOtaCampaignJob", summary = "读取OTA设备作业与转移时间线", description = "单个作业与不可变转移时间线；未知、跨项目或跨活动身份都是70034。")
    public ResponseEntity<OtaDeviceJobDetailResponse> job(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,
            @io.swagger.v3.oas.annotations.Parameter(description = "升级活动标识") @PathVariable UUID campaignId, @io.swagger.v3.oas.annotations.Parameter(description = "批量任务标识") @PathVariable UUID jobId) {
        manage(projectId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(OtaDeviceJobDetailResponse.from(service.findJob(projectId, campaignId, jobId)));
    }
    /**
     * 一次排程冻结全部目标并占位，任一失败全部回滚。
     *
     * @param projectId 接口指定的项目标识
     * @param campaignId 升级活动标识
     * @param key 本次操作的幂等键，用于识别重复提交
     * @param body 原始请求体字节，由当前接口按请求契约解析
     * @return 当前接口的操作结果，响应结构见 {@code ResponseEntity<OtaCampaignResponse>}
     */
    @PostMapping(value = "/{campaignId}/scheduling", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "scheduleOtaCampaign", summary = "原子冻结OTA目标与批次", description = "一次排程冻结全部目标并占位，任一失败全部回滚。")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = Scheduling.class)))
    public ResponseEntity<OtaCampaignResponse> schedule(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId, @io.swagger.v3.oas.annotations.Parameter(description = "升级活动标识") @PathVariable UUID campaignId,
            @Parameter(required = true, description = "本次操作的幂等键，用于识别重复提交") @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody byte[] body) {
        manage(projectId);
        var fields = fields(body, Set.of("expectedRevision"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(OtaCampaignResponse.from(
                service.schedule(projectId, campaignId, key, (String) fields.get("expectedRevision"))));
    }
    /**
     * 未派发取消不暗示任何设备降级或刷写中断。
     *
     * @param projectId 接口指定的项目标识
     * @param campaignId 升级活动标识
     * @param key 本次操作的幂等键，用于识别重复提交
     * @param body 原始请求体字节，由当前接口按请求契约解析
     * @return 当前接口的操作结果，响应结构见 {@code ResponseEntity<OtaCampaignResponse>}
     */
    @PostMapping(value = "/{campaignId}/cancellation", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "cancelOtaCampaign", summary = "请求取消OTA活动并保留已派发安全责任", description = "未派发取消不暗示任何设备降级或刷写中断。")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = Cancellation.class)))
    public ResponseEntity<OtaCampaignResponse> cancel(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId, @io.swagger.v3.oas.annotations.Parameter(description = "升级活动标识") @PathVariable UUID campaignId,
            @Parameter(required = true, description = "本次操作的幂等键，用于识别重复提交") @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody byte[] body) {
        manage(projectId);
        var fields = fields(body, Set.of("expectedRevision", "reason"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(OtaCampaignResponse.from(
                service.cancel(projectId, campaignId, key, (String) fields.get("expectedRevision"), (String) fields.get("reason"))));
    }
    /** 正文必须精确字符串字段，不能由宽松DTO忽略重复字段。 */
    private Map<String, Object> fields(byte[] body, Set<String> expected) {
        try {
            var result = json.parseObject(body);
            if (!result.keySet().equals(expected) || result.values().stream().anyMatch(v -> !(v instanceof String))) {
                throw new IllegalArgumentException();
            }
            return result;
        } catch (IllegalArgumentException failure) { throw new BusinessException(CommonErrorCode.MALFORMED_REQUEST); }
    }
    /** 当前成员角色优先，不泄露无权正文校验细节。 */
    private void manage(UUID project) {
        if (!authorization.mayDeploy(project)) {
            throw new BusinessException(OtaCampaignErrorCode.FORBIDDEN);
        }
    }
    /** 排程仅提供CAS，不能覆盖冻结草稿。
     * @param expectedRevision 原状态修订
     */
    @Schema(name = "OtaCampaignSchedulingRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Scheduling(@Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "0|[1-9][0-9]{0,18}")
                             String expectedRevision) { }
    /** 取消必须说明原因并符合当前CAS。
     * @param expectedRevision 原状态修订
     * @param reason 高危操作理由
     */
    @Schema(name = "OtaCampaignCancellationRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Cancellation(@Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "0|[1-9][0-9]{0,18}")
                               String expectedRevision,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 256) String reason) { }
}
