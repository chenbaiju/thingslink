package com.things.link.integration.application;
import com.things.link.integration.domain.*;
import com.things.link.device.application.DeviceRuntimeDataService;
import com.things.link.enduser.application.*;
import com.things.link.project.application.*;
import com.things.link.shared.error.BusinessException;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.stream.Collectors;
/** 每次使用短票据仍回到所属领域的当前身份和精确设备范围，不把票据当授权缓存。 */
@Service
public class RealtimeTicketAuthorization {
    private final TransactionLocalRlsScope rls;private final AccountDirectory accounts;
    private final ProjectActorRoleReader roles;private final ProjectLifecycleAccessService lifecycle;
    private final ApiKeyRepository keys;private final RealtimeTicketRepository tickets;
    private final AppRealtimeAccessService apps;private final DeviceRuntimeDataService devices;
    public RealtimeTicketAuthorization(TransactionLocalRlsScope rls,AccountDirectory accounts,ProjectActorRoleReader roles,
        ProjectLifecycleAccessService lifecycle,ApiKeyRepository keys,RealtimeTicketRepository tickets,
        AppRealtimeAccessService apps,DeviceRuntimeDataService devices){this.rls=rls;this.accounts=accounts;this.roles=roles;
        this.lifecycle=lifecycle;this.keys=keys;this.tickets=tickets;this.apps=apps;this.devices=devices;}
    @Transactional(propagation=Propagation.MANDATORY)
    public void require(RealtimeIdentity identity,RealtimeTicketRequest request,String peerIp){
        rls.establish(identity.tenant(),identity.project());
        var now=tickets.now();var policy=lifecycle.snapshot(identity.tenant(),identity.project());
        if(!identity.identityExpiresAt().isAfter(now)||!policy.readAllowed()||!policy.matchesGeneration(identity.generation()))throw invalid();
        if(identity.kind()==RealtimeIdentity.Kind.APP){
            apps.requireDevices(new AppAuthenticatedPrincipal(identity.tenant(),identity.project(),identity.subject(),identity.generation()),
                request.devices().stream().map(d->d.deviceId()).collect(Collectors.toSet()));
        } else {
            if(!accounts.isActive(identity.account())||roles.current(identity.project(),identity.account()).isEmpty())throw invalid();
            if(identity.kind()==RealtimeIdentity.Kind.API_KEY){
                var key=keys.find(identity.tenant(),identity.project(),identity.key()).orElseThrow(RealtimeTicketAuthorization::invalid);
                if(!key.issuer().equals(identity.account())||key.generation()!=identity.generation()||!key.status().equals("ACTIVE")
                    ||!key.expiresAt().isAfter(now)||!key.scopes().contains("device:read")||!tickets.ipAllowed(peerIp,key.cidrs()))throw invalid();
            }
        }
        devices.requireAllAvailable(identity.project(),request.devices());
        // 同时校验属性键确实属于所选不可变模型；结果只用于验证，不进入票据或日志。
        devices.queryCurrentValues(identity.project(),request.devices());
    }
    private static BusinessException invalid(){return new BusinessException(IntegrationErrorCode.REALTIME_TICKET_INVALID);}
}
