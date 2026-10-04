package com.things.link.project.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S14-6a 订阅生命周期触发器的开关契约（关闭 D-169）。
 *
 * <p>这里钉住的是**默认值语义**：{@code matchIfMissing = true}。D-169 记录过反向取值造成的后果——
 * 生产不配置就永远不推进到期/宽限/受限切换，而文档却写着「状态机已实现」。以注解元数据做契约断言，
 * 是因为「属性缺省时 Bean 是否装配」在集成测试里恰恰被测试 profile 的显式关闭掩盖了：
 * 缺省路径无法在共享容器的上下文里直接观察，但它的**定义**必须唯一且可断言。
 *
 * <p>运行路径由 {@code SubscriptionLifecycleWorkerIntegrationTests}（显式开启的真实 PG 上下文）
 * 负责证明；两者合起来才是「默认开启且真的能推进」。
 */
@DisplayName("S14-6a 订阅生命周期触发器开关契约")
class SubscriptionLifecycleWorkerContractTests {

    /** 默认必须开启：缺省属性时 Bean 也要装配，否则生产永远不会自动推进。 */
    @Test
    void triggerDefaultsToEnabledWhenPropertyIsAbsent() {
        ConditionalOnProperty condition = SubscriptionLifecycleWorker.class
                .getAnnotation(ConditionalOnProperty.class);

        assertThat(condition).as("触发器必须由属性开关守卫，而不是无条件装配").isNotNull();
        assertThat(condition.prefix()).isEqualTo("things-link.commercial.subscription-lifecycle");
        assertThat(condition.name()).containsExactly("enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing())
                .as("D-169：缺省必须视为开启；显式写 enabled=false 才关闭（测试 profile 就是这么做的）")
                .isTrue();
    }

    /** 调度周期只影响推进频率，不是正确性依赖：延迟必须可配置且有缺省值。 */
    @Test
    void scheduleDelaysAreConfigurable() throws NoSuchMethodException {
        var scheduled = SubscriptionLifecycleWorker.class.getMethod("advanceNextBatch")
                .getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);

        assertThat(scheduled).as("推进入口必须是调度入口").isNotNull();
        assertThat(scheduled.fixedDelayString())
                .as("固定延迟必须可配置且带缺省值")
                .contains("things-link.commercial.subscription-lifecycle.fixed-delay-millis")
                .contains(":");
        assertThat(scheduled.initialDelayString())
                .contains("things-link.commercial.subscription-lifecycle.initial-delay-millis")
                .contains(":");
        assertThat(scheduled.scheduler())
                .as("必须走运维调度器，避免与业务调度互相阻塞")
                .isEqualTo("maintenanceScheduler");
    }
}
