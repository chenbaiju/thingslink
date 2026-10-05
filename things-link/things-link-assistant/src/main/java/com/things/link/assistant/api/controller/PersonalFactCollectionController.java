package com.things.link.assistant.api.controller;

import com.things.link.assistant.application.PersonalFactCollectionReport;
import com.things.link.assistant.application.PersonalFactCollectionService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.DeserializationFeature;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 本人历史集合报告入口；只有选择标识，没有持久写入、任意模板或模型调用。 */
@RestController
@Tag(name="Agent 个人报告",description="手动汇编本人历史事实，不提供模型诊断")
@SecurityRequirement(name="consoleAccessBearer")
public class PersonalFactCollectionController {
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final PersonalFactCollectionService service;
    /** @param service 有界纯事实汇编端口 */
    public PersonalFactCollectionController(PersonalFactCollectionService service) { this.service=service; }
    /** 请求只携带规范来源标识，不接收身份、设备值、报告正文或范围扩张。 */
    @Schema(name="PersonalFactCollectionInput",requiredProperties={"recordIds"},additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record Input(@ArraySchema(minItems=1,maxItems=5,uniqueItems=true,schema=@Schema(type="string",format="uuid",pattern="[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) List<UUID> recordIds) { }

    /**
     * 明确选择后生成本人历史设备集合报告；当前四角色可读，全部来源成功且最终统一确权才返回。
     * @param projectId 当前项目，不能读取其他项目或成员来源
     * @param request 原始请求，最多两千字节封闭JSON，仅接收一至五个不同规范来源标识，禁止查询参数
     * @return 禁止缓存的来源、选择范围统计和固定纯文本摘要；重复生成无写入或付费，取消不承诺撤销读取
     */
    @PostMapping(value="/api/v1/projects/{projectId}/assistant/fact-reports/collection",consumes="application/json")
    @Operation(operationId="generatePersonalFactCollection",summary="手动生成本人历史设备集合事实报告",
            description="最多五设备各一条本人有效记录，返回前统一复核来源及当前权限；范围只限所选历史记录，不代表实时或全项目覆盖。固定纯文本/no-store，无写入、模型、设备查询或付费重试。",
            requestBody=@io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(schema=@Schema(implementation=Input.class))))
    public ResponseEntity<PersonalFactCollectionReport> generate(@Parameter(description="当前受权项目标识") @PathVariable UUID projectId,HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw invalid();
        List<UUID> ids;
        try {
            byte[] bytes=request.getInputStream().readNBytes(2049);
            if(bytes.length>2048) throw invalid();
            var body=JSON.readTree(bytes);
            if(body==null || !body.isObject() || body.size()!=1 || !body.has("recordIds") || !body.path("recordIds").isArray()
                    || body.path("recordIds").isEmpty() || body.path("recordIds").size()>5) throw invalid();
            ids=new ArrayList<>();
            for(var value:body.path("recordIds")) {
                if(!value.isString() || !value.asString().matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) throw invalid();
                ids.add(UUID.fromString(value.asString()));
            }
        } catch(BusinessException error) { throw error; }
        catch(Exception ignored) { throw invalid(); }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.generate(projectId,ids));
    }
    private static BusinessException invalid() { return new BusinessException(CommonErrorCode.INVALID_PARAMETER); }
}
