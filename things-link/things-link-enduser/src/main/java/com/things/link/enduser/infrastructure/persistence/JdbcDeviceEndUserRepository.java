package com.things.link.enduser.infrastructure.persistence;

import com.things.link.enduser.domain.DeviceEndUserItem;
import com.things.link.enduser.domain.DeviceEndUserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 只在enduser域内关联三种授权事实；有效状态必须先于分页过滤。 */
@Repository
public class JdbcDeviceEndUserRepository implements DeviceEndUserRepository {
    private final JdbcTemplate jdbc;
    public JdbcDeviceEndUserRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public List<DeviceEndUserItem> find(UUID tenantId, UUID projectId, UUID deviceId,
            Instant beforeTime, UUID beforeId, int fetchLimit) {
        if ((beforeTime == null) != (beforeId == null) || fetchLimit < 1 || fetchLimit > 51)
            throw new IllegalArgumentException("设备终端用户查询边界不合法");
        String sql = """
                SELECT binding.id, binding.app_user_id, identity.display_name,
                       binding.relation_role, binding.created_at
                  FROM app_user_device binding
                  JOIN app_user identity
                    ON identity.tenant_id = binding.tenant_id AND identity.id = binding.app_user_id
                  JOIN app_user_role assignment
                    ON assignment.tenant_id = binding.tenant_id AND assignment.project_id = binding.project_id
                   AND assignment.app_user_id = binding.app_user_id
                 WHERE binding.tenant_id = ? AND binding.project_id = ? AND binding.device_id = ?
                   AND binding.status = 'ACTIVE' AND identity.status = 'ACTIVE' AND assignment.status = 'ACTIVE'
                """;
        List<Object> params = new ArrayList<>(List.of(tenantId, projectId, deviceId));
        if (beforeTime != null) {
            sql += " AND (binding.created_at,binding.id) < (?,?)";
            params.add(Timestamp.from(beforeTime)); params.add(beforeId);
        }
        sql += " ORDER BY binding.created_at DESC,binding.id DESC LIMIT ?";
        params.add(fetchLimit);
        return jdbc.query(sql, (r, row) -> new DeviceEndUserItem(r.getObject("id", UUID.class),
                r.getObject("app_user_id", UUID.class), r.getString("display_name"),
                r.getString("relation_role"), r.getTimestamp("created_at").toInstant()), params.toArray());
    }
}
