package com.things.link.dashboard.infrastructure.persistence;

import com.things.link.dashboard.domain.DashboardShareRuntimeIdentity;
import com.things.link.dashboard.domain.DashboardShareRuntimeRepository;
import com.things.link.dashboard.domain.DashboardShareRuntimeState;
import com.things.link.dashboard.domain.DashboardShareToken;
import com.things.link.dashboard.domain.DashboardShareVariableScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** ADR0101最小匿名定位、token状态与有限scope读取，只有首次定位可调用固定definer函数。 */
@Repository
public class JdbcDashboardShareRuntimeRepository implements DashboardShareRuntimeRepository {
    /** 只解释已由数据库保存的JSONB，不用格式条件把损坏事实过滤为404。 */
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 加入调用方只读事务的普通APP数据库入口。 */
    private final JdbcTemplate jdbc;

    /** @param jdbc 已配置项目RLS的事务感知连接 */
    public JdbcDashboardShareRuntimeRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public Optional<DashboardShareRuntimeIdentity> locate(UUID shareId, String secretHash) {
        return jdbc.query("SELECT tenant_id, project_id FROM public.resolve_dashboard_share_identity(?,?)",
                (row, index) -> new DashboardShareRuntimeIdentity(row.getObject("tenant_id", UUID.class),
                        row.getObject("project_id", UUID.class)), shareId, secretHash).stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<DashboardShareRuntimeState> findState(UUID tenantId, UUID projectId, UUID shareId, String secretHash) {
        return jdbc.query("""
                SELECT token.*, clock_timestamp() AS database_now, version.version_number,
                       (dashboard.id IS NOT NULL AND dashboard.deleted_at IS NULL
                        AND dashboard.current_version_id IS NOT NULL) AS dashboard_runnable
                  FROM public.dash_share_token token
                  JOIN public.dash_share_creation_result creation
                    ON creation.tenant_id=token.tenant_id AND creation.project_id=token.project_id
                   AND creation.dashboard_id=token.dashboard_id AND creation.share_id=token.id
                  LEFT JOIN public.dash_dashboard dashboard
                    ON dashboard.tenant_id=token.tenant_id AND dashboard.project_id=token.project_id
                   AND dashboard.id=token.dashboard_id
                  LEFT JOIN public.dash_dashboard_version version
                    ON version.tenant_id=token.tenant_id AND version.project_id=token.project_id
                   AND version.dashboard_id=token.dashboard_id AND version.id=token.dashboard_version_id
                 WHERE token.tenant_id=? AND token.project_id=? AND token.id=? AND token.secret_hash=?
                """, (row, index) -> new DashboardShareRuntimeState(token(row), row.getBoolean("dashboard_runnable"),
                row.getLong("version_number"), row.getTimestamp("database_now").toInstant()),
                tenantId, projectId, shareId, secretHash).stream().findFirst();
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<DashboardShareVariableScope> findScopes(UUID tenantId, UUID projectId, UUID shareId) {
        return jdbc.query("""
                SELECT scope.variable_key, scope.thing_model_version_id, scope.position,
                       COALESCE((SELECT jsonb_agg(device.device_id ORDER BY device.position)
                           FROM public.dash_share_scope_device device
                          WHERE device.tenant_id=scope.tenant_id AND device.project_id=scope.project_id
                            AND device.share_id=scope.share_id AND device.variable_key=scope.variable_key), '[]'::jsonb)::text AS device_ids
                  FROM public.dash_share_scope scope
                 WHERE scope.tenant_id=? AND scope.project_id=? AND scope.share_id=?
                 ORDER BY scope.position LIMIT 21
                """, (row, index) -> {
            if (row.getInt("position") != index || index >= 20) throw new IllegalStateException("分享变量范围顺序或数量损坏");
            JsonNode ids = JSON.readTree(row.getString("device_ids"));
            if (!ids.isArray() || ids.isEmpty() || ids.size() > 20) throw new IllegalStateException("分享设备候选数量损坏");
            List<UUID> devices = new ArrayList<>();
            for (JsonNode id : ids) devices.add(UUID.fromString(id.stringValue()));
            return new DashboardShareVariableScope(row.getString("variable_key"),
                    row.getObject("thing_model_version_id", UUID.class), devices);
        }, tenantId, projectId, shareId);
    }

    /** 内部token行仅用于真实复验，不把凭据摘要直接投影为响应。 */
    private static DashboardShareToken token(ResultSet row) throws SQLException {
        return new DashboardShareToken(row.getObject("id", UUID.class), row.getObject("tenant_id", UUID.class),
                row.getObject("project_id", UUID.class), row.getObject("dashboard_id", UUID.class),
                row.getObject("dashboard_version_id", UUID.class), row.getLong("project_generation"),
                row.getString("secret_hash"), JSON.readTree(row.getString("host_compatibility")), row.getString("referer_policy"),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("expires_at").toInstant(),
                row.getObject("creator_account_id", UUID.class), instant(row.getTimestamp("revoked_at")),
                row.getObject("revoked_by", UUID.class));
    }

    /** 撤销空值不得被当前时间替代。 */
    private static Instant instant(Timestamp timestamp) { return timestamp == null ? null : timestamp.toInstant(); }
}
