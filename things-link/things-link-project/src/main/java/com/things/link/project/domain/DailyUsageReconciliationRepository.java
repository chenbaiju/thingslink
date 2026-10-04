package com.things.link.project.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * UTC 日用量归并的 project 权威持久化端口。
 */
public interface DailyUsageReconciliationRepository {

    /**
     * 从 SECURITY DEFINER 函数领取有限项目范围并设置短租约。
     *
     * @param maximumRows 单轮最多领取项目数；数据库仍会夹在 1..100
     * @return 本实例当前可推进的项目范围
     */
    List<DailyUsageScope> claimDueScopes(int maximumRows);

    /** 计量写入前锁项目，避免与原事务额度预留形成counter→project的逆序。 */
    void lockScope(DailyUsageScope scope);

    /**
     * 以绝对值单调归并一个项目日指标。
     *
     * @param scope 数据库领取的项目范围
     * @param usageDate UTC 计量日期
     * @param value 幂等业务事实重算值
     */
    void mergeAbsolute(DailyUsageScope scope, LocalDate usageDate, DailyUsageValue value);

    /**
     * 成功完成今天与昨天归并后释放租约并顺延下一扫描时刻。
     *
     * @param scope 已完成项目范围
     */
    void complete(DailyUsageScope scope);
}
