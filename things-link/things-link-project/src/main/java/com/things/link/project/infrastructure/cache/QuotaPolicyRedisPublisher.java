package com.things.link.project.infrastructure.cache;

import com.things.link.project.application.QuotaPolicyChanged;
import com.things.link.project.application.QuotaPolicyTemplateChanged;
import com.things.link.shared.id.Uuid7;
import com.things.link.support.cache.CacheInvalidationEvent;
import com.things.link.support.cache.CacheInvalidationOperation;
import com.things.link.support.cache.CacheInvalidationPublisher;
import com.things.link.support.cache.CacheResource;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 把配额领域事件适配为 S7-5 三类缓存统一失效协议。
 *
 * <p>领域服务仍只表达“租户绑定变化”与“模板变化”；固定 JSON、提交后发送和 Redis 故障降级
 * 都由 support 公共设施承担，避免控制面形成第二套消息格式。</p>
 */
@Component
public class QuotaPolicyRedisPublisher {
    /** 三类缓存统一提交后发布端口。 */
    private final CacheInvalidationPublisher publisher;

    /** @param publisher 统一缓存失效发布端口 */
    public QuotaPolicyRedisPublisher(CacheInvalidationPublisher publisher) {
        this.publisher = publisher;
    }

    /** @param changed 已提交的租户策略绑定版本 */
    public void publishAfterCommit(QuotaPolicyChanged changed) {
        Instant occurredAt = Instant.now();
        publisher.publishAfterCommit(new CacheInvalidationEvent(
                Uuid7.generate(), CacheResource.QUOTA_POLICY, CacheInvalidationOperation.ASSIGN,
                changed.tenantId(), null, changed.assignmentVersion(), changed.policyVersion(), occurredAt));
    }

    /** @param changed 已提交的共享策略模板版本 */
    public void publishAfterCommit(QuotaPolicyTemplateChanged changed) {
        Instant occurredAt = Instant.now();
        publisher.publishAfterCommit(new CacheInvalidationEvent(
                Uuid7.generate(), CacheResource.QUOTA_POLICY, CacheInvalidationOperation.UPDATE,
                changed.policyId(), null, changed.policyVersion(), 0L, occurredAt));
    }
}
