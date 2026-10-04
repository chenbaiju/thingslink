package com.things.link.rule.application;

import java.time.Duration;

/**
 * 脚本执行的安全投影，不回传可能泄漏实现或宿主信息的原始异常文本。
 *
 * @param status 封闭执行状态
 * @param outputJson 成功时的 JSON 文本，失败时为 {@code null}
 * @param errorCode 稳定内部错误分类，成功时为 {@code null}
 * @param duration 宿主观察到的完整执行耗时
 */
public record ScriptExecutionResult(
        ScriptExecutionStatus status,
        String outputJson,
        String errorCode,
        Duration duration) {

    /** 结果字段必须与状态一致，避免调用方把失败的空输出误当合法 {@code null}。 */
    public ScriptExecutionResult {
        if (status == null || duration == null || duration.isNegative()) {
            throw new IllegalArgumentException("脚本结果状态和耗时不合法");
        }
        if (status == ScriptExecutionStatus.SUCCESS && (outputJson == null || errorCode != null)) {
            throw new IllegalArgumentException("成功结果必须只携带输出");
        }
        if (status != ScriptExecutionStatus.SUCCESS && (outputJson != null || errorCode == null)) {
            throw new IllegalArgumentException("失败结果必须只携带错误分类");
        }
    }

    /**
     * @param outputJson 已通过大小约束的 JSON 输出
     * @param duration 完整执行耗时
     * @return 成功结果
     */
    public static ScriptExecutionResult success(String outputJson, Duration duration) {
        return new ScriptExecutionResult(ScriptExecutionStatus.SUCCESS, outputJson, null, duration);
    }

    /**
     * @param status 非成功状态
     * @param errorCode 固定内部错误分类
     * @param duration 完整执行耗时
     * @return 失败或拒绝结果
     */
    public static ScriptExecutionResult failed(
            ScriptExecutionStatus status, String errorCode, Duration duration) {
        return new ScriptExecutionResult(status, null, errorCode, duration);
    }
}
