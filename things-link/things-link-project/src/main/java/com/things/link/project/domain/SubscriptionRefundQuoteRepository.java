package com.things.link.project.domain;

import java.util.Optional;
import java.util.UUID;

/** ADR0166 报价保存与明确租户读取；全部方法加入原事务。 */
public interface SubscriptionRefundQuoteRepository {
    /** @param tenant 真实租户 @param operation 原操作 @return 原报价，不重新计算 */
    Optional<SubscriptionRefundQuote> find(UUID tenant,UUID operation);
    /** @param quote 已由来源核验及整日算法产生的不可变报价 */
    void insert(SubscriptionRefundQuote quote);
    /** @param tenant 已锁定租户 @return 当前身份/状态/策略JSON，租户不存在为空 */
    Optional<String> tenantSnapshot(UUID tenant);
}
