package com.things.link.integration.application;
import com.things.link.integration.domain.*;
import com.things.link.device.application.DeviceRuntimeDataService;
import com.things.link.enduser.application.*;
import com.things.link.project.application.*;
import com.things.link.shared.error.BusinessException;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.function.Function;
/** 领域行锁保护单帧接管；不允许把异步排队本身当作完成出站授权。 */
@Service
public class RealtimeOutboundAdmission {
    private final RealtimeTicketRepository tickets;private final RealtimeTicketAuthorization authorization;
    private final TransactionLocalRlsScope rls;private final ProjectLifecycleAccessService projects;
    private final AccountDirectory accounts;private final ProjectActorRoleReader roles;private final ApiKeyRepository keys;
    private final DeviceRuntimeDataService devices;private final AppRealtimeDeliveryGuard apps;private final RealtimeConnectionLease leases;private final boolean enabled;
    public RealtimeOutboundAdmission(RealtimeTicketRepository tickets,RealtimeTicketAuthorization authorization,TransactionLocalRlsScope rls,
        ProjectLifecycleAccessService projects,AccountDirectory accounts,ProjectActorRoleReader roles,ApiKeyRepository keys,
        DeviceRuntimeDataService devices,AppRealtimeDeliveryGuard apps,RealtimeConnectionLease leases,@Value("${things-link.integration.realtime.enabled:false}") boolean enabled){
        this.tickets=tickets;this.authorization=authorization;this.rls=rls;this.projects=projects;this.accounts=accounts;this.roles=roles;this.keys=keys;this.devices=devices;this.apps=apps;this.leases=leases;this.enabled=enabled;
    }
    @Transactional(timeout=10)
    public <T>T withAdmission(RealtimeTicketRepository.Candidate candidate,RealtimeTicketRequest.Protocol protocol,Function<RealtimeTicket,T> send){
        if(!enabled)throw new BusinessException(IntegrationErrorCode.NOT_ENABLED);
        rls.establish(candidate.tenant(),candidate.project());
        var generation=projects.lockReadableGeneration(candidate.tenant(),candidate.project());if(generation.isEmpty())throw invalid();
        var snapshot=tickets.find(candidate.id()).orElseThrow(RealtimeOutboundAdmission::invalid);
        var identity=snapshot.identity();if(generation.getAsLong()!=identity.generation())throw invalid();
        AppAuthenticatedPrincipal app=null;
        if(identity.kind()==RealtimeIdentity.Kind.APP){app=new AppAuthenticatedPrincipal(identity.tenant(),identity.project(),identity.subject(),identity.generation());apps.lockIdentity(app);}
        else{
            if(!accounts.lockActive(identity.account())||roles.lockCurrent(identity.project(),identity.account()).isEmpty())throw invalid();
            if(identity.kind()==RealtimeIdentity.Kind.API_KEY&&keys.lockForDelivery(identity.tenant(),identity.project(),identity.key()).isEmpty())throw invalid();
        }
        devices.lockForRealtimeDelivery(identity.project(),snapshot.request().devices());
        if(app!=null)apps.lockBindings(app,snapshot.request().devices().stream().map(d->d.deviceId()).toList());
        var ticket=tickets.lockForDelivery(candidate.id()).orElseThrow(RealtimeOutboundAdmission::invalid);
        if(!ticket.status().equals("CONNECTED")||ticket.request().protocol()!=protocol||!ticket.expiresAt().isAfter(tickets.now()))throw invalid();
        authorization.require(ticket.identity(),ticket.request(),ticket.peerIp());
        if(ticket.leaseMember()!=null&&leases.renew(new RealtimeConnectionLease.ConnectionLease(ticket.identity().tenant(),ticket.leaseMember()))!=RealtimeConnectionLease.RenewDecision.RENEWED)throw new BusinessException(IntegrationErrorCode.REALTIME_UNAVAILABLE);
        return send.apply(ticket);
    }
    private static BusinessException invalid(){return new BusinessException(IntegrationErrorCode.REALTIME_TICKET_INVALID);}
}
