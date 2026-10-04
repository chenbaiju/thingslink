package com.things.link.dashboard.application.publication;

import tools.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

/**
 * 分享签发的严格命令，不携带客户端绝对到期时刻或secret。
 * @param dashboardVersionId 精确已发布历史版本
 * @param expectedDashboardPublicationRevision 已观察发布代次
 * @param expiresInSeconds 数据库时钟相对TTL
 * @param refererPolicy NONE或HOST_ORIGIN
 * @param hostCompatibility 有限宿主SemVer区间
 * @param variables 全部设备变量的候选scope
 */
public record DashboardShareCreateRequest(UUID dashboardVersionId, String expectedDashboardPublicationRevision,
        int expiresInSeconds, String refererPolicy, JsonNode hostCompatibility,
        List<DashboardShareVariableRequest> variables) {
    /** 保留非法null供服务统一分类，但不允许调用者之后改变签发范围。 */
    public DashboardShareCreateRequest {
        hostCompatibility = hostCompatibility == null ? null : hostCompatibility.deepCopy();
        variables = variables == null ? null : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(variables));
    }
    /** JSON属于命令冻结内容，取出时不能修改后续摘要。 */
    @Override public JsonNode hostCompatibility() { return hostCompatibility == null ? null : hostCompatibility.deepCopy(); }
}
