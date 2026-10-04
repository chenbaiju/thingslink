package com.things.link.project.domain;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** ADR0164：平台授权布尔投影与单语句租户审批快照。 */
public interface CommercialOperatorRepository {
    /** 写路径必须holdLock，锁在调用者RC事务内持有。 */
    boolean enabled(UUID actor, boolean holdLock);
    /** 只读取有合法套餐的当前租户；无全平台枚举。 */
    Optional<Snapshot> snapshot(UUID tenant);
    /** 数值均是十进制精确字符串，不经JSON浮点。 */
    record Snapshot(String tenantName, long assignmentVersion, Map<String,String> effectiveAmounts) { }
}
