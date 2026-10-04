package com.things.link.project.domain;

import java.util.Optional;
import java.util.UUID;

/** ADR0166 显式租户的只增模拟执行结果，所有操作加入原事务。 */
public interface SubscriptionRefundExecutionRepository {
    /** @param tenant 真实租户 @param operation 报价身份 @return 原模拟执行结果 */
    Optional<SubscriptionRefundExecution> find(UUID tenant,UUID operation);
    /** @param result 与同事务报价/取消一致的原始成功结果 */
    void insert(SubscriptionRefundExecution result);
}
