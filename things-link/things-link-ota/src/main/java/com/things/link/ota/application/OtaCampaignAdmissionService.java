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

/** ADR0126真实租约驱动的无账号准入，客户端身份与Claim副本均不是授权来源。 */
@Service
@DataPlaneDatabase
public class OtaCampaignAdmissionService {
    /** 唯一可信领取及最终数据库时钟CAS。 */ private final OtaCampaignRuntimeRepository repository;
    /** 当前项目仍ACTIVE的持续许可。 */ private final ProjectLifecycleAccessService lifecycle;
    /** 在实际绑定连接建立权威双轴范围。 */ private final TransactionLocalRlsScope rls;
    /** 当前设备、报告、基线、发布和密钥检查。 */ private final OtaRuntimeQualification qualification;
    /** 同事务SYSTEM审计，账号必须为空。 */ private final AuditLogService audit;

    /** 显式注入固定领域端口，不接受HTTP选择项目或设备。 */
    public OtaCampaignAdmissionService(OtaCampaignRuntimeRepository repository,
            ProjectLifecycleAccessService lifecycle, TransactionLocalRlsScope rls,
            OtaRuntimeQualification qualification, AuditLogService audit) {
        this.repository = repository; this.lifecycle = lifecycle; this.rls = rls;
        this.qualification = qualification; this.audit = audit;
    }
    /** 一次只领取一条固定条件的待准入作业，提交后方可独立处理。 */
    @Transactional(timeout = 5)
    public Optional<OtaCampaignRuntimeRepository.Claim> claimOne() {
        requireBackground();
        return repository.claimOne();
    }
    /** 返回处理成功含合格、明确跳过和安全暂停，不代表设备收到通知。 */
    @Transactional(timeout = 5)
    public boolean admit(UUID jobId, UUID token) {
        requireBackground();
        if (jobId == null || token == null) return false;
        var found = repository.authoritativeClaim(jobId, token);
        if (found.isEmpty()) return false;
        var claim = found.orElseThrow();
        rls.establish(claim.tenantId(), claim.projectId());
        lifecycle.requireActiveForWrite(claim.tenantId(), claim.projectId());
        repository.controlLock(claim.tenantId(), claim.projectId());
        var runtime = repository.lockRuntime(claim.projectId(), claim.campaignId()).orElse(null);
        if (runtime == null || !"RUNNING".equals(runtime.campaign().status())
                || !Integer.valueOf(claim.batchNumber()).equals(runtime.currentBatch())
                || repository.authoritativeClaim(jobId, token).filter(claim::equals).isEmpty()) return false;
        var outcome = qualification.check(claim.tenantId(), claim.projectId(), claim.deviceId(),
                runtime.campaign().firmwareId());
        boolean changed = switch (outcome.disposition()) {
            case ELIGIBLE -> repository.admit(claim, outcome.credentialVersion(), outcome.reportRevision(),
                    outcome.reportHash(), outcome.checkedAt());
            case SKIP -> repository.skip(claim, outcome.reason());
            case SECURITY -> repository.securityPauseJob(claim, outcome.reason());
        };
        if (!changed) return false;
        var details = new LinkedHashMap<String, Object>();
        details.put("actorKind", "SYSTEM");
        details.put("campaignId", claim.campaignId().toString());
        details.put("disposition", outcome.disposition().name());
        details.put("reason", outcome.reason());
        audit.record(new AuditLogEntry(claim.tenantId(), claim.projectId(), null, "ota_device_job", jobId,
                "ota.campaign.admission", details));
        return true;
    }
    /** 防止线程误继承管理账号并以后台入口绕过其当前角色。 */
    private static void requireBackground() {
        if (TenantContext.current().isPresent()) throw new IllegalStateException("OTA后台准入不能继承管理账号");
    }
}
