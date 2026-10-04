package com.things.link.dashboard.application;

import tools.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 匿名分享最小恢复上下文，公开整个不可变看板的有限候选范围。
 * @param shareId 分享ID @param dashboardId 看板 @param dashboardVersionId 精确版本
 * @param dashboardVersionNumber 展示版本号 @param expiresAt 到期时刻 @param historyAnchorAt 本轮数据库历史锚点
 * @param hostCompatibility 签发时冻结宿主范围 @param variableScopes 冻结变量候选，不扩容
 */
public record DashboardShareContext(UUID shareId, UUID dashboardId, UUID dashboardVersionId,
        long dashboardVersionNumber, Instant expiresAt, Instant historyAnchorAt,
        JsonNode hostCompatibility, List<DashboardShareVariableScopeView> variableScopes) {
    /** JSON和集合均隔离调用方修改。 */
    public DashboardShareContext {
        hostCompatibility = hostCompatibility.deepCopy();
        variableScopes = List.copyOf(variableScopes);
    }
    /** @return 冻结HostRange副本 */
    @Override public JsonNode hostCompatibility() { return hostCompatibility.deepCopy(); }
}
