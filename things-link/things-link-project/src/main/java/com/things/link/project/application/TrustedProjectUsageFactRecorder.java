package com.things.link.project.application;
import java.time.Instant;
import java.util.UUID;
/** ADR0172供已证明owner/project的非Console入口记账；不能接收HTTP自报范围。 */
public interface TrustedProjectUsageFactRecorder {
    /** 与Console沿同一受控PG函数落账；调用方已完成身份、项目及scope验证。 */
    boolean recordTrusted(UUID ownerTenant,UUID project,QuotaMetric metric,String eventKey,Instant occurredAt);
}
