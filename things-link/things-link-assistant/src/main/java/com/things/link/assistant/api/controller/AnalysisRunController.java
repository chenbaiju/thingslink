package com.things.link.assistant.api.controller;

import com.things.link.assistant.application.*;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 当前项目付费角色的固定分析入口；领域台账防重，未准入模型正文永不返回。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/assistant/analysis-runs")
@Tag(name="Agent 模型分析",description="受权固定调用状态与领域防重；当前模型业务未准入")
@SecurityRequirement(name="consoleAccessBearer")
@ApiResponses({@ApiResponse(responseCode="400",description="封闭输入或幂等键无效"),
        @ApiResponse(responseCode="401",description="Console身份失效"),
        @ApiResponse(responseCode="403",description="当前角色无付费分析权限"),
        @ApiResponse(responseCode="404",description="项目、设备或本人调用不可见"),
        @ApiResponse(responseCode="409",description="请求身份、设备模型或配置变化"),
        @ApiResponse(responseCode="429",description="共享并发或请求限额已满")})
public class AnalysisRunController {
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final AnalysisRunService runs;
    private final AnalysisCallService calls;
    public AnalysisRunController(AnalysisRunService runs, AnalysisCallService calls) { this.runs=runs;this.calls=calls; }

    /**
     * 读取当前项目的模型业务状态，不读取密钥、不联网，内部装配不代表业务放行。
     * @param projectId 当前身份所选项目，仅OWNER、ADMIN和OPERATOR可读
     * @param request 原始请求，不允许查询参数
     * @param response 响应对象，用于禁止缓存
     * @return 封闭可用状态及原因，不含内部地址、配置版本或项目密钥信息
     */
    @GetMapping("/status")
    @Operation(operationId="getAssistantAnalysisAvailability",summary="读取项目模型分析可用状态",description="只允许当前项目付费角色；固定审查批准和项目启用均有效才可发起。状态不保证供应商在线或余额，提交仍重新确权，不解密或联网。")
    public AnalysisRunService.Availability status(@Parameter(description="当前项目标识") @PathVariable UUID projectId,
            HttpServletRequest request,HttpServletResponse response) {
        checkQuery(request,response);return runs.availability(projectId);
    }

    /**
     * 提交一次固定分析请求；同键只重读本人当前有权的元数据，不缓存结果或重复发送。
     * @param projectId 当前身份所选项目，仅OWNER、ADMIN和OPERATOR可调用
     * @param request 请求正文上限4KiB，仅设备、物模型版本、属性键和固定模板；单个UUIDv7幂等头必填
     * @param response 响应对象，用于禁止缓存；响应成功不表示模型分析成功
     * @return 调用元数据、类别与可空结果；只有单次受权释放成功才有正文，关闭/未准入/未知/重放均无正文
     */
    @PostMapping(consumes="application/json")
    @Operation(operationId="runAssistantAnalysis",summary="提交单设备固定分析调用",
            description="单个规范UUIDv7 Idempotency-Key必填，24小时保护；同键重新确权仅返回元数据，改变请求409。原60秒不续期，无自动重试；仅首次持有受信单次凭证并最终确权可返回SUCCEEDED与result。未知、未准入和重放的result为空。生产签发链仍关闭，关闭通道不创建调用。",
            parameters=@Parameter(name="Idempotency-Key",in=ParameterIn.HEADER,required=true,
                    description="规范小写UUIDv7调用键；不是项目模型密钥，过期不能复用",schema=@Schema(type="string",format="uuid")),
            requestBody=@io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,
                    content=@Content(schema=@Schema(implementation=AnalysisRequest.class))))
    public AnalysisRunService.View run(@Parameter(description="当前项目标识") @PathVariable UUID projectId,
            HttpServletRequest request,HttpServletResponse response) {
        checkQuery(request,response);
        var keys=Collections.list(request.getHeaders("Idempotency-Key"));
        if(keys.size()!=1) throw invalid();
        return runs.run(projectId,keys.getFirst(),parse(request));
    }

    /**
     * 重新确权后读取本人调用元数据；不恢复模型正文，不提供重试、取消或释放槽的能力。
     * @param projectId 当前身份所选项目，仅付费角色可读
     * @param callId 当前用户创建且仍在保留范围内的调用标识
     * @param request 原始请求，不允许查询参数
     * @param response 响应对象，用于禁止缓存
     * @return 当前持久状态，过期未完成调用按原合同收敛为未知
     */
    @GetMapping("/{callId}")
    @Operation(operationId="getAssistantAnalysisCall",summary="读取本人分析调用状态",description="当前项目付费角色且本人创建，读取前重新验证设备与当前权限；不包含正文，不会重发或释放执行槽。")
    public AnalysisCallView read(@Parameter(description="当前项目标识") @PathVariable UUID projectId,
            @Parameter(description="本人调用标识") @PathVariable UUID callId,HttpServletRequest request,HttpServletResponse response) {
        checkQuery(request,response);return calls.read(projectId,callId);
    }

    /**
     * 原提交响应丢失时按原键只读定位本人调用，不创建记录或触发分析。
     * @param projectId 当前身份所选项目，仅当前付费分析角色可读
     * @param request 仅接受单个规范UUIDv7幂等请求头，不允许查询参数
     * @param response 用于设置禁止缓存，失败也不得缓存
     * @return 本人且当前有权的最小调用元数据；未找到不代表未消费，不返回正文或凭据
     */
    @GetMapping("/by-key")
    @Operation(operationId="getAssistantAnalysisCallByKey",summary="按原幂等键读取本人分析调用",
            description="仅只读恢复元数据，不创建调用或执行；单个原UUIDv7幂等头必填，原24小时期限不续期。404不证明原请求未受理或没有消费，不能自动重发。",
            parameters=@Parameter(name="Idempotency-Key",in=ParameterIn.HEADER,required=true,
                    description="原调用的规范小写UUIDv7幂等键，不是调用ID或模型密钥",schema=@Schema(type="string",format="uuid")))
    public AnalysisCallView readByKey(@Parameter(description="当前项目标识") @PathVariable UUID projectId,
            HttpServletRequest request,HttpServletResponse response) {
        checkQuery(request,response);
        var keys=Collections.list(request.getHeaders("Idempotency-Key"));
        if(keys.size()!=1) throw invalid();
        return calls.readByKey(projectId,keys.getFirst());
    }

    /** 关闭查询参数扩展及缓存，错误也不能缓存项目调用状态。 */
    private static void checkQuery(HttpServletRequest request,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        if(!request.getParameterMap().isEmpty()) throw invalid();
    }

    /** 有界读取并手工构造封闭请求，避免转换器调试日志打印原请求。 */
    private static AnalysisRequest parse(HttpServletRequest request) {
        AnalysisRequest result=null;
        try {
            byte[] body=request.getInputStream().readNBytes(4097);
            if(body.length<1 || body.length>4096) throw invalid();
            JsonNode node=JSON.readTree(body);
            if(node==null || !node.isObject() || !Set.copyOf(node.propertyNames()).equals(
                    Set.of("deviceId","expectedModelVersionId","propertyKeys","template"))
                    || !node.path("propertyKeys").isArray()) throw invalid();
            var keys=new ArrayList<String>();
            for(var key:node.path("propertyKeys")) keys.add(text(key));
            result=new AnalysisRequest(uuid(node.path("deviceId")),uuid(node.path("expectedModelVersionId")),keys,
                    PreparedModelEvidence.Template.valueOf(text(node.path("template"))));
        } catch(Exception ignored) { /* 固定错误不携带原JSON或字段值。 */ }
        if(result==null) throw invalid();
        return result;
    }

    /** 仅接受字符串，不将数字、布尔或空值转换为业务参数。 */
    private static String text(JsonNode value) { if(!value.isString()) throw invalid();return value.asString(); }
    /** 只接受规范小写UUID，拒绝缩写和宽松解析。 */
    private static UUID uuid(JsonNode node) { String raw=text(node);UUID id=UUID.fromString(raw);if(!id.toString().equals(raw))throw invalid();return id; }
    private static BusinessException invalid() { return new BusinessException(CommonErrorCode.INVALID_PARAMETER); }
}
