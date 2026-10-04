package com.things.link.ota.application;

import com.things.link.ota.domain.OtaCampaignRuntimeRepository;
import com.things.link.ota.domain.OtaJobProgressRepository;
import com.things.link.ota.domain.OtaRollbackPreflightErrorCode;
import com.things.link.ota.domain.OtaRollbackPreflightQuery;
import com.things.link.ota.domain.OtaRollbackPreflightResult;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 管理读取必须重新计算当前准备资格，历史观察不产生执行权。 */
@Service
public class OtaRollbackPreflightReadService {
    /** 当前项目权限。 */ private final ProjectService projects;
    /** ACTIVE项目共享锁。 */ private final ProjectLifecycleAccessService lifecycle;
    /** 完整活动图锁和数据库时钟。 */ private final OtaCampaignRuntimeRepository runtime;
    /** 当前精确作业。 */ private final OtaJobProgressRepository progress;
    /** 当前查询和原始观察。 */ private final com.things.link.ota.domain.OtaRollbackPreflightRepository queries;
    /** 真实设备、基线和安全下限独立重验。 */ private final OtaRollbackPreflightAssessmentService assessments;
    /** 严格已持久报告解码。 */ private final OtaRollbackPreflightReportCodec codec = new OtaRollbackPreflightReportCodec();

    /** 不接受客户端提供观察或原始设备身份。 */
    public OtaRollbackPreflightReadService(ProjectService projects, ProjectLifecycleAccessService lifecycle,
            OtaCampaignRuntimeRepository runtime, OtaJobProgressRepository progress,
            com.things.link.ota.domain.OtaRollbackPreflightRepository queries,
            OtaRollbackPreflightAssessmentService assessments) {
        this.projects=projects; this.lifecycle=lifecycle; this.runtime=runtime; this.progress=progress;
        this.queries=queries; this.assessments=assessments;
    }
    /** 管理授权和当前资格均在同一短事务中，仅读取不刷新查询窗口。 */
    @Transactional(timeout=5)
    public Snapshot find(UUID project, UUID campaign, UUID jobId) {
        manage(project); UUID tenant=projects.requireProjectTenant(project);
        lifecycle.requireActiveForWrite(tenant,project); manage(project); runtime.readControlLock(tenant,project);
        runtime.lockRuntime(project,campaign).orElseThrow(OtaRollbackPreflightReadService::missing);
        var job=progress.locate(jobId).orElseThrow(OtaRollbackPreflightReadService::missing);
        if(!tenant.equals(job.tenantId())||!project.equals(job.projectId())||!campaign.equals(job.campaignId())) throw missing();
        var query=queries.currentQuery(jobId,job.attemptNo()).orElseThrow(OtaRollbackPreflightReadService::missing);
        var observed=queries.latestReport(jobId,job.attemptNo()).orElseThrow(OtaRollbackPreflightReadService::missing);
        if(!query.id().equals(observed.receipt().queryId())) throw missing();
        var report=codec.decode(observed.receipt().canonical()).value();
        Instant now=runtime.currentTime(), deadline=Instant.ofEpochSecond(query.deadlineAt().getEpochSecond());
        Instant brokerAt=observed.receipt().brokerReceivedAt();
        boolean current=now.isBefore(deadline)&&!brokerAt.isBefore(query.createdAt())&&!brokerAt.isAfter(deadline)
                &&"RECOVERY_REQUIRED".equals(job.status())&&job.revision()==query.recoveryRevision();
        var assessment=assessments.assess(job,query,report,brokerAt,current);
        // 身份或类型锁等待可能跨过原截止；仅读取不能延续已过期的准备资格。
        if ("PREPARABLE".equals(assessment.decision().disposition()) && !runtime.currentTime().isBefore(deadline)) {
            assessment=assessments.assess(job,query,report,brokerAt,false);
        }
        return new Snapshot(query,observed,report,assessment);
    }
    /** 普通项目成员不允许读取设备安全准备事实。 */
    private void manage(UUID project) {
        var role=projects.requireRoleInProject(project);
        if(role!=ProjectRole.OWNER&&role!=ProjectRole.ADMIN) throw new BusinessException(OtaRollbackPreflightErrorCode.FORBIDDEN);
    }
    /** 无当前观察和异活动作业均不泄漏身份。 */
    private static BusinessException missing(){return new BusinessException(OtaRollbackPreflightErrorCode.NOT_FOUND);}
    /** 历史和动态判断分离，内部规范正文不直接暴露到HTTP。
     * @param query 不可变查询
     * @param observed 当时观察
     * @param report 已认证证据
     * @param current 当前重验
     */
    public record Snapshot(OtaRollbackPreflightQuery query,OtaRollbackPreflightResult observed,
            OtaRollbackPreflightReportCodec.Report report,OtaRollbackPreflightAssessmentService.Assessment current) { }
}
