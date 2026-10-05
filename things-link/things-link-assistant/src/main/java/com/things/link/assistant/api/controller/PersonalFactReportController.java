package com.things.link.assistant.api.controller;

import com.things.link.assistant.application.PersonalFactReport;
import com.things.link.assistant.application.PersonalFactReportService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

/** 本人记录的报告视图入口；没有持久写入、模型发送、外部下载或任意模板。 */
@RestController
@Tag(name="Agent 个人报告",description="手动汇编本人历史事实，不提供模型诊断")
@SecurityRequirement(name="consoleAccessBearer")
public class PersonalFactReportController {
    private final PersonalFactReportService service;
    public PersonalFactReportController(PersonalFactReportService service) { this.service=service; }

    /**
     * 明确请求后生成本人历史事实报告；当前四角色可用，来源删除、到期或撤权即拒绝。
     * @param projectId 当前选择且仍有权的项目
     * @param recordId 本人来源记录，不能读取其他成员的记录
     * @param request 原始请求，不允许任何查询参数或自报报告正文
     * @return 带原来源摘要及报告摘要的确定性纯文本，禁止缓存；无持久写入或付费副作用
     */
    @GetMapping("/api/v1/projects/{projectId}/assistant/evidence-records/{recordId}/fact-report")
    @Operation(operationId="generatePersonalFactReport",summary="手动生成本人历史事实报告",
            description="仅汇编当前有权且未到期的本人历史记录，返回前独立复核权限与来源；固定事实格式、无查询参数、no-store，不调用模型或设备，不把历史事实判为当前健康。")
    public ResponseEntity<PersonalFactReport> generate(
            @Parameter(description="当前项目标识，不能跨项目读取") @PathVariable UUID projectId,
            @Parameter(description="本人且未到期的历史记录标识") @PathVariable UUID recordId,
            HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.generate(projectId,recordId));
    }
}
