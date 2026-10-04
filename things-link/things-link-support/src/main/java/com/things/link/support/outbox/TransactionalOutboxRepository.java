package com.things.link.support.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 事务 Outbox 的跨领域公开端口。
 *
 * <p>业务模块只调用 {@link #append(OutboxEvent)}，并让它加入自身事实事务；发布器通过受控数据库函数
 * 领取和确认，应用角色不会因此获得跨项目扫描任意业务表的权限。</p>
 */
public interface TransactionalOutboxRepository {

    /**
     * 在当前业务事务中保存待发布事件。
     *
     * @param event 待发布事件
     */
    void append(OutboxEvent event);

    /**
     * 原子领取可投递事件并写入短租约。
     *
     * @param maximumEvents 本轮最大事件数，G1-C3b 按四条 stripe 的运行+排队容量强制上限 8
     * @param leaseDuration 租约时长，避免崩溃实例永久占有记录
     * @return 同一租约令牌保护的一批事件
     */
    OutboxClaim claimReady(int maximumEvents, Duration leaseDuration);

    /**
     * 查询全部未发布 lane head 中最老事件的年龄，供 head-of-line 阻塞告警使用。
     *
     * @param now 统一观测时刻
     * @return 无积压时为零
     */
    default Duration oldestUnpublishedAge(Instant now) {
        return Duration.ZERO;
    }

    /**
     * 在 Kafka broker 已确认后确认发布；租约失效或被接管时返回 false。
     *
     * @param eventId Outbox 事件 ID
     * @param leaseToken 本批领取令牌
     * @return 是否成功推进为已发布
     */
    boolean markPublished(UUID eventId, UUID leaseToken);

    /**
     * 记录可重试的投递失败并释放租约。
     *
     * @param eventId Outbox 事件 ID
     * @param leaseToken 本批领取令牌
     * @param nextAvailableAt 下一次允许领取的时刻
     * @param failureMessage 已脱敏的失败诊断，不得包含业务载荷或凭据
     * @return 是否成功释放并登记失败
     */
    boolean markRetry(UUID eventId, UUID leaseToken, Instant nextAvailableAt, String failureMessage);
}
