package com.things.link.ota.domain;

import java.util.List;
import java.util.UUID;

/** ADR0204只读原完成来源；调用方须处于原实际事务与双轴scope内。 */
public interface OtaJobCompletionRepository {
    /** 精确原事务分页，稳定jobId游标；每页1..100，不能扫描历史补发。 */
    List<OtaJobCompletion> page(UUID tenant, UUID project, String transaction, UUID afterJob, int limit);
}
