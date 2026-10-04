package com.things.link.rule.application.queue;

/** 规则执行的租户日额度门禁；实现必须同时检查脚本次数与 CPU 毫秒两项权威 UTC 日额度。 */
public interface RuleQuotaGate {

    /**
     * @param envelope 已确权且绑定不可变版本的规则执行信封
     * @return 两项日额度都允许新增规则执行时为 {@code true}
     */
    boolean allows(RuleExecutionEnvelope envelope);
}
