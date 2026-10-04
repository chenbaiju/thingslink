package com.things.link.dashboard.application.publication;

import java.util.Optional;

/**
 * 提供当前部署宿主的一次性不可变注册快照。
 *
 * <p>实现只有在受管注册流程已核验完整HostDescriptor的制品摘要、应用格式、manifest、资源字节及
 * 其他冻结字段后才能返回值；部分字段投影或未经核验的配置必须返回空。</p>
 */
public interface DashboardHostQualificationPort {

    /**
     * 读取同一制品的格式、宿主版本、Schema、组件和资源事实，避免逐项读取发生代际漂移。
     *
     * @return 从同一份完整且已核验HostDescriptor投影的当前快照；未配置生产注册事实时为空
     */
    Optional<DashboardHostQualificationDescriptor> current();
}
