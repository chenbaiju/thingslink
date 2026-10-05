package com.things.link.device.application;

import com.things.link.device.domain.DeviceAccessBinding;
import com.things.link.device.domain.DeviceAccessSessionRepository;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static com.things.link.device.application.DeviceAccessSessionPort.*;

/** ADR0191：TCP 活跃状态由真实心跳及原事务决定，所属进程退出后仍按此规则处理。 */
@Service
public class DeviceTcpSessionService {
    private final DeviceAccessSessionRepository repository;
    private final JdbcTemplate jdbc;
    private final TransactionLocalRlsScope rls;
    private final ProjectLifecycleAccessService projects;
    private final DevicePresenceWebhookSource source;
    public DeviceTcpSessionService(DeviceAccessSessionRepository repository,JdbcTemplate jdbc,TransactionLocalRlsScope rls,
            ProjectLifecycleAccessService projects,DevicePresenceWebhookSource source){this.repository=repository;this.jdbc=jdbc;this.rls=rls;this.projects=projects;this.source=source;}

    @Transactional
    public Establishment establish(EstablishmentRequest request){
        return establishCurrent(request, null);
    }

    /** ADR0199：最终建会话在控制写者的同一锁内复核完整原身份。 */
    @Transactional
    public Establishment establishAuthenticated(EstablishmentRequest request,
            com.things.link.shared.message.AuthenticatedDeviceIdentity identity) {
        Objects.requireNonNull(identity, "原认证身份不能为空");
        if (request.protocol() != TransportProtocol.TCP || !request.tenantId().equals(identity.tenantId())
                || !request.projectId().equals(identity.projectId()) || !request.deviceId().equals(identity.deviceId())) {
            return new Establishment.Rejected(Rejection.DEVICE_NOT_FOUND);
        }
        return establishCurrent(request, identity.credentialVersion());
    }

    /** 原兼容入口无版本，网络入口必须传递真实身份版本。 */
    private Establishment establishCurrent(EstablishmentRequest request, Long credentialVersion) {
        UUID tenant=request.tenantId(),project=request.projectId(),device=request.deviceId();
        rls.establish(tenant,project);
        if(!projects.lockActiveForWrite(tenant,project))return new Establishment.Rejected(Rejection.PROJECT_UNAVAILABLE);
        var generation=source.capture(tenant,project);
        if(!lockDevice(tenant,project,device,false))return new Establishment.Rejected(Rejection.DEVICE_NOT_FOUND);
        if (credentialVersion != null && !credentialVersion.equals(jdbc.queryForObject(
                "SELECT credential_version FROM dev_device WHERE tenant_id=? AND project_id=? AND id=?",
                Long.class, tenant, project, device))) {
            return new Establishment.Rejected(Rejection.CREDENTIAL_CHANGED);
        }
        var binding=lockBinding(tenant,project,device);
        if(!binding.enabled())return new Establishment.Rejected(Rejection.DISABLED);
        if(!binding.allows(TransportProtocol.TCP))return new Establishment.Rejected(Rejection.PROTOCOL_MISMATCH);
        Instant at=now();
        Optional<Session> prior=current(project,device);
        boolean online=prior.isPresent();
        if(online&&prior.get().expired(at)){
            closeFact(tenant,project,device,generation,prior.get(),"heartbeat_timeout","HEARTBEAT_EXPIRED",prior.get().deadline());online=false;
        }
        boolean history=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM dev_connection WHERE project_id=? AND device_id=? AND protocol='TCP')",Boolean.class,project,device));
        var established=repository.establish(tenant,project,device,TransportProtocol.TCP,request.sessionId(),request.ownerInstance(),binding.configVersion(),request.clientIp(),at,request.heartbeatIntervalMillis());
        if(!online)source.append(tenant,project,device,generation,history?"OFFLINE":"INACTIVE","ONLINE","TCP","CONNECTED",null,null,at);
        return new Establishment.Allowed(new Established(established.rowId(),established.sessionId(),established.generation(),established.configVersion(),established.replacedPriorSession()));
    }
    @Transactional
    public boolean touch(UUID tenant,UUID project,UUID device,UUID row,String owner){
        rls.establish(tenant,project);var readable=projects.lockReadableGeneration(tenant,project);
        boolean writable=readable.isPresent()&&projects.snapshot(tenant,project).writeAllowed();
        var generation=writable?source.capture(tenant,project):OptionalLong.empty();
        if(!lockDevice(tenant,project,device,true))return false;
        var binding=lockBinding(tenant,project,device);var found=current(project,device);
        if(found.isEmpty())return writable&&repository.touch(project,device,row,owner,now());
        Session session=found.get();if(!session.row().equals(row)||!Objects.equals(session.owner(),owner))return false;
        Instant at=now();
        if(session.expired(at)) {closeFact(tenant,project,device,generation,session,"heartbeat_timeout","HEARTBEAT_EXPIRED",session.deadline());return false;}
        if(!writable||!binding.allows(TransportProtocol.TCP)||binding.configVersion()!=session.configVersion()||deleted(project,device)){
            closeFact(tenant,project,device,generation,session,"session_no_longer_authorized","DISCONNECTED",at);return false;
        }
        return repository.touch(project,device,row,owner,at.isAfter(session.lastSeen())?at:session.lastSeen());
    }
    @Transactional
    public boolean close(UUID tenant,UUID project,UUID device,UUID row,String owner,String reason){
        rls.establish(tenant,project);var readable=projects.lockReadableGeneration(tenant,project);
        var generation=readable.isPresent()?source.capture(tenant,project):OptionalLong.empty();
        if(!lockDevice(tenant,project,device,true))return false;
        var found=current(project,device);
        if(found.isEmpty())return repository.close(project,device,row,owner,reason,now());
        Session session=found.get();if(!session.row().equals(row)||!Objects.equals(session.owner(),owner))return false;
        Instant at=now();boolean expired=session.expired(at);
        closeFact(tenant,project,device,generation,session,expired?"heartbeat_timeout":reason,expired?"HEARTBEAT_EXPIRED":"DISCONNECTED",expired?session.deadline():at);
        return true;
    }
    @Transactional(timeout=5)
    public boolean expire(UUID tenant,UUID project,UUID device){
        rls.establish(tenant,project);var readable=projects.lockReadableGeneration(tenant,project);
        var generation=readable.isPresent()?source.capture(tenant,project):OptionalLong.empty();
        if(!lockDevice(tenant,project,device,true))return false;
        var found=current(project,device);if(found.isEmpty()||!found.get().expired(now()))return false;
        closeFact(tenant,project,device,generation,found.get(),"heartbeat_timeout","HEARTBEAT_EXPIRED",found.get().deadline());return true;
    }
    private void closeFact(UUID tenant,UUID project,UUID device,OptionalLong generation,Session session,String reason,String publicReason,Instant at){
        jdbc.update("UPDATE dev_connection SET disconnected_at=?,disconnect_reason=? WHERE project_id=? AND id=? AND disconnected_at IS NULL",Timestamp.from(at),reason,project,session.row());
        if(!deleted(project,device))source.append(tenant,project,device,generation,"ONLINE","OFFLINE","TCP",publicReason,null,null,at);
    }
    private boolean deleted(UUID project,UUID device){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM dev_device WHERE project_id=? AND id=?",Boolean.class,project,device));}
    private boolean lockDevice(UUID tenant,UUID project,UUID device,boolean includeDeleted){return !jdbc.queryForList("SELECT id FROM dev_device WHERE tenant_id=? AND project_id=? AND id=?"+(includeDeleted?"":" AND deleted_at IS NULL")+" FOR NO KEY UPDATE",UUID.class,tenant,project,device).isEmpty();}
    private DeviceAccessBinding lockBinding(UUID tenant,UUID project,UUID device){
        jdbc.queryForList("SELECT device_id FROM dev_access_binding WHERE project_id=? AND device_id=? FOR UPDATE",UUID.class,project,device);
        return repository.findBinding(project,device).orElseGet(()->DeviceAccessBinding.legacyMqtt(tenant,project,device));
    }
    private Optional<Session> current(UUID project,UUID device){return jdbc.query("SELECT id,owner_instance,config_version,last_seen_at,heartbeat_interval_millis FROM dev_connection WHERE project_id=? AND device_id=? AND protocol='TCP' AND disconnected_at IS NULL FOR UPDATE",(r,n)->new Session(r.getObject(1,UUID.class),r.getString(2),r.getLong(3),r.getTimestamp(4).toInstant(),r.getLong(5)),project,device).stream().findFirst();}
    private Instant now(){return jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();}
    private record Session(UUID row,String owner,long configVersion,Instant lastSeen,long interval){Instant deadline(){return lastSeen.plusMillis(interval*3);}boolean expired(Instant at){return !deadline().isAfter(at);}}
}
