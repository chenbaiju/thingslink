package com.things.link.rule.application.queue;

import java.util.Objects;
import java.util.UUID;

/**
 * 调度器所需的最小可信工作描述。
 *
 * <p>租户与项目标识必须由上游可信归属解析结果构造，不能从设备报文载荷回填；S8-2C 接入设备链路时
 * 仍需保持这两个字段不可变。</p>
 *
 * @param tenantId 项目所有者租户 ID
 * @param projectId 规则所属项目 ID
 * @param work 已完成规则版本绑定的执行工作
 */
public record RuleQueueWorkItem(UUID tenantId, UUID projectId, Runnable work) {

    /** 在进入共享队列前完成空值校验，避免后台线程才暴露坏消息。 */
    public RuleQueueWorkItem {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(work, "work");
    }
}
