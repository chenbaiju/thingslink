package com.things.link.support.tenant;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 双池路由上下文必须默认安全、允许同域嵌套并拒绝跨域。 */
class DatabaseWorkloadContextTests {
    /** 默认 CONTROL 事务已开始后再标记 DATA 必须失败，不允许指标与真实连接分叉。 */
    @Test
    void rejectsDataRouteAfterDefaultTransactionStarted() {
        long conflictsBefore = DatabaseWorkloadContext.routeConflicts();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> DatabaseWorkloadContext.enter(DatabaseWorkload.DATA))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("CONTROL");
            assertThat(DatabaseWorkloadContext.routeConflicts()).isEqualTo(conflictsBefore + 1);
            assertThat(DatabaseWorkloadContext.current()).isEqualTo(DatabaseWorkload.CONTROL);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    /** 未分类 HTTP 与维护路径固定使用 CONTROL。 */
    @Test
    void defaultsToControlAndClearsAfterScope() {
        assertThat(DatabaseWorkloadContext.current()).isEqualTo(DatabaseWorkload.CONTROL);
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            assertThat(DatabaseWorkloadContext.current()).isEqualTo(DatabaseWorkload.DATA);
        }
        assertThat(DatabaseWorkloadContext.current()).isEqualTo(DatabaseWorkload.CONTROL);
    }

    /** 同域服务嵌套保持 DATA，并在内层和外层退出时按栈恢复。 */
    @Test
    void sameWorkloadMayNest() {
        try (DatabaseWorkloadContext.Scope outer = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            try (DatabaseWorkloadContext.Scope inner = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
                assertThat(DatabaseWorkloadContext.current()).isEqualTo(DatabaseWorkload.DATA);
            }
            assertThat(DatabaseWorkloadContext.current()).isEqualTo(DatabaseWorkload.DATA);
        }
    }

    /** 已进入 DATA 后不得借 CONTROL，以免同一事务跨池形成无法原子提交的双写。 */
    @Test
    void crossWorkloadSwitchIsRejectedAndCounted() {
        long before = DatabaseWorkloadContext.routeConflicts();
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            assertThatThrownBy(() -> DatabaseWorkloadContext.enter(DatabaseWorkload.CONTROL))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(DatabaseWorkloadContext.current()).isEqualTo(DatabaseWorkload.DATA);
        }
        assertThat(DatabaseWorkloadContext.routeConflicts()).isEqualTo(before + 1);
    }
}
