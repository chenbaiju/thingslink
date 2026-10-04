package com.things.link.device.infrastructure.persistence;

import com.things.link.device.application.DeviceMqttDownlinkRoute;
import com.things.link.device.application.DeviceMqttDownlinkRoutePort;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 项目公开许可加设备本域事实；禁止跨模块查表或在发送网络期间持锁。 */
@Repository
public class JdbcDeviceMqttDownlinkRouteAdapter implements DeviceMqttDownlinkRoutePort {
    /** 原业务数据库连接。 */ private final JdbcTemplate jdbc;
    /** 精确身份范围。 */ private final TransactionLocalRlsScope rls;
    /** 项目生命周期许可。 */ private final ProjectLifecycleAccessService lifecycle;
    /** 项目键只通过项目公开端口读取。 */ private final ProjectService projects;

    /** 显式装配当前事务依赖。 */
    public JdbcDeviceMqttDownlinkRouteAdapter(JdbcTemplate jdbc, TransactionLocalRlsScope rls,
            ProjectLifecycleAccessService lifecycle, ProjectService projects) {
        this.jdbc=jdbc; this.rls=rls; this.lifecycle=lifecycle; this.projects=projects;
    }

    /** 锁等待后使用新语句读取配置，历史缺行才允许版本0。 */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<DeviceMqttDownlinkRoute> lockCurrent(UUID tenantId, UUID projectId, UUID deviceId) {
        rls.establish(tenantId, projectId);
        if (!lifecycle.lockActiveForWrite(tenantId, projectId)) return Optional.empty();
        var devices = jdbc.queryForList("""
                SELECT device_key FROM dev_device WHERE tenant_id=? AND project_id=? AND id=?
                  AND deleted_at IS NULL FOR SHARE
                """, String.class, tenantId, projectId, deviceId);
        if (devices.size() != 1) return Optional.empty();
        var bindings = jdbc.query("""
                SELECT tenant_id,protocol,enabled,config_version FROM dev_access_binding
                WHERE project_id=? AND device_id=?
                """, (rs,row)->new Binding(rs.getObject("tenant_id",UUID.class),rs.getString("protocol"),
                rs.getBoolean("enabled"),rs.getLong("config_version")),projectId,deviceId);
        if (!bindings.isEmpty() && (!bindings.getFirst().tenantId().equals(tenantId)
                || !bindings.getFirst().enabled() || !"MQTT".equals(bindings.getFirst().protocol()))) return Optional.empty();
        var project = projects.requireRoutingContext(projectId);
        if (!project.tenantId().equals(tenantId)) return Optional.empty();
        return Optional.of(new DeviceMqttDownlinkRoute(tenantId,projectId,deviceId,
                bindings.isEmpty()?0:bindings.getFirst().version(),project.projectKey(),devices.getFirst()));
    }

    /** 配置仅在本域映射，不外泄持久表模型。 */
    private record Binding(UUID tenantId,String protocol,boolean enabled,long version) { }
}
