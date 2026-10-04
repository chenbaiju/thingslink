package com.things.link.device.infrastructure.persistence;

import com.things.link.device.application.DeviceCommandReceiverPort;
import com.things.link.device.application.DeviceCommandReceiverRoute;
import com.things.link.device.domain.DeviceType;
import com.things.link.device.domain.DeviceTypeRepository;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 设备域当前关系校验；NOWAIT避免原接收者锁与拓扑写者之间增加反向等待边。 */
@Repository
public class JdbcDeviceCommandReceiverAdapter implements DeviceCommandReceiverPort {
    /** 本域查询与锁。 */ private final JdbcTemplate jdbc;
    /** 完整身份RLS。 */ private final TransactionLocalRlsScope rls;
    /** 类型非键字段的既有NOWAIT保护。 */ private final DeviceTypeRepository types;
    /** 项目持续许可。 */ private final ProjectLifecycleAccessService lifecycle;
    /** 项目键通过公开端口读取。 */ private final ProjectService projects;

    /** 显式装配原事务依赖。 */
    public JdbcDeviceCommandReceiverAdapter(JdbcTemplate jdbc, TransactionLocalRlsScope rls,
            DeviceTypeRepository types, ProjectLifecycleAccessService lifecycle, ProjectService projects) {
        this.jdbc=jdbc;this.rls=rls;this.types=types;this.lifecycle=lifecycle;this.projects=projects;
    }

    /** 先锁设备再读权威拓扑，禁止以等待前的JOIN快照签发许可。 */
    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public Optional<DeviceCommandReceiverRoute> lockCurrent(UUID tenantId, UUID projectId,
            UUID targetDeviceId, UUID connectionDeviceId) {
        if(tenantId==null||projectId==null||targetDeviceId==null||connectionDeviceId==null) {
            throw new IllegalArgumentException("命令接收关系身份不能为空");
        }
        rls.establish(tenantId,projectId);
        if(!lifecycle.lockActiveForWrite(tenantId,projectId)) return Optional.empty();
        var devices=jdbc.query("""
                SELECT id,device_key,device_type_id,gateway_id FROM dev_device
                WHERE tenant_id=? AND project_id=? AND id IN (?,?) AND deleted_at IS NULL
                ORDER BY id FOR SHARE NOWAIT
                """,(rs,row)->new Device(rs.getObject("id",UUID.class),rs.getString("device_key"),
                rs.getObject("device_type_id",UUID.class),rs.getObject("gateway_id",UUID.class)),
                tenantId,projectId,targetDeviceId,connectionDeviceId);
        var target=devices.stream().filter(d->d.id().equals(targetDeviceId)).findFirst().orElse(null);
        var connection=devices.stream().filter(d->d.id().equals(connectionDeviceId)).findFirst().orElse(null);
        if(target==null||connection==null||target.type()==null||connection.type()==null) return Optional.empty();
        var targetType=types.findByIdForShareNowait(projectId,target.type()).orElse(null);
        var connectionType=target.type().equals(connection.type())?targetType:
                types.findByIdForShareNowait(projectId,connection.type()).orElse(null);
        if(targetType==null||connectionType==null||!tenantId.equals(targetType.tenantId())
                ||!tenantId.equals(connectionType.tenantId())) return Optional.empty();
        if(targetDeviceId.equals(connectionDeviceId)) {
            if(target.gateway()!=null||targetType.deviceKind()==DeviceType.DeviceKind.SUB_DEVICE) return Optional.empty();
        } else {
            if(!connectionDeviceId.equals(target.gateway())||targetType.deviceKind()!=DeviceType.DeviceKind.SUB_DEVICE
                    ||connectionType.deviceKind()!=DeviceType.DeviceKind.GATEWAY) return Optional.empty();
            Boolean exists=jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM dev_topo WHERE tenant_id=? AND project_id=?
                        AND sub_device_id=? AND gateway_device_id=? AND unbound_at IS NULL)
                    """,Boolean.class,tenantId,projectId,targetDeviceId,connectionDeviceId);
            if(!Boolean.TRUE.equals(exists)) return Optional.empty();
        }
        var project=projects.requireRoutingContext(projectId);
        if(!tenantId.equals(project.tenantId())) return Optional.empty();
        return Optional.of(new DeviceCommandReceiverRoute(tenantId,projectId,project.projectKey(),
                targetDeviceId,target.key(),connectionDeviceId,connection.key()));
    }

    /** 仅本域映射必要路由字段。 */
    private record Device(UUID id,String key,UUID type,UUID gateway) { }
}
