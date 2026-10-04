package com.things.link.dashboard.application.publication;

import java.util.List;
import java.util.UUID;

/** @param variableKey 完整Schema中的设备变量 @param deviceIds 此变量冻结候选设备集合 */
public record DashboardShareVariableRequest(String variableKey, List<UUID> deviceIds) {
    /** 防御复制，非法null由业务边界统一60049拒绝。 */
    public DashboardShareVariableRequest {
        deviceIds = deviceIds == null ? null : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(deviceIds));
    }
}
