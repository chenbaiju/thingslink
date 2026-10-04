package com.things.link.device.infrastructure.persistence;

import com.things.link.device.application.OtaDeviceIdentity;
import com.things.link.device.application.OtaDeviceIdentityPort;
import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** device本域当前身份锁投影，沿真实RLS事务读取本域配置及凭据，不跨域查表。 */
@Repository
public class JdbcOtaDeviceIdentityAdapter implements OtaDeviceIdentityPort {
    /** 绑定调用者事务及RLS的普通连接。 */
    private final JdbcTemplate jdbc;

    /** 显式注入当前数据库入口。 */
    public JdbcOtaDeviceIdentityAdapter(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc, "jdbc"); }

    /** 先定位类型，再锁类型和设备并重查绑定；不在设备锁后获取凭据锁。 */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<OtaDeviceIdentity> lockCurrent(AuthenticatedDeviceIdentity authenticated) {
        if (authenticated == null) return Optional.empty();
        var typeIds = jdbc.query("""
                SELECT device_type_id FROM dev_device
                WHERE tenant_id=? AND project_id=? AND id=? AND deleted_at IS NULL
                """, (rs, row) -> rs.getObject("device_type_id", UUID.class),
                authenticated.tenantId(), authenticated.projectId(), authenticated.deviceId());
        if (typeIds.isEmpty() || typeIds.getFirst() == null) return Optional.empty();
        UUID typeId = typeIds.getFirst();
        var types = jdbc.query("""
                SELECT product_key FROM dev_type
                WHERE tenant_id=? AND project_id=? AND id=? AND status='PUBLISHED'
                  AND device_kind='DIRECT' AND deleted_at IS NULL AND product_key IS NOT NULL AND product_key<>''
                FOR SHARE
                """, (rs, row) -> rs.getString("product_key"), authenticated.tenantId(), authenticated.projectId(), typeId);
        if (types.isEmpty()) return Optional.empty();
        var current = jdbc.query("""
                SELECT d.tenant_id,d.project_id,d.id,d.device_type_id,d.credential_version,d.thing_model_version_id,
                       v.digest_algorithm,v.schema_digest,v.schema_profile
                FROM dev_device d JOIN dev_thing_model_version v
                  ON v.tenant_id=d.tenant_id AND v.project_id=d.project_id
                 AND v.device_type_id=d.device_type_id AND v.id=d.thing_model_version_id
                WHERE d.tenant_id=? AND d.project_id=? AND d.id=? AND d.device_type_id=?
                  AND d.credential_version=? AND d.deleted_at IS NULL
                FOR SHARE OF d
                """, (rs, row) -> new OtaDeviceIdentity(rs.getObject("tenant_id", UUID.class),
                rs.getObject("project_id", UUID.class), rs.getObject("id", UUID.class),
                rs.getObject("device_type_id", UUID.class), types.getFirst(), rs.getLong("credential_version"),
                rs.getObject("thing_model_version_id", UUID.class), rs.getString("digest_algorithm"),
                rs.getString("schema_digest"), rs.getString("schema_profile"), null),
                authenticated.tenantId(), authenticated.projectId(), authenticated.deviceId(), typeId,
                authenticated.credentialVersion()).stream().findFirst();
        if (current.isEmpty() || !DeviceMqttCapability.allowed(jdbc, authenticated.tenantId(),
                authenticated.projectId(), authenticated.deviceId())) return Optional.empty();
        // 普通 MVCC 读取不额外获取凭据行锁，设备锁已阻止凭据轮换提交。
        // 使用真实时钟，不能让事务开始时now()掩盖锁等待期间的自然到期。
        var expiries = jdbc.query("""
                SELECT expires_at FROM dev_credential
                WHERE tenant_id=? AND project_id=? AND device_id=? AND auth_type='ACCESS_TOKEN'
                  AND deleted_at IS NULL AND (expires_at IS NULL OR expires_at>clock_timestamp())
                """, (rs, row) -> {
                    var timestamp = rs.getTimestamp("expires_at");
                    return timestamp == null ? null : timestamp.toInstant();
                }, authenticated.tenantId(), authenticated.projectId(), authenticated.deviceId());
        if (expiries.size() != 1) return Optional.empty();
        var identity = current.orElseThrow();
        return Optional.of(new OtaDeviceIdentity(identity.tenantId(), identity.projectId(), identity.deviceId(),
                identity.deviceTypeId(), identity.productKey(), identity.credentialVersion(), identity.thingModelVersionId(),
                identity.schemaDigestAlgorithm(), identity.schemaDigest(), identity.schemaProfile(), expiries.getFirst()));
    }

    /** 不获取凭据行锁，设备锁已阻止轮换提交；只复核最终真实时钟与当前代际。 */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean credentialValid(AuthenticatedDeviceIdentity authenticated) {
        if (authenticated == null || !DeviceMqttCapability.allowed(jdbc, authenticated.tenantId(),
                authenticated.projectId(), authenticated.deviceId())) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM dev_device d JOIN dev_credential c
                  ON c.tenant_id=d.tenant_id AND c.project_id=d.project_id AND c.device_id=d.id
                  WHERE d.tenant_id=? AND d.project_id=? AND d.id=? AND d.credential_version=?
                    AND d.deleted_at IS NULL AND c.auth_type='ACCESS_TOKEN' AND c.deleted_at IS NULL
                    AND (c.expires_at IS NULL OR c.expires_at>clock_timestamp()))
                """, Boolean.class, authenticated.tenantId(), authenticated.projectId(),
                authenticated.deviceId(), authenticated.credentialVersion()));
    }

}
