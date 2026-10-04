package com.things.link.rule.api.controller;
import com.things.link.rule.application.RuleManagementPage;
import com.things.link.rule.application.automation.*;
import com.things.link.rule.api.support.RuleExecutionApiAuthorization;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.Instant;
import java.util.UUID;
/** 自动化日志只读入口；无重试、手动执行、配置或私有输入出口。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/automation-executions")
@Tag(name="自动化运行",description="自动化运行与投递状态摘要")
public class AutomationExecutionController {
    private final AutomationExecutionQueryService service;
    private final RuleExecutionApiAuthorization authorization;
    public AutomationExecutionController(AutomationExecutionQueryService service,RuleExecutionApiAuthorization authorization){this.service=service;this.authorization=authorization;}
    @GetMapping @Operation(operationId="listAutomationExecutions",summary="查询自动化执行记录")
    public RuleManagementPage<AutomationExecutionView> list(@PathVariable UUID projectId,@RequestParam(required=false) UUID automationId,
            @RequestParam(required=false) String status,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant to,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit){
        authorization.requireProjectRead(projectId);return service.list(projectId,automationId,status,from,to,cursor,limit);}
    @GetMapping("/{executionId}") @Operation(operationId="getAutomationExecution",summary="查询自动化执行详情")
    public AutomationExecutionDetailView detail(@PathVariable UUID projectId,@PathVariable UUID executionId){authorization.requireProjectRead(projectId);return service.detail(projectId,executionId);}
}
