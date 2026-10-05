package com.things.link.task.api.controller;

import com.things.link.task.api.dto.request.SaveTaskJobRequest;
import com.things.link.task.api.dto.response.TaskExecutionResponse;
import com.things.link.task.api.dto.response.TaskJobResponse;
import com.things.link.task.api.support.TaskApiAuthorization;
import com.things.link.task.application.TaskJobCommand;
import com.things.link.task.application.TaskJobService;
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
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

/** 批量设备任务配置、启停和执行日志 REST API。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/task-jobs")
@Tag(name = "任务调度", description = "项目时区 cron、批量设备命令和执行日志")
public class TaskJobController {
    /** 任务应用服务。 */ private final TaskJobService service;
    /** HTTP 角色守卫。 */ private final TaskApiAuthorization authorization;
    /** JSON 映射器。 */ private final ObjectMapper mapper;
    /** @param service 任务应用服务 @param authorization HTTP 角色守卫 @param mapper JSON 映射器 */
    public TaskJobController(TaskJobService service, TaskApiAuthorization authorization, ObjectMapper mapper) { this.service = service; this.authorization = authorization; this.mapper = mapper; }
    /**
     * 列出项目任务。
     *
     * @param projectId 接口指定的项目标识
     * @return 符合当前查询条件的结果列表
     */
    @GetMapping @Operation(summary = "列出批量任务", description = "列出项目任务。") public List<TaskJobResponse> list(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId) { authorization.requireRead(projectId); return service.list(projectId).stream().map(value -> TaskJobResponse.from(value, mapper)).toList(); }
    /**
     * 创建任务。
     *
     * @param projectId 接口指定的项目标识
     * @param request 本次操作的请求数据，结构见 {@code SaveTaskJobRequest}
     * @return 当前接口的操作结果，响应结构见 {@code TaskJobResponse}
     */
    @PostMapping @ResponseStatus(HttpStatus.CREATED) @Operation(summary = "创建批量任务", description = "创建任务。") public TaskJobResponse create(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId, @Valid @RequestBody SaveTaskJobRequest request) { authorization.requireManage(projectId); return TaskJobResponse.from(service.create(projectId, command(request)), mapper); }
    /**
     * 更新任务。
     *
     * @param projectId 接口指定的项目标识
     * @param jobId 批量任务标识
     * @param request 本次操作的请求数据，结构见 {@code SaveTaskJobRequest}
     * @return 当前接口的操作结果，响应结构见 {@code TaskJobResponse}
     */
    @PutMapping("/{jobId}") @Operation(summary = "更新批量任务", description = "更新任务。") public TaskJobResponse update(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId, @io.swagger.v3.oas.annotations.Parameter(description = "批量任务标识") @PathVariable UUID jobId, @Valid @RequestBody SaveTaskJobRequest request) { authorization.requireManage(projectId); return TaskJobResponse.from(service.update(projectId, jobId, command(request)), mapper); }
    /**
     * 软删除任务。
     *
     * @param projectId 接口指定的项目标识
     * @param jobId 批量任务标识
     * @param expectedVersion 调用方期望的资源版本，用于并发更新校验
     */
    @DeleteMapping("/{jobId}") @ResponseStatus(HttpStatus.NO_CONTENT) @Operation(summary = "删除批量任务", description = "软删除任务。") public void delete(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId, @io.swagger.v3.oas.annotations.Parameter(description = "批量任务标识") @PathVariable UUID jobId, @io.swagger.v3.oas.annotations.Parameter(description = "调用方期望的资源版本，用于并发更新校验") @RequestParam long expectedVersion) { authorization.requireManage(projectId); service.delete(projectId, jobId, expectedVersion); }
    /**
     * 启用任务。
     *
     * @param projectId 接口指定的项目标识
     * @param jobId 批量任务标识
     * @return 当前接口的操作结果，响应结构见 {@code TaskJobResponse}
     */
    @PostMapping("/{jobId}/enable") @Operation(summary = "启用批量任务", description = "启用任务。") public TaskJobResponse enable(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId, @io.swagger.v3.oas.annotations.Parameter(description = "批量任务标识") @PathVariable UUID jobId) { authorization.requireManage(projectId); return TaskJobResponse.from(service.enable(projectId, jobId), mapper); }
    /**
     * 暂停任务。
     *
     * @param projectId 接口指定的项目标识
     * @param jobId 批量任务标识
     * @return 当前接口的操作结果，响应结构见 {@code TaskJobResponse}
     */
    @PostMapping("/{jobId}/disable") @Operation(summary = "暂停批量任务", description = "暂停任务。") public TaskJobResponse disable(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId, @io.swagger.v3.oas.annotations.Parameter(description = "批量任务标识") @PathVariable UUID jobId) { authorization.requireManage(projectId); return TaskJobResponse.from(service.disable(projectId, jobId), mapper); }
    /**
     * 手工触发一次执行。
     *
     * @param projectId 接口指定的项目标识
     * @param jobId 批量任务标识
     * @return 当前接口的操作结果，响应结构见 {@code TaskExecutionResponse}
     */
    @PostMapping("/{jobId}/runs") @ResponseStatus(HttpStatus.ACCEPTED) @Operation(summary = "手工执行任务", description = "手工触发一次执行。") public TaskExecutionResponse run(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId, @io.swagger.v3.oas.annotations.Parameter(description = "批量任务标识") @PathVariable UUID jobId) { authorization.requireRun(projectId); return TaskExecutionResponse.from(service.run(projectId, jobId)); }
    /**
     * 列出执行日志。
     *
     * @param projectId 接口指定的项目标识
     * @param jobId 批量任务标识
     * @return 符合当前查询条件的结果列表
     */
    @GetMapping("/{jobId}/executions") @Operation(summary = "列出任务执行日志", description = "列出执行日志。") public List<TaskExecutionResponse> executions(@io.swagger.v3.oas.annotations.Parameter(description = "接口指定的项目标识") @PathVariable UUID projectId, @io.swagger.v3.oas.annotations.Parameter(description = "批量任务标识") @PathVariable UUID jobId) { authorization.requireRead(projectId); return service.executions(projectId, jobId).stream().map(TaskExecutionResponse::from).toList(); }
    /** HTTP 请求到 application 命令的唯一映射点。 */
    private static TaskJobCommand command(SaveTaskJobRequest value) { return new TaskJobCommand(value.name(), value.description(), value.scheduleType(), value.runAt(), value.cronExpression(), value.timezone(), value.targetType(), value.targetGroupId(), value.commandKey(), value.input(), value.enabled(), value.expectedVersion()); }
}
