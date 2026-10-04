package com.things.link.project.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** ADR0166 取消事实端口；写入方持有租户锁，全部方法加入原事务。 */
public interface SubscriptionCancellationRepository {
    /** @param tenant 租户 @param operation 操作身份 @return 同租户原始回执 */
    Optional<SubscriptionCancellationReceipt> find(UUID tenant, UUID operation);
    /** @return 当前数据库实际时间，不能用等待锁之前的事务开始时间 */
    Instant currentTime();
    /** @param tenant 租户 @param subscription 当前付费行 @param at 核验时刻 @return 是否真正终止 */
    boolean cancelActive(UUID tenant, UUID subscription, Instant at);
    /** @param receipt 只能关联本事务终止行与新FREE的不可变结果 */
    void record(SubscriptionCancellationReceipt receipt);
}
