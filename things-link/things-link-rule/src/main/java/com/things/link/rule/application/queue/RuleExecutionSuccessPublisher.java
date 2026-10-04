package com.things.link.rule.application.queue;

import com.things.link.rule.application.engine.RuleMessage;

/** 在回执完成前持久发布最终预处理结果的端口。 */
public interface RuleExecutionSuccessPublisher {

    /** 实现必须等待 broker 确认后返回，保证回执完成意味着 continuation 已持久化。 */
    void publish(RuleExecutionEnvelope envelope, RuleMessage transformedMessage);
}
