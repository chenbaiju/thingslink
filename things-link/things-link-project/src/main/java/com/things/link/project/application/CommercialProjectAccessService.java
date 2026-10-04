package com.things.link.project.application;

import com.things.link.project.domain.EffectiveQuotaPolicyRepository;
import com.things.link.project.domain.ProjectCommercialRestrictionRepository;
import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.project.domain.ProjectRepository;
import com.things.link.project.domain.SubscriptionStatus;
import com.things.link.project.domain.TenantSubscriptionLifecycleRepository;
import com.things.link.shared.error.BusinessException;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** ADR0166 R7f：租户锁下按当前权威项目额度限制/恢复，不修改用户主动归档。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class CommercialProjectAccessService {

    /** 状态与租户互斥。 */
    private final TenantSubscriptionLifecycleRepository lifecycle;
    /** 数据库权威投影，不经过旧缓存或故障默认值。 */
    private final EffectiveQuotaPolicyRepository quotas;
    /** 自有项目与实际写状态。 */
    private final ProjectRepository projects;
    /** 商业限制的来源台账。 */
    private final ProjectCommercialRestrictionRepository restrictions;
    /** 与状态变更同事务的审计。 */
    private final AuditLogService audit;

    /** @param lifecycle 租户锁 @param quotas 权威额度 @param projects 项目 @param restrictions 台账 @param audit 审计 */
    public CommercialProjectAccessService(TenantSubscriptionLifecycleRepository lifecycle,
            EffectiveQuotaPolicyRepository quotas,ProjectRepository projects,
            ProjectCommercialRestrictionRepository restrictions,AuditLogService audit) {
        this.lifecycle=lifecycle; this.quotas=quotas; this.projects=projects; this.restrictions=restrictions; this.audit=audit;
    }

    /**
     * 将超出当前额度且仍ACTIVE的项目置为商业受限；不重记用户归档或已有限制。
     * @param tenant 租户 @param sourceSubscription 触发本次限制的订阅 @param now 事件时刻 @param reason 审计原因
     * @return 实际保留/限制集合，数据均未删除
     */
    public LimitOutcome restrictToCurrentLimit(UUID tenant,UUID sourceSubscription,Instant now,String reason) {
        lifecycle.lockTenantAndReadAssignmentVersion(tenant);
        if (!lifecycle.findState(sourceSubscription).map(s -> s.tenantId().equals(tenant)).orElse(false)) {
            throw new IllegalArgumentException("商业限制来源订阅必须属于同一租户");
        }
        List<UUID> owned=projects.findOwnedLiveProjectIds(tenant);
        List<UUID> eligible=eligible(tenant,owned);
        var allowed=new HashSet<>(eligible);
        List<UUID> active=projects.findActiveOwnedLiveProjectIds(tenant);
        List<UUID> restricted=active.stream().filter(id -> !allowed.contains(id)).toList();
        if (projects.restrictCommercialWrite(restricted)!=restricted.size()) {
            throw new IllegalStateException("商业限制项目状态并发变化，回滚重算");
        }
        for (UUID project:restricted) {
            if (!restrictions.recordActive(tenant,project,sourceSubscription,now)) {
                throw new IllegalStateException("商业限制台账已有冲突事实");
            }
            audit.record(new AuditLogEntry(tenant,project,null,"sys_project",project,"commercial.project.write_restricted",
                    Map.of("subscriptionId",sourceSubscription.toString(),"restrictedAt",now.toString(),"reason",reason,
                            "quotaEligibleProjectIds",eligible.stream().map(UUID::toString).toList())));
        }
        return new LimitOutcome(owned,active.stream().filter(allowed::contains).toList(),restricted);
    }

    /** @return 按统一UUID顺序排列的候选租户；只读扫描不代表已获得恢复资格 */
    public List<UUID> pendingTenantIds() {
        return restrictions.findPendingTenantIds().stream().sorted().toList();
    }

    /**
     * 在当前额度允许集合内恢复商业台账项目，FREE也不能无条件全部恢复。
     * @param tenant 真实租户 @param now 事件时刻 @return 实际恢复数量
     */
    public int restoreEligible(UUID tenant,Instant now) {
        lifecycle.lockTenantAndReadAssignmentVersion(tenant);
        var current=lifecycle.findCurrentState(tenant).orElse(null);
        if (current==null || !(current.status()==SubscriptionStatus.RESTRICTED_FREE
                || current.status()==SubscriptionStatus.ACTIVE
                && (current.endsAt()==null || current.endsAt().isAfter(now)))) {
            return 0;
        }
        var active=restrictions.findActiveByTenant(tenant);
        if (active.isEmpty()) return 0;
        var allowed=new HashSet<>(eligible(tenant,projects.findOwnedLiveProjectIds(tenant)));
        var restoring=active.stream().filter(r -> allowed.contains(r.projectId())).toList();
        if (projects.restoreCommercialWrite(restoring.stream().map(r -> r.projectId()).toList())!=restoring.size()) {
            throw new IllegalStateException("商业恢复项目状态并发变化，回滚重算");
        }
        for (var restriction:restoring) {
            if (!restrictions.markLifted(restriction.id(),now)) {
                throw new IllegalStateException("商业恢复台账并发变化，回滚重算");
            }
            audit.record(new AuditLogEntry(tenant,restriction.projectId(),null,"sys_project",restriction.projectId(),
                    "commercial.project.write_restored",Map.of("restrictionId",restriction.id().toString(),
                    "subscriptionId",restriction.subscriptionId().toString(),"currentSubscriptionId",current.id().toString(),
                    "restrictedAt",restriction.restrictedAt().toString(),"restoredAt",now.toString(),
                    "reason","CURRENT_QUOTA_CAPACITY_AVAILABLE")));
        }
        return restoring.size();
    }

    /** 未知投影失败；名单顺序由数据库created_at/id决定，归档项目仍占自有项目名额。 */
    private List<UUID> eligible(UUID tenant,List<UUID> owned) {
        var quota=quotas.findForCommercialLifecycle(tenant)
                .orElseThrow(() -> new BusinessException(ProjectErrorCode.PROJECT_QUOTA_UNAVAILABLE));
        return owned.stream().limit(quota.projectsMax()).toList();
    }

    /** @param ownedProjectIds 全部未删除项目 @param keptWritableProjectIds 原本可写且额度内项目 @param restrictedProjectIds 本次受限项目 */
    public record LimitOutcome(List<UUID> ownedProjectIds,List<UUID> keptWritableProjectIds,List<UUID> restrictedProjectIds) {
        /** 返回不可变集合，避免调用方改写已核验结果。 */
        public LimitOutcome {
            ownedProjectIds=List.copyOf(ownedProjectIds);
            keptWritableProjectIds=List.copyOf(keptWritableProjectIds);
            restrictedProjectIds=List.copyOf(restrictedProjectIds);
        }
    }
}
