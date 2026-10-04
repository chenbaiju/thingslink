package com.things.link.device.infrastructure.persistence;

import com.things.link.device.application.OtaDeviceTypeIdentity;
import com.things.link.device.application.OtaDeviceTypeIdentityPort;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** device域内只读投影，沿调用方事务/RLS连接查询，不把dev表读取权授予OTA。 */
@Repository
public class JdbcOtaDeviceTypeIdentityAdapter implements OtaDeviceTypeIdentityPort {
    /** 调用方真实事务连接，不新建owner连接。 */
    private final JdbcTemplate jdbc;

    /** 显式装配本域查询入口。 */
    public JdbcOtaDeviceTypeIdentityAdapter(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** 精确三轴且只返回已发布未删除类型，不通过管理角色限制内部设备调用者。 */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<OtaDeviceTypeIdentity> find(UUID tenantId, UUID projectId, UUID deviceTypeId) {
        if (tenantId == null || projectId == null || deviceTypeId == null) return Optional.empty();
        return jdbc.query("""
                SELECT tenant_id, project_id, id, product_key FROM dev_type
                WHERE tenant_id=? AND project_id=? AND id=? AND status='PUBLISHED'
                  AND deleted_at IS NULL AND product_key IS NOT NULL AND product_key<>''
                FOR SHARE
                """, (rs, row) -> new OtaDeviceTypeIdentity(rs.getObject("tenant_id", UUID.class),
                rs.getObject("project_id", UUID.class), rs.getObject("id", UUID.class), rs.getString("product_key")),
                tenantId, projectId, deviceTypeId).stream().findFirst();
    }
}
