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
    /** 分页查询项目事故历史。 */
    @GetMapping @Operation(summary = "分页查询告警实例")
    public ResponseEntity<CursorPage<AlarmInstanceResponse>> page(@PathVariable UUID projectId,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
        authorization.requireRead(projectId); return ResponseEntity.ok(service.page(projectId, cursor, limit).map(AlarmInstanceResponse::from));
    }
    /** 查询单个事故。 */
    @GetMapping("/{instanceId}") @Operation(summary = "查询告警实例")
    public AlarmInstanceResponse get(@PathVariable UUID projectId, @PathVariable UUID instanceId) {
        authorization.requireRead(projectId); return AlarmInstanceResponse.from(service.get(projectId, instanceId));
    }
    /** 查询不可变状态迁移事件。 */
    @GetMapping("/{instanceId}/events") @Operation(summary = "分页查询告警事件")
    public ResponseEntity<CursorPage<AlarmEventResponse>> events(@PathVariable UUID projectId, @PathVariable UUID instanceId,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
        authorization.requireRead(projectId); return ResponseEntity.ok(service.pageEvents(projectId, instanceId, cursor, limit).map(AlarmEventResponse::from));
    }
    /** 确认事故，不改变其是否仍然活动的条件状态。 */
    @PostMapping("/{instanceId}/ack") @Operation(summary = "确认告警实例")
    public AlarmInstanceResponse acknowledge(@PathVariable UUID projectId, @PathVariable UUID instanceId,
            @Valid @RequestBody AlarmStateMutationRequest request) {
        authorization.requireMaintain(projectId); return AlarmInstanceResponse.from(service.acknowledge(projectId, instanceId, request.version()));
    }
    /** 人工清除仍活动事故，不自动标记为已确认。 */
    @PostMapping("/{instanceId}/clear") @Operation(summary = "人工清除告警实例")
    public AlarmInstanceResponse clear(@PathVariable UUID projectId, @PathVariable UUID instanceId,
            @Valid @RequestBody AlarmStateMutationRequest request) {
        authorization.requireMaintain(projectId); return AlarmInstanceResponse.from(service.clear(projectId, instanceId, request.version()));
    }
}
