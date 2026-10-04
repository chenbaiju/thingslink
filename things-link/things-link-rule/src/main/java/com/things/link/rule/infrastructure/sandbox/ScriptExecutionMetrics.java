package com.things.link.rule.infrastructure.sandbox;

import com.things.link.rule.application.ScriptExecutionStatus;
import com.things.link.rule.application.ScriptKind;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** D-023 的真实脚本执行指标；标签仅来自两个封闭枚举。 */
@Component
public class ScriptExecutionMetrics {

    /** 应用级指标注册表，由 Bootstrap Actuator 提供。 */
    private final MeterRegistry registry;

    /** @param registry 应用级指标注册表 */
    public ScriptExecutionMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * @param kind 固定脚本类别
     * @param status 固定执行结果
     * @param duration 宿主观察到的完整耗时
     */
    public void record(ScriptKind kind, ScriptExecutionStatus status, Duration duration) {
        Timer.builder("thingslink.script.execution")
                .description("不可信脚本执行耗时与结果")
                .tag("kind", kind.name().toLowerCase())
                .tag("result", status.name().toLowerCase())
                .publishPercentileHistogram()
                .serviceLevelObjectives(
                        Duration.ofMillis(10), Duration.ofMillis(50),
                        Duration.ofMillis(100), Duration.ofMillis(500))
                .register(registry)
                .record(duration);
    }
}
