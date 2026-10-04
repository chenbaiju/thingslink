package com.things.link.dashboard.application.publication;

import java.util.Optional;

/** 显式目标宿主的只读核验；登记事实与当前部署选择互相独立。 */
public interface DashboardRegisteredHostQualificationPort {
    /**
     * 从同一次完整文件核验取得指定已登记宿主的能力与字节身份，不执行激活。
     *
     * @param hostVersion 显式宿主版本；实现必须拒绝未知版本或路径，不能回退
     * @return 已完整复验的目标事实；缺失、损坏、竞态或不支持时为空
     */
    Optional<DashboardRegisteredHostQualification> findRegistered(String hostVersion);
}
