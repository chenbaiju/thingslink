package com.things.link.device.application;

import java.util.UUID;

/**
 * 批量任务冻结设备目标时使用的受控范围。
 *
 * <p>任务域只能表达全项目或既有设备组，不能把任意查询 DSL 透传到设备域；这样一次执行的目标语义
 * 与控制台可见的设备组语义一致，且不会扩张跨模块契约。</p>
 *
 * @param projectId 项目隔离轴
 * @param groupId 可选设备组；为空时表示项目全部未删除设备
 */
public record TaskTargetScope(UUID projectId, UUID groupId) {

    /** 创建目标范围并拒绝缺失项目隔离轴。 */
    public TaskTargetScope {
        if (projectId == null) {
            throw new IllegalArgumentException("批量任务目标必须指定项目");
        }
    }

    /** @return 是否选择项目内全部设备 */
    public boolean allDevices() {
        return groupId == null;
    }
}
