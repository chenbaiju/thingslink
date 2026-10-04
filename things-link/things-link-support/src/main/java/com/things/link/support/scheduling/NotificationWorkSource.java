package com.things.link.support.scheduling;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** 告警与规则模块向共享通知执行器提供可恢复数据库租约工作的端口。 */
public interface NotificationWorkSource {

    /** @return 按租户公平领取、且已由数据库投递租约保护的工作 */
    List<NotificationWork> claim(int limit, Duration leaseDuration);

    /** @return 固定来源标签，只允许 alarm/rule 等部署清单常量 */
    default String sourceName() {
        return "unknown";
    }

    /** @return 当前 QUEUED/SENDING 最老事实年龄，无积压时均为零 */
    default BacklogAges observeAges() {
        return new BacklogAges(Duration.ZERO, Duration.ZERO);
    }

    /**
     * @param tenantId 集群公平轴
     * @param execute 获取租户槽后执行；内部必须在网络调用前用 CAS 增加 attempt
     * @param release 未进入执行器或未取得租户槽时释放投递租约
     */
    record NotificationWork(UUID tenantId, Runnable execute, Runnable release) {
    }

    /** @param queued 最老 QUEUED 年龄 @param sending 最老 SENDING 年龄 */
    record BacklogAges(Duration queued, Duration sending) {
    }
}
