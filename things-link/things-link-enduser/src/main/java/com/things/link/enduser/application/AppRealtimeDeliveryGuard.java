package com.things.link.enduser.application;
import com.things.link.enduser.domain.*;
import com.things.link.shared.error.BusinessException;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;
/** 两阶段锁顺序由调用方保证：项目、App身份、设备、绑定；仍须当前完整授权复验。 */
@Service
@Transactional(propagation=Propagation.MANDATORY)
public class AppRealtimeDeliveryGuard {
    private final AppRealtimeDeliveryRepository repository;private final TransactionLocalRlsScope rls;
    public AppRealtimeDeliveryGuard(AppRealtimeDeliveryRepository repository,TransactionLocalRlsScope rls){this.repository=repository;this.rls=rls;}
    public void lockIdentity(AppAuthenticatedPrincipal identity){
        AppRuntimeIdentityService.validateParameters(identity.tenantId(),identity.projectId(),identity.appUserId(),identity.projectGeneration());
        rls.establish(identity.tenantId(),identity.projectId());
        if(!repository.lockIdentity(identity.tenantId(),identity.projectId(),identity.appUserId()))throw invalid();
    }
    public void lockBindings(AppAuthenticatedPrincipal identity,List<UUID> devices){
        if(devices==null||devices.isEmpty()||devices.size()>20||devices.stream().anyMatch(Objects::isNull)||new HashSet<>(devices).size()!=devices.size())throw invalid();
        if(repository.lockBindings(identity.tenantId(),identity.projectId(),identity.appUserId(),devices.stream().sorted().toList())!=devices.size())throw invalid();
    }
    private static BusinessException invalid(){return new BusinessException(EndUserErrorCode.END_USER_ACCESS_INVALID);}
}
