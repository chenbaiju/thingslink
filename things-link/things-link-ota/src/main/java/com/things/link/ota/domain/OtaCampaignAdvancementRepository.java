package com.things.link.ota.domain;

import java.util.Optional;
import java.util.UUID;

/** 成功批次推进与活动完成仓储，候选仅用于恢复权威项目范围。 */
public interface OtaCampaignAdvancementRepository {
    /** 一个符合冻结自动策略的可信候选，不预先取得活动行锁。 */
    Optional<Candidate> candidate();
    /** 精确当前批与活动修订CAS，人工或系统模式由原计划决定。 */
    boolean advance(UUID project, UUID campaign, long revision, int expectedBatchNumber, UUID actor, String reason);
    /** 最后成功批的系统完成，不改变原设备作业事实。 */
    boolean complete(UUID project, UUID campaign, long revision);

    /** 数据库可信候选，不能由HTTP请求替代。
     * @param tenantId 权威租户
     * @param projectId 权威项目
     * @param campaignId 待复验活动
     * @param stateVersion 候选时活动修订
     */
    record Candidate(UUID tenantId, UUID projectId, UUID campaignId, long stateVersion) { }
}
