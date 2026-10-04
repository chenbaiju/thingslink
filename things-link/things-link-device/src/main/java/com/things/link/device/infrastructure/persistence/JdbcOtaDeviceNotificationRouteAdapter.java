package com.things.link.device.infrastructure.persistence;

import com.things.link.device.application.OtaDeviceNotificationRoute;
import com.things.link.device.application.OtaDeviceNotificationRoutePort;
import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 本域DIRECT当前路由，按类型→设备锁序，不反向获取凭据锁。 */
@Repository
public class JdbcOtaDeviceNotificationRouteAdapter implements OtaDeviceNotificationRoutePort {
    /** 调用者当前RLS连接。 */ private final JdbcTemplate jdbc;
    /** 显式注入数据库边界。 */
    public JdbcOtaDeviceNotificationRouteAdapter(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }
    /** 同一事务重查类型归属和代际，凭据自然到期使用数据库实际时钟。 */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<OtaDeviceNotificationRoute> lockCurrent(AuthenticatedDeviceIdentity identity) {
        if (identity == null) return Optional.empty();
        var types = jdbc.query("SELECT device_type_id FROM dev_device WHERE tenant_id=? AND project_id=?"
                + " AND id=? AND deleted_at IS NULL", (rs, row) -> rs.getObject(1, UUID.class),
                identity.tenantId(), identity.projectId(), identity.deviceId());
        if (types.size() != 1 || types.getFirst() == null) return Optional.empty();
        UUID type = types.getFirst();
        var locked = jdbc.query("SELECT id FROM dev_type WHERE tenant_id=? AND project_id=? AND id=?"
                + " AND status='PUBLISHED' AND device_kind='DIRECT' AND deleted_at IS NULL FOR SHARE",
                (rs, row) -> rs.getObject(1, UUID.class), identity.tenantId(), identity.projectId(), type);
        if (locked.size() != 1) return Optional.empty();
        var routes = jdbc.query("""
                SELECT device_key FROM dev_device WHERE tenant_id=? AND project_id=? AND id=?
                  AND device_type_id=? AND credential_version=? AND deleted_at IS NULL FOR SHARE
                """, (rs, row) -> rs.getString(1), identity.tenantId(), identity.projectId(), identity.deviceId(),
                type, identity.credentialVersion());
        if (routes.size() != 1 || routes.getFirst() == null
                || !routes.getFirst().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) return Optional.empty();
        if (!DeviceMqttCapability.allowed(jdbc, identity.tenantId(), identity.projectId(),
                identity.deviceId())) return Optional.empty();
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM dev_credential WHERE tenant_id=? AND project_id=? AND device_id=?
                  AND auth_type='ACCESS_TOKEN' AND deleted_at IS NULL
                  AND (expires_at IS NULL OR expires_at>clock_timestamp())
                """, Long.class, identity.tenantId(), identity.projectId(), identity.deviceId());
        if (count == null || count != 1) return Optional.empty();
        return Optional.of(new OtaDeviceNotificationRoute(identity.tenantId(), identity.projectId(), identity.deviceId(),
                identity.credentialVersion(), routes.getFirst()));
    }
}
