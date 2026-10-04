package com.things.link.rule.application.queue;

import java.time.Duration;

/**
 * 把规则失败交给可靠消息基础设施的端口。
 *
 * <p>基础设施实现会把 retry 决定映射到固定退避 Topic，并把永久失败写入规则 DLQ；端口只传递不可变信封和封闭枚举，
 * 不允许调用方把异常正文、租户源码或任意主题名带入恢复链路。</p>
 */
public interface RuleRecoveryPublisher {

    /**
     * 发布一条有限退避重试消息。
     *
     * @param envelope 保留原 messageId 与规则版本的执行信封
     * @param delay 冻结的退避档位
     */
    void publishRetry(RuleExecutionEnvelope envelope, Duration delay);

    /**
     * 发布一条永久失败的规则死信。
     *
     * @param envelope 保留原身份的执行信封
     * @param failure 封闭失败分类
     */
    void publishDeadLetter(RuleExecutionEnvelope envelope, RuleExecutionFailure failure);
}
