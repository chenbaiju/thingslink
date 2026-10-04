package com.things.link.telemetry.application;

import java.util.UUID;

/**
 * 任务域归并执行进度所需的命令最小投影。
 *
 * @param commandId 命令事实 ID
 * @param status 当前命令状态
 * @param terminal 是否已进入最终状态
 * @param failureSummary 受理前永久拒绝的稳定摘要；已创建命令时为空
 */
public record TaskDeviceCommandResult(UUID commandId, Status status, boolean terminal, String failureSummary) {
    /** 任务域可见的命令状态投影，避免跨模块泄露 telemetry domain 类型。 */
    public enum Status { ACCEPTED, DISPATCHED, ACKNOWLEDGED, SUCCEEDED, FAILED, TIMED_OUT, REJECTED }

    /** @return 是否在创建命令事实前已被物模型或目标校验永久拒绝 */
    public boolean rejected() {
        return status == Status.REJECTED;
    }
}
