package com.things.link.alarm.application;

import com.things.link.alarm.domain.AlarmInstance;
import com.things.link.alarm.domain.AlarmInstanceRepository;
import com.things.link.alarm.domain.AlarmRule;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * App与Console共用的按设备告警窄查询端口，不代替调用方的身份、设备可见性和当前模型检查。
 * 数据运行合同§3.5/3.6要求普通RLS内先过滤再分页，不能以Console全项目页裁剪派生App结果。
 */
@Service
public class AlarmDeviceQueryService {
    /** 告警域独占的有界查询仓储。 */
    private final AlarmInstanceRepository repository;

    /** 创建纯读取端口，不引入写许可或跨域表查询。 */
    public AlarmDeviceQueryService(AlarmInstanceRepository repository) {
        this.repository = repository;
    }

    /**
     * @param tenantId 调用方权威租户
     * @param projectId 调用方权威项目
     * @param deviceIds 全部已验证可见且当前模型符合的1..20个不同设备
     * @param conditionStates 非空条件状态集合
     * @param ackStates 非空确认状态集合
     * @param severities 非空严重程度集合
     * @param beforeUpdatedAt 已验证游标的排他更新时间锚点
     * @param beforeId 已验证游标的排他ID锚点
     * @param limit 1..50页大小
     * @return 最多一页及用于签名的下一页内部锚点
     */
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    public AlarmDeviceQueryPage query(UUID tenantId, UUID projectId, List<UUID> deviceIds,
            Set<String> conditionStates, Set<String> ackStates, Set<String> severities,
            Instant beforeUpdatedAt, UUID beforeId, int limit) {
        if (tenantId == null || projectId == null || deviceIds == null || deviceIds.isEmpty() || deviceIds.size() > 20
                || deviceIds.stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(deviceIds).size() != deviceIds.size() || limit < 1 || limit > 50
                || (beforeUpdatedAt == null) != (beforeId == null)) {
            throw invalid();
        }
        Set<AlarmInstance.ConditionState> conditions = parse(conditionStates, AlarmInstance.ConditionState.class);
        Set<AlarmInstance.AckState> acknowledgements = parse(ackStates, AlarmInstance.AckState.class);
        Set<AlarmRule.Severity> levels = parse(severities, AlarmRule.Severity.class);
        List<AlarmInstance> rows = repository.findByDevices(tenantId, projectId, deviceIds,
                conditions, acknowledgements, levels, beforeUpdatedAt, beforeId, limit + 1);
        if (rows.size() > limit + 1 || rows.stream().anyMatch(row -> !tenantId.equals(row.tenantId())
                || !projectId.equals(row.projectId()) || row.originatorType() != AlarmRule.OriginatorType.DEVICE
                || !deviceIds.contains(row.originatorId()) || !conditions.contains(row.conditionState())
                || !acknowledgements.contains(row.ackState()) || !levels.contains(row.severity()))) {
            throw new IllegalStateException("按设备告警查询返回范围或过滤外的事实");
        }
        boolean more = rows.size() > limit;
        List<AlarmInstance> visible = more ? rows.subList(0, limit) : rows;
        AlarmInstance last = more ? visible.getLast() : null;
        return new AlarmDeviceQueryPage(visible.stream().map(AlarmDeviceQueryService::item).toList(),
                last == null ? null : last.updatedAt(), last == null ? null : last.id(), more);
    }

    /** 对外只接受闭集机器值，禁止枚举错误穿透为内部500。 */
    private static <E extends Enum<E>> Set<E> parse(Set<String> values, Class<E> type) {
        if (values == null || values.isEmpty() || values.size() > type.getEnumConstants().length) throw invalid();
        try {
            return values.stream().map(value -> Enum.valueOf(type, value)).collect(Collectors.toUnmodifiableSet());
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw invalid();
        }
    }

    /** 显式投影闭集字段，版本保持既有int而不擅改告警CAS合同。 */
    private static AlarmDeviceQueryItem item(AlarmInstance value) {
        return new AlarmDeviceQueryItem(value.id(), value.originatorId(), value.alarmType(), value.severity().name(),
                value.conditionState().name(), value.ackState().name(), value.firstConditionAt(), value.activatedAt(),
                value.clearedAt(), value.acknowledgedAt(), value.lastReceivedAt(), value.version());
    }

    /** 普通请求范围/过滤/页大小非法统一10001。 */
    private static BusinessException invalid() {
        return new BusinessException(CommonErrorCode.INVALID_PARAMETER);
    }
}
