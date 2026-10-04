package com.things.link.project.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** S7-4 任务派发阈值的 null/0/正数契约测试。 */
class RuntimeQuotaPolicyCommandTests {

    /** null 表示套餐不限制，0 表示禁用，两者都必须保留而不能被 DTO 默认值改写。 */
    @Test
    void preservesUnlimitedAndDisabledSemantics() {
        RuntimeQuotaPolicyCommand unlimited = command(null, null);
        RuntimeQuotaPolicyCommand disabled = command(0L, 0L);

        assertThat(unlimited.taskProjectDispatchPerSecond()).isNull();
        assertThat(unlimited.taskTenantDispatchPerSecond()).isNull();
        assertThat(disabled.taskProjectDispatchPerSecond()).isZero();
        assertThat(disabled.taskTenantDispatchPerSecond()).isZero();
    }

    /** 负值没有业务语义，必须在进入 PostgreSQL CHECK 前给出稳定应用层失败。 */
    @Test
    void rejectsNegativeTaskDispatchLimits() {
        assertThatThrownBy(() -> command(-1L, 100L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("单项目任务派发");
        assertThatThrownBy(() -> command(20L, -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("租户任务派发");
    }

    /** 规则队列字段同样保留 null/0 语义，并拒绝负数避免破坏有界调度器。 */
    @Test
    void preservesAndValidatesRuleQueueLimits() {
        RuntimeQuotaPolicyCommand unlimited = new RuntimeQuotaPolicyCommand(10L, 20L, 1_000L, 60_000L,
                20L, 10L, 1_200L, 600L, 200L, 20L, 100L,
                null, null, null, 8000, 12000);
        RuntimeQuotaPolicyCommand disabled = new RuntimeQuotaPolicyCommand(10L, 20L, 1_000L, 60_000L,
                20L, 10L, 1_200L, 600L, 200L, 20L, 100L,
                0L, 0L, 0L, 8000, 12000);

        assertThat(unlimited.ruleTenantConcurrencyLimit()).isNull();
        assertThat(unlimited.ruleTenantQueueCapacity()).isNull();
        assertThat(unlimited.ruleProjectQueueCapacity()).isNull();
        assertThat(disabled.ruleTenantConcurrencyLimit()).isZero();
        assertThat(disabled.ruleTenantQueueCapacity()).isZero();
        assertThat(disabled.ruleProjectQueueCapacity()).isZero();
        assertThatThrownBy(() -> new RuntimeQuotaPolicyCommand(10L, 20L, 1_000L, 60_000L,
                20L, 10L, 1_200L, 600L, 200L, 20L, 100L,
                -1L, 100L, 20L, 8000, 12000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("租户规则执行并发");
    }

    /** @param projectRate 项目秒速率 @param tenantRate 租户秒速率 @return 完整运行时更新命令 */
    private static RuntimeQuotaPolicyCommand command(Long projectRate, Long tenantRate) {
        return new RuntimeQuotaPolicyCommand(10L, 20L, 1_000L, 60_000L,
                20L, 10L, 1_200L, 600L, 200L, projectRate, tenantRate);
    }
}
