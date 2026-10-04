package com.things.link.project.application;

import com.things.link.project.domain.QuotaPolicyAssignmentRepository;
import com.things.link.project.infrastructure.cache.QuotaPolicyRedisPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ConcurrentModificationException;
import java.util.UUID;

/**
 * 租户套餐切换的事务应用服务。
 *
 * <p>当前没有对外套餐管理 API；本服务先冻结唯一允许写绑定的 CAS 和缓存失效闭环，后续运营入口必须复用
 * 它而不是直接 UPDATE {@code sys_tenant}。
 */
@Service
public class QuotaPolicyAssignmentService {

    /** 绑定 CAS 仓储。 */
    private final QuotaPolicyAssignmentRepository repository;
    /** 持有本机 LKG 的策略提供者。 */
    private final CachedEffectiveQuotaPolicyProvider provider;
    /** 跨实例 Redis Pub/Sub 广播器。 */
    private final QuotaPolicyRedisPublisher publisher;

    /**
     * @param repository 绑定 CAS 仓储
     * @param provider 持有本机 LKG 的策略提供者
     * @param publisher 跨实例 Redis Pub/Sub 广播器
     */
    public QuotaPolicyAssignmentService(QuotaPolicyAssignmentRepository repository,
                                        CachedEffectiveQuotaPolicyProvider provider,
                                        QuotaPolicyRedisPublisher publisher) {
        this.repository = repository;
        this.provider = provider;
        this.publisher = publisher;
    }

    /**
     * CAS 切换一名租户当前策略，并在提交后同时失效本机及其他实例缓存。
     *
     * @param tenantId 租户 ID
     * @param policyId 新策略 ID
     * @param expectedAssignmentVersion 调用方读取时看到的绑定版本
     * @return 最新绑定和模板版本组成的失效事件
     */
    @Transactional
    public QuotaPolicyChanged assign(UUID tenantId, UUID policyId, long expectedAssignmentVersion) {
        QuotaPolicyChanged changed = repository.assign(tenantId, policyId, expectedAssignmentVersion)
                .orElseThrow(() -> new ConcurrentModificationException("配额策略绑定已被并发更新或目标策略不存在"));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            /** @param status 仅事务成功提交后才能让缓存看见新绑定 */
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) {
                    provider.markStale(changed);
                }
            }
        });
        // 发布器同样注册 afterCommit；先本机标 stale，再广播给其他实例，避免本节点短暂继续使用旧策略。
        publisher.publishAfterCommit(changed);
        return changed;
    }
}
