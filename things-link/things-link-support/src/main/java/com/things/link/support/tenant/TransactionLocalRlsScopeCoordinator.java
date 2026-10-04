package com.things.link.support.tenant;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.util.UUID;

/**
 * 协调同一事务连接上的 tenant-only 与 tenant/project 两种 RLS 授权模式。
 *
 * <p>S12-2a1e / D-098：事务标记不能按组件实例隔离，否则两个组件可依次覆盖数据库 GUC 并绕过
 * “事务内禁止切域”。本包内协调器用 Spring 绑定的连接资源作为共享键；不同组件实例只要落在同一
 * 事务连接，就必须观察到同一个首次授权模式与范围。</p>
 */
final class TransactionLocalRlsScopeCoordinator {

    /** 工具类只提供包内静态协调能力，不允许构造实例。 */
    private TransactionLocalRlsScopeCoordinator() {
    }

    /**
     * 读取当前事务连接已经登记的 RLS 授权。
     *
     * @param connectionResource Spring 为当前数据源绑定的连接资源
     * @return 尚未登记时为空
     */
    static EstablishedScope current(Object connectionResource) {
        return (EstablishedScope) TransactionSynchronizationManager.getResource(key(connectionResource));
    }

    /**
     * 登记当前事务连接的首次 RLS 授权，并在任意完成路径后清除。
     *
     * @param connectionResource Spring 为当前数据源绑定的连接资源
     * @param established 首次可信授权
     */
    static void bind(Object connectionResource, EstablishedScope established) {
        ScopeResourceKey resourceKey = key(connectionResource);
        TransactionSynchronizationManager.bindResource(resourceKey, established);
        try {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                /** 提交、回滚和异常回滚都必须移除本事务连接的共享授权标记。 */
                @Override
                public void afterCompletion(int status) {
                    unbindIfOwned(resourceKey, established);
                }
            });
        } catch (RuntimeException | Error registrationFailure) {
            // 注册失败后没有完成回调兜底，必须立即撤销刚绑定的共享标记。
            unbindIfOwned(resourceKey, established);
            throw registrationFailure;
        }
    }

    /** 只解绑本次登记的对象，不能误删同线程已由其他事务恢复的授权。 */
    private static void unbindIfOwned(ScopeResourceKey resourceKey, EstablishedScope established) {
        Object current = TransactionSynchronizationManager.getResource(resourceKey);
        if (current == established) {
            TransactionSynchronizationManager.unbindResource(resourceKey);
        }
    }

    /** @return 跨组件实例共享、按当前连接资源区分的事务键 */
    private static ScopeResourceKey key(Object connectionResource) {
        return new ScopeResourceKey(Objects.requireNonNull(connectionResource, "事务连接资源不能为空"));
    }

    /** 两种范围组件互斥的授权模式。 */
    enum ScopeMode {
        /** 只建立租户轴并明确清空项目轴。 */ TENANT_ONLY,
        /** 同时建立租户与项目轴。 */ TENANT_AND_PROJECT
    }

    /**
     * 当前事务连接的首次可信授权。
     *
     * @param mode 授权模式
     * @param tenantId 已核验租户
     * @param projectId tenant-only 时为空，双轴模式时为已核验项目
     */
    record EstablishedScope(ScopeMode mode, UUID tenantId, UUID projectId) {

        /** 创建 tenant-only 授权，项目轴固定为空。 */
        static EstablishedScope tenantOnly(UUID tenantId) {
            return new EstablishedScope(ScopeMode.TENANT_ONLY,
                    Objects.requireNonNull(tenantId, "租户授权不得为空"), null);
        }

        /** 创建完整双轴授权。 */
        static EstablishedScope tenantAndProject(UUID tenantId, UUID projectId) {
            return new EstablishedScope(ScopeMode.TENANT_AND_PROJECT,
                    Objects.requireNonNull(tenantId, "租户授权不得为空"),
                    Objects.requireNonNull(projectId, "项目授权不得为空"));
        }

        /** @return 是否为相同 tenant-only 授权 */
        boolean matchesTenantOnly(UUID expectedTenantId) {
            return mode == ScopeMode.TENANT_ONLY && expectedTenantId.equals(tenantId) && projectId == null;
        }

        /** @return 是否为相同租户与项目双轴授权 */
        boolean matchesTenantAndProject(UUID expectedTenantId, UUID expectedProjectId) {
            return mode == ScopeMode.TENANT_AND_PROJECT
                    && expectedTenantId.equals(tenantId)
                    && expectedProjectId.equals(projectId);
        }
    }

    /**
     * 共享事务资源键；连接资源身份让 REQUIRES_NEW 与外层事务分别登记。
     *
     * @param connectionResource Spring 当前事务绑定的连接资源
     */
    private record ScopeResourceKey(Object connectionResource) {
    }
}
