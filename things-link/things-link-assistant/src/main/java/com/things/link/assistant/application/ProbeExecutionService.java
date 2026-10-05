package com.things.link.assistant.application;

import com.things.link.assistant.domain.*;
import com.things.link.assistant.domain.ProbeLedger.*;
import com.things.link.project.application.ProjectManagementWriteGuard;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.*;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 在短事务中重新鉴权并提交一次派发许可；网络调用不占用该事务。 */
@Service
public class ProbeExecutionService {
    /**
     * 仅供内部单次派发使用的许可；不应进入公开响应、日志或图状态。
     * @param attempt 已重新核验的持久机会
     * @param authorization 与当前配置匹配的服务端授权
     * @param credential 当前启用的密文凭据，明文仅在交付回调中产生
     * @param token 本次共享并发槽的释放凭证
     */
    public record Permit(Attempt attempt,ProbeAuthorization authorization,ModelCredential credential,UUID token) {
        @Override public String toString(){return "ProbePermit[REDACTED]";}
    }
    private final ProjectService projects;
    private final ProjectManagementWriteGuard guard;
    private final TransactionLocalRlsScope rls;
    private final ProbeLedgerRepository ledger;
    private final ModelCredentialRepository credentials;
    private final ProbeAuthorizationProvider grants;
    private final ProbeSlotRepository slots;
    private final Clock clock;
    public ProbeExecutionService(ProjectService p,ProjectManagementWriteGuard g,TransactionLocalRlsScope r,
            ProbeLedgerRepository l,ModelCredentialRepository c,ProbeAuthorizationProvider a,ProbeSlotRepository j,Clock clock){
        projects=p;guard=g;rls=r;ledger=l;credentials=c;grants=a;slots=j;this.clock=clock;
    }
    /**
     * 在独立短事务内重验身份、授权、机会及配置版本，并原子获取共享并发槽。
     * @param project 当前身份所选项目
     * @param original 已提交的原始认领记录，必须与持久记录完全一致
     * @param authorization 已冻结授权，必须仍与服务端当前授权一致
     * @return 事务提交后可供同步传输使用的单次许可
     * @throws BusinessException 权限不符、状态漂移、期限已过或共享并发槽已满
     */
    @Transactional(propagation=Propagation.REQUIRES_NEW) public Permit dispatch(UUID project,Attempt original,ProbeAuthorization authorization){
        UUID tenant=authorize(project),actor=TenantContext.require().accountId();
        var currentGrant=grants.find(authorization.id()).filter(authorization::equals).orElseThrow(ProbeExecutionService::conflict);
        if(!currentGrant.projectId().equals(project)) throw conflict();
        var attempt=ledger.find(tenant,project,actor,original.id()).filter(original::equals).orElseThrow(ProbeExecutionService::conflict);
        if(attempt.status()!=Status.CLAIMED || !clock.instant().isBefore(attempt.deadline())) throw conflict();
        var credential=credentials.find(tenant,project).filter(c->c.enabled()&&c.ciphertext()!=null
            &&c.revision()==authorization.configurationRevision()).orElseThrow(ProbeExecutionService::conflict);
        UUID token=UUID.randomUUID();
        String admission=slots.acquire(attempt,token);
        if("BUSY".equals(admission)) throw new BusinessException(CommonErrorCode.TOO_MANY_REQUESTS);
        if(!"ADMITTED".equals(admission) || !clock.instant().isBefore(attempt.deadline())) throw conflict();
        return new Permit(attempt,authorization,credential,token);
    }
    /**
     * 重验当前权限后按许可令牌释放共享并发槽，不返还已消耗的探针机会。
     * @param project 当前身份所选项目
     * @param permit 本次派发获得的许可，含原始机会及槽令牌
     * @return 匹配的并发槽成功释放时为真
     */
    @Transactional(propagation=Propagation.REQUIRES_NEW) public boolean release(UUID project,Permit permit){
        UUID tenant=authorize(project);
        var attempt=ledger.find(tenant,project,TenantContext.require().accountId(),permit.attempt().id()).orElseThrow(ProbeExecutionService::conflict);
        return slots.release(attempt,permit.token());
    }
    /** 重验所选项目、两条成员权限路径并建立事务级行隔离范围。 */
    private UUID authorize(UUID project){
        var scope=TenantContext.require();if(!Objects.equals(scope.projectId(),project)) throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        paid(projects.requireRoleInProject(project));paid(guard.requireMember(project,scope.accountId()));
        UUID tenant=projects.requireProjectTenant(project);rls.establish(tenant,project);return tenant;
    }
    private static void paid(ProjectRole r){if(r!=ProjectRole.OWNER&&r!=ProjectRole.ADMIN&&r!=ProjectRole.OPERATOR)
        throw new BusinessException(AssistantErrorCode.MODEL_ANALYSIS_FORBIDDEN);}
    private static BusinessException conflict(){return new BusinessException(CommonErrorCode.RESOURCE_STATE_CONFLICT);}
}
