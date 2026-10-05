package com.things.link.assistant.application;

import com.things.link.assistant.domain.*;
import com.things.link.assistant.domain.ProbeLedger.*;
import com.things.link.project.application.ProjectManagementWriteGuard;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

/** 管理独立探针台账；认领事务必须提交后才可发送，本服务不解密凭据或执行网络调用。 */
@Service
public class ProbeLedgerService {
    private final ProjectService projects;
    private final ProjectManagementWriteGuard guard;
    private final TransactionLocalRlsScope rls;
    private final ProbeLedgerRepository ledger;
    private final ModelCredentialRepository credentials;
    private final ProbeAuthorizationProvider authorizations;
    private final Clock clock;
    public ProbeLedgerService(ProjectService projects,ProjectManagementWriteGuard guard,TransactionLocalRlsScope rls,
            ProbeLedgerRepository ledger,ModelCredentialRepository credentials,ProbeAuthorizationProvider authorizations,Clock clock) {
        this.projects=projects;this.guard=guard;this.rls=rls;this.ledger=ledger;this.credentials=credentials;
        this.authorizations=authorizations;this.clock=clock;
    }
    /**
     * 重验付费权限及凭据版本，在独立事务中持久认领一个固定样本机会。
     * @param project 当前身份所选项目
     * @param authorizationId 服务端已冻结的授权标识，不创建新授权
     * @param sampleIndex 固定样本序号，取值为 1 至 3，每批每序号最多认领一次
     * @return 已占用且具有六十秒原始期限的持久机会
     * @throws BusinessException 权限不符、样本非法、授权或配置失效、机会已占用
     */
    @Transactional(propagation=Propagation.REQUIRES_NEW) public Attempt claim(UUID project,String authorizationId,int sampleIndex) {
        UUID tenant=authorize(project);
        if(sampleIndex<1 || sampleIndex>3) throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
        var authorization=authorizations.find(authorizationId).filter(a->a.projectId().equals(project)).orElseThrow(ProbeLedgerService::conflict);
        var configuration=credentials.find(tenant,project).filter(c->c.enabled() && c.ciphertext()!=null).orElseThrow(ProbeLedgerService::conflict);
        if(configuration.revision()!=authorization.configurationRevision()) throw conflict();
        var now=clock.instant();
        var candidate=new Batch(Uuid7.generate(),tenant,project,authorization.id(),authorization.environmentId(),
            authorization.configurationRevision(),authorization.manifestSha256(),now);
        var batch=ledger.ensure(candidate).orElseThrow(ProbeLedgerService::conflict);
        if(!Objects.equals(batch.environmentId(),candidate.environmentId()) || batch.configurationRevision()!=candidate.configurationRevision()
                || !Objects.equals(batch.manifestSha256(),candidate.manifestSha256())) throw conflict();
        var attempt=new Attempt(Uuid7.generate(),batch.id(),tenant,project,TenantContext.require().accountId(),sampleIndex,
            Status.CLAIMED,now,now.plusSeconds(60),null);
        if(!ledger.claim(attempt)) throw conflict();
        return attempt;
    }
    /**
     * 查询当前调用者的探针机会；已过期的认领记录收敛为未知终态。
     * @param project 当前身份所选项目
     * @param id 仅限当前调用者创建的机会标识
     * @return 当前持久状态，不续期或重新开放机会
     */
    @Transactional(propagation=Propagation.REQUIRES_NEW) public Attempt read(UUID project,UUID id) {
        var attempt=owned(authorize(project),project,id);
        if(attempt.status()==Status.CLAIMED && !clock.instant().isBefore(attempt.deadline()))
            return finishStored(attempt,Status.UNKNOWN);
        return attempt;
    }
    /**
     * 将仍在认领状态的机会推进为终态；相同终态可重复读取，过期仅允许未知终态。
     * @param project 当前身份所选项目
     * @param id 当前调用者创建的机会标识
     * @param status 目标终态，不得为空或仍为认领状态
     * @return 已持久化的终态记录
     * @throws BusinessException 状态非法、记录不可见、并发冲突或期限不满足
     */
    @Transactional(propagation=Propagation.REQUIRES_NEW) public Attempt finish(UUID project,UUID id,Status status) {
        var attempt=owned(authorize(project),project,id);
        if(status==null || status==Status.CLAIMED) throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
        if(attempt.status()==status) return attempt;
        if(attempt.status()!=Status.CLAIMED || (!clock.instant().isBefore(attempt.deadline()) && status!=Status.UNKNOWN)) throw conflict();
        return finishStored(attempt,status);
    }
    private Attempt finishStored(Attempt a,Status status) {
        var now=clock.instant();
        if(!ledger.finish(a,status,now)) throw conflict();
        return new Attempt(a.id(),a.batchId(),a.tenantId(),a.projectId(),a.createdBy(),a.sampleIndex(),status,a.claimedAt(),a.deadline(),now);
    }
    private Attempt owned(UUID tenant,UUID project,UUID id) {
        return ledger.find(tenant,project,TenantContext.require().accountId(),id).orElseThrow(ProbeLedgerService::missing);
    }
    private UUID authorize(UUID project) {
        if(!Objects.equals(project,TenantContext.require().projectId())) throw missing();
        paid(projects.requireRoleInProject(project));
        paid(guard.requireMember(project,TenantContext.require().accountId()));
        UUID tenant=projects.requireProjectTenant(project);rls.establish(tenant,project);return tenant;
    }
    private static void paid(ProjectRole role) {
        if(role!=ProjectRole.OWNER && role!=ProjectRole.ADMIN && role!=ProjectRole.OPERATOR)
            throw new BusinessException(AssistantErrorCode.MODEL_ANALYSIS_FORBIDDEN);
    }
    private static BusinessException conflict() { return new BusinessException(CommonErrorCode.RESOURCE_STATE_CONFLICT); }
    private static BusinessException missing() { return new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND); }
}
