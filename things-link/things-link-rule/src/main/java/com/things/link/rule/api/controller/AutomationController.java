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
    @GetMapping @Operation(operationId="listManagedAutomations",summary="查询自动化目录")
    public RuleManagementPage<AutomationView> list(@PathVariable UUID projectId,@RequestParam(required=false) String name,
            @RequestParam(required=false) String status,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit){
        authorization.manage(projectId);return service.list(projectId,name,status,cursor,limit);}
    @GetMapping("/{id}") @Operation(operationId="getManagedAutomation",summary="查询自动化")
    public AutomationView get(@PathVariable UUID projectId,@PathVariable UUID id){authorization.manage(projectId);return service.overview(projectId,id);}
    @GetMapping("/{id}/version-history") @Operation(operationId="pageManagedAutomationVersions",summary="查询自动化版本目录")
    public RuleManagementPage<AutomationVersionView> history(@PathVariable UUID projectId,@PathVariable UUID id,
            @RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit){authorization.manage(projectId);return service.history(projectId,id,cursor,limit);}
    @GetMapping("/{id}/versions/{versionId}") @Operation(operationId="getManagedAutomationVersion",summary="查询自动化版本")
    public AutomationVersionView version(@PathVariable UUID projectId,@PathVariable UUID id,@PathVariable UUID versionId){authorization.manage(projectId);return service.versionView(projectId,id,versionId);}
    @PostMapping @ResponseStatus(HttpStatus.CREATED) @Operation(operationId="createManagedAutomation",summary="创建自动化")
    public AutomationView create(@PathVariable UUID projectId,@Valid @RequestBody CreateAutomationRequest r){authorization.manage(projectId);
        var d=service.create(projectId,new AutomationManagementService.Edit(r.name(),r.description(),r.triggerType(),r.triggerConfig(),conditions(r.conditions()),actions(r.actions())));return service.overview(projectId,d.id());}
    @PutMapping("/{id}") @Operation(operationId="reviseManagedAutomation",summary="追加自动化版本")
    public AutomationView revise(@PathVariable UUID projectId,@PathVariable UUID id,@Valid @RequestBody ReviseAutomationRequest r){authorization.manage(projectId);
        service.revise(projectId,id,r.expectedVersion(),new AutomationManagementService.Edit(r.name(),r.description(),r.triggerType(),r.triggerConfig(),conditions(r.conditions()),actions(r.actions())));return service.overview(projectId,id);}
    @PostMapping("/{id}/versions/{versionId}/activate") @Operation(operationId="activateManagedAutomationVersion",summary="发布自动化版本")
    public AutomationView activate(@PathVariable UUID projectId,@PathVariable UUID id,@PathVariable UUID versionId,@RequestParam long expectedVersion){authorization.manage(projectId);var v=service.getVersion(projectId,id,versionId);if("PROPERTY_REPORTED".equals(v.triggerType())?!enabled:!timeEnabled)throw new BusinessException(RuleErrorCode.AUTOMATION_NOT_ENABLED);service.activate(projectId,id,versionId,expectedVersion);return service.overview(projectId,id);}
    @PostMapping("/{id}/pause") @Operation(operationId="pauseManagedAutomation",summary="暂停自动化")
    public AutomationView pause(@PathVariable UUID projectId,@PathVariable UUID id,@RequestParam long expectedVersion){authorization.manage(projectId);service.pause(projectId,id,expectedVersion);return service.overview(projectId,id);}
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) @Operation(operationId="deleteManagedAutomation",summary="删除自动化")
    public void delete(@PathVariable UUID projectId,@PathVariable UUID id,@RequestParam long expectedVersion){authorization.manage(projectId);service.delete(projectId,id,expectedVersion);}
    private static List<ConditionSpec> conditions(List<AutomationNodeRequest> rows){return rows==null?List.of():rows.stream().map(r->{if(r==null)throw new BusinessException(RuleErrorCode.AUTOMATION_INVALID);return new ConditionSpec(r.nodeType(),r.config());}).toList();}
    private static List<ActionSpec> actions(List<AutomationNodeRequest> rows){return rows==null?List.of():rows.stream().map(r->{if(r==null)throw new BusinessException(RuleErrorCode.AUTOMATION_INVALID);return new ActionSpec(r.nodeType(),r.config());}).toList();}
}
