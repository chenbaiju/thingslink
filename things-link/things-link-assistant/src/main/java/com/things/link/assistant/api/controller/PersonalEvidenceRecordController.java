package com.things.link.assistant.api.controller;
import com.things.link.assistant.application.PersonalEvidenceRecordService;
import com.things.link.assistant.application.PersonalEvidenceRecordView;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;
/** 个人事实记录入口；仅服务端重新采集，不接收浏览器结论或证据正文。 */
@RestController
@Tag(name="Agent 个人记录", description="创建者且当前项目成员可访问的历史事实")
@SecurityRequirement(name="consoleAccessBearer")
@RequestMapping("/api/v1/projects/{projectId}/assistant/evidence-records")
public class PersonalEvidenceRecordController {
    private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder()
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final PersonalEvidenceRecordService service;
    public PersonalEvidenceRecordController(PersonalEvidenceRecordService service) { this.service=service; }
    /**
     * 手动重新采集设备证据并保存为本人历史事实；四角色可用，不消费模型Key。
     * @param projectId 当前项目标识
     * @param raw 最多四千字节的封闭选择器：设备、精确模型和一至十个属性键
     * @param request 原始请求，拒绝查询参数
     * @return 新记录元数据及资源位置；不返回模型诊断
     */
    @PostMapping
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="201",description="本人历史事实记录已保存",
            content=@io.swagger.v3.oas.annotations.media.Content(schema=@Schema(implementation=PersonalEvidenceRecordView.class)))
    @Operation(operationId="createPersonalEvidenceRecord",summary="手动保存个人设备事实记录",description="在当前项目重新采集本人设备事实，拒绝浏览器事实正文与额外查询参数；不调用外部模型，返回不缓存。")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@io.swagger.v3.oas.annotations.media.Content(schema=@Schema(implementation=CreateRequest.class)))
    public ResponseEntity<PersonalEvidenceRecordView> create(@Parameter(description="当前项目标识") @PathVariable UUID projectId,
            @RequestBody byte[] raw, HttpServletRequest request) {
        closedQuery(request);
        if(raw.length==0 || raw.length>4096) throw invalid();
        JsonNode body;
        try { body=JSON.readTree(raw); }
        catch(RuntimeException ignored) { throw invalid(); }
        if (body==null || !body.isObject() || !Set.copyOf(body.propertyNames()).equals(Set.of("deviceId","expectedModelVersionId","propertyKeys"))) throw invalid();
        UUID device=uuid(body.path("deviceId")), model=uuid(body.path("expectedModelVersionId"));
        var keys=body.path("propertyKeys");
        if (!keys.isArray() || keys.size()<1 || keys.size()>10 || keys.valueStream().anyMatch(k->!k.isString())) throw invalid();
        var result=service.create(projectId,device,model,keys.valueStream().map(JsonNode::asString).toList());
        return ResponseEntity.created(URI.create("/api/v1/projects/"+projectId+"/assistant/evidence-records/"+result.id()))
                .cacheControl(CacheControl.noStore()).body(result);
    }
    /**
     * 读取本人仍可访问的个人事实记录元数据；不以历史记录授予当前项目权限。
     *
     * @param projectId 当前项目标识
     * @param request 原始请求，禁止额外查询参数
     * @return 最多100条本人有效元数据；不使用游标分页，响应不缓存
     */
    @GetMapping
    @Operation(operationId="listPersonalEvidenceRecords",summary="读取个人设备事实记录列表",description="读取本人在当前项目可访问的历史事实元数据，最多100条；拒绝额外查询参数，不返回事实正文或模型诊断。")
    public ResponseEntity<List<PersonalEvidenceRecordView>> list(@Parameter(description="当前项目标识") @PathVariable UUID projectId, HttpServletRequest request) {
        closedQuery(request);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(projectId));
    }
    /**
     * 读取本人历史事实详情及来源；历史快照不代表当前设备状态。
     *
     * @param projectId 当前项目标识
     * @param recordId 本人记录标识
     * @param request 原始请求，禁止额外查询参数
     * @return 带来源的历史事实快照，响应不缓存
     */
    @GetMapping("/{recordId}")
    @Operation(operationId="getPersonalEvidenceRecord",summary="读取本人历史事实详情",description="按本人记录与当前项目权限读取历史快照及来源；拒绝额外查询参数，不把历史事实当作当前状态，响应不缓存。")
    public ResponseEntity<PersonalEvidenceRecordView.Detail> read(@Parameter(description="当前项目标识") @PathVariable UUID projectId,
            @Parameter(description="本人记录标识") @PathVariable UUID recordId, HttpServletRequest request) {
        closedQuery(request);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.read(projectId,recordId));
    }
    /**
     * 删除当前可写项目中本人的历史事实记录；不重置模型或探针次数。
     *
     * @param projectId 当前可写项目标识
     * @param recordId 本人记录标识
     * @param request 原始请求，禁止额外查询参数
     * @return 删除成功且无正文，响应不缓存
     */
    @DeleteMapping("/{recordId}")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="204",description="本人历史记录已删除")
    @Operation(operationId="deletePersonalEvidenceRecord",summary="删除本人设备事实记录",description="按当前可写项目权限删除本人的历史事实记录；拒绝额外查询参数，不重置模型配置或探针次数，成功无正文。")
    public ResponseEntity<Void> delete(@Parameter(description="当前项目标识") @PathVariable UUID projectId,
            @Parameter(description="本人记录标识") @PathVariable UUID recordId, HttpServletRequest request) {
        closedQuery(request);service.delete(projectId,recordId);return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    private static void closedQuery(HttpServletRequest request) { if (!request.getParameterMap().isEmpty()) throw invalid(); }
    private static UUID uuid(JsonNode value) {
        if (!value.isString()) throw invalid();
        try { UUID id=UUID.fromString(value.asString());if(!id.toString().equals(value.asString())) throw invalid();return id; }
        catch(IllegalArgumentException ignored) { throw invalid(); }
    }
    private static BusinessException invalid() { return new BusinessException(CommonErrorCode.INVALID_PARAMETER); }
    /** 生成合同的封闭选择器，不接受证据或身份正文。 */
    @Schema(name="CreatePersonalEvidenceRecordRequest", requiredProperties={"deviceId","expectedModelVersionId","propertyKeys"}, additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record CreateRequest(UUID deviceId, UUID expectedModelVersionId,
            @io.swagger.v3.oas.annotations.media.ArraySchema(minItems=1,maxItems=10,uniqueItems=true,
                    schema=@Schema(type="string",pattern="[A-Za-z0-9_-]{1,64}")) List<String> propertyKeys) { }
}
