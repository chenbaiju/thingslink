package com.things.link.dashboard.application;

import java.util.List;
import java.util.UUID;

/** @param variableKey 冻结变量键 @param deviceIds 该变量冻结候选设备，不含模型或全项目目录 */
public record DashboardShareVariableScopeView(String variableKey, List<UUID> deviceIds) {
    /** 防御复制，禁止响应组装改变冻结候选集合。 */
    public DashboardShareVariableScopeView { deviceIds = List.copyOf(deviceIds); }
}
