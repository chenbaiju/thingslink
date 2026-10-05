package com.things.link.integration.application;

import com.things.link.integration.domain.*;
import com.things.link.project.application.*;
import com.things.link.shared.error.*;
import com.things.link.support.audit.*;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;

/** ADR0184：显式人工恢复独立于网络重试和订阅修订操作。 */
@Service
@Transactional(timeout=10)
public class WebhookRecoveryService {
    private final ProjectManagementWriteGuard management;
    private final ProjectLifecycleAccessService projects;
    private final AccountDirectory accounts;
    private final ProjectActorRoleReader roles;
    private final TransactionLocalRlsScope rls;
    private final WebhookEventRepository events;
    private final WebhookDeliveryRepository deliveries;
    private final WebhookRecoveryRepository operations;
    private final WebhookOutboundAuthority authority;
    private final AuditLogService audit;
    private final boolean enabled;
    public WebhookRecoveryService(ProjectManagementWriteGuard management,ProjectLifecycleAccessService projects,
            AccountDirectory accounts,ProjectActorRoleReader roles,TransactionLocalRlsScope rls,
            WebhookEventRepository events,WebhookDeliveryRepository deliveries,WebhookRecoveryRepository operations,
            WebhookOutboundAuthority authority,AuditLogService audit,@Value("${things-link.integration.webhook.enabled:false}") boolean enabled){
        this.management=management;this.projects=projects;this.accounts=accounts;this.roles=roles;this.rls=rls;
        this.events=events;this.deliveries=deliveries;this.operations=operations;this.authority=authority;this.audit=audit;this.enabled=enabled;
    }
    public Result recover(UUID tenant,UUID project,UUID actor,UUID operation,UUID delivery,int expectedRound){
        authorize(tenant,project,actor);
        if(operation==null||delivery==null||expectedRound<1||expectedRound>3)throw invalid();
        String digest=digest(delivery,expectedRound);
        var previous=operations.find(operation);
        if(previous.isPresent()){
            var o=previous.get();if(!o.actor().equals(actor)||!o.digest().equals(digest))throw conflict();
            return result(o,true);
        }
        events.lockProject(project);
        var snapshot=deliveries.find(delivery).orElseThrow(WebhookRecoveryService::notFound);
        var candidate=new WebhookDeliveryRepository.Candidate(tenant,project,delivery);
        if(authority.lock(candidate,snapshot).isEmpty())throw conflict();
        var d=deliveries.lock(delivery).orElseThrow(WebhookRecoveryService::notFound);Instant now=events.now();
        if(!d.status().equals("DEAD")||d.round()!=expectedRound||d.round()>=3||d.terminalAt()==null
            ||!d.terminalAt().plusSeconds(7*86400L).isAfter(now)||deliveries.eventText(delivery)==null)throw conflict();
        if(events.pending(project)>=10000)throw new BusinessException(CommonErrorCode.TOO_MANY_REQUESTS);
        if(!deliveries.recover(delivery,expectedRound))throw conflict();
        var receipt=new WebhookRecoveryOperation(tenant,project,operation,actor,digest,delivery,expectedRound+1,events.now());
        operations.insert(receipt);
        audit.record(new AuditLogEntry(tenant,project,actor,"integration_webhook_delivery",delivery,"integration.webhook.recover",
            Map.of("operationId",operation.toString(),"previousRound",expectedRound,"resultRound",expectedRound+1)));
        return result(receipt,false);
    }
    public Result operation(UUID tenant,UUID project,UUID actor,UUID operation){
        authorize(tenant,project,actor);if(operation==null)throw invalid();
        var receipt=operations.find(operation).filter(o->o.actor().equals(actor)).orElseThrow(WebhookRecoveryService::notFound);
        return result(receipt,true);
    }
    private void authorize(UUID tenant,UUID project,UUID actor){
        if(!enabled)throw new BusinessException(IntegrationErrorCode.NOT_ENABLED);
        if(tenant==null||project==null||actor==null)throw invalid();
        if(!management.requireMember(project,actor).canManageMembers())throw new BusinessException(IntegrationErrorCode.MANAGE_FORBIDDEN);
        if(!accounts.lockActive(actor)||roles.lockCurrent(project,actor).filter(r->r.canManageMembers()).isEmpty()
            ||!projects.snapshot(tenant,project).writeAllowed())throw notFound();
        rls.establish(tenant,project);
    }
    private Result result(WebhookRecoveryOperation o,boolean replayed){return new Result(o.operation(),o.delivery(),o.resultRound(),o.completedAt(),replayed,deliveries.find(o.delivery()).map(d->new Current(d.status(),d.round(),d.attempts(),d.roundAttempts(),d.deadlineAt(),d.terminalAt())).orElse(null));}
    private static String digest(UUID id,int expected){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(("RECOVER\n"+id+"\n"+expected).getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static BusinessException conflict(){return new BusinessException(CommonErrorCode.RESOURCE_STATE_CONFLICT);}
    private static BusinessException invalid(){return new BusinessException(CommonErrorCode.INVALID_PARAMETER);}
    private static BusinessException notFound(){return new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);}
    public record Current(String status,int round,int attempts,int roundAttempts,Instant deadlineAt,Instant terminalAt){}
    public record Result(UUID operationId,UUID deliveryId,int resultRound,Instant completedAt,boolean replayed,Current current){}
}
