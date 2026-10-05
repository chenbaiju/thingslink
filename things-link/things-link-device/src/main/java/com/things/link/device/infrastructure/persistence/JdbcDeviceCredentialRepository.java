package com.things.link.device.infrastructure.persistence;

import com.things.link.device.domain.DeviceCredential;
import com.things.link.device.domain.DeviceCredentialRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 以显式 SQL 持久化设备凭据。 */
@Repository
public class JdbcDeviceCredentialRepository implements DeviceCredentialRepository {
    private final JdbcTemplate jdbcTemplate;
    public JdbcDeviceCredentialRepository(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    @Override public void create(DeviceCredential c) {
        jdbcTemplate.update("""
                INSERT INTO dev_credential
                    (id, tenant_id, project_id, device_id, auth_type, credential_hash,
                     display_name, serial_number, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, c.id(), c.tenantId(), c.projectId(), c.deviceId(), c.authType().name(),
                c.credentialHash(), c.displayName(), c.serialNumber(), c.expiresAt());
    }

    @Override public List<DeviceCredential> findByDevice(UUID projectId, UUID deviceId) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, project_id, device_id, auth_type, credential_hash,
                       display_name, serial_number, expires_at, last_used_at, created_at
                  FROM dev_credential
                 WHERE project_id = ? AND device_id = ? AND deleted_at IS NULL
                 ORDER BY created_at DESC
                """, this::map, projectId, deviceId);
    }

    @Override public Optional<DeviceCredential> findById(UUID projectId, UUID deviceId, UUID id) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, project_id, device_id, auth_type, credential_hash,
                       display_name, serial_number, expires_at, last_used_at, created_at
                  FROM dev_credential WHERE project_id = ? AND device_id = ? AND id = ? AND deleted_at IS NULL
                """, this::map, projectId, deviceId, id).stream().findFirst();
    }

    @Override public void revokeByDeviceAndType(UUID projectId, UUID deviceId, DeviceCredential.AuthType authType) {
        jdbcTemplate.update("""
                UPDATE dev_credential SET deleted_at = now(), updated_at = now()
                 WHERE project_id = ? AND device_id = ? AND auth_type = ? AND deleted_at IS NULL
                """, projectId, deviceId, authType.name());
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public long incrementCredentialVersion(UUID projectId, UUID deviceId) {
        Long version = jdbcTemplate.queryForObject("""
                UPDATE dev_device
                   SET credential_version = credential_version + 1,
                       updated_at = now()
                 WHERE project_id = ? AND id = ? AND deleted_at IS NULL
                 RETURNING credential_version
                """, Long.class, projectId, deviceId);
        if (version == null) {
            throw new IllegalStateException("设备凭据版本递增未返回结果");
        }
        return version;
    }

    @Override public boolean softDelete(UUID projectId, UUID deviceId, UUID id) {
        return jdbcTemplate.update("""
                UPDATE dev_credential SET deleted_at = now(), updated_at = now()
                 WHERE project_id = ? AND device_id = ? AND id = ? AND deleted_at IS NULL
                """, projectId, deviceId, id) == 1;
    }

    private DeviceCredential map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp expires = rs.getTimestamp("expires_at"),
                  lastUsed = rs.getTimestamp("last_used_at");
        return new DeviceCredential(rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getObject("device_id", UUID.class),
                DeviceCredential.AuthType.valueOf(rs.getString("auth_type")),
                rs.getString("credential_hash"), rs.getString("display_name"),
                rs.getString("serial_number"), expires == null ? null : expires.toInstant(),
                lastUsed == null ? null : lastUsed.toInstant(), null, rs.getTimestamp("created_at").toInstant());
    }
}
