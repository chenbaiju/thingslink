package com.things.link.assistant.application;

import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.tenant.DataPlaneDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.UUID;

/** 每轮合计500条到期事实；无网络、模型、凭据和任意清理入口。 */
@Component
@DataPlaneDatabase
@ConditionalOnProperty(name="things-link.assistant.evidence-retention.enabled",havingValue="true",matchIfMissing=true)
public class EvidenceRetentionMaintenance {
    private static final Logger LOG=LoggerFactory.getLogger(EvidenceRetentionMaintenance.class);
    private final EvidenceRetentionService retention;
    private UUID afterProject;
    public EvidenceRetentionMaintenance(EvidenceRetentionService retention) { this.retention=retention; }

    /** 周期回收只使用独立事务；不确定提交时停止本轮，下轮重试同一项目并恢复调用线程身份。 */
    @Scheduled(scheduler="maintenanceScheduler",fixedDelay=60000,initialDelay=60000)
    public synchronized void tick() {
        var previous=TenantContext.current();TenantContext.clear();
        try {
            var scopes=retention.candidates(afterProject);
            if (scopes.isEmpty()) { afterProject=null;return; }
            int remaining=500;
            for (var scope:scopes) {
                int deleted=retention.purge(scope,remaining);
                if (deleted<0 || deleted>remaining) throw new IllegalStateException("事实回收预算回执异常");
                remaining-=deleted;afterProject=scope.projectId();
                if (remaining==0) break;
            }
        } catch (RuntimeException failure) {
            LOG.warn("个人事实保留回收稍后重试，类型={}",failure.getClass().getSimpleName());
        } finally { TenantContext.clear();previous.ifPresent(TenantContext::set); }
    }
}
