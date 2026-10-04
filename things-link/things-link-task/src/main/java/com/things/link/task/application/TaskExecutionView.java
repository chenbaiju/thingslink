package com.things.link.task.application;

import com.things.link.task.domain.TaskExecution;

/** 执行日志的跨层只读投影，避免 Controller 直接依赖持久化实现。 */
public record TaskExecutionView(TaskExecution execution) {
}
