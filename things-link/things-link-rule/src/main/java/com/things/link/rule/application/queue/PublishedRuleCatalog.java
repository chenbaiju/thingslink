package com.things.link.rule.application.queue;

import java.util.UUID;

/** 按可信 owner tenant 与项目读取当前活动规则并冻结执行顺序的端口。 */
public interface PublishedRuleCatalog {

    /**
     * @param tenantId 已确权项目 owner tenant ID
     * @param projectId 已确权项目 ID
     * @param messageId 上行稳定幂等 ID，用于重复投递恢复首次冻结的原计划
     * @return 按定义创建时刻、规则 ID 稳定排序的不可变活动版本计划
     */
    PublishedRulePlan resolve(UUID tenantId, UUID projectId, UUID messageId);
}
