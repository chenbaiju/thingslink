package com.things.link.device.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Modbus 平台驱动轮询的持久化端口。 */
public interface ModbusPollRepository {
    /**
     * 用最新已发布点位物化轮询表：删除网关旧轮询行，插入新轮询行（next_poll_at 取当前时刻）。
     *
     * @param projectId 项目 ID
     * @param deviceId 网关设备 ID
     * @param projectKey MQTT 项目键
     * @param gatewayKey MQTT 网关设备键
     * @param points 最新已发布点位
     */
    void syncSchedule(UUID projectId, UUID deviceId, String projectKey, String gatewayKey,
                      List<ModbusPointMapping> points);

    /**
     * 领取到期且网关无在途请求的IDLE轮询行：网关事务锁后复查，每网关最多一点，忙锁跳过。
     * 调用方必须已有READ COMMITTED或PostgreSQL等价READ UNCOMMITTED事务（D-117）；
     * limit同时约束候选网关数，忙候选可使实际领取不足上限，不保证任意网关无饥饿。
     *
     * @param limit 单次领取上限
     * @return 已锁定并置 IN_FLIGHT 的到期轮询行
     */
    List<ModbusPoll> claimDue(int limit);

    /**
     * 在有界候选网关中最多领取一个到期轮询，供逐轮询独立事务编排使用。
     * 调用方必须已有READ COMMITTED或PostgreSQL等价READ UNCOMMITTED事务；
     * D-117的网关事务锁、锁后复查和每网关至多一点语义与批量入口完全相同。
     *
     * @param candidateLimit 最多检查的候选网关数
     * @return 已锁定并置 IN_FLIGHT 的一个到期轮询；候选均忙或不存在时为空
     */
    Optional<ModbusPoll> claimOneDue(int candidateLimit);

    /** @param requestId 在途请求关联标识 @return 对应轮询行 */
    Optional<ModbusPoll> findByRequestId(UUID requestId);

    /** 设置在途请求的关联标识与尝试序号。 @param id 轮询行 ID @param requestId 关联标识 @param attempt 尝试序号 @return 是否命中 */
    boolean markRequest(UUID id, UUID requestId, int attempt);

    /** 领取租约过期的在途请求，供超时重试/释放。 @param limit 单次上限 @return 已锁定并延长租约的在途行 */
    List<ModbusPoll> claimExpiredInFlight(int limit);

    /**
     * 在有界候选行中最多领取一个租约过期请求，供逐轮询独立事务编排使用。
     *
     * @param candidateLimit 最多检查的过期候选行数
     * @return 已锁定并延长租约的一个在途行；候选均忙或不存在时为空
     */
    Optional<ModbusPoll> claimOneExpiredInFlight(int candidateLimit);

    /**
     * D-120：仅结束仍匹配预期requestId的在途请求，旧响应或重复响应不得覆盖后来重试。
     * @param id 轮询行
     * @param expectedRequestId 响应所对应请求
     * @param nextPollAt 下一正常周期
     * @return 只有本次赢得完成状态转换才为true
     */
    boolean completeRequest(UUID id, UUID expectedRequestId, Instant nextPollAt);

    /** 推进轮询行到下一周期。 @param id 轮询行 ID @param nextPollAt 下一轮询时刻 @return 是否命中 */
    boolean complete(UUID id, Instant nextPollAt);

    /**
     * ADR0062：资格失效时清除本地在途关联，按数据库当前事务时间加自身周期暂停；不撤回既有Outbox。
     * @param id 已领取轮询行
     * @return 是否命中
     */
    boolean pauseUntilNextInterval(UUID id);

    /** 释放轮询行为 IDLE（失败耗尽后等待下一周期）。 @param id 轮询行 ID @param nextPollAt 下一轮询时刻 @return 是否命中 */
    boolean release(UUID id, Instant nextPollAt);
}
