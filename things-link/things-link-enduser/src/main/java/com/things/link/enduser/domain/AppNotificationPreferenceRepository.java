package com.things.link.enduser.domain;

import java.util.UUID;

/** 账号偏好仓储，只能在可信租户RLS范围内操作。 */
public interface AppNotificationPreferenceRepository {
    /** 读取本人偏好，缺行返回默认开启及零版本。 */
    AppNotificationPreference read(UUID tenantId, UUID userId);
    /** 在持有稳定用户锁的原事务写入新事实，不能绕过应用层版本仲裁。 */
    void save(UUID tenantId, UUID userId, AppNotificationPreference preference);
}
