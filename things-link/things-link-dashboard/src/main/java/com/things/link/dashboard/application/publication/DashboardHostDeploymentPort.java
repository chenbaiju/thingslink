package com.things.link.dashboard.application.publication;

import java.util.Optional;

/** 控制面当前宿主事务准入；与显式只读制品盘点不同。 */
public interface DashboardHostDeploymentPort {
    /**
     * 加入调用方已有事务的共享围栏并读取当前选择，持锁至事务结束。
     * @return 无行表示未启用激活的旧版兼容模式；依赖失败必须抛出，不能伪装无行
     */
    Optional<DashboardHostDeploymentSelection> admitCurrent();
}
