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
    /**
     * 查询自动化执行记录。
     *
     * @param projectId 接口指定的项目标识
     * @param automationId 自动化标识
     * @param status 状态筛选条件
     * @param from 查询时间区间起点
     * @param to 查询时间区间终点
     * @param cursor 可选分页游标，继续读取上一页后的记录
     * @param limit 分页条数，具体边界由当前接口校验
     * @return 当前接口的操作结果，响应结构见 {@code RuleManagementPage<AutomationExecutionView>}
     */
    @GetMapping @Operation(operationId="listAutomationExecutions",summary="查询自动化执行记录", description = "查询自动化执行记录。")
    public RuleManagementPage<AutomationExecutionView> list(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,@io.swagger.v3.oas.annotations.Parameter(description = "自动化标识") @RequestParam(required=false) UUID automationId,
            @io.swagger.v3.oas.annotations.Parameter(description = "状态筛选条件") @RequestParam(required=false) String status,@io.swagger.v3.oas.annotations.Parameter(description = "查询时间区间起点") @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant from,
            @io.swagger.v3.oas.annotations.Parameter(description = "查询时间区间终点") @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant to,@io.swagger.v3.oas.annotations.Parameter(description = "可选分页游标，继续读取上一页后的记录") @RequestParam(required=false) String cursor,@io.swagger.v3.oas.annotations.Parameter(description = "分页条数，具体边界由当前接口校验") @RequestParam(defaultValue="20") int limit){
        authorization.requireProjectRead(projectId);return service.list(projectId,automationId,status,from,to,cursor,limit);}
    /**
     * 查询自动化执行详情。
     *
     * @param projectId 接口指定的项目标识
     * @param executionId 执行记录标识
     * @return 当前接口的操作结果，响应结构见 {@code AutomationExecutionDetailView}
     */
    @GetMapping("/{executionId}") @Operation(operationId="getAutomationExecution",summary="查询自动化执行详情", description = "查询自动化执行详情。")
    public AutomationExecutionDetailView detail(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,@io.swagger.v3.oas.annotations.Parameter(description = "执行记录标识") @PathVariable UUID executionId){authorization.requireProjectRead(projectId);return service.detail(projectId,executionId);}
}
