package com.things.link.integration.application;

import com.things.link.integration.domain.*;
import com.things.link.project.application.*;
import com.things.link.shared.error.BusinessException;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.HashSet;

/** ADR0171：凭据证明后复验当前事实，不读取请求自报tenant/project，也不伪造Console身份。 */
@Service
public class ApiKeyAuthenticationService {
    private final ApiKeyAuthenticationRepository keys;
    private final ProjectActorRoleReader roles;
    private final AccountDirectory accounts;
    private final ProjectLifecycleAccessService lifecycle;
    private final TransactionLocalRlsScope rls;
    private final boolean enabled;
    public ApiKeyAuthenticationService(ApiKeyAuthenticationRepository keys,ProjectActorRoleReader roles,
            AccountDirectory accounts,ProjectLifecycleAccessService lifecycle,TransactionLocalRlsScope rls,
            @Value("${things-link.integration.api-key.enabled:false}") boolean enabled){
        this.keys=keys;this.roles=roles;this.accounts=accounts;this.lifecycle=lifecycle;this.rls=rls;this.enabled=enabled;
    }
    @Transactional(readOnly=true)
    public ApiKeyPrincipal authenticate(String raw,String sourceIp){
        if(!enabled)throw new BusinessException(IntegrationErrorCode.NOT_ENABLED);
        var credential=ApiKeyCredential.parse(raw).orElseThrow(ApiKeyAuthenticationService::invalid);
        if(sourceIp==null||sourceIp.length()>64||!sourceIp.matches("[0-9A-Fa-f:.]+"))throw invalid();
        ApiKeyAuthenticationRepository.Proof proof;
        try {proof=keys.prove(credential.keyId(),credential.digest(),sourceIp).orElseThrow(ApiKeyAuthenticationService::invalid);}
        catch(DataAccessException ex){
            for(Throwable cause=ex;cause!=null;cause=cause.getCause())
                if(cause instanceof java.sql.SQLException sql&&"22P02".equals(sql.getSQLState()))throw invalid();
            throw ex;
        }
        rls.establish(proof.tenant(),proof.project());
        if(!accounts.isActive(proof.issuer()))throw invalid();
        var policy=lifecycle.snapshot(proof.tenant(),proof.project());
        if(!policy.readAllowed()||!policy.matchesGeneration(proof.generation()))throw invalid();
        var role=roles.current(proof.project(),proof.issuer()).orElseThrow(ApiKeyAuthenticationService::invalid);
        var scopes=new HashSet<>(proof.scopes());
        if(!role.canControlDevices())scopes.remove("device:control");
        return new ApiKeyPrincipal(proof.keyId(),proof.tenant(),proof.project(),proof.generation(),proof.issuer(),
                scopes,proof.expiresAt(),policy.writeAllowed());
    }
    private static BusinessException invalid(){return new BusinessException(IntegrationErrorCode.ACCESS_INVALID);}
}
