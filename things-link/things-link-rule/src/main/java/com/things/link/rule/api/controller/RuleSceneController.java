package com.things.link.rule.api.controller;

import com.things.link.rule.api.dto.request.CreateSceneRequest;
import com.things.link.rule.api.dto.request.ExecuteSceneRequest;
import com.things.link.rule.api.dto.request.ReviseSceneRequest;
import com.things.link.rule.api.dto.request.SceneActionRequest;
import com.things.link.rule.api.dto.request.SceneConditionRequest;
import com.things.link.rule.api.support.RuleSceneApiAuthorization;
import com.things.link.rule.application.RuleManagementPage;
import com.things.link.rule.domain.RuleErrorCode;
import com.things.link.shared.error.BusinessException;
import com.things.link.rule.application.ActionSpec;
import com.things.link.rule.application.ConditionSpec;
import com.things.link.rule.application.CreateSceneCommand;
import com.things.link.rule.application.ExecuteSceneCommand;
import com.things.link.rule.application.ReviseSceneCommand;
import com.things.link.rule.application.RuleSceneExecutionView;
import com.things.link.rule.application.RuleSceneService;
import com.things.link.rule.application.RuleSceneVersionView;
import com.things.link.rule.application.RuleSceneView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** 手动场景管理、版本发布与一键执行 API；服务层仍重复 OWNER/ADMIN 校验，HTTP 仅是第一层。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/scenes")
@Tag(name = "手动场景", description = "有序条件与动作的组合场景，定义、发布与同步幂等一键执行")
public class RuleSceneController {
    /** 场景应用服务。 */ private final RuleSceneService service;
    /** HTTP 第一层项目授权。 */ private final RuleSceneApiAuthorization authorization;
    /** @param service 场景用例 @param authorization HTTP 授权守卫 */
    public RuleSceneController(RuleSceneService service, RuleSceneApiAuthorization authorization) {
        this.service = service; this.authorization = authorization;
    }

    /** 有界场景目录；不返回条件或动作正文。 */
    @GetMapping
    @Operation(operationId = "listManagedScenes", summary = "查询手动场景目录")
    public RuleManagementPage<RuleSceneView> list(@PathVariable UUID projectId,
            @RequestParam(required = false) String name, @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
        authorization.requireSceneManage(projectId);
        return service.list(projectId, name, status, cursor, limit);
    }
    /** 有界版本历史供管理页面使用，旧数组入口保持兼容。 */
    @GetMapping("/{sceneId}/version-history")
    @Operation(operationId = "pageManagedSceneVersions", summary = "分页查询手动场景版本")
    public RuleManagementPage<RuleSceneVersionView> history(@PathVariable UUID projectId,
            @PathVariable UUID sceneId, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        authorization.requireSceneManage(projectId);
        return service.history(projectId, sceneId, cursor, limit);
    }
    /** 单版本回读包含完整有序条件与动作。 */
    @GetMapping("/{sceneId}/versions/{versionId}")
    @Operation(operationId = "getManagedSceneVersion", summary = "查询手动场景单版本")
    public RuleSceneVersionView version(@PathVariable UUID projectId, @PathVariable UUID sceneId,
            @PathVariable UUID versionId) {
        authorization.requireSceneManage(projectId);
        return service.version(projectId, sceneId, versionId);
    }
    /** 暂停新执行，保留活动版本与旧执行事实。 */
    @PostMapping("/{sceneId}/pause")
    @Operation(operationId = "pauseManagedScene", summary = "暂停手动场景")
    public RuleSceneView pause(@PathVariable UUID projectId, @PathVariable UUID sceneId,
            @RequestParam long expectedVersion) {
        authorization.requireSceneManage(projectId);
        return service.pause(projectId, sceneId, expectedVersion);
    }

    /** 创建 DRAFT 场景与版本号 1 的不可变条件/动作事实。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "创建手动场景")
    public RuleSceneView create(@PathVariable UUID projectId, @Valid @RequestBody CreateSceneRequest request) {
        authorization.requireSceneManage(projectId);
        return service.create(projectId, command(request));
    }

    /** 读取场景定义与活动版本指针。 */
    @GetMapping("/{sceneId}")
    @Operation(summary = "查询手动场景")
    public RuleSceneView get(@PathVariable UUID projectId, @PathVariable UUID sceneId) {
        authorization.requireSceneManage(projectId);
        return service.get(projectId, sceneId);
    }

    /** 追加新不可变版本；expectedVersion 校验定义乐观锁，既有版本绝不覆盖。 */
    @PutMapping("/{sceneId}")
    @Operation(summary = "修改手动场景", description = "追加新不可变版本，expectedVersion 校验乐观锁")
    public RuleSceneView revise(@PathVariable UUID projectId, @PathVariable UUID sceneId,
                                @Valid @RequestBody ReviseSceneRequest request) {
        authorization.requireSceneManage(projectId);
        return service.revise(projectId, sceneId, command(request));
    }

    /** 发布指定历史版本为活动版本，兼作显式回滚入口。 */
    @PostMapping("/{sceneId}/versions/{versionId}/activate")
    @Operation(summary = "发布手动场景版本", description = "把指定历史版本设为活动版本")
    public RuleSceneView activate(@PathVariable UUID projectId, @PathVariable UUID sceneId,
                                  @PathVariable UUID versionId, @RequestParam long expectedVersion) {
        authorization.requireSceneManage(projectId);
        return service.activate(projectId, sceneId, versionId, expectedVersion);
    }

    /** 软删除场景定义，不可变版本与执行事实继续保留。 */
    @DeleteMapping("/{sceneId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "删除手动场景")
    public void delete(@PathVariable UUID projectId, @PathVariable UUID sceneId,
                       @RequestParam long expectedVersion) {
        authorization.requireSceneManage(projectId);
        service.delete(projectId, sceneId, expectedVersion);
    }

    /** 读取完整不可变版本历史。 */
    @GetMapping("/{sceneId}/versions")
    @Operation(summary = "查询手动场景版本历史")
    public List<RuleSceneVersionView> versions(@PathVariable UUID projectId, @PathVariable UUID sceneId) {
        authorization.requireSceneManage(projectId);
        return service.versions(projectId, sceneId);
    }

    /** 一键执行活动版本；同步返回封闭终态，同一幂等键同内容重试返回既有事实。 */
    @PostMapping("/{sceneId}/executions")
    @Operation(summary = "一键执行手动场景",
            description = "Idempotency-Key 必填；同步返回 DISPATCHED/SKIPPED/FAILED 终态，DISPATCHED 只表示副作用已可靠写入 Outbox")
    public RuleSceneExecutionView execute(@PathVariable UUID projectId, @PathVariable UUID sceneId,
                                          @Parameter(required = true) @RequestHeader("Idempotency-Key") String key,
                                          @Valid @RequestBody ExecuteSceneRequest request) {
        authorization.requireSceneManage(projectId);
        return service.execute(projectId, sceneId, key, command(request));
    }

    /** HTTP DTO 到 application 命令的唯一映射点。 */
    private static CreateSceneCommand command(CreateSceneRequest request) {
        return new CreateSceneCommand(request.name(), request.description(),
                conditions(request.conditions()), actions(request.actions()));
    }

    /** HTTP DTO 到 application 命令的唯一映射点。 */
    private static ReviseSceneCommand command(ReviseSceneRequest request) {
        return new ReviseSceneCommand(request.name(), request.description(), request.expectedVersion(),
                conditions(request.conditions()), actions(request.actions()));
    }

    /** HTTP DTO 到 application 命令的唯一映射点。 */
    private static ExecuteSceneCommand command(ExecuteSceneRequest request) {
        return new ExecuteSceneCommand(request.deviceId(), request.payload());
    }

    /** @return 条件规格列表；空等价于“总是执行” */
    private static List<ConditionSpec> conditions(List<SceneConditionRequest> conditions) {
        return conditions == null ? List.of() : conditions.stream()
                .map(value -> {
                    if (value == null || value.nodeType() == null || value.nodeType().isBlank() || value.config() == null)
                        throw new BusinessException(RuleErrorCode.SCENE_CONDITION_INVALID);
                    return new ConditionSpec(value.nodeType(), value.config());
                }).toList();
    }

    /** @return 动作规格列表 */
    private static List<ActionSpec> actions(List<SceneActionRequest> actions) {
        return actions == null ? List.of() : actions.stream()
                .map(value -> {
                    if (value == null || value.nodeType() == null || value.nodeType().isBlank() || value.config() == null)
                        throw new BusinessException(RuleErrorCode.RULE_ACTION_INVALID);
                    return new ActionSpec(value.nodeType(), value.config());
                }).toList();
    }
}
