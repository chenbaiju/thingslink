package com.things.link.dashboard.application.publication;

import java.util.UUID;

/** 提供当前已交付运行数据适配器的精确能力资格事实。 */
public interface DashboardDataAdapterQualificationPort {

    /**
     * 判断指定项目能否按需求提供完整、有界且授权正确的数据适配。
     *
     * @param projectId 已确权项目ID
     * @param requirement Schema派生的精确能力需求
     * @return 生产能力完整交付时为true
     */
    boolean supports(UUID projectId, DashboardPublicationEligibilityRequirement.DataAdapter requirement);

    /**
     * 判断精确粒度与聚合组合是否具有已交付的有界历史适配能力。
     *
     * @param projectId 已确权项目ID
     * @param requirement 包含精确粒度与聚合的历史属性需求
     * @return 完整历史组合已交付时为true
     */
    boolean supportsHistory(
            UUID projectId, DashboardPublicationEligibilityRequirement.HistoricalProperty requirement);
}
