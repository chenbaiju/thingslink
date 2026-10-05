package com.things.link.alarm.api.controller;

import com.things.link.alarm.api.dto.request.AlarmStateMutationRequest;
import com.things.link.alarm.api.dto.response.AlarmEventResponse;
import com.things.link.alarm.api.dto.response.AlarmInstanceResponse;
import com.things.link.alarm.api.support.AlarmApiAuthorization;
import com.things.link.alarm.application.AlarmInstanceService;
import com.things.link.shared.page.CursorPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 项目告警实例历史与人工维护 API。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/alarms")
@Tag(name = "告警实例", description = "事故历史、不可变迁移事件、确认和人工清除")
public class AlarmInstanceController {
    /** 实例应用服务。 */ private final AlarmInstanceService service;
    /** HTTP 第一层项目授权。 */ private final AlarmApiAuthorization authorization;
    /** @param service 实例用例 @param authorization HTTP 授权守卫 */
    public AlarmInstanceController(AlarmInstanceService service, AlarmApiAuthorization authorization) { this.service = service; this.authorization = authorization; }
    /**
     * 分页查询项目事故历史。
     *
     * @param projectId 接口指定的项目标识
     * @param cursor 可选分页游标，继续读取上一页后的记录
     * @param limit 分页条数，具体边界由当前接口校验
     * @return 符合条件的记录页及后续分页游标
     */
    @GetMapping @Operation(summary = "分页查询告警实例", description = "分页查询项目事故历史。")
    public ResponseEntity<CursorPage<AlarmInstanceResponse>> page(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId,
            @io.swagger.v3.oas.annotations.Parameter(description = "可选分页游标，继续读取上一页后的记录") @RequestParam(required = false) String cursor, @io.swagger.v3.oas.annotations.Parameter(description = "分页条数，具体边界由当前接口校验") @RequestParam(defaultValue = "20") int limit) {
        authorization.requireRead(projectId); return ResponseEntity.ok(service.page(projectId, cursor, limit).map(AlarmInstanceResponse::from));
    }
    /**
     * 查询单个事故。
     *
     * @param projectId 接口指定的项目标识
     * @param instanceId 告警实例标识
     * @return 当前接口的操作结果，响应结构见 {@code AlarmInstanceResponse}
     */
    @GetMapping("/{instanceId}") @Operation(summary = "查询告警实例", description = "查询单个事故。")
    public AlarmInstanceResponse get(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId, @io.swagger.v3.oas.annotations.Parameter(description = "告警实例标识") @PathVariable UUID instanceId) {
        authorization.requireRead(projectId); return AlarmInstanceResponse.from(service.get(projectId, instanceId));
    }
    /**
     * 查询不可变状态迁移事件。
     *
     * @param projectId 接口指定的项目标识
     * @param instanceId 告警实例标识
     * @param cursor 可选分页游标，继续读取上一页后的记录
     * @param limit 分页条数，具体边界由当前接口校验
     * @return 符合条件的记录页及后续分页游标
     */
    @GetMapping("/{instanceId}/events") @Operation(summary = "分页查询告警事件", description = "查询不可变状态迁移事件。")
    public ResponseEntity<CursorPage<AlarmEventResponse>> events(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId, @io.swagger.v3.oas.annotations.Parameter(description = "告警实例标识") @PathVariable UUID instanceId,
            @io.swagger.v3.oas.annotations.Parameter(description = "可选分页游标，继续读取上一页后的记录") @RequestParam(required = false) String cursor, @io.swagger.v3.oas.annotations.Parameter(description = "分页条数，具体边界由当前接口校验") @RequestParam(defaultValue = "20") int limit) {
        authorization.requireRead(projectId); return ResponseEntity.ok(service.pageEvents(projectId, instanceId, cursor, limit).map(AlarmEventResponse::from));
    }
    /**
     * 确认事故，不改变其是否仍然活动的条件状态。
     *
     * @param projectId 接口指定的项目标识
     * @param instanceId 告警实例标识
     * @param request 本次操作的请求数据，结构见 {@code AlarmStateMutationRequest}
     * @return 当前接口的操作结果，响应结构见 {@code AlarmInstanceResponse}
     */
    @PostMapping("/{instanceId}/ack") @Operation(summary = "确认告警实例", description = "确认事故，不改变其是否仍然活动的条件状态。")
    public AlarmInstanceResponse acknowledge(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId, @io.swagger.v3.oas.annotations.Parameter(description = "告警实例标识") @PathVariable UUID instanceId,
            @Valid @RequestBody AlarmStateMutationRequest request) {
        authorization.requireMaintain(projectId); return AlarmInstanceResponse.from(service.acknowledge(projectId, instanceId, request.version()));
    }
    /**
     * 人工清除仍活动事故，不自动标记为已确认。
     *
     * @param projectId 接口指定的项目标识
     * @param instanceId 告警实例标识
     * @param request 本次操作的请求数据，结构见 {@code AlarmStateMutationRequest}
     * @return 当前接口的操作结果，响应结构见 {@code AlarmInstanceResponse}
     */
    @PostMapping("/{instanceId}/clear") @Operation(summary = "人工清除告警实例", description = "人工清除仍活动事故，不自动标记为已确认。")
    public AlarmInstanceResponse clear(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId, @io.swagger.v3.oas.annotations.Parameter(description = "告警实例标识") @PathVariable UUID instanceId,
            @Valid @RequestBody AlarmStateMutationRequest request) {
        authorization.requireMaintain(projectId); return AlarmInstanceResponse.from(service.clear(projectId, instanceId, request.version()));
    }
}
