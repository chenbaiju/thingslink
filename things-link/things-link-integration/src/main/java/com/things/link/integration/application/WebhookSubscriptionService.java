package com.things.link.integration.application;
import com.things.link.integration.domain.*;
import com.things.link.project.application.*;
import com.things.link.device.application.PublicDeviceReadService;
import com.things.link.shared.error.*;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.net.SafeOutboundHost;
import com.things.link.shared.page.*;
import com.things.link.support.audit.*;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
/** Webhook 订阅管理服务；管理鉴权、规范输入、版本更新、操作去重及签名密钥单次交付。 */
@Service
@Transactional
public class WebhookSubscriptionService {
    public static final Set<String> EVENT_TYPES=com.things.link.shared.message.PublicWebhookEvent.EVENT_TYPES;
    private final WebhookSubscriptionRepository repository;private final ProjectManagementWriteGuard management;private final ProjectLifecycleAccessService lifecycle;private final AccountDirectory accounts;private final TransactionLocalRlsScope rls;private final PublicDeviceReadService devices;private final AuditLogService audit;private final WebhookSigningKeys keys;private final ObjectMapper json;private final boolean enabled;
    public WebhookSubscriptionService(WebhookSubscriptionRepository repository,ProjectManagementWriteGuard management,ProjectLifecycleAccessService lifecycle,AccountDirectory accounts,TransactionLocalRlsScope rls,PublicDeviceReadService devices,AuditLogService audit,WebhookSigningKeys keys,ObjectMapper json,@Value("${things-link.integration.webhook.enabled:false}") boolean enabled){this.repository=repository;this.management=management;this.lifecycle=lifecycle;this.accounts=accounts;this.rls=rls;this.devices=devices;this.audit=audit;this.keys=keys;this.json=json;this.enabled=enabled;}
    public Result create(UUID tenant,UUID project,UUID actor,UUID operation,Spec raw){long generation=authorize(tenant,project,actor);if(operation==null)throw invalid();var spec=normalize(raw);String digest=digest("CREATE",null,0,spec);var previous=repository.operation(operation);if(previous.isPresent())return replay(previous.get(),actor,digest);
        validateDevices(project,spec);Instant now=repository.now();UUID id=Uuid7.generate(),signing=Uuid7.generate();var value=new WebhookSubscription(id,tenant,project,generation,actor,now,1,"ACTIVE",now,spec.name(),spec.targetUrl(),spec.eventTypes(),spec.deviceIds(),actor,keys.currentId(),signing);repository.insert(value);finish(operation,actor,"CREATE",digest,value);return result(value,true,false);
    }
    public Result change(UUID tenant,UUID project,UUID actor,UUID operation,UUID target,long expected,String kind,Spec raw){long generation=authorize(tenant,project,actor);if(operation==null||target==null||expected<1||kind==null||!Set.of("UPDATE","PAUSE","RESUME","REVOKE","ROTATE").contains(kind))throw invalid();
        Spec spec="UPDATE".equals(kind)?normalize(raw):null;if(spec==null&&raw!=null)throw invalid();String digest=digest(kind,target,expected,spec);var previous=repository.operation(operation);if(previous.isPresent())return replay(previous.get(),actor,digest);
        var old=required(target);if(old.generation()!=generation||old.revision()!=expected||old.status().equals("REVOKED")||expected==Long.MAX_VALUE)throw conflict();
        if(kind.equals("PAUSE")&&!old.status().equals("ACTIVE")||kind.equals("RESUME")&&!old.status().equals("PAUSED"))throw conflict();
        if(spec==null)spec=new Spec(old.name(),old.target(),old.eventTypes(),old.deviceIds());if(!kind.equals("REVOKE")&&!kind.equals("PAUSE"))validateDevices(project,spec);
        boolean rotate=kind.equals("ROTATE")||!spec.targetUrl().equals(old.target());String keyId=rotate?keys.currentId():old.signingKeyId();UUID signing=rotate?Uuid7.generate():old.signingGeneration();
        String status=switch(kind){case "PAUSE"->"PAUSED";case "RESUME"->"ACTIVE";case "REVOKE"->"REVOKED";default->old.status();};
        var next=new WebhookSubscription(old.id(),tenant,project,generation,old.createdBy(),old.createdAt(),expected+1,status,repository.now(),spec.name(),spec.targetUrl(),spec.eventTypes(),spec.deviceIds(),actor,keyId,signing);repository.revise(next,expected);finish(operation,actor,kind,digest,next);return result(next,rotate,false);
    }
    public View get(UUID tenant,UUID project,UUID actor,UUID id){authorize(tenant,project,actor);return view(required(id));}
    public Recovery recover(UUID tenant,UUID project,UUID actor,UUID operation){authorize(tenant,project,actor);var receipt=repository.operation(operation).orElseThrow(WebhookSubscriptionService::notFound);if(!receipt.actor().equals(actor))throw notFound();return new Recovery(operation,receipt.result(),receipt.kind(),Long.toString(receipt.revision()),repository.find(receipt.result()).map(WebhookSubscriptionService::view).orElse(null));}
    public CursorPage<View> list(UUID tenant,UUID project,UUID actor,String cursor,int limit){authorize(tenant,project,actor);if(limit<1||limit>100||cursor!=null&&cursor.length()>256)throw invalid();UUID after=null;if(cursor!=null){String decoded=Cursor.decode(cursor),prefix="webhook-v1|"+project+"|";if(!decoded.startsWith(prefix))throw invalid();try{after=UUID.fromString(decoded.substring(prefix.length()));}catch(IllegalArgumentException bad){throw invalid();}}
        var rows=repository.page(project,after,limit+1);var values=rows.stream().limit(limit).map(WebhookSubscriptionService::view).toList();return rows.size()>limit?CursorPage.of(values,Cursor.encode("webhook-v1|"+project+"|"+values.getLast().id())):CursorPage.last(values);
    }
    private long authorize(UUID tenant,UUID project,UUID actor){if(!enabled)throw new BusinessException(IntegrationErrorCode.NOT_ENABLED);if(tenant==null||project==null||actor==null)throw invalid();if(!management.requireMember(project,actor).canManageMembers())throw new BusinessException(IntegrationErrorCode.MANAGE_FORBIDDEN);if(!accounts.lockActive(actor))throw notFound();var policy=lifecycle.snapshot(tenant,project);if(!policy.writeAllowed())throw notFound();rls.establish(tenant,project);return policy.lifecycleGeneration();}
    private void validateDevices(UUID project,Spec spec){for(UUID id:spec.deviceIds())devices.detail(project,id);}
    private WebhookSubscription required(UUID id){return repository.find(id).orElseThrow(WebhookSubscriptionService::notFound);}
    private Result replay(WebhookOperation op,UUID actor,String digest){if(!op.actor().equals(actor)||!op.digest().equals(digest))throw conflict();return new Result(repository.find(op.result()).map(WebhookSubscriptionService::view).orElse(null),null,true);}
    private Result result(WebhookSubscription value,boolean reveal,boolean replay){return new Result(view(value),reveal?Base64.getEncoder().encodeToString(keys.derive(value.signingKeyId(),value.tenant(),value.project(),value.id(),value.signingGeneration())):null,replay);}
    private void finish(UUID operation,UUID actor,String kind,String digest,WebhookSubscription value){repository.complete(new WebhookOperation(value.tenant(),value.project(),operation,actor,kind,digest,value.id(),value.revision(),value.updatedAt()));audit.record(new AuditLogEntry(value.tenant(),value.project(),actor,"integration_webhook",value.id(),"integration.webhook."+kind.toLowerCase(Locale.ROOT),Map.of("operationId",operation.toString(),"revision",Long.toString(value.revision()),"kind",kind)));}
    static Spec normalize(Spec raw){try{if(raw==null||raw.name()==null||raw.targetUrl()==null||raw.eventTypes()==null||raw.deviceIds()==null)throw invalid();String name=raw.name().strip();if(name.isEmpty()||name.length()>80||name.chars().anyMatch(Character::isISOControl)||raw.targetUrl().length()>2048||raw.eventTypes().isEmpty()||raw.eventTypes().size()>7||!EVENT_TYPES.containsAll(raw.eventTypes())||raw.deviceIds().size()>100||raw.deviceIds().stream().anyMatch(Objects::isNull))throw invalid();URI target=URI.create(raw.targetUrl());if(!"https".equals(target.getScheme())||target.getHost()==null||target.getRawUserInfo()!=null||target.getRawFragment()!=null||target.getRawQuery()!=null||target.getPort()>65535||target.getPort()==0)throw invalid();SafeOutboundHost.requireNoForbiddenLiteral(target);if(target.toASCIIString().length()>2048)throw invalid();return new Spec(name,target.toASCIIString(),raw.eventTypes().stream().distinct().sorted().toList(),raw.deviceIds().stream().distinct().sorted().toList());}catch(IllegalArgumentException|NullPointerException bad){throw invalid();}}
    private String digest(String kind,UUID target,long expected,Spec spec){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(Arrays.asList(kind,target,Long.toString(expected),spec))));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static View view(WebhookSubscription t){return new View(t.id(),t.name(),t.target(),t.eventTypes(),t.deviceIds(),t.status(),Long.toString(t.revision()),t.signingKeyId(),t.createdAt(),t.updatedAt());}
    private static BusinessException invalid(){return new BusinessException(CommonErrorCode.INVALID_PARAMETER);}private static BusinessException conflict(){return new BusinessException(CommonErrorCode.RESOURCE_STATE_CONFLICT);}private static BusinessException notFound(){return new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);}
    public record Spec(String name,String targetUrl,List<String> eventTypes,List<UUID> deviceIds){}
    public record View(UUID id,String name,String targetUrl,List<String> eventTypes,List<UUID> deviceIds,String status,String revision,String signingKeyId,Instant createdAt,Instant updatedAt){}
    public record Result(View subscription,String signingSecret,boolean replayed){@Override public String toString(){return "WebhookResult[id="+(subscription==null?null:subscription.id())+",secret=REDACTED]";}}
    public record Recovery(UUID operationId,UUID resultId,String kind,String resultRevision,View current){}
}
