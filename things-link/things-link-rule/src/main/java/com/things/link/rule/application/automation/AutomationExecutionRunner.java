package com.things.link.rule.application.automation;

import com.things.link.rule.domain.*;
import com.things.link.rule.application.RuleSceneExecutionProcessor;
import com.things.link.rule.application.engine.RuleMessage;
import com.things.link.rule.application.outbox.*;
import com.things.link.project.application.BackgroundProjectActorAccess;
import com.things.link.device.application.DeviceAutomationAccess;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import com.things.link.support.scheduling.TenantWorkSlotRepository;
import org.springframework.dao.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Duration;
import java.util.Map;

/** 短领取与业务事务分离；末尾执行fence失败会撤销本次全部动作。 */
@Service
public class AutomationExecutionRunner {
    private final AutomationExecutionRepository executions;
    private final AutomationRepository definitions;
    private final AutomationEventReceiptRepository receipts;
    private final BackgroundProjectActorAccess actors;
    private final DeviceAutomationAccess devices;
    private final TransactionLocalRlsScope rls;
    private final TenantWorkSlotRepository slots;
    private final RuleSceneExecutionProcessor evaluator;
    private final RuleActionDispatcher dispatcher;
    private final TransactionTemplate transaction;
    public AutomationExecutionRunner(AutomationExecutionRepository executions,AutomationRepository definitions,
            AutomationEventReceiptRepository receipts,BackgroundProjectActorAccess actors,DeviceAutomationAccess devices,
            TransactionLocalRlsScope rls,TenantWorkSlotRepository slots,RuleSceneExecutionProcessor evaluator,
            RuleActionDispatcher dispatcher,PlatformTransactionManager manager){
        this.executions=executions;this.definitions=definitions;this.receipts=receipts;this.actors=actors;
        this.devices=devices;this.rls=rls;this.slots=slots;this.evaluator=evaluator;this.dispatcher=dispatcher;
        transaction=new TransactionTemplate(manager);transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    /** 仅接受持久扫描身份，线程上残留HTTP身份不能成为后台授权。 */
    public void run(AutomationExecutionRepository.Candidate candidate){
        var previous=TenantContext.current().orElse(null);TenantContext.clear();
        try{
            var acquired=slots.tryAcquire(TenantWorkSlotRepository.WorkType.AUTOMATION,candidate.tenantId(),Duration.ofSeconds(30));
            if(acquired.isEmpty())return;
            var lease=acquired.get();
            try{
                var execution=transaction.execute(status->claim(candidate,lease));
                if(execution==null)return;
                try{transaction.executeWithoutResult(status->evaluate(candidate,execution,lease));}
                catch(AutomationActionRejectedException rejected){failure(candidate,execution,lease,"ACTION_REJECTED",false);}
                catch(TransientDataAccessException|RecoverableDataAccessException|DataAccessResourceFailureException transientFailure){
                    failure(candidate,execution,lease,"DEPENDENCY_TRANSIENT",true);
                }
                catch(StaleLeaseException lost){/* 旧工作者无权登记任何结果；新租约或恢复扫描负责收束。 */}
            }finally{slots.release(lease);}
        }finally{if(previous==null)TenantContext.clear();else TenantContext.set(previous);}
    }
    private AutomationExecution claim(AutomationExecutionRepository.Candidate c,TenantWorkSlotRepository.Lease lease){
        if(lockScope(c)==BackgroundProjectActorAccess.State.MISSING)return null;
        definitions.find(c.projectId(),c.automationId(),true);
        var existing=executions.lock(c).orElse(null);if(existing==null)return null;
        var now=executions.now();
        if(!existing.deadline().isAfter(now)||("RUNNING".equals(existing.status())&&existing.leaseUntil()!=null&&!existing.leaseUntil().isAfter(now))){executions.expire(existing);return null;}
        if(!existing.status().equals("QUEUED")&&!existing.status().equals("RETRY_WAIT"))return null;
        if(existing.nextAttemptAt()!=null&&existing.nextAttemptAt().isAfter(now))return null;
        if(!slots.fence(lease))return null;
        return executions.claim(c);
    }
    private void evaluate(AutomationExecutionRepository.Candidate c,AutomationExecution claimed,TenantWorkSlotRepository.Lease lease){
        var state=lockScope(c);if(state==BackgroundProjectActorAccess.State.MISSING)throw new StaleLeaseException();
        var definition=definitions.find(c.projectId(),c.automationId(),true).orElse(null);
        var e=current(c,claimed);
        String rejection=null;
        if(state!=BackgroundProjectActorAccess.State.ACTIVE)rejection="PROJECT_READ_ONLY";
        else if(definition==null||definition.status()!=AutomationDefinition.Status.ACTIVE)rejection="DEFINITION_DISABLED";
        else if(!actors.lockManager(e.projectId(),e.responsibleAccountId()))rejection="AUTH_REVOKED";
        else if(devices.lock(e.tenantId(),e.projectId(),e.deviceId())!=DeviceAutomationAccess.State.AVAILABLE)rejection="DEVICE_UNAVAILABLE";
        if(rejection!=null){finish(e,lease,"REJECTED",rejection);return;}
        var version=definitions.version(e.projectId(),e.automationId(),e.versionId()).orElseThrow();
        if(e.input()==null)throw new IllegalStateException("未终态执行丢失冻结输入");
        var message=new RuleMessage(e.id(),e.tenantId(),e.projectId(),e.deviceId(),e.traceId(),e.occurredAt(),
                version.triggerType(),e.input(),Map.of());
        // 此处理器是纯ALL_OF与白名单节点求值，不创建任何场景事实或MANUAL来源。
        var outcome=evaluator.process(message,e.automationId(),e.versionId(),executions.now(),version.conditions(),version.actions());
        String terminal=outcome.status().name();
        if(terminal.equals("DISPATCHED"))dispatcher.dispatch(RuleActionProvenance.automation(e.tenantId(),e.projectId(),e.deviceId(),
                e.traceId(),e.occurredAt(),e.acceptedAt(),e.responsibleAccountId(),e.automationId(),e.versionId(),e.id()),outcome.intents());
        finish(e,lease,terminal,terminal.equals("SKIPPED")?"CONDITION_FALSE":terminal.equals("FAILED")?"CONFIG_INVALID":null);
    }
    private void failure(AutomationExecutionRepository.Candidate c,AutomationExecution claimed,TenantWorkSlotRepository.Lease lease,String reason,boolean retry){
        try{transaction.executeWithoutResult(status->{
            if(lockScope(c)==BackgroundProjectActorAccess.State.MISSING)throw new StaleLeaseException();
            definitions.find(c.projectId(),c.automationId(),true);
            var e=current(c,claimed);
            if(!slots.fence(lease))throw new StaleLeaseException();
            if(!(retry?executions.retry(e):executions.finish(e,"FAILED",reason)))throw new StaleLeaseException();
        });}catch(StaleLeaseException lost){/* 失败结果也不能覆盖新领取者。 */}
    }
    private BackgroundProjectActorAccess.State lockScope(AutomationExecutionRepository.Candidate c){
        var state=actors.lockProject(c.tenantId(),c.projectId());
        if(state!=BackgroundProjectActorAccess.State.MISSING){rls.establish(c.tenantId(),c.projectId());receipts.lockProject(c.projectId());}
        return state;
    }
    private AutomationExecution current(AutomationExecutionRepository.Candidate c,AutomationExecution claimed){
        var e=executions.lock(c).orElseThrow(StaleLeaseException::new);
        if(!e.status().equals("RUNNING")||e.token()!=claimed.token()||e.leaseUntil()==null||!e.leaseUntil().isAfter(executions.now()))throw new StaleLeaseException();
        return e;
    }
    private void finish(AutomationExecution e,TenantWorkSlotRepository.Lease lease,String status,String reason){
        if(!slots.fence(lease)||!executions.finish(e,status,reason))throw new StaleLeaseException();
    }
    /** 无业务正文的围栏失败，只触发原事务回滚。 */
    private static final class StaleLeaseException extends RuntimeException {}
}
