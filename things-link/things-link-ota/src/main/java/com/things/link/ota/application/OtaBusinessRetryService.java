package com.things.link.ota.application;

import com.things.link.ota.domain.OtaCampaignRuntimeRepository;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import com.things.link.support.tenant.DataPlaneDatabase;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR0138业务有限重试的到期归类：只依数据库到期事实驱动，不建立无界扫描或进程内定时器。
 *
 * <p>退避到期后本服务消费租约并按冻结预算派发同一jobId的新尝试；停止围栏先赢时改为安全取消，
 * 预算已耗尽时进入封闭耗尽终态，三种结果各自写不可变转移与SYSTEM审计。</p>
 */
@Service
@DataPlaneDatabase
public class OtaBusinessRetryService {
    /** 唯一可信领取与冻结预算判定。 */ private final OtaCampaignRuntimeRepository repository;
    /** 当前项目仍ACTIVE的持续许可。 */ private final ProjectLifecycleAccessService lifecycle;
    /** 在实际绑定连接建立权威双轴范围。 */ private final TransactionLocalRlsScope rls;
    /** 同事务SYSTEM审计，账号必须为空。 */ private final AuditLogService audit;

    /** 显式注入固定领域端口，不接受HTTP选择项目或作业。 */
    public OtaBusinessRetryService(OtaCampaignRuntimeRepository repository,
            ProjectLifecycleAccessService lifecycle, TransactionLocalRlsScope rls, AuditLogService audit) {
        this.repository = repository; this.lifecycle = lifecycle; this.rls = rls; this.audit = audit;
    }

    /** 一次只领取一条重试到期作业；提交后方可在独立事务归类。 */
    @Transactional(timeout = 5)
    public Optional<OtaCampaignRuntimeRepository.RetryDue> claimDue() {
        requireBackground();
        return repository.claimRetryDue();
    }

    /**
     * 对已领取的到期事实归类：停止围栏先赢则取消，否则回到可派发状态并按冻结预算继续。
     *
     * @param due 同一事务内刚领取的到期范围，不接受调用方自报scope
     * @param reason 稳定归类原因
     * @return 是否产生了一次新的业务归类
     */
    @Transactional(timeout = 5)
    public boolean classify(OtaCampaignRuntimeRepository.RetryDue due, String reason) {
        requireBackground();
        if (due == null) return false;
        rls.establish(due.tenantId(), due.projectId());
        lifecycle.requireActiveForWrite(due.tenantId(), due.projectId());
        repository.controlLock(due.tenantId(), due.projectId());
        if (!repository.consumeRetryClaim(due)) return false;
        // 停止围栏先赢：不派发新尝试，直接按既有安全停止证明收束为取消。
        if (repository.hasInstallStopFence(due.jobId())) {
            boolean cancelled = repository.cancelRetryWait(due.jobId(), "INSTALL_STOP_WON_BEFORE_RETRY");
            if (cancelled) record(due, "cancelRetryWait", "INSTALL_STOP_WON_BEFORE_RETRY");
            return cancelled;
        }
        // 到达就表示退避已结束：仍有冻结预算才派发新尝试，否则进入封闭耗尽终态。
        String failure = due.failureCode() == null ? "DISPATCH_TRANSIENT_FAILURE" : due.failureCode();
        if (!repository.retryBudgetRemains(due.jobId())) {
            if (repository.exhaustRetry(due.jobId(), "RETRY_BUDGET_EXHAUSTED", reason)) {
                record(due, "exhaustRetry", "RETRY_BUDGET_EXHAUSTED");
                return true;
            }
            return false;
        }
        if (repository.dispatchRetry(due.jobId(), reason)) {
            record(due, "dispatchRetry", failure);
            return true;
        }
        return false;
    }

    /**
     * 停止先赢后的收束：由停止受理在成功受理取消后调用。
     *
     * <p>被围栏的重试等待作业不会被到期领取（不派发新尝试），必须由停止流程显式收束为
     * {@code CANCELLED}，否则会永久停在重试等待。</p>
     *
     * @param tenantId 权威租户
     * @param projectId 权威项目
     * @param jobId 作业
     * @return 是否完成了收束
     */
    @Transactional(timeout = 5)
    public boolean reconcileFencedRetry(UUID tenantId, UUID projectId, UUID jobId) {
        requireBackground();
        if (tenantId == null || projectId == null || jobId == null) return false;
        rls.establish(tenantId, projectId);
        lifecycle.requireActiveForWrite(tenantId, projectId);
        repository.controlLock(tenantId, projectId);
        if (!repository.hasInstallStopFence(jobId)) return false;
        boolean cancelled = repository.cancelRetryWait(jobId, "INSTALL_STOP_WON_BEFORE_RETRY");
        if (cancelled) {
            audit.record(new AuditLogEntry(tenantId, projectId, null, "ota_device_job", jobId,
                    "ota.retry.cancelRetryWait", details("cancelRetryWait", "INSTALL_STOP_WON_BEFORE_RETRY")));
        }
        return cancelled;
    }

    /** 同事务SYSTEM审计，账号必须为空。 */
    private void record(OtaCampaignRuntimeRepository.RetryDue due, String action, String failure) {
        audit.record(new AuditLogEntry(due.tenantId(), due.projectId(), null, "ota_device_job", due.jobId(),
                "ota.retry." + action, details(action, failure)));
    }

    /** 只记录非秘密归类事实，不写入凭据、签名或设备正文。 */
    private static LinkedHashMap<String, Object> details(String action, String failure) {
        var details = new LinkedHashMap<String, Object>();
        details.put("actorKind", "SYSTEM");
        details.put("action", action);
        details.put("failureCode", failure);
        return details;
    }

    /** 后台重试归类不能继承管理账号，也不建立虚构账号。 */
    private static void requireBackground() {
        if (TenantContext.current().isPresent()) throw new IllegalStateException("OTA后台重试不能继承管理账号");
    }
}
