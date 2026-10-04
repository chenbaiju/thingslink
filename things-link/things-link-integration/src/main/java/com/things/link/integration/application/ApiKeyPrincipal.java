package com.things.link.integration.application;

import com.things.link.integration.domain.IntegrationErrorCode;
import com.things.link.shared.error.BusinessException;
import java.security.Principal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** 每次认证重新建立的独立集成身份；无秘密/摘要，不等于持锁写入许可。 */
public record ApiKeyPrincipal(UUID keyId,UUID tenantId,UUID projectId,long generation,UUID issuerAccountId,
                              Set<String> scopes,Instant expiresAt,boolean writeAllowed) implements Principal {
    public ApiKeyPrincipal {scopes=Set.copyOf(scopes);}
    @Override public String getName(){return "api-key:"+keyId;}
    /** 数据面仍须验证设备范围、共享额度与原事务许可，scope不是完整授权。 */
    public void require(String scope,boolean write){
        if(!scopes.contains(scope)||write&&!writeAllowed)throw new BusinessException(IntegrationErrorCode.ACCESS_FORBIDDEN);
    }
}
