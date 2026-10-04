package com.things.link.alarm.application;

import com.things.link.alarm.domain.AlarmInstance;
import com.things.link.alarm.domain.AlarmRule;
import com.things.link.device.application.DeviceRuntimeDataService;
import com.things.link.device.application.RuntimeDeviceAvailability;
import com.things.link.device.application.RuntimeDeviceQuery;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.page.CursorPage;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.query.SignedQueryCursorCodec;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** 数据运行合同§3.6：Console按当前项目成员和设备真实范围读取，不借App令牌或绑定。 */
@Service
public class ConsoleAlarmDeviceQueryService {
    /** 与App告警游标独立的用途，防止身份种类之间重放。 */
    private static final String CURSOR_PURPOSE = "CONSOLE_ALARM_QUERY";
    /** 项目成员与权威租户归属。 */
    private final ProjectService projects;
    /** 普通只读事务的可信二轴范围。 */
    private final TransactionLocalRlsScope scope;
    /** 指定设备当前可见性和模型事实。 */
    private final DeviceRuntimeDataService devices;
    /** 过滤前分页的告警域窄端口。 */
    private final AlarmDeviceQueryService alarms;
    /** 全项目统一签名游标，不自行创造密钥配置。 */
    private final SignedQueryCursorCodec cursors;

    /** 创建Console读取编排，权限、设备检查、告警查询在同一普通RLS只读事务。 */
    public ConsoleAlarmDeviceQueryService(ProjectService projects, TransactionLocalRlsScope scope,
            DeviceRuntimeDataService devices, AlarmDeviceQueryService alarms, SignedQueryCursorCodec cursors) {
        this.projects = projects;
        this.scope = scope;
        this.devices = devices;
        this.alarms = alarms;
        this.cursors = cursors;
    }

    /**
     * @param projectId 路径项目，必须仍为实际成员
     * @param requested 1..20个不同设备与当前模型预期
     * @param conditionStates 非空且不重复的条件状态
     * @param ackStates 非空且不重复的确认状态
     * @param severities 非空且不重复的严重程度
     * @param cursor 上一页签名游标，缺省为首页
     * @param requestedLimit 可省略页大小，默认20且最大50
     * @return 最小事故事实及绑定Console身份/过滤的下一游标
     */
    @Transactional(readOnly = true)
    public CursorPage<AlarmDeviceQueryItem> query(UUID projectId, List<AlarmDeviceExpectation> requested,
            List<String> conditionStates, List<String> ackStates, List<String> severities, String cursor, Integer requestedLimit) {
        projects.requireRoleInProject(projectId);
        int limit = requestedLimit == null ? 20 : requestedLimit;
        if (requested == null || requested.isEmpty() || requested.size() > 20 || limit < 1 || limit > 50
                || requested.stream().anyMatch(item -> item == null || item.deviceId() == null || item.expectedModelVersionId() == null)
                || requested.stream().map(AlarmDeviceExpectation::deviceId).distinct().count() != requested.size()) throw invalid();
        Set<String> conditions = filters(conditionStates, AlarmInstance.ConditionState.class);
        Set<String> acknowledgements = filters(ackStates, AlarmInstance.AckState.class);
        Set<String> levels = filters(severities, AlarmRule.Severity.class);
        UUID tenant = projects.requireProjectTenant(projectId);
        UUID actor = TenantContext.current().orElseThrow(() -> new IllegalStateException("缺少Console可信身份")).accountId();
        if (actor == null) throw new IllegalStateException("缺少Console账号身份");
        scope.establish(tenant, projectId);
        String binding = tenant + "|" + projectId + "|CONSOLE|" + actor + "|" + limit + "|"
                + requested.stream().map(item -> item.deviceId() + ":" + item.expectedModelVersionId()).sorted().collect(Collectors.joining(","))
                + "|" + ordered(conditions) + "|" + ordered(acknowledgements) + "|" + ordered(levels) + "|updatedAt,id:DESC";
        var anchor = cursors.decode(cursor, CURSOR_PURPOSE, binding);
        List<RuntimeDeviceAvailability> availability = devices.inspect(projectId, requested.stream()
                .map(item -> new RuntimeDeviceQuery(item.deviceId(), item.expectedModelVersionId(), List.of())).toList());
        if (availability.size() != requested.size()) throw new IllegalStateException("设备批量检查结果数量漂移");
        for (int index = 0; index < requested.size(); index++) {
            if (!requested.get(index).deviceId().equals(availability.get(index).deviceId())) {
                throw new IllegalStateException("设备批量检查结果身份漂移");
            }
        }
        devices.requireAllVisible(availability);
        // 只有全部设备可见之后才处理模型失配，不能用一个配置错误掩盖另一设备失权。
        for (int index = 0; index < requested.size(); index++) {
            RuntimeDeviceAvailability item = availability.get(index);
            if (item.status() == RuntimeDeviceAvailability.Status.MODEL_MISMATCH) throw invalid();
            if (!requested.get(index).expectedModelVersionId().equals(item.currentModelVersionId())) {
                throw new IllegalStateException("设备可用状态与模型精确身份不一致");
            }
        }
        AlarmDeviceQueryPage page = alarms.query(tenant, projectId, requested.stream().map(AlarmDeviceExpectation::deviceId).toList(),
                conditions, acknowledgements, levels, anchor.map(SignedQueryCursorCodec.Anchor::sortTime).orElse(null),
                anchor.map(SignedQueryCursorCodec.Anchor::sortId).orElse(null), limit);
        return page.hasMore() ? CursorPage.of(page.items(), cursors.encode(CURSOR_PURPOSE, binding, page.nextUpdatedAt(), page.nextId()))
                : CursorPage.last(page.items());
    }

    /** 列表不能先转Set吞掉重复输入；再用本域枚举验证规范机器值。 */
    private static <E extends Enum<E>> Set<String> filters(List<String> values, Class<E> type) {
        if (values == null || values.isEmpty() || values.size() > type.getEnumConstants().length
                || new HashSet<>(values).size() != values.size()) throw invalid();
        try {
            values.forEach(value -> Enum.valueOf(type, value));
            return Set.copyOf(values);
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw invalid();
        }
    }

    /** 固定顺序使相同集合的不同输入排列共享同一游标身份。 */
    private static String ordered(Set<String> values) {
        return values.stream().sorted().collect(Collectors.joining(","));
    }

    /** 配置或分页输入非法不影响Console认证会话。 */
    private static BusinessException invalid() {
        return new BusinessException(CommonErrorCode.INVALID_PARAMETER);
    }
}
