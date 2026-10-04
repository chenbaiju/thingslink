package com.things.link.rule.api.controller;

import com.things.link.rule.application.query.DeviceMessageRuleQueryService;
import com.things.link.rule.api.dto.response.DeviceMessageRuleResponse;
import com.things.link.rule.api.dto.response.DeviceMessageRuleActionResponse;
import com.things.link.rule.api.dto.response.DeviceMessageRuleExecutionResponse;
import com.things.link.shared.page.CursorPage;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Set;
import java.util.UUID;

/** 设备当前目标与执行快照使用不同端点，不从定义猜测历史参与。 */
@RestController
@Tag(name="消息规则")
@SecurityRequirement(name="consoleAccessBearer")
@RequestMapping("/api/v1/projects/{projectId}/devices/{deviceId}")
public class DeviceMessageRuleQueryController {
    private final DeviceMessageRuleQueryService service;
    public DeviceMessageRuleQueryController(DeviceMessageRuleQueryService service) { this.service=service; }
    @GetMapping("/message-rule-candidates")
    @Operation(operationId="listConsoleDeviceMessageRuleCandidates",summary="分页读取可用于设备上下文的项目消息规则候选",
            description="仅OWNER/ADMIN可读；PROJECT_CANDIDATE不表示设备已绑定或脚本匹配；任意JS过滤只在运行时裁决。limit为1至50。")
    public ResponseEntity<CursorPage<DeviceMessageRuleResponse>> definitions(@PathVariable UUID projectId,@PathVariable UUID deviceId,
            @RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit,HttpServletRequest request) {
        validate(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.definitions(projectId,deviceId,cursor,limit).map(DeviceMessageRuleResponse::from));
    }
    @GetMapping("/message-rule-executions")
    @Operation(operationId="listConsoleDeviceMessageRuleExecutions",summary="分页读取设备消息规则原执行历史",
            description="仅含已记录设备身份的执行尝试，旧设备未知行不回填；SUCCESS不表示物理动作成功。limit为1至50。")
    public ResponseEntity<CursorPage<DeviceMessageRuleExecutionResponse>> history(@PathVariable UUID projectId,@PathVariable UUID deviceId,
            @RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit,HttpServletRequest request) {
        validate(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.history(projectId,deviceId,cursor,limit).map(DeviceMessageRuleExecutionResponse::from));
    }
    @GetMapping("/message-rule-actions")
    @Operation(operationId="listConsoleDeviceMessageRuleActions",summary="分页读取消息规则原设备动作记录",
            description="按动作原设备查询，含旧记录；投递终态不代替物理设备验收。limit为1至50。")
    public ResponseEntity<CursorPage<DeviceMessageRuleActionResponse>> actions(@PathVariable UUID projectId,@PathVariable UUID deviceId,
            @RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit,HttpServletRequest request) {
        validate(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.actions(projectId,deviceId,cursor,limit).map(DeviceMessageRuleActionResponse::from));
    }
    private void validate(HttpServletRequest request) {
        if (!Set.of("cursor","limit").containsAll(request.getParameterMap().keySet())
                || request.getParameterMap().values().stream().anyMatch(v->v.length!=1))
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
    }
}
