package com.things.link.alarm.api.controller;

import com.things.link.alarm.api.dto.request.SaveAlarmRuleRequest;
import com.things.link.alarm.api.dto.response.AlarmRuleResponse;
import com.things.link.alarm.api.support.AlarmApiAuthorization;
import com.things.link.alarm.application.AlarmRuleCommand;
import com.things.link.alarm.application.AlarmRuleService;
import com.things.link.shared.page.CursorPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 项目告警规则配置 API；服务层仍重复角色校验，HTTP 仅是第一层。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/alarm-rules")
@Tag(name = "告警规则", description = "固定数值阈值规则与显式恢复条件")
public class AlarmRuleController {
    /** 规则应用服务。 */ private final AlarmRuleService service;
    /** HTTP 第一层项目授权。 */ private final AlarmApiAuthorization authorization;
    /** @param service 规则用例 @param authorization HTTP 授权守卫 */
    public AlarmRuleController(AlarmRuleService service, AlarmApiAuthorization authorization) { this.service = service; this.authorization = authorization; }
    /**
     * 创建规则。
     *
     * @param projectId 接口指定的项目标识
     * @param request 本次操作的请求数据，结构见 {@code SaveAlarmRuleRequest}
     * @return 当前接口的操作结果，响应结构见 {@code AlarmRuleResponse}
     */
    @PostMapping @ResponseStatus(HttpStatus.CREATED) @Operation(summary = "创建告警规则", description = "创建规则。")
    public AlarmRuleResponse create(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId, @Valid @RequestBody SaveAlarmRuleRequest request) {
        authorization.requireRuleManage(projectId);
        return AlarmRuleResponse.from(service.create(projectId, command(request)));
    }
    /**
     * 获取规则。
     *
     * @param projectId 接口指定的项目标识
     * @param ruleId 规则标识
     * @return 当前接口的操作结果，响应结构见 {@code AlarmRuleResponse}
     */
    @GetMapping("/{ruleId}") @Operation(summary = "查询告警规则", description = "获取规则。")
    public AlarmRuleResponse get(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId, @io.swagger.v3.oas.annotations.Parameter(description = "规则标识") @PathVariable UUID ruleId) { authorization.requireRead(projectId); return AlarmRuleResponse.from(service.get(projectId, ruleId)); }
    /**
     * 分页列出规则。
     *
     * @param projectId 接口指定的项目标识
     * @param cursor 可选分页游标，继续读取上一页后的记录
     * @param limit 分页条数，具体边界由当前接口校验
     * @return 符合条件的记录页及后续分页游标
     */
    @GetMapping @Operation(summary = "分页查询告警规则", description = "分页列出规则。")
    public ResponseEntity<CursorPage<AlarmRuleResponse>> page(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,
            @io.swagger.v3.oas.annotations.Parameter(description = "可选分页游标，继续读取上一页后的记录") @RequestParam(required = false) String cursor, @io.swagger.v3.oas.annotations.Parameter(description = "分页条数，具体边界由当前接口校验") @RequestParam(defaultValue = "20") int limit) {
        authorization.requireRead(projectId); return ResponseEntity.ok(service.page(projectId, cursor, limit).map(AlarmRuleResponse::from));
    }
    /**
     * 修改规则。
     *
     * @param projectId 接口指定的项目标识
     * @param ruleId 规则标识
     * @param request 本次操作的请求数据，结构见 {@code SaveAlarmRuleRequest}
     * @return 当前接口的操作结果，响应结构见 {@code AlarmRuleResponse}
     */
    @PutMapping("/{ruleId}") @Operation(summary = "修改告警规则", description = "修改规则。")
    public AlarmRuleResponse update(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId, @io.swagger.v3.oas.annotations.Parameter(description = "规则标识") @PathVariable UUID ruleId,
                                    @Valid @RequestBody SaveAlarmRuleRequest request) {
        authorization.requireRuleManage(projectId); return AlarmRuleResponse.from(service.update(projectId, ruleId, command(request)));
    }
    /**
     * 软删除规则。
     *
     * @param projectId 接口指定的项目标识
     * @param ruleId 规则标识
     * @param version 调用方期望的资源版本，用于并发更新校验
     */
    @DeleteMapping("/{ruleId}") @ResponseStatus(HttpStatus.NO_CONTENT) @Operation(summary = "删除告警规则", description = "软删除规则。")
    public void delete(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId, @io.swagger.v3.oas.annotations.Parameter(description = "规则标识") @PathVariable UUID ruleId, @io.swagger.v3.oas.annotations.Parameter(description = "调用方期望的资源版本，用于并发更新校验") @RequestParam int version) { authorization.requireRuleManage(projectId); service.delete(projectId, ruleId, version); }
    /** HTTP DTO 到 application 命令的唯一映射点。 */
    private static AlarmRuleCommand command(SaveAlarmRuleRequest value) { return new AlarmRuleCommand(value.name(), value.alarmType(),
            value.deviceId(), value.propertyKey(), value.triggerOperator(), value.triggerThreshold(), value.triggerDurationSeconds(),
            value.clearOperator(), value.clearThreshold(), value.clearDurationSeconds(), value.severity(), value.enabled(), value.version()); }
}
