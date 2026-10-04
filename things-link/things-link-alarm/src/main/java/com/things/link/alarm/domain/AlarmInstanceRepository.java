package com.things.link.alarm.domain;

import com.things.link.shared.page.CursorPage;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 告警当前事故与不可变事件持久化端口。 */
public interface AlarmInstanceRepository {
    /** @return 指定规则/来源的 PENDING 或 ACTIVE 事故 */
    Optional<AlarmInstance> findActive(UUID projectId, UUID ruleId, UUID originatorId, String alarmType);
    /** @return 仅当前项目可见的实例 */ Optional<AlarmInstance> findById(UUID projectId, UUID instanceId);
    /** @return 项目实例页 */ CursorPage<AlarmInstance> page(UUID projectId, String cursor, int limit);
    /**
     * 数据运行合同§3.5：设备和三组过滤在LIMIT前执行，最多取一页加一候选。
     * 调用方已完成实际设备可见性、当前模型及游标身份复核。
     */
    List<AlarmInstance> findByDevices(UUID tenantId, UUID projectId, List<UUID> deviceIds,
            Set<AlarmInstance.ConditionState> conditionStates, Set<AlarmInstance.AckState> ackStates,
            Set<AlarmRule.Severity> severities, Instant beforeUpdatedAt, UUID beforeId, int candidateLimit);
    /** @return 实例事件页 */ CursorPage<AlarmEvent> pageEvents(UUID projectId, UUID instanceId, String cursor, int limit);
    /**
     * 统计项目中被 ACTIVE 事故影响的不同设备数。
     *
     * <p>PENDING 仍处于持续触发确认窗口，不能计入运营告警率；同一设备的多条 ACTIVE 事故只计一次。</p>
     *
     * @param projectId 项目 ID
     * @return ACTIVE 实例关联的去重设备数
     */
    long countDistinctActiveDevices(UUID projectId);
    /** 项目设备最高ACTIVE严重度，按设备UUID升序键集分页；ACK不影响归类。 */
    List<DeviceSeverity> activeDeviceSeverities(UUID projectId, UUID afterDeviceId, int limit);
    /** 请求集合内有 ACTIVE 实例的设备，最多20项，不受实例分页影响。 */
    Set<UUID> activeDeviceIds(UUID projectId, List<UUID> deviceIds);
    record DeviceSeverity(UUID deviceId, AlarmRule.Severity severity) { }

    /** @param instance 新事故 @return 插入是否成功，活动唯一约束可仲裁并发 */ boolean create(AlarmInstance instance);
    /** @param instance 带期望 version 的完整新快照 @return CAS 是否成功 */ boolean update(AlarmInstance instance);
    /** @param event 不可变迁移事实 @return 是否真正写入；重复 source message/type 返回 false */ boolean appendEvent(AlarmEvent event);
    /** 查询当前活动实例，若收到时间较旧则不推进，防止乱序消息倒退持续窗口。 */
    default boolean acceptsReceivedAt(AlarmInstance instance, Instant receivedAt) {
        return !receivedAt.isBefore(instance.lastReceivedAt());
    }
}
