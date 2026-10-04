package com.things.link.dashboard.domain;

import java.util.List;
import java.util.UUID;

/**
 * 一个设备变量的冻结精确模型和候选集合，不允许由后续用户绑定自动扩容。
 * @param variableKey Schema变量键 @param modelVersionId 精确物模型版本 @param deviceIds 候选设备
 */
public record DashboardShareVariableScope(String variableKey, UUID modelVersionId, List<UUID> deviceIds) {
    /** 防御复制有界候选列表，避免资格验证后改变持久范围。 */
    public DashboardShareVariableScope { deviceIds = List.copyOf(deviceIds); }

}
