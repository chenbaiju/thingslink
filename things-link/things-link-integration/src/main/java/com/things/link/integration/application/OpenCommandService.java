package com.things.link.integration.application;
import com.things.link.integration.domain.*;
import com.things.link.project.application.*;
import com.things.link.telemetry.application.*;
import com.things.link.shared.error.*;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import java.util.*;

/** ADR0173/0175项目SHARE→账号锁→当前Key/角色复验→原命令及收据原子提交。 */
@Service
public class OpenCommandService {
    private final ProjectLifecycleAccessService lifecycle;private final AccountDirectory accounts;private final ProjectActorRoleReader roles;
    private final ApiKeyRepository keys;private final CommandReceiptRepository receipts;private final PublicCommandService commands;private final TransactionLocalRlsScope rls;
    public OpenCommandService(ProjectLifecycleAccessService lifecycle,AccountDirectory accounts,ProjectActorRoleReader roles,ApiKeyRepository keys,
        CommandReceiptRepository receipts,PublicCommandService commands,TransactionLocalRlsScope rls){this.lifecycle=lifecycle;this.accounts=accounts;this.roles=roles;this.keys=keys;this.receipts=receipts;this.commands=commands;this.rls=rls;}
    @Transactional
    public PublicCommandService.Result submit(ApiKeyPrincipal key,UUID device,String clientKey,String command,JsonNode input){
        key.require("device:control",true);
        if(!lifecycle.lockActiveForWrite(key.tenantId(),key.projectId(),key.generation()))throw denied();
        if(!accounts.lockActive(key.issuerAccountId()))throw invalid();
        rls.establish(key.tenantId(),key.projectId());
        var fact=keys.find(key.tenantId(),key.projectId(),key.keyId()).orElseThrow(OpenCommandService::invalid);
        if(!fact.issuer().equals(key.issuerAccountId())||fact.generation()!=key.generation()||!fact.status().equals("ACTIVE")||!fact.expiresAt().isAfter(keys.now()))throw invalid();
        if(!fact.scopes().contains("device:control")||!roles.current(key.projectId(),key.issuerAccountId()).map(v->v.canControlDevices()).orElse(false))throw denied();
        String hash=PublicCommandIdentity.digest(clientKey);
        var old=receipts.byKey(key.tenantId(),key.projectId(),key.keyId(),hash);
        if(old.isPresent()){
            if(!old.get().device().equals(device))throw new BusinessException(CommonErrorCode.RESOURCE_STATE_CONFLICT);
            if(commands.find(key.projectId(),device,old.get().command(),key.keyId()).isEmpty())throw tombstone();
        }
        var result=commands.submit(key.projectId(),device,key.keyId(),clientKey,command,input,key.issuerAccountId());
        receipts.insert(new CommandReceipt(key.tenantId(),key.projectId(),key.generation(),key.keyId(),key.issuerAccountId(),hash,result.commandId(),device));
        return result;
    }
    @Transactional(readOnly=true)
    public PublicCommandService.Result result(ApiKeyPrincipal key,UUID device,UUID command){
        key.require("device:control",false);rls.establish(key.tenantId(),key.projectId());
        var receipt=receipts.byCommand(key.tenantId(),key.projectId(),key.keyId(),command).filter(r->r.device().equals(device));
        return commands.require(key.projectId(),device,receipt.map(CommandReceipt::command).orElse(null),key.keyId());
    }
    @Transactional(readOnly=true)
    public PublicCommandService.Result recover(ApiKeyPrincipal key,UUID device,String clientKey){
        key.require("device:control",false);rls.establish(key.tenantId(),key.projectId());
        var receipt=receipts.byKey(key.tenantId(),key.projectId(),key.keyId(),PublicCommandIdentity.digest(clientKey)).filter(r->r.device().equals(device));
        if(receipt.isPresent()&&commands.find(key.projectId(),device,receipt.get().command(),key.keyId()).isEmpty())throw tombstone();
        return commands.require(key.projectId(),device,receipt.map(CommandReceipt::command).orElse(null),key.keyId());
    }
    private static BusinessException invalid(){return new BusinessException(IntegrationErrorCode.ACCESS_INVALID);}
    private static BusinessException denied(){return new BusinessException(IntegrationErrorCode.ACCESS_FORBIDDEN);}
    private static BusinessException tombstone(){return new BusinessException(CommonErrorCode.IDEMPOTENCY_RESULT_NOT_REPLAYABLE);}
}
