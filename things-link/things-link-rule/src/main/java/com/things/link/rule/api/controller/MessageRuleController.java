package com.things.link.rule.api.controller;

import com.things.link.rule.api.dto.request.MessageRuleWriteRequest;
import com.things.link.rule.api.dto.request.MessageRuleDebugRequest;
import com.things.link.rule.api.dto.request.RuleActionRequest;
import com.things.link.rule.application.ActionSpec;
import com.things.link.rule.application.CreateMessageRuleCommand;
import com.things.link.rule.application.DebugMessageRuleCommand;
import com.things.link.rule.application.MessageRuleDebugService;
import com.things.link.rule.application.MessageRuleService;
import com.things.link.rule.application.MessageRuleVersionView;
import com.things.link.rule.application.MessageRuleView;
import com.things.link.rule.application.ReviseMessageRuleCommand;
import com.things.link.rule.application.RuleManagementAccess;
import com.things.link.rule.application.RuleManagementPage;
import com.things.link.rule.domain.RuleErrorCode;
import com.things.link.shared.error.BusinessException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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
import java.util.List;
import java.util.UUID;

/** 消息规则管理HTTP；服务层再次授权，Controller不参与持久事务。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/message-rules")
@Tag(name = "消息规则管理")
public class MessageRuleController {
    /** 规则管理用例。 */
    private final MessageRuleService service;
    /** 无副作用样例执行。 */
    private final MessageRuleDebugService debug;
    /** HTTP第一层真实角色授权。 */
    private final RuleManagementAccess access;
    /** 装配管理用例，不开放仓储。 */
    public MessageRuleController(MessageRuleService service, MessageRuleDebugService debug, RuleManagementAccess access) {
        this.service = service; this.debug = debug; this.access = access;
    }
    /** 目录不返回源码。 */
    @GetMapping @Operation(operationId="listMessageRules", summary="分页查询消息规则")
    public RuleManagementPage<MessageRuleView> list(@PathVariable UUID projectId,
            @RequestParam(required=false) String name, @RequestParam(required=false) String status,
            @RequestParam(required=false) String cursor, @RequestParam(defaultValue="20") int limit) {
        access.read(projectId, false); return service.list(projectId, name, status, cursor, limit);
    }
    /** 创建草稿，不自动发布。 */
    @PostMapping @ResponseStatus(HttpStatus.CREATED) @Operation(operationId="createMessageRule", summary="创建消息规则")
    public MessageRuleView create(@PathVariable UUID projectId, @Valid @RequestBody MessageRuleWriteRequest request) {
        access.read(projectId, false);
        return service.create(projectId, new CreateMessageRuleCommand(request.name(), request.description(), request.source(), actions(request.actions())));
    }
    /** 读取规则控制面状态。 */
    @GetMapping("/{ruleId}") @Operation(operationId="getMessageRule", summary="查询消息规则")
    public MessageRuleView get(@PathVariable UUID projectId, @PathVariable UUID ruleId) {
        access.read(projectId, false); return service.get(projectId, ruleId);
    }
    /** 保存完整新版本，不能隐式清空动作。 */
    @PutMapping("/{ruleId}") @Operation(operationId="reviseMessageRule", summary="修订消息规则")
    public MessageRuleView revise(@PathVariable UUID projectId, @PathVariable UUID ruleId, @Valid @RequestBody MessageRuleWriteRequest request) {
        access.read(projectId, false);
        return service.revise(projectId, ruleId, new ReviseMessageRuleCommand(request.name(), request.description(), request.source(),
                request.expectedVersion() == null ? 0 : request.expectedVersion(), actions(request.actions())));
    }
    /** 保留数组形状的历史读取。 */
    @GetMapping("/{ruleId}/versions") @Operation(operationId="listMessageRuleVersions", summary="读取消息规则版本历史")
    public List<MessageRuleVersionView> versions(@PathVariable UUID projectId, @PathVariable UUID ruleId) {
        access.read(projectId, false); return service.versions(projectId, ruleId);
    }
    /** 管理页面使用有界历史。 */
    @GetMapping("/{ruleId}/version-history") @Operation(operationId="pageMessageRuleVersions", summary="分页读取消息规则版本")
    public RuleManagementPage<MessageRuleVersionView> history(@PathVariable UUID projectId, @PathVariable UUID ruleId,
            @RequestParam(required=false) String cursor, @RequestParam(defaultValue="20") int limit) {
        access.read(projectId, false); return service.history(projectId, ruleId, cursor, limit);
    }
    /** 单版本包含完整动作。 */
    @GetMapping("/{ruleId}/versions/{versionId}") @Operation(operationId="getMessageRuleVersion", summary="读取消息规则指定版本")
    public MessageRuleVersionView version(@PathVariable UUID projectId, @PathVariable UUID ruleId, @PathVariable UUID versionId) {
        access.read(projectId, false); return service.version(projectId, ruleId, versionId);
    }
    /** 发布、恢复和显式回滚共享一个CAS入口。 */
    @PostMapping("/{ruleId}/versions/{versionId}/activate") @Operation(operationId="activateMessageRuleVersion", summary="发布消息规则版本")
    public MessageRuleView activate(@PathVariable UUID projectId, @PathVariable UUID ruleId, @PathVariable UUID versionId,
            @RequestParam long expectedVersion) {
        access.read(projectId, false); return service.activate(projectId, ruleId, versionId, expectedVersion);
    }
    /** 暂停只影响未来计划。 */
    @PostMapping("/{ruleId}/pause") @Operation(operationId="pauseMessageRule", summary="暂停消息规则")
    public MessageRuleView pause(@PathVariable UUID projectId, @PathVariable UUID ruleId, @RequestParam long expectedVersion) {
        access.read(projectId, false); return service.pause(projectId, ruleId, expectedVersion);
    }
    /** 删除不清除历史。 */
    @DeleteMapping("/{ruleId}") @ResponseStatus(HttpStatus.NO_CONTENT) @Operation(operationId="deleteMessageRule", summary="删除消息规则")
    public void delete(@PathVariable UUID projectId, @PathVariable UUID ruleId, @RequestParam long expectedVersion) {
        access.read(projectId, false); service.delete(projectId, ruleId, expectedVersion);
    }
    /** 真实沙箱结果与可追溯事件绑定，无动作派发。 */
    @PostMapping("/{ruleId}/versions/{versionId}/debug") @Operation(operationId="debugMessageRuleVersion", summary="调试消息规则版本")
    public DebugResponse debug(@PathVariable UUID projectId, @PathVariable UUID ruleId, @PathVariable UUID versionId,
            @RequestBody MessageRuleDebugRequest request) {
        access.read(projectId, false);
        var result = debug.execute(projectId, new DebugMessageRuleCommand(ruleId, versionId, request.inputJson()));
        return new DebugResponse(result.eventId(), result.status().name(), result.outputJson(), result.resultCode(),
                result.duration().toMillis(), result.occurredAt());
    }
    /** DTO中null或缺失字段转领域错误，避免构造器抛500。 */
    private static List<ActionSpec> actions(List<RuleActionRequest> values) {
        if (values == null) return List.of();
        return values.stream().map(value -> {
            if (value == null || value.nodeType() == null || value.nodeType().isBlank() || value.config() == null)
                throw new BusinessException(RuleErrorCode.RULE_ACTION_INVALID);
            return new ActionSpec(value.nodeType(), value.config());
        }).toList();
    }
    /** @param eventId 调试事实 @param status 沙箱终态 @param outputJson 有界输出 @param errorCode 固定结果码
     * @param durationMillis 耗时毫秒 @param occurredAt 受理时间 */
    public record DebugResponse(UUID eventId, String status, String outputJson, String errorCode, long durationMillis, java.time.Instant occurredAt) { }
}
