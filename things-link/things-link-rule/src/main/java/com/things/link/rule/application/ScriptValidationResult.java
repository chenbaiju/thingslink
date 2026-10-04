package com.things.link.rule.application;

import java.time.Duration;

/**
 * 脚本只解析不执行的封闭结果。
 *
 * @param valid 是否可解析为可执行函数
 * @param errorCode 无效或运行时不可用时的固定错误分类
 * @param duration 包含排队与 Worker 启动的完整耗时
 */
public record ScriptValidationResult(boolean valid, String errorCode, Duration duration) {

    /** 验证结果字段必须一致，避免调用方把缺失错误码当成成功。 */
    public ScriptValidationResult {
        if (duration == null || duration.isNegative()
                || (valid && errorCode != null) || (!valid && errorCode == null)) {
            throw new IllegalArgumentException("脚本验证结果不合法");
        }
    }

    /** @param duration 完整耗时 @return 成功验证 */
    public static ScriptValidationResult valid(Duration duration) {
        return new ScriptValidationResult(true, null, duration);
    }

    /** @param errorCode 固定错误分类 @param duration 完整耗时 @return 失败验证 */
    public static ScriptValidationResult invalid(String errorCode, Duration duration) {
        return new ScriptValidationResult(false, errorCode, duration);
    }
}
