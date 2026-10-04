package com.things.link.project.application;

import com.things.link.project.domain.RuntimeQuotaPolicyRepository;
import com.things.link.project.infrastructure.cache.QuotaPolicyRedisPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ConcurrentModificationException;
import java.util.UUID;

/**
 * 可复用套餐模板运行时阈值更新的事务服务。
 *
 * <p>没有 Controller 之前它只供运营后台或迁移后的内部命令调用；任何未来入口都必须复用 CAS 与提交后
 * 双通道失效，不得直接更新 {@code sys_quota_policy}。
 */
@Service
public class QuotaPolicyCommandService {

    /** 策略模板 CAS 仓储。 */
    private final RuntimeQuotaPolicyRepository repository;
    /** 本机缓存失效器。 */
    private final CachedEffectiveQuotaPolicyProvider provider;
    /** 跨实例 Redis 广播器。 */
    private final QuotaPolicyRedisPublisher publisher;

    /**
     * @param repository 策略模板 CAS 仓储
     * @param provider 本机缓存失效器
     * @param publisher 跨实例 Redis 广播器
     */
    public QuotaPolicyCommandService(RuntimeQuotaPolicyRepository repository,
                                     CachedEffectiveQuotaPolicyProvider provider,
                                     QuotaPolicyRedisPublisher publisher) {
        this.repository = repository;
        this.provider = provider;
        this.publisher = publisher;
    }

    /**
     * 以 CAS 更新共享模板，并在提交后发布模板级失效事件。
     *
     * @param policyId 策略模板 ID
     * @param expectedPolicyVersion 期望模板版本
     * @param command 新运行时阈值
     * @return 更新后的模板失效事件
     */
    @Transactional
    public QuotaPolicyTemplateChanged updateRuntimePolicy(UUID policyId, long expectedPolicyVersion,
                                                           RuntimeQuotaPolicyCommand command) {
        QuotaPolicyTemplateChanged changed = repository.update(policyId, expectedPolicyVersion, command)
                .orElseThrow(() -> new ConcurrentModificationException("配额策略已被并发更新或不存在"));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            /** @param status 仅事务成功提交后让本机缓存及其他实例观察新版本 */
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) {
                    provider.markStale(changed);
                }
            }
        });
        publisher.publishAfterCommit(changed);
        return changed;
    }
}
