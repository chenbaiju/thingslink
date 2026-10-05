package com.things.link.assistant.api.controller;
import com.things.link.assistant.application.ModelConfigurationService;
import com.things.link.assistant.application.ModelConfigurationView;
import com.things.link.assistant.application.ModelCredentialInput;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.media.Content;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;

/** 秘密请求使用闭合解析；不让解析器异常携带原始正文进入全局日志。 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "模型配置", description = "项目模型凭据与启用状态；秘密只写不回显")
@RestController
@RequestMapping("/api/v1/projects/{projectId}/assistant/model-configurations/deepseek-chat")
@SecurityRequirement(name="consoleAccessBearer")
public class ModelConfigurationController {
    private final ModelConfigurationService service;
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public ModelConfigurationController(ModelConfigurationService service) { this.service=service; }
    @Schema(additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
    public record EnableRequest(@Schema(requiredMode=Schema.RequiredMode.REQUIRED,pattern="0|[1-9][0-9]{0,18}") String expectedRevision,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) boolean enabled) {}

    /**
     * 管理员读取项目模型配置元数据。
     *
     * @param projectId 接口指定的项目标识
     * @param request 原始 HTTP 请求，供封闭输入、头部或身份校验使用
     * @param response HTTP 响应对象，用于设置响应头或状态
     * @return 项目模型配置的脱敏状态，不包含凭据
     */
    @GetMapping
    @Operation(operationId="getAssistantModelConfiguration",summary="管理员读取项目模型配置元数据", description = "管理员读取项目模型配置元数据。")
    public ModelConfigurationView read(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,HttpServletRequest request,HttpServletResponse response) {
        checkQuery(request,response,Set.of()); return service.read(projectId);
    }
    /**
     * 管理员保存并启用项目模型凭据。
     * 同一事务替换并验证本地解密后启用，配置版本递增两次；失败全部回滚。版本CAS，不返回凭据、不执行供应商测试。重复提交冲突后重新GET确认结果。
     *
     * @param projectId 接口指定的项目标识
     * @param request 原始 HTTP 请求，供封闭输入、头部或身份校验使用
     * @param response HTTP 响应对象，用于设置响应头或状态
     * @return 项目模型配置的脱敏状态，不包含凭据
     */
    @PutMapping(consumes="application/json")
    @ApiResponses({
        @ApiResponse(responseCode="200",description="OK",content=@Content(mediaType="*/*",schema=@Schema(implementation=ModelConfigurationView.class))),
        @ApiResponse(responseCode="503",description="50061：服务器模型凭据保护不可用，整笔回滚；不得自动重发秘密",
            content=@Content(schema=@Schema(implementation=ApiError.class)))})
    @Operation(operationId="replaceAssistantModelConfiguration",summary="管理员保存并启用项目模型凭据",
        description="同一事务替换并验证本地解密后启用，配置版本递增两次；失败全部回滚。版本CAS，不返回凭据、不执行供应商测试。重复提交冲突后重新GET确认结果。",
        requestBody=@io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@io.swagger.v3.oas.annotations.media.Content(
            schema=@Schema(implementation=ModelCredentialInput.class))))
    public ModelConfigurationView replace(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,HttpServletRequest request,HttpServletResponse response) {
        checkQuery(request,response,Set.of()); var json=parse(request,Set.of("expectedRevision","apiKey"));
        if (!json.path("apiKey").isString()) throw invalid();
        return service.replaceAndEnable(projectId,new ModelCredentialInput(revision(json),json.path("apiKey").asString()));
    }
    /**
     * 管理员启用或停用项目模型凭据。
     *
     * @param projectId 接口指定的项目标识
     * @param request 原始 HTTP 请求，供封闭输入、头部或身份校验使用
     * @param response HTTP 响应对象，用于设置响应头或状态
     * @return 项目模型配置的脱敏状态，不包含凭据
     */
    @PatchMapping(consumes="application/json")
    @ApiResponses({
        @ApiResponse(responseCode="200",description="OK",content=@Content(mediaType="*/*",schema=@Schema(implementation=ModelConfigurationView.class))),
        @ApiResponse(responseCode="503",description="50061：启用时服务器模型凭据保护不可用；停用不依赖解密",
            content=@Content(schema=@Schema(implementation=ApiError.class)))})
    @Operation(operationId="enableAssistantModelConfiguration",summary="管理员启用或停用项目模型凭据",
        requestBody=@io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@io.swagger.v3.oas.annotations.media.Content(
            schema=@Schema(implementation=EnableRequest.class))), description = "管理员启用或停用项目模型凭据。")
    public ModelConfigurationView enable(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,HttpServletRequest request,HttpServletResponse response) {
        checkQuery(request,response,Set.of()); var json=parse(request,Set.of("expectedRevision","enabled"));
        if (!json.path("enabled").isBoolean()) throw invalid();
        return service.enable(projectId,revision(json),json.path("enabled").asBoolean());
    }
    /**
     * 管理员清除项目模型凭据并保留版本墓碑。
     *
     * @param projectId 接口指定的项目标识
     * @param expectedRevision 调用方期望的配置修订版本
     * @param request 原始 HTTP 请求，供封闭输入、头部或身份校验使用
     * @param response HTTP 响应对象，用于设置响应头或状态
     * @return 项目模型配置的脱敏状态，不包含凭据
     */
    @DeleteMapping
    @Operation(operationId="removeAssistantModelConfiguration",summary="管理员清除项目模型凭据并保留版本墓碑", description = "管理员清除项目模型凭据并保留版本墓碑。")
    public ModelConfigurationView remove(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,@io.swagger.v3.oas.annotations.Parameter(description = "调用方期望的配置修订版本") @RequestParam String expectedRevision,HttpServletRequest request,HttpServletResponse response) {
        checkQuery(request,response,Set.of("expectedRevision"));
        if (request.getParameterValues("expectedRevision").length!=1) throw invalid();
        return service.remove(projectId,expectedRevision);
    }
    private static void checkQuery(HttpServletRequest request,HttpServletResponse response,Set<String> fields) {
        response.setHeader("Cache-Control","no-store");
        if (!request.getParameterMap().keySet().equals(fields)) throw invalid();
    }
    private static JsonNode parse(HttpServletRequest request,Set<String> fields) {
        JsonNode node;
        // 不使用 @RequestBody String：MVC 转换器会在 DEBUG/TRACE 记录原始秘密。
        try { node=JSON.readTree(request.getInputStream()); } catch (IOException | RuntimeException ignored) { throw invalid(); }
        if (node==null || !node.isObject() || !Set.copyOf(node.propertyNames()).equals(fields)) throw invalid();
        return node;
    }
    private static String revision(JsonNode node) { if (!node.path("expectedRevision").isString()) throw invalid(); return node.path("expectedRevision").asString(); }
    private static BusinessException invalid() { return new BusinessException(CommonErrorCode.INVALID_PARAMETER); }
}
