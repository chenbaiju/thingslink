package com.things.link.project.domain;

import java.util.OptionalLong;
import java.util.Optional;
import java.util.UUID;

/** S14-R4：同一数据库快照读取可信项目所属租户的有效容量。 */
public interface PlanCapacityRepository {
    /** 已授权项目的终端用户上限，缺合法套餐或归属不匹配返回空。 */
    OptionalLong findEndUsersLimit(UUID tenantId, UUID projectId);

    /** 已授权项目所属租户的看板上限，缺合法投影返回空。 */
    OptionalLong findDashboardsLimit(UUID tenantId, UUID projectId);

    /** 已确权项目所属租户的去重外部席位上限。 */
    OptionalLong findExternalSeatsLimit(UUID tenantId, UUID projectId);
    /** 有效历史窗口；无投影、归属不符或无法表达的日期返回空。 */
    Optional<PlanHistoryWindow> findHistoryWindow(UUID tenantId, UUID projectId);
}
