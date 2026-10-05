package com.things.link.integration.application;

import com.things.link.integration.domain.*;
import com.things.link.project.application.AccountDirectory;
import com.things.link.project.application.ProjectManagementWriteGuard;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.id.Uuid7;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** ADR0169内部管理用例。账号必须由真实Console认证提供；Console HTTP适配不替代本层资格复核。 */
@Service
@Transactional
public class ApiKeyManagementService {
    private static final Set<String> SCOPES=Set.of("device:read","device:control","alarm:read");
    private final ApiKeyRepository repository;
    private final ProjectManagementWriteGuard management;
    private final ProjectLifecycleAccessService lifecycle;
    private final AccountDirectory accounts;
    private final TransactionLocalRlsScope rls;
    private final AuditLogService audit;
    public ApiKeyManagementService(ApiKeyRepository repository,ProjectManagementWriteGuard management,
            ProjectLifecycleAccessService lifecycle,AccountDirectory accounts,TransactionLocalRlsScope rls,AuditLogService audit) {
        this.repository=repository;this.management=management;this.lifecycle=lifecycle;
        this.accounts=accounts;this.rls=rls;this.audit=audit;
    }

    /** 经可信Console适配器进入后仍在项目排他锁内复验资格，避免成员变化与签发竞态。 */
    public Result issue(UUID tenant,UUID project,UUID actor,UUID operation,Spec spec) {
        return create(tenant,project,actor,operation,null,spec);
    }
    /** 同事务旧Key立即撤销、新Key首次秘密和操作事实提交。 */
    public Result rotate(UUID tenant,UUID project,UUID actor,UUID operation,UUID target,Spec spec) {
        if(target==null)throw invalid();
        return create(tenant,project,actor,operation,target,spec);
    }
    private Result create(UUID tenant,UUID project,UUID actor,UUID operation,UUID target,Spec raw) {
        if(operation==null)throw invalid();
        long generation=authorize(tenant,project,actor);
        Spec spec=normalize(raw);
        String kind=target==null?"ISSUE":"ROTATE";
        String digest=requestDigest(kind,target,spec);
        var previous=repository.operation(tenant,project,operation);
        if(previous.isPresent())return replay(previous.get(),actor,digest);
        Instant now=repository.now();
        if(!spec.expiresAt().isAfter(now)||spec.expiresAt().isAfter(now.plus(366,ChronoUnit.DAYS)))throw invalid();
        if(target!=null) {
            ApiKeyFact old=required(tenant,project,target);
            if(!old.status().equals("ACTIVE")||!old.expiresAt().isAfter(now)||old.generation()!=generation)throw conflict();
            repository.revoke(tenant,project,target,now);
        }
        if(repository.activeCount(tenant,project,now)>=100)throw conflict();
        UUID id=Uuid7.generate();
        ApiKeyCredential credential=ApiKeyCredential.generate(id);
        repository.insert(new ApiKeyFact(id,tenant,project,generation,actor,spec.name(),credential.digest(),spec.scopes(),
                spec.ipCidrs(),"ACTIVE",now,spec.expiresAt(),null,0));
        finish(new ApiKeyOperation(tenant,project,operation,actor,kind,digest,target,id,now));
        return new Result(view(required(tenant,project,id),now),credential.reveal(),false);
    }
    public ApiKeyView revoke(UUID tenant,UUID project,UUID actor,UUID operation,UUID target) {
        if(operation==null||target==null)throw invalid();
        authorize(tenant,project,actor);
        String digest=requestDigest("REVOKE",target,null);
        var previous=repository.operation(tenant,project,operation);
        if(previous.isPresent())return replay(previous.get(),actor,digest).key();
        required(tenant,project,target);
        Instant now=repository.now();
        repository.revoke(tenant,project,target,now);
        finish(new ApiKeyOperation(tenant,project,operation,actor,"REVOKE",digest,target,target,now));
        return view(required(tenant,project,target),now);
    }
    /** 当前授权后查询原操作的当前元数据；秘密丢失只能明确撤销/轮换。 */
    public ApiKeyView recover(UUID tenant,UUID project,UUID actor,UUID operation) {
        if(operation==null)throw invalid();
        authorize(tenant,project,actor);
        var receipt=repository.operation(tenant,project,operation).orElseThrow(ApiKeyManagementService::notFound);
        if(!receipt.actor().equals(actor))throw notFound();
        return view(required(tenant,project,receipt.result()),repository.now());
    }
    /** 按当前管理授权读取，无摘要或秘密出口。 */
    public ApiKeyView get(UUID tenant,UUID project,UUID actor,UUID key) {
        authorize(tenant,project,actor);
        return view(required(tenant,project,key),repository.now());
    }
    /** 项目绑定不透明游标，最多读取101行；不支持深offset或无限列表。 */
    public com.things.link.shared.page.CursorPage<ApiKeyView> list(UUID tenant,UUID project,UUID actor,String cursor,int limit) {
        if(limit<1||limit>100||cursor!=null&&cursor.length()>256)throw invalid();
        authorize(tenant,project,actor);
        UUID after=null;
        if(cursor!=null){
            String raw=com.things.link.shared.page.Cursor.decode(cursor);
            String prefix="key-v1|"+project+"|";
            if(!raw.startsWith(prefix))throw invalid();
            try {after=UUID.fromString(raw.substring(prefix.length()));}
            catch(IllegalArgumentException ex){throw invalid();}
        }
        List<ApiKeyFact> rows=repository.page(tenant,project,after,limit+1);
        Instant now=repository.now();boolean more=rows.size()>limit;
        List<ApiKeyView> items=rows.stream().limit(limit).map(k->view(k,now)).toList();
        if(!more)return com.things.link.shared.page.CursorPage.last(items);
        return com.things.link.shared.page.CursorPage.of(items,com.things.link.shared.page.Cursor.encode("key-v1|"+project+"|"+items.getLast().id()));
    }
    private long authorize(UUID tenant,UUID project,UUID actor) {
        if(tenant==null||project==null||actor==null)throw invalid();
        if(!management.requireMember(project,actor).canManageMembers())throw new BusinessException(IntegrationErrorCode.MANAGE_FORBIDDEN);
        if(!accounts.lockActive(actor))throw notFound();
        var policy=lifecycle.snapshot(tenant,project);
        if(!policy.writeAllowed())throw notFound();
        rls.establish(tenant,project);
        return policy.lifecycleGeneration();
    }
    private Result replay(ApiKeyOperation receipt,UUID actor,String digest) {
        if(!receipt.actor().equals(actor)||!receipt.requestDigest().equals(digest))throw conflict();
        return new Result(view(required(receipt.tenant(),receipt.project(),receipt.result()),repository.now()),null,true);
    }
    private ApiKeyFact required(UUID tenant,UUID project,UUID key) {
        return repository.find(tenant,project,key).orElseThrow(ApiKeyManagementService::notFound);
    }
    private void finish(ApiKeyOperation op) {
        repository.complete(op);
        audit.record(new AuditLogEntry(op.tenant(),op.project(),op.actor(),"integration_api_key",op.result(),
                "integration.api_key."+op.kind().toLowerCase(Locale.ROOT),Map.of("operationId",op.operation().toString(),
                "resultKeyId",op.result().toString(),"kind",op.kind())));
    }
    private Spec normalize(Spec spec) {
        if(spec==null||spec.name()==null||spec.scopes()==null||spec.ipCidrs()==null||spec.expiresAt()==null)throw invalid();
        String name=spec.name().strip();
        if(name.isEmpty()||name.length()>80||name.chars().anyMatch(Character::isISOControl)
                ||spec.scopes().isEmpty()||spec.scopes().size()>3||spec.scopes().stream().anyMatch(Objects::isNull)||!SCOPES.containsAll(spec.scopes())
                ||spec.ipCidrs().isEmpty()||spec.ipCidrs().size()>32)throw invalid();
        for(String ip:spec.ipCidrs())if(ip==null||ip.length()>64||!ip.matches("[0-9A-Fa-f:.]+/[0-9]{1,3}"))throw invalid();
        List<String> cidrs;
        try { cidrs=repository.canonicalCidrs(spec.ipCidrs()); }
        catch(DataAccessException ex) {
            // 仅 PostgreSQL 的非法字面量或网络掩码错误归为用户输入错误；其他故障继续向上传播。
            Throwable cause=ex;
            while(cause!=null) {
                if(cause instanceof java.sql.SQLException sql && ("22P02".equals(sql.getSQLState())||"22023".equals(sql.getSQLState())))throw invalid();
                cause=cause.getCause();
            }
            throw ex;
        }
        return new Spec(name,spec.scopes().stream().distinct().sorted().toList(),cidrs,spec.expiresAt().truncatedTo(ChronoUnit.MICROS));
    }
    private static String requestDigest(String kind,UUID target,Spec spec) {
        // 字段带长度前缀，避免用户提供的名称引起分隔符歧义。
        List<String> fields=new ArrayList<>(List.of(kind,target==null?"":target.toString()));
        if(spec!=null)fields.addAll(List.of(spec.name(),String.join(",",spec.scopes()),String.join(",",spec.ipCidrs()),spec.expiresAt().toString()));
        StringBuilder value=new StringBuilder();
        for(String field:fields)value.append(field.length()).append(':').append(field);
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
    }
    private static ApiKeyView view(ApiKeyFact k,Instant now) {
        return new ApiKeyView(k.id(),k.projectId(),k.generation(),k.issuer(),k.name(),k.scopes(),k.cidrs(),
                k.status().equals("ACTIVE")&&!k.expiresAt().isAfter(now)?"EXPIRED":k.status(),k.createdAt(),k.expiresAt(),k.revokedAt(),k.revision());
    }
    private static BusinessException invalid(){return new BusinessException(CommonErrorCode.INVALID_PARAMETER);}
    private static BusinessException conflict(){return new BusinessException(CommonErrorCode.RESOURCE_STATE_CONFLICT);}
    private static BusinessException notFound(){return new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);}
    /** 持久化前通过规范化复制输入；调用者不能提供秘密字段。 */
    public record Spec(String name,List<String> scopes,List<String> ipCidrs,Instant expiresAt) {}
    /** 首次响应之外secret为空；默认诊断绝不打印secret。 */
    public record Result(ApiKeyView key,String secret,boolean replayed) {
        @Override public String toString(){return "ApiKeyResult[key="+key.id()+", replayed="+replayed+", secret=REDACTED]";}
    }
}
