package com.things.link.enduser.infrastructure.persistence;

import com.things.link.enduser.domain.AppNotificationPreference;
import com.things.link.enduser.domain.AppNotificationPreferenceRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.UUID;

/** 双身份条件与租户RLS共同保护账号级通知偏好。 */
@Repository
public class JdbcAppNotificationPreferenceRepository implements AppNotificationPreferenceRepository {
    private final JdbcTemplate jdbc;
    /** @param jdbc 租户感知数据库入口 */
    public JdbcAppNotificationPreferenceRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    /** {@inheritDoc} */
    @Override
    public AppNotificationPreference read(UUID tenantId, UUID userId) {
        return jdbc.query("SELECT app_push_enabled, revision FROM app_notification_preference WHERE tenant_id=? AND app_user_id=?",
                (rs, row) -> new AppNotificationPreference(rs.getBoolean(1), rs.getLong(2)), tenantId, userId)
                .stream().findFirst().orElse(new AppNotificationPreference(true, 0));
    }
    /** {@inheritDoc} */
    @Override
    public void save(UUID tenantId, UUID userId, AppNotificationPreference preference) {
        jdbc.update("""
                INSERT INTO app_notification_preference (tenant_id,app_user_id,app_push_enabled,revision)
                VALUES (?,?,?,?) ON CONFLICT (tenant_id,app_user_id) DO UPDATE
                SET app_push_enabled=EXCLUDED.app_push_enabled, revision=EXCLUDED.revision, updated_at=now()
                """, tenantId, userId, preference.appPushEnabled(), preference.revision());
    }
}
