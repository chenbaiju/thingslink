package com.things.link.assistant.api.controller;

import com.things.link.assistant.application.*;
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
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 人工批准的项目知识入口，只做有界本地检索，正文及关键词退出原文请求日志。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/assistant/knowledge")
@Tag(name="Agent 项目知识",description="管理员批准的项目资料、不可变版本及本地关键词引用，不调用模型")
@SecurityRequirement(name="consoleAccessBearer")
public class ProjectKnowledgeController {
    private final ProjectKnowledgeService service;
    private final JsonMapper json=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    /** @param service 当前项目知识及引用服务 */
    public ProjectKnowledgeController(ProjectKnowledgeService service) { this.service=service; }

    /**
     * 当前四角色读取最多100条批准来源元数据，归档可读，不包含知识原文。
     * @param projectId 当前选定项目
     * @param request 请求对象，未知查询参数拒绝
     * @return 当前版本元数据及禁止缓存响应
     */
    @GetMapping("/sources")
    @Operation(operationId="listAssistantKnowledgeSources",summary="读取当前批准的项目知识目录",description="当前四角色手动读取最多100个最新批准来源元数据，不包含正文；返回前重新核验成员和版本，不授予外部出站资格。")
    public ResponseEntity<List<KnowledgeSourceView>> list(@Parameter(description="当前选定项目") @PathVariable UUID projectId,
            HttpServletRequest request) { noQuery(request); return ok(service.list(projectId)); }

    /**
     * 当前四角色读取明确批准为项目共用的最新正文，变更或撤权后拒绝旧结果。
     * @param projectId 当前选定项目
     * @param sourceKey 受控ASCII来源标识
     * @param request 请求对象，未知查询参数拒绝
     * @return 最新规范纯文本和版本摘要，不提供出站资格
     */
    @GetMapping("/sources/{sourceKey}")
    @Operation(operationId="getAssistantKnowledgeSource",summary="读取最新批准知识正文",description="仅读取当前批准版本的规范纯文本；成员撤权或来源换版后拒绝旧结果，不提供历史正文或出站资格。")
    public ResponseEntity<KnowledgeSourceView.Detail> detail(@Parameter(description="当前选定项目") @PathVariable UUID projectId,
            @Parameter(description="受控来源标识",schema=@Schema(pattern="[a-z][a-z0-9_-]{0,63}")) @PathVariable String sourceKey,
            HttpServletRequest request) { noQuery(request); return ok(service.detail(projectId,sourceKey)); }

    /**
     * 仅当前OWNER/ADMIN明确批准项目共享后发布新版本；匹配当前UUID，首次为null，不自动重发。
     * @param projectId 当前选定项目
     * @param sourceKey 受控ASCII来源标识
     * @param body 最多128KiB的封闭JSON，规范正文最多16KiB UTF-8
     * @param request 请求对象，未知查询参数拒绝
     * @return 新版本元数据，旧正文仍不可检索，禁止缓存
     */
    @PutMapping(value="/sources/{sourceKey}",consumes="application/json")
    @Operation(operationId="publishAssistantKnowledgeSource",summary="明确批准项目共享并发布知识新版本",description="仅当前活跃项目OWNER/ADMIN可发布，逐次明确批准项目共享并匹配当前版本UUID；最多100来源、每来源20版本、规范正文16KiB，不自动重发。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="201",description="已发布新版本，不自动重试或重放正文",
            content=@io.swagger.v3.oas.annotations.media.Content(schema=@Schema(implementation=KnowledgeSourceView.class)))
    public ResponseEntity<KnowledgeSourceView> publish(@Parameter(description="当前选定项目") @PathVariable UUID projectId,
            @Parameter(description="受控来源标识") @PathVariable String sourceKey,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,description="封闭有界JSON，只接受Schema明列字段，不接受身份、查询表达式或外部凭据",content=@io.swagger.v3.oas.annotations.media.Content(
                schema=@Schema(implementation=PublishRequest.class))) @RequestBody byte[] body, HttpServletRequest request) {
        noQuery(request); var value=parse(body,131072,Set.of("expectedCurrentVersionId","content","approvedForProjectMembers"));
        if (!value.path("content").isString() || !value.path("approvedForProjectMembers").isBoolean()
                || !value.path("approvedForProjectMembers").asBoolean()) throw invalid();
        UUID expected=value.path("expectedCurrentVersionId").isNull()?null:uuid(value.path("expectedCurrentVersionId"));
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(
                service.publish(projectId,sourceKey,expected,value.path("content").asString(),true));
    }

    /**
     * 当前管理员按精确版本物理删除来源全部最多20个版本，旧引用不再可读，取消不保证撤销事务。
     * @param projectId 当前选定项目
     * @param sourceKey 受控来源标识
     * @param expectedCurrentVersionId 精确当前版本，必填且只能一个
     * @param request 请求对象，拒绝额外、重复或空查询
     * @return 删除成功204及禁止缓存响应，不重置模型或探针额度
     */
    @DeleteMapping("/sources/{sourceKey}")
    @Operation(operationId="deleteAssistantKnowledgeSource",summary="按当前版本删除项目知识来源",description="仅当前活跃项目管理员按精确当前UUID物理删除来源全部最多20版本；旧引用不再可读，取消请求不能证明事务撤销。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="204",description="已删除来源全部版本，不返回正文")
    public ResponseEntity<Void> delete(@Parameter(description="当前选定项目") @PathVariable UUID projectId,
            @Parameter(description="受控来源标识") @PathVariable String sourceKey,
            @Parameter(description="精确当前版本UUID") @RequestParam UUID expectedCurrentVersionId, HttpServletRequest request) {
        var params=request.getParameterMap();
        if (!params.keySet().equals(Set.of("expectedCurrentVersionId")) || params.values().stream().anyMatch(v -> v.length!=1 || v[0].isBlank())) throw invalid();
        service.delete(projectId,sourceKey,expectedCurrentVersionId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    /**
     * 当前四角色手动字面检索最新批准资料；无资料与无命中明确区分，不生成诊断或外发片段。
     * @param projectId 当前选定项目
     * @param body 最多2KiB封闭关键词JSON，最多5个不同关键词
     * @param request 请求对象，未知查询参数拒绝
     * @return 最多3条版本绑定原文片段及禁止缓存响应
     */
    @PostMapping(value="/search",consumes="application/json")
    @Operation(operationId="searchAssistantProjectKnowledge",summary="本地字面检索受权项目知识",description="按1至5个不同字面关键词读取当前批准版本，最多3条摘要及码点绑定原文片段；无资料、无命中和源失败分开，不生成诊断、不执行文本指令、不发送外部模型。")
    public ResponseEntity<LocalKnowledgeRetriever.Result> search(@Parameter(description="当前选定项目") @PathVariable UUID projectId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,description="封闭有界JSON，只接受Schema明列字段，不接受身份、查询表达式或外部凭据",content=@io.swagger.v3.oas.annotations.media.Content(
                schema=@Schema(implementation=SearchRequest.class))) @RequestBody byte[] body, HttpServletRequest request) {
        noQuery(request); var value=parse(body,2048,Set.of("keywords")); var keys=value.path("keywords");
        if (!keys.isArray() || keys.isEmpty() || keys.size()>5 || keys.valueStream().anyMatch(key -> !key.isString())) throw invalid();
        return ok(service.search(projectId,keys.valueStream().map(JsonNode::asString).toList()));
    }
    /** @param body 有界JSON原字节 @param max 最大请求体 @param fields 唯一允许字段 @return 严格对象，不记录解析异常原文 */
    private JsonNode parse(byte[] body,int max,Set<String> fields) {
        if (body==null || body.length==0 || body.length>max) throw invalid();
        try { var value=json.readTree(body); if (!value.isObject() || !Set.copyOf(value.propertyNames()).equals(fields)) throw invalid(); return value; }
        catch (RuntimeException failure) { throw invalid(); }
    }
    /** @param value 规范UUID字符串 @return 校验后的标识，不接受缩写或非字符串 */
    private static UUID uuid(JsonNode value) {
        if (!value.isString()) throw invalid();
        try { UUID id=UUID.fromString(value.asString()); if (!id.toString().equals(value.asString())) throw invalid(); return id; }
        catch (IllegalArgumentException ignored) { throw invalid(); }
    }
    /** @param request 请求对象 @return 无返回值，禁止额外查询表达式 */
    private static void noQuery(HttpServletRequest request) { if (!request.getParameterMap().isEmpty()) throw invalid(); }
    /** @param value 有界受权结果 @param <T> 响应类型 @return 禁止缓存响应 */
    private static <T> ResponseEntity<T> ok(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
    /** @return 不含原文或解析异常的固定参数错误 */
    private static BusinessException invalid() { return new BusinessException(CommonErrorCode.INVALID_PARAMETER); }
    /** @param expectedCurrentVersionId 首次必须null，后续必须匹配当前UUID @param content 已筛选脱敏的规范正文 @param approvedForProjectMembers 必须明确批准为true */
    @Schema(name="PublishAssistantKnowledgeRequest",requiredProperties={"expectedCurrentVersionId","content","approvedForProjectMembers"},additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record PublishRequest(@Schema(types={"string","null"},format="uuid") UUID expectedCurrentVersionId,
            @Schema(maxLength=16384,description="UTF-8规范正文最多16KiB，需管理员人工筛选脱敏") String content,
            @Schema(description="必须明确true，批准全部当前项目成员读取；不授予外部出站资格") boolean approvedForProjectMembers) { }
    /** @param keywords 1至5个不同的2至32码点字面关键词 */
    @Schema(name="SearchAssistantKnowledgeRequest",requiredProperties={"keywords"},additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record SearchRequest(@io.swagger.v3.oas.annotations.media.ArraySchema(minItems=1,maxItems=5,uniqueItems=true,
            schema=@Schema(type="string",minLength=2,maxLength=32)) List<String> keywords) { }
}
