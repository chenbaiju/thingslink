package com.things.link.assistant.application;

import com.things.link.alarm.application.AlarmDeviceExpectation;
import com.things.link.alarm.application.AlarmDeviceQueryItem;
import com.things.link.alarm.application.ConsoleAlarmDeviceQueryService;
import com.things.link.device.application.ConsoleDeviceEvidenceService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

/** 受权只读告警工具；查询留在告警域，投影和最终确权不提供模型调用许可。 */
@Service
public class AlarmEvidenceService {
    private static final List<String> CONDITIONS = List.of("PENDING", "ACTIVE", "CLEARED");
    private static final List<String> ACKNOWLEDGEMENTS = List.of("UNACKNOWLEDGED", "ACKNOWLEDGED");
    private static final List<String> SEVERITIES = List.of("CRITICAL", "MAJOR", "MINOR", "WARNING", "INFO");
    private final ConsoleAlarmDeviceQueryService alarms;
    private final ConsoleDeviceEvidenceService devices;
    private final Clock clock;

    /**
     * 装配所属域中性读取端口，不消费跨域仓储或告警领域对象。
     * @param alarms 带真实项目范围和签名游标的告警查询端口
     * @param devices 独立事务的当前成员和精确模型复核端口
     * @param clock 取证时钟，不改变告警域事件时间
     */
    public AlarmEvidenceService(ConsoleAlarmDeviceQueryService alarms, ConsoleDeviceEvidenceService devices, Clock clock) {
        this.alarms = alarms; this.devices = devices; this.clock = clock;
    }

    /**
     * 当前设备的事故页，按告警域更新时间与标识降序；不支持时间窗或全量诊断。
     * @param project 当前登录身份选择的项目
     * @param device 当前项目内单个设备
     * @param model 预期的精确当前模型版本
     * @param cursor 原样回传的账号和过滤绑定游标，首页为空
     * @param requestedLimit 页大小，默认二十，最多五十
     * @return 省略自由文本的事实页；撤权、模型变化或来源失败拒绝返回
     */
    public AlarmEvidence read(UUID project, UUID device, UUID model, String cursor, Integer requestedLimit) {
        int limit = requestedLimit == null ? 20 : requestedLimit;
        if (project == null || device == null || model == null || limit < 1 || limit > 50
                || cursor != null && (cursor.isBlank() || cursor.length() > 4096))
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
        devices.revalidate(project, device, model);
        var page = alarms.query(project, List.of(new AlarmDeviceExpectation(device, model)),
                CONDITIONS, ACKNOWLEDGEMENTS, SEVERITIES, cursor, limit);
        if (page == null || page.items().size() > limit
                || page.hasMore() != (page.nextCursor() != null)
                || page.nextCursor() != null && (page.nextCursor().isBlank() || page.nextCursor().length() > 4096)
                || page.items().stream().map(AlarmDeviceQueryItem::id).distinct().count() != page.items().size())
            throw new IllegalStateException("告警证据页不符合有界合同");
        var items = page.items().stream().map(item -> project(item, device)).toList();
        var evidence = new AlarmEvidence(project, device, model, clock.instant(), "NOT_PROVIDED",
                items, page.nextCursor(), page.hasMore(), limit);
        devices.revalidate(project, device, model);
        return evidence;
    }

    /**
     * 显式复制允许字段；自由文本告警类型从不进入工具响应。
     * @param item 所属域读取的单个事故
     * @param device 请求范围内的唯一设备
     * @return 封闭枚举和时间事实，来源身份或状态漂移时失败
     */
    private static AlarmEvidence.Item project(AlarmDeviceQueryItem item, UUID device) {
        if (item == null || item.id() == null || !device.equals(item.deviceId())
                || item.severity() == null || item.conditionState() == null || item.ackState() == null
                || !SEVERITIES.contains(item.severity()) || !CONDITIONS.contains(item.conditionState())
                || !ACKNOWLEDGEMENTS.contains(item.ackState()) || item.firstConditionAt() == null
                || item.lastReceivedAt() == null || item.version() < 0)
            throw new IllegalStateException("告警事故事实越过允许范围");
        return new AlarmEvidence.Item(item.id(), item.severity(), item.conditionState(), item.ackState(),
                item.firstConditionAt(), item.activatedAt(), item.clearedAt(), item.acknowledgedAt(),
                item.lastReceivedAt(), item.version());
    }
}
