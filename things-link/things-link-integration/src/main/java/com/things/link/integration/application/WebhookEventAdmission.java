package com.things.link.integration.application;
import com.things.link.integration.domain.WebhookEventRepository;
import com.things.link.shared.message.PublicWebhookEvent;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.things.link.project.application.ProjectDailyQuotaDecisionService;
import com.things.link.project.application.QuotaMetric;
import com.things.link.project.application.QuotaStatus;
import org.springframework.transaction.annotation.Transactional;
/** 可信 Webhook 来源准入；同一事务内校验项目代次、去重、预算及积压，再写入投递意图。 */
@Service
public class WebhookEventAdmission {
    private final WebhookEventRepository events;private final WebhookEventCodec codec;private final ProjectLifecycleAccessService projects;private final TransactionLocalRlsScope rls;private final WebhookAdmissionMetrics metrics;private final boolean enabled;private final ProjectDailyQuotaDecisionService quota;
    public WebhookEventAdmission(WebhookEventRepository events,WebhookEventCodec codec,ProjectLifecycleAccessService projects,TransactionLocalRlsScope rls,ProjectDailyQuotaDecisionService quota,WebhookAdmissionMetrics metrics,@Value("${things-link.integration.webhook.enabled:false}") boolean enabled){this.events=events;this.codec=codec;this.projects=projects;this.rls=rls;this.enabled=enabled;this.quota=quota;this.metrics=metrics;}
    @Transactional public String accept(PublicWebhookEvent event){if(!enabled)return metrics.afterCommit("NOT_ENABLED");return acceptEncoded(event,codec.encode(event));}
    @Transactional public String acceptSource(com.things.link.shared.message.PublicWebhookSource source){codec.validate(source);return acceptEncoded(source.event(),new WebhookEventCodec.Encoded(source.hash(),source.eventText()));}
    private String acceptEncoded(PublicWebhookEvent event,WebhookEventCodec.Encoded encoded){if(!enabled)return metrics.afterCommit("NOT_ENABLED");rls.establish(event.tenantId(),event.projectId());var generation=projects.lockReadableGeneration(event.tenantId(),event.projectId());if(generation.isEmpty()||generation.getAsLong()!=event.projectGeneration()||!projects.snapshot(event.tenantId(),event.projectId()).writeAllowed())return metrics.afterCommit("OUT_OF_SCOPE");
        events.lockProject(event.projectId());var floor=events.advanceFloor(event.tenantId(),event.projectId());var now=events.now();
        var existing=events.find(event);if(existing.isPresent()){if(existing.get().hash().equals(encoded.hash()))return metrics.afterCommit("DUPLICATE");events.conflict(event,encoded.hash(),now);return metrics.afterCommit("CONFLICT");}
        if(event.recordedAt().isAfter(now.plusSeconds(300)))throw new IllegalStateException("Webhook source time unavailable");
        String result=!event.recordedAt().isAfter(floor)?"STALE":encoded.eventText()==null?"OVERSIZE":"ACCEPTED";
        var plans=result.equals("ACCEPTED")?events.matching(event):java.util.List.<WebhookEventRepository.Plan>of();if(result.equals("ACCEPTED")&&!plans.isEmpty()){var decision=quota.decisionTrustedProject(event.tenantId(),event.projectId(),QuotaMetric.NOTIFICATION_DELIVERY);if(decision.disabled()||decision.status()==QuotaStatus.DEGRADED)result="QUOTA_DEGRADED";}if(result.equals("ACCEPTED")&&events.pending(event.projectId())+plans.size()>10000)result="BACKLOG_FULL";
        events.insert(event,encoded.hash(),result.equals("ACCEPTED")?encoded.eventText():null,result,now);if(result.equals("ACCEPTED"))for(var plan:plans)events.enqueue(event,plan,now);return metrics.afterCommit(result);
    }
}
