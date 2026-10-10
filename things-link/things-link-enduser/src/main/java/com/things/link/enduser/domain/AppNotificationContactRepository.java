package com.things.link.enduser.domain;
import java.util.UUID;

/** 当前项目内接收号码，只能在可信双轴RLS范围读写。 */
public interface AppNotificationContactRepository {
    /** 缺行返回空号码与零版本，不查询其他项目。 */
    AppNotificationContact read(UUID tenantId, UUID projectId, UUID userId);
    /** 已持有用户锁且完成版本判定的原事务写入。 */
    void save(UUID tenantId, UUID projectId, UUID userId, AppNotificationContact contact);
}
