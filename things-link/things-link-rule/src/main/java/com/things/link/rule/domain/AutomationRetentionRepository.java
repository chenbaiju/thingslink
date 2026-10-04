package com.things.link.rule.domain;

import java.util.List;
import java.util.UUID;

/** 固定保留合同清理；不接受外部时间、表名或任意删除条件。 */
public interface AutomationRetentionRepository {
    record Scope(UUID tenantId,UUID projectId) {}
    List<Scope> candidates(UUID afterProjectId);
    int purgeInputs();
    int purgeIngressRejections();
    int purgeHistory(Scope scope);
}
