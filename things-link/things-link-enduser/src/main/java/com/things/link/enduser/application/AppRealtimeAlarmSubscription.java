package com.things.link.enduser.application;

import com.things.link.dashboard.application.DashboardRuntimeDeviceRequest;

import java.util.List;
import java.util.Set;

/**
 * ADR0109的独立App告警订阅域；过滤条件须与当前已授权Schema完整匹配。
 *
 * @param queryKey 本连接内固定短标识
 * @param devices 明确设备与预期模型，属性键必须为空
 * @param conditionStates 条件状态闭集
 * @param ackStates 确认状态闭集，不能解释为已读
 * @param severities 严重程度闭集
 */
public record AppRealtimeAlarmSubscription(String queryKey, List<DashboardRuntimeDeviceRequest> devices,
        Set<String> conditionStates, Set<String> ackStates, Set<String> severities) {
    /** 防御复制订阅域，避免发送前复验被外部可变集合改写。 */
    public AppRealtimeAlarmSubscription {
        devices = List.copyOf(devices);
        conditionStates = Set.copyOf(conditionStates);
        ackStates = Set.copyOf(ackStates);
        severities = Set.copyOf(severities);
    }
}
