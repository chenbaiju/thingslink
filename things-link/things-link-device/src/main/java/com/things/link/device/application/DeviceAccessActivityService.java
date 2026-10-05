package com.things.link.device.application;

import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/** ADR0190：活跃事实的原事务负责维护窗口及其可靠边沿事件。 */
@Service
public class DeviceAccessActivityService {
    public static final long WINDOW_SECONDS = 300;
    private final JdbcTemplate jdbc;
    private final TransactionLocalRlsScope rls;
    private final ProjectLifecycleAccessService projects;
    private final DevicePresenceWebhookSource source;
    public DeviceAccessActivityService(JdbcTemplate jdbc, TransactionLocalRlsScope rls,
            ProjectLifecycleAccessService projects, DevicePresenceWebhookSource source) {
        this.jdbc=jdbc;this.rls=rls;this.projects=projects;this.source=source;
    }

    /** 可信内部兼容入口；外部原生认证必须走携带凭据版本的recordAuthenticated。 */
    @Transactional
    public DeviceAccessSessionPort.ActivityResult record(UUID tenant, UUID project, UUID device,
            TransportProtocol expected) {
        return admit(tenant, project, device, expected, null);
    }

    /** ADR0198：完整认证身份在控制写者的同一设备锁内复核，再登记活动。 */
    @Transactional
    public DeviceAccessSessionPort.ActivityResult recordAuthenticated(
            com.things.link.shared.message.AuthenticatedDeviceIdentity identity, TransportProtocol expected) {
        java.util.Objects.requireNonNull(identity, "认证身份不能为空");
        return admit(identity.tenantId(), identity.projectId(), identity.deviceId(), expected, identity.credentialVersion());
    }

    /** trusted兼容入口无版本；外部原生认证必须经recordAuthenticated。 */
    private DeviceAccessSessionPort.ActivityResult admit(UUID tenant, UUID project, UUID device,
            TransportProtocol expected, Long credentialVersion) {
        rls.establish(tenant,project);
        if(!projects.lockActiveForWrite(tenant,project)) return DeviceAccessSessionPort.ActivityResult.PROJECT_UNAVAILABLE;
        OptionalLong generation=source.capture(tenant,project);
        if(!lockDevice(tenant,project,device,false)) return DeviceAccessSessionPort.ActivityResult.PLANE_NOT_ENABLED;
        if (credentialVersion != null && !credentialVersion.equals(jdbc.queryForObject(
                "SELECT credential_version FROM dev_device WHERE tenant_id=? AND project_id=? AND id=?",
                Long.class, tenant, project, device))) {
            return DeviceAccessSessionPort.ActivityResult.CREDENTIAL_CHANGED;
        }
        var found=binding(project,device);
        if(found.isEmpty()) return expected==TransportProtocol.MQTT
            ?DeviceAccessSessionPort.ActivityResult.ACCEPTED:DeviceAccessSessionPort.ActivityResult.PLANE_NOT_ENABLED;
        Binding b=found.get();
        if(!b.enabled()||b.protocol()!=expected) return DeviceAccessSessionPort.ActivityResult.PLANE_NOT_ENABLED;
        Instant now=now();
        Instant at=b.last()!=null&&b.last().isAfter(now)?b.last():now;
        boolean activity=b.protocol()==TransportProtocol.HTTP||b.protocol()==TransportProtocol.COAP;
        if(activity) {
            boolean online=b.online();
            if(online&&!b.last().plusSeconds(WINDOW_SECONDS).isAfter(now)) {
                edge(tenant,project,device,generation,b,"ONLINE","OFFLINE","ACTIVITY_EXPIRED",b.last().plusSeconds(WINDOW_SECONDS));
                online=false;
            }
            if(!online) edge(tenant,project,device,generation,b,b.last()==null?"INACTIVE":"OFFLINE","ONLINE","ACTIVITY",at);
        }
        jdbc.update("UPDATE dev_access_binding SET last_activity_at=?,activity_online=? WHERE project_id=? AND device_id=?",
            Timestamp.from(at),activity||b.online(),project,device);
        return DeviceAccessSessionPort.ActivityResult.ACCEPTED;
    }

    /** 候选仅用于定位；必须持有与活跃事实写入相同的设备锁后重新检查。 */
    @Transactional(timeout=5)
    public boolean expire(UUID tenant, UUID project, UUID device) {
        rls.establish(tenant,project);
        var readable=projects.lockReadableGeneration(tenant,project);
        OptionalLong generation=readable.isPresent()?source.capture(tenant,project):OptionalLong.empty();
        if(!lockDevice(tenant,project,device,true))return false;
        var found=binding(project,device);
        if(found.isEmpty())return false;
        Binding b=found.get();
        if(!b.online()||(b.protocol()!=TransportProtocol.HTTP&&b.protocol()!=TransportProtocol.COAP)
                ||b.last().plusSeconds(WINDOW_SECONDS).isAfter(now()))return false;
        jdbc.update("UPDATE dev_access_binding SET activity_online=false WHERE project_id=? AND device_id=?",project,device);
        // 已删除设备或项目仅作清理，不产生新的公开来源事实。
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT deleted_at IS NULL FROM dev_device WHERE project_id=? AND id=?",Boolean.class,project,device)))
            edge(tenant,project,device,generation,b,"ONLINE","OFFLINE","ACTIVITY_EXPIRED",b.last().plusSeconds(WINDOW_SECONDS));
        return true;
    }
    private void edge(UUID tenant,UUID project,UUID device,OptionalLong generation,Binding b,String from,String to,String reason,Instant at){
        source.append(tenant,project,device,generation,from,to,b.protocol().name(),reason,null,null,at);
    }
    private boolean lockDevice(UUID tenant,UUID project,UUID device,boolean includeDeleted){
        return !jdbc.queryForList("SELECT id FROM dev_device WHERE tenant_id=? AND project_id=? AND id=?"+(includeDeleted?"":" AND deleted_at IS NULL")+" FOR NO KEY UPDATE",UUID.class,tenant,project,device).isEmpty();
    }
    private Optional<Binding> binding(UUID project,UUID device){
        return jdbc.query("SELECT protocol,enabled,last_activity_at,activity_online FROM dev_access_binding WHERE project_id=? AND device_id=? FOR UPDATE",
            (r,n)->new Binding(TransportProtocol.valueOf(r.getString(1)),r.getBoolean(2),r.getTimestamp(3)==null?null:r.getTimestamp(3).toInstant(),r.getBoolean(4)),project,device).stream().findFirst();
    }
    private Instant now(){return jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();}
    private record Binding(TransportProtocol protocol,boolean enabled,Instant last,boolean online){}
}
