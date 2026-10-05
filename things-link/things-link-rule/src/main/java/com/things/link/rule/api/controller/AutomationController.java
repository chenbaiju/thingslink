package com.things.link.rule.api.controller;
import com.things.link.rule.api.dto.request.*;
import com.things.link.rule.api.support.AutomationApiAuthorization;
import com.things.link.rule.application.*;
import com.things.link.rule.application.automation.*;
import com.things.link.rule.domain.RuleErrorCode;
import com.things.link.shared.error.BusinessException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;
/** 自动化管理入口；不提供手动执行或终态重派。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/automations")
@Tag(name="自动化",description="属性与时间自动化定义和不可变版本管理")
public class AutomationController {
    @org.springframework.beans.factory.annotation.Value("${things-link.automation.property.enabled:false}")
    private boolean enabled;
    @org.springframework.beans.factory.annotation.Value("${things-link.automation.time.enabled:false}")
    private boolean timeEnabled;
    private final AutomationManagementService service;
    private final AutomationApiAuthorization authorization;
    public AutomationController(AutomationManagementService service,AutomationApiAuthorization authorization){this.service=service;this.authorization=authorization;}
    /**
     * 查询自动化目录。
     *
     * @param projectId 接口指定的项目标识
     * @param name 名称筛选条件
     * @param status 状态筛选条件
     * @param cursor 可选分页游标，继续读取上一页后的记录
     * @param limit 分页条数，具体边界由当前接口校验
     * @return 当前接口的操作结果，响应结构见 {@code RuleManagementPage<AutomationView>}
     */
    @GetMapping @Operation(operationId="listManagedAutomations",summary="查询自动化目录", description = "查询自动化目录。")
    public RuleManagementPage<AutomationView> list(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,@io.swagger.v3.oas.annotations.Parameter(description = "名称筛选条件") @RequestParam(required=false) String name,
            @io.swagger.v3.oas.annotations.Parameter(description = "状态筛选条件") @RequestParam(required=false) String status,@io.swagger.v3.oas.annotations.Parameter(description = "可选分页游标，继续读取上一页后的记录") @RequestParam(required=false) String cursor,@io.swagger.v3.oas.annotations.Parameter(description = "分页条数，具体边界由当前接口校验") @RequestParam(defaultValue="20") int limit){
        authorization.manage(projectId);return service.list(projectId,name,status,cursor,limit);}
    /**
     * 查询自动化。
     *
     * @param projectId 接口指定的项目标识
     * @param id 自动化标识
     * @return 当前接口的操作结果，响应结构见 {@code AutomationView}
     */
    @GetMapping("/{id}") @Operation(operationId="getManagedAutomation",summary="查询自动化", description = "查询自动化。")
    public AutomationView get(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,@io.swagger.v3.oas.annotations.Parameter(description = "自动化标识") @PathVariable UUID id){authorization.manage(projectId);return service.overview(projectId,id);}
    /**
     * 查询自动化版本目录。
     *
     * @param projectId 接口指定的项目标识
     * @param id 自动化标识
     * @param cursor 可选分页游标，继续读取上一页后的记录
     * @param limit 分页条数，具体边界由当前接口校验
     * @return 当前接口的操作结果，响应结构见 {@code RuleManagementPage<AutomationVersionView>}
     */
    @GetMapping("/{id}/version-history") @Operation(operationId="pageManagedAutomationVersions",summary="查询自动化版本目录", description = "查询自动化版本目录。")
    public RuleManagementPage<AutomationVersionView> history(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,@io.swagger.v3.oas.annotations.Parameter(description = "自动化标识") @PathVariable UUID id,
            @io.swagger.v3.oas.annotations.Parameter(description = "可选分页游标，继续读取上一页后的记录") @RequestParam(required=false) String cursor,@io.swagger.v3.oas.annotations.Parameter(description = "分页条数，具体边界由当前接口校验") @RequestParam(defaultValue="20") int limit){authorization.manage(projectId);return service.history(projectId,id,cursor,limit);}
    /**
     * 查询自动化版本。
     *
     * @param projectId 接口指定的项目标识
     * @param id 自动化标识
     * @param versionId 当前资源的不可变版本标识
     * @return 当前接口的操作结果，响应结构见 {@code AutomationVersionView}
     */
    @GetMapping("/{id}/versions/{versionId}") @Operation(operationId="getManagedAutomationVersion",summary="查询自动化版本", description = "查询自动化版本。")
    public AutomationVersionView version(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,@io.swagger.v3.oas.annotations.Parameter(description = "自动化标识") @PathVariable UUID id,@io.swagger.v3.oas.annotations.Parameter(description = "当前资源的不可变版本标识") @PathVariable UUID versionId){authorization.manage(projectId);return service.versionView(projectId,id,versionId);}
    /**
     * 创建自动化。
     *
     * @param projectId 接口指定的项目标识
     * @param r 本次操作的请求数据，结构见 {@code CreateAutomationRequest}
     * @return 当前接口的操作结果，响应结构见 {@code AutomationView}
     */
    @PostMapping @ResponseStatus(HttpStatus.CREATED) @Operation(operationId="createManagedAutomation",summary="创建自动化", description = "创建自动化。")
    public AutomationView create(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,@Valid @RequestBody CreateAutomationRequest r){authorization.manage(projectId);
        var d=service.create(projectId,new AutomationManagementService.Edit(r.name(),r.description(),r.triggerType(),r.triggerConfig(),conditions(r.conditions()),actions(r.actions())));return service.overview(projectId,d.id());}
    /**
     * 追加自动化版本。
     *
     * @param projectId 接口指定的项目标识
     * @param id 自动化标识
     * @param r 本次操作的请求数据，结构见 {@code ReviseAutomationRequest}
     * @return 当前接口的操作结果，响应结构见 {@code AutomationView}
     */
    @PutMapping("/{id}") @Operation(operationId="reviseManagedAutomation",summary="追加自动化版本", description = "追加自动化版本。")
    public AutomationView revise(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,@io.swagger.v3.oas.annotations.Parameter(description = "自动化标识") @PathVariable UUID id,@Valid @RequestBody ReviseAutomationRequest r){authorization.manage(projectId);
        service.revise(projectId,id,r.expectedVersion(),new AutomationManagementService.Edit(r.name(),r.description(),r.triggerType(),r.triggerConfig(),conditions(r.conditions()),actions(r.actions())));return service.overview(projectId,id);}
    /**
     * 发布自动化版本。
     *
     * @param projectId 接口指定的项目标识
     * @param id 自动化标识
     * @param versionId 当前资源的不可变版本标识
     * @param expectedVersion 调用方期望的资源版本，用于并发更新校验
     * @return 当前接口的操作结果，响应结构见 {@code AutomationView}
     */
    @PostMapping("/{id}/versions/{versionId}/activate") @Operation(operationId="activateManagedAutomationVersion",summary="发布自动化版本", description = "发布自动化版本。")
    public AutomationView activate(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,@io.swagger.v3.oas.annotations.Parameter(description = "自动化标识") @PathVariable UUID id,@io.swagger.v3.oas.annotations.Parameter(description = "当前资源的不可变版本标识") @PathVariable UUID versionId,@io.swagger.v3.oas.annotations.Parameter(description = "调用方期望的资源版本，用于并发更新校验") @RequestParam long expectedVersion){authorization.manage(projectId);var v=service.getVersion(projectId,id,versionId);if("PROPERTY_REPORTED".equals(v.triggerType())?!enabled:!timeEnabled)throw new BusinessException(RuleErrorCode.AUTOMATION_NOT_ENABLED);service.activate(projectId,id,versionId,expectedVersion);return service.overview(projectId,id);}
    /**
     * 暂停自动化。
     *
     * @param projectId 接口指定的项目标识
     * @param id 自动化标识
     * @param expectedVersion 调用方期望的资源版本，用于并发更新校验
     * @return 当前接口的操作结果，响应结构见 {@code AutomationView}
     */
    @PostMapping("/{id}/pause") @Operation(operationId="pauseManagedAutomation",summary="暂停自动化", description = "暂停自动化。")
    public AutomationView pause(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,@io.swagger.v3.oas.annotations.Parameter(description = "自动化标识") @PathVariable UUID id,@io.swagger.v3.oas.annotations.Parameter(description = "调用方期望的资源版本，用于并发更新校验") @RequestParam long expectedVersion){authorization.manage(projectId);service.pause(projectId,id,expectedVersion);return service.overview(projectId,id);}
    /**
     * 删除自动化。
     *
     * @param projectId 接口指定的项目标识
     * @param id 自动化标识
     * @param expectedVersion 调用方期望的资源版本，用于并发更新校验
     */
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) @Operation(operationId="deleteManagedAutomation",summary="删除自动化", description = "删除自动化。")
    public void delete(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,@io.swagger.v3.oas.annotations.Parameter(description = "自动化标识") @PathVariable UUID id,@io.swagger.v3.oas.annotations.Parameter(description = "调用方期望的资源版本，用于并发更新校验") @RequestParam long expectedVersion){authorization.manage(projectId);service.delete(projectId,id,expectedVersion);}
    private static List<ConditionSpec> conditions(List<AutomationNodeRequest> rows){return rows==null?List.of():rows.stream().map(r->{if(r==null)throw new BusinessException(RuleErrorCode.AUTOMATION_INVALID);return new ConditionSpec(r.nodeType(),r.config());}).toList();}
    private static List<ActionSpec> actions(List<AutomationNodeRequest> rows){return rows==null?List.of():rows.stream().map(r->{if(r==null)throw new BusinessException(RuleErrorCode.AUTOMATION_INVALID);return new ActionSpec(r.nodeType(),r.config());}).toList();}
}
