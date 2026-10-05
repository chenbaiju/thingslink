package com.things.link.assistant.api.controller;

import com.things.link.assistant.application.ProbeRunService;
import com.things.link.shared.error.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** 固定合成探针入口；拒绝正文及查询参数，调用资格与持久次数由应用服务重新核验。 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "模型探针", description = "冻结授权范围内的单次合成计数验证")
@RestController
@RequestMapping("/api/v1/projects/{projectId}/assistant/model-probes")
@SecurityRequirement(name="consoleAccessBearer")
public class ModelProbeController {
    private final ProbeRunService service;
    public ModelProbeController(ProbeRunService s){service=s;}
    /**
     * 执行已冻结授权的单次合成模型计数探针。
     * 仅OWNER/ADMIN/OPERATOR，固定1至3样本，无正文及查询；每批每样本持久至多一次，禁止自动重试。仅返回usage摘要，不授予业务模型资格。默认无服务端授权时关闭。
     *
     * @param projectId 接口指定的项目标识
     * @param sampleIndex 固定合成样本序号文本，仅允许 1 至 3
     * @param request 原始 HTTP 请求，供封闭输入、头部或身份校验使用
     * @param response HTTP 响应对象，用于设置响应头或状态
     * @return 持久探针终态及经校验的用量元数据，不含模型正文
     */
    @PostMapping("/{sampleIndex}")
    @Operation(operationId="runAssistantSyntheticModelProbe",summary="执行已冻结授权的单次合成模型计数探针",
        description="仅OWNER/ADMIN/OPERATOR，固定1至3样本，无正文及查询；每批每样本持久至多一次，禁止自动重试。仅返回usage摘要，不授予业务模型资格。默认无服务端授权时关闭。")
    public ProbeRunService.View run(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,@PathVariable @io.swagger.v3.oas.annotations.Parameter(schema=@io.swagger.v3.oas.annotations.media.Schema(type="string",pattern="[1-3]"), description = "固定合成样本序号文本，仅允许 1 至 3") String sampleIndex,HttpServletRequest request,HttpServletResponse response){
        response.setHeader("Cache-Control","no-store");
        if(!request.getParameterMap().isEmpty())throw invalid();
        try{if(request.getInputStream().read()!=-1)throw invalid();}catch(IOException ignored){throw invalid();}
        if(!sampleIndex.matches("[1-3]"))throw invalid();
        return service.run(projectId,Integer.parseInt(sampleIndex));
    }
    private static BusinessException invalid(){return new BusinessException(CommonErrorCode.INVALID_PARAMETER);}
}
