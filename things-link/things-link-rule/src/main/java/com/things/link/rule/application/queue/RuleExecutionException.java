package com.things.link.rule.application.queue;

import java.util.Objects;

/**
 * 携带稳定失败分类的规则执行异常。
 *
 * <p>调用方只能依赖 {@link #failure()} 决定恢复策略；message 仅允许写入不含用户载荷、凭据和源码的稳定诊断，
 * 避免错误处理器把非结构化文本传播到重试主题、DLQ 或指标。</p>
 */
public final class RuleExecutionException extends RuntimeException {

    /** 序列化兼容标识；异常不跨 Kafka 传输，但保留 Java 基类要求。 */
    private static final long serialVersionUID = 1L;
    /** 封闭失败分类。 */
    private final RuleExecutionFailure failure;

    /**
     * @param failure 封闭失败分类
     * @param diagnostic 脱敏的稳定诊断
     */
    public RuleExecutionException(RuleExecutionFailure failure, String diagnostic) {
        super(diagnostic);
        this.failure = Objects.requireNonNull(failure, "failure 不能为空");
    }

    /**
     * @param failure 封闭失败分类
     * @param diagnostic 脱敏的稳定诊断
     * @param cause 仅供本进程日志关联的根因，不能写入恢复消息
     */
    public RuleExecutionException(RuleExecutionFailure failure, String diagnostic, Throwable cause) {
        super(diagnostic, cause);
        this.failure = Objects.requireNonNull(failure, "failure 不能为空");
    }

    /** @return 不随异常文本变化的恢复分类 */
    public RuleExecutionFailure failure() {
        return failure;
    }
}
