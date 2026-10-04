package com.things.link.project.application;

import java.time.LocalDate;
import java.util.List;

/**
 * 业务模块向 project 日账单归并器提供绝对事实的公开 SPI。
 *
 * <p>实现只能查询本模块拥有的表，并必须从幂等事实重算；禁止每条消息同步更新
 * {@code sys_usage_counter_daily}，也禁止返回 Redis 近似计数。</p>
 */
public interface DailyUsageContributor {

    /**
     * 计算一个项目在固定 UTC 日窗口中的绝对用量。
     *
     * @param scope project 模块领取并已写入事务级 RLS 的权威范围
     * @param usageDate UTC 计量日期
     * @return 本模块拥有的指标快照；没有事实时可返回零或空集合
     */
    List<DailyUsageValue> calculate(DailyUsageScope scope, LocalDate usageDate);
}
