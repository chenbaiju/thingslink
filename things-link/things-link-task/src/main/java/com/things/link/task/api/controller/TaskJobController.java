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
    /** 列出项目任务。 */
    @GetMapping @Operation(summary = "列出批量任务") public List<TaskJobResponse> list(@PathVariable UUID projectId) { authorization.requireRead(projectId); return service.list(projectId).stream().map(value -> TaskJobResponse.from(value, mapper)).toList(); }
    /** 创建任务。 */
    @PostMapping @ResponseStatus(HttpStatus.CREATED) @Operation(summary = "创建批量任务") public TaskJobResponse create(@PathVariable UUID projectId, @Valid @RequestBody SaveTaskJobRequest request) { authorization.requireManage(projectId); return TaskJobResponse.from(service.create(projectId, command(request)), mapper); }
    /** 更新任务。 */
    @PutMapping("/{jobId}") @Operation(summary = "更新批量任务") public TaskJobResponse update(@PathVariable UUID projectId, @PathVariable UUID jobId, @Valid @RequestBody SaveTaskJobRequest request) { authorization.requireManage(projectId); return TaskJobResponse.from(service.update(projectId, jobId, command(request)), mapper); }
    /** 软删除任务。 */
    @DeleteMapping("/{jobId}") @ResponseStatus(HttpStatus.NO_CONTENT) @Operation(summary = "删除批量任务") public void delete(@PathVariable UUID projectId, @PathVariable UUID jobId, @RequestParam long expectedVersion) { authorization.requireManage(projectId); service.delete(projectId, jobId, expectedVersion); }
    /** 启用任务。 */
    @PostMapping("/{jobId}/enable") @Operation(summary = "启用批量任务") public TaskJobResponse enable(@PathVariable UUID projectId, @PathVariable UUID jobId) { authorization.requireManage(projectId); return TaskJobResponse.from(service.enable(projectId, jobId), mapper); }
    /** 暂停任务。 */
    @PostMapping("/{jobId}/disable") @Operation(summary = "暂停批量任务") public TaskJobResponse disable(@PathVariable UUID projectId, @PathVariable UUID jobId) { authorization.requireManage(projectId); return TaskJobResponse.from(service.disable(projectId, jobId), mapper); }
    /** 手工触发一次执行。 */
    @PostMapping("/{jobId}/runs") @ResponseStatus(HttpStatus.ACCEPTED) @Operation(summary = "手工执行任务") public TaskExecutionResponse run(@PathVariable UUID projectId, @PathVariable UUID jobId) { authorization.requireRun(projectId); return TaskExecutionResponse.from(service.run(projectId, jobId)); }
    /** 列出执行日志。 */
    @GetMapping("/{jobId}/executions") @Operation(summary = "列出任务执行日志") public List<TaskExecutionResponse> executions(@PathVariable UUID projectId, @PathVariable UUID jobId) { authorization.requireRead(projectId); return service.executions(projectId, jobId).stream().map(TaskExecutionResponse::from).toList(); }
    /** HTTP 请求到 application 命令的唯一映射点。 */
    private static TaskJobCommand command(SaveTaskJobRequest value) { return new TaskJobCommand(value.name(), value.description(), value.scheduleType(), value.runAt(), value.cronExpression(), value.timezone(), value.targetType(), value.targetGroupId(), value.commandKey(), value.input(), value.enabled(), value.expectedVersion()); }
}
