package com.things.link.integration.application;
import com.things.link.integration.domain.*;
import com.things.link.project.application.*;
import com.things.link.shared.error.*;
import com.things.link.shared.id.Uuid7;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
/** PG硬预算与原Redis租户池双重准入；跨存储失败补偿，原身份每次复验。 */
@Service
public class RealtimeTicketService {
    private final RealtimeTicketRepository repository;private final RealtimeTicketAuthorization authorization;
    private final RealtimeConnectionLease leases;private final EffectiveQuotaPolicyProvider policies;
    private final TransactionLocalRlsScope rls;private final ObjectMapper json;private final boolean enabled;
    private final String instanceId=UUID.randomUUID().toString();
    public RealtimeTicketService(RealtimeTicketRepository repository,RealtimeTicketAuthorization authorization,
        RealtimeConnectionLease leases,EffectiveQuotaPolicyProvider policies,TransactionLocalRlsScope rls,ObjectMapper json,
        @Value("${things-link.integration.realtime.enabled:false}") boolean enabled){this.repository=repository;this.authorization=authorization;
        this.leases=leases;this.policies=policies;this.rls=rls;this.json=json;this.enabled=enabled;}
    @Transactional
    public Issued issue(RealtimeIdentity identity,RealtimeTicketRequest raw,String peerIp){
        if(!enabled)throw new BusinessException(IntegrationErrorCode.NOT_ENABLED);
        var request=new RealtimeTicketParser().parse(json.writeValueAsBytes(raw));
        authorization.require(identity,request,peerIp);
        Instant now=repository.now();if(!identity.identityExpiresAt().isAfter(now.plusSeconds(5)))throw invalid();
        Instant expiry=now.plusSeconds(300).isBefore(identity.identityExpiresAt())?now.plusSeconds(300):identity.identityExpiresAt();
        UUID id=Uuid7.generate();var credential=RealtimeTicketCredential.generate(id);
        var policy=policies.resolveTrustedTenant(identity.tenant());
        if(!identity.tenant().equals(policy.tenantId()))throw new BusinessException(IntegrationErrorCode.REALTIME_UNAVAILABLE);
        var lease=leases.create(identity.tenant(),"public:"+id);String member=null;
        Long limit=policy.websocketConnectionLimit();
        if(limit!=null){
            var decision=leases.acquire(lease,limit);
            if(decision!=RealtimeConnectionLease.LeaseDecision.ACQUIRED)throw new BusinessException(decision==RealtimeConnectionLease.LeaseDecision.REJECTED?IntegrationErrorCode.REALTIME_CAPACITY:IntegrationErrorCode.REALTIME_UNAVAILABLE);
            member=lease.member();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                @Override public void afterCompletion(int status){if(status!=STATUS_COMMITTED)leases.release(lease);}
            });
        }
        repository.insert(new RealtimeTicket(id,identity,request,now,expiry,"RESERVED",member,instanceId,peerIp),credential.digest());
        return new Issued(id,credential.reveal(),expiry,request.protocol(),
            request.protocol()==RealtimeTicketRequest.Protocol.MQTT?"tc-app-v1:"+id:null,
            request.protocol()==RealtimeTicketRequest.Protocol.MQTT?"tc-app-v1-"+id:null,
            request.protocol()==RealtimeTicketRequest.Protocol.MQTT?"tc/app/v1/"+id+"/events":null,
            request.protocol()==RealtimeTicketRequest.Protocol.WS?"/api/open/v1/realtime/ws":null,
            request.protocol()==RealtimeTicketRequest.Protocol.WS?"tc-realtime-v1":null);
    }
    /** 协议适配器调用；这里只证明当前票据和范围，连接唯一性由协议绑定子片处理。 */
    @Transactional
    public RealtimeTicket authenticate(String raw,RealtimeTicketRequest.Protocol protocol,String peerIp){
        if(!enabled)throw new BusinessException(IntegrationErrorCode.NOT_ENABLED);
        var credential=RealtimeTicketCredential.parse(raw).orElseThrow(RealtimeTicketService::invalid);
        var ticket=repository.prove(credential.id(),credential.digest()).orElseThrow(RealtimeTicketService::invalid);
        if(ticket.request().protocol()!=protocol)throw invalid();
        authorization.require(ticket.identity(),ticket.request(),peerIp);requireLease(ticket);
        return ticket;
    }
    /** Broker CONNECT认证：固定clientId由适配器校验，当前票据转为可订阅状态。 */
    @Transactional
    public RealtimeTicket connectMqtt(String raw,String peerIp){
        var ticket=authenticate(raw,RealtimeTicketRequest.Protocol.MQTT,peerIp);
        if(!repository.bindMqtt(ticket.id(),peerIp))throw invalid();
        return ticket;
    }
    /** WS票据仅一次绑定；数据库触发器同时检查接收实例容量。 */
    @Transactional public RealtimeTicket connectWs(String raw,String peerIp){
        var ticket=authenticate(raw,RealtimeTicketRequest.Protocol.WS,peerIp);
        if(!repository.bindWs(ticket.id(),peerIp,instanceId))throw invalid();
        return repository.find(ticket.id()).orElseThrow(RealtimeTicketService::invalid);
    }
    @Transactional public void closeWs(RealtimeTicketRepository.Candidate candidate){
        rls.establish(candidate.tenant(),candidate.project());
        repository.find(candidate.id()).filter(t->t.request().protocol()==RealtimeTicketRequest.Protocol.WS&&instanceId.equals(t.instanceId())).ifPresent(t->closeRejected(candidate));
    }
    @Transactional(readOnly=true) public String wsClosedReason(RealtimeTicketRepository.Candidate candidate){rls.establish(candidate.tenant(),candidate.project());return repository.closedReason(candidate.id());}
    public String instanceId(){return instanceId;}
    /** 仅供已保护的Broker回调使用；不能暴露为凭ID的公开认证入口。 */
    @Transactional
    public RealtimeTicket authorizeMqtt(UUID id,String peerIp){
        if(!enabled)throw new BusinessException(IntegrationErrorCode.NOT_ENABLED);
        var ticket=repository.connected(id).orElseThrow(RealtimeTicketService::invalid);
        if(ticket.request().protocol()!=RealtimeTicketRequest.Protocol.MQTT)throw invalid();
        authorization.require(ticket.identity(),ticket.request(),peerIp);requireLease(ticket);return ticket;
    }
    @Transactional
    public void validateMaintenance(RealtimeTicketRepository.Candidate candidate){
        rls.establish(candidate.tenant(),candidate.project());
        var found=repository.find(candidate.id());if(found.isEmpty())return;var ticket=found.get();
        if(ticket.status().equals("CLOSED"))return;
        if(!ticket.expiresAt().isAfter(repository.now()))throw invalid();
        authorization.require(ticket.identity(),ticket.request(),ticket.peerIp());requireLease(ticket);
    }
    /** 授权失败事务可能已被领域端口标记回滚，关闭事实必须另开事务。 */
    @Transactional
    public void closeRejected(RealtimeTicketRepository.Candidate candidate){
        rls.establish(candidate.tenant(),candidate.project());
        var found=repository.find(candidate.id());if(found.isEmpty())return;var ticket=found.get();
        repository.close(ticket.id(),"AUTH_OR_LEASE_REJECTED");
        if(ticket.leaseMember()!=null)TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void afterCommit(){leases.release(new RealtimeConnectionLease.ConnectionLease(ticket.identity().tenant(),ticket.leaseMember()));}
        });
    }
    /** Trusted Broker enumeration only; not a public proof-by-ID endpoint. */
    @Transactional public void closeMqttById(UUID id){
        repository.connected(id).ifPresent(t->{if(t.request().protocol()==RealtimeTicketRequest.Protocol.MQTT)closeRejected(new RealtimeTicketRepository.Candidate(t.identity().tenant(),t.identity().project(),id));});
    }
    @Transactional(readOnly=true) public List<RealtimeTicketRepository.Candidate> candidates(){return repository.candidates(instanceId,1000);}
    @Transactional public int purgeExpired(){return repository.purgeExpired(500);}
    private void requireLease(RealtimeTicket ticket){
        if(ticket.leaseMember()==null)return;
        var result=leases.renew(new RealtimeConnectionLease.ConnectionLease(ticket.identity().tenant(),ticket.leaseMember()));
        if(result!=RealtimeConnectionLease.RenewDecision.RENEWED)throw new BusinessException(IntegrationErrorCode.REALTIME_UNAVAILABLE);
    }
    private static BusinessException invalid(){return new BusinessException(IntegrationErrorCode.REALTIME_TICKET_INVALID);}
    @io.swagger.v3.oas.annotations.media.Schema(name="PublicRealtimeTicketIssued")
    public record Issued(UUID ticketId,String credential,Instant expiresAt,RealtimeTicketRequest.Protocol protocol,
        String username,String clientId,String topic,String endpoint,String subprotocol){
        @Override public String toString(){return "Issued[ticketId="+ticketId+",credential=REDACTED]";}
    }
}
