package com.things.link.iam.infrastructure.security;
import com.things.link.iam.application.RestQuotaPolicy;
import com.things.link.project.application.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.util.Optional;
import java.util.UUID;
/** 中性Key端口，不调用要求Console选中项目的策略入口，不伪造线程身份。 */
@Component
public class KeyRestQuotaAdmissionAdapter implements ProjectRestQuotaAdmission {
    private final EffectiveQuotaPolicyProvider policies;
    private final ProjectLifecycleAccessService projects;
    private final StringRedisTemplate redis;
    private final RestQuotaRateLimitMetrics metrics;
    private final ProjectDailyQuotaDecisionService daily;
    private final TrustedProjectUsageFactRecorder recorder;
    private final boolean recording;
    public KeyRestQuotaAdmissionAdapter(EffectiveQuotaPolicyProvider policies,ProjectLifecycleAccessService projects,
            StringRedisTemplate redis,RestQuotaRateLimitMetrics metrics,ProjectDailyQuotaDecisionService daily,
            TrustedProjectUsageFactRecorder recorder,@Value("${things-link.quota.daily-usage-recording-enabled:true}") boolean recording){
        this.policies=policies;this.projects=projects;this.redis=redis;this.metrics=metrics;
        this.daily=daily;this.recorder=recorder;this.recording=recording;
    }
    @Override public Decision admit(UUID owner,UUID project,UUID issuer,UUID key,boolean write){
        if(owner==null||key==null||project==null||issuer==null)throw new IllegalArgumentException("公开配额缺少可信Key身份");
        // 二元组错配不是辅助配额故障，必须在内核的有限默认降级之外明确拒绝。
        if(!projects.snapshot(owner,project).readAllowed())throw new IllegalStateException("公开配额项目归属无效");
        var engine=new RestQuotaAdmissionEngine(ignored->{
            var policy=policies.resolveTrustedTenant(owner);
            return Optional.of(new RestQuotaPolicy(policy.tenantId(),policy.restApiReadRatePerSecond(),policy.restApiWriteRatePerSecond(),policy.restApiReadRatePerMinute(),policy.restApiWriteRatePerMinute()));
        },redis,metrics,daily,recorder::recordTrusted,recording);
        var result=engine.admit(issuer,project,owner,key,write?RestQuotaRateLimitMetrics.RequestKind.WRITE:RestQuotaRateLimitMetrics.RequestKind.READ);
        return new Decision(result.allowed(),result.retryAfterSeconds());
    }
}
