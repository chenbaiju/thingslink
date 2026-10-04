package com.things.link.rule.application.queue;

/**
 * 规则执行的封闭失败分类。
 *
 * <p>分类而不是异常正文决定重试与 DLQ 去向；异常文本可能携带租户源码、载荷或基础设施细节，不能成为持久化字段或
 * Prometheus 标签。确定性契约、脚本与安全失败重放不会恢复，只有暂态依赖与排队压力允许有限重试。</p>
 */
public enum RuleExecutionFailure {
    /** 队列信封或节点输入违反冻结契约。 */
    CONTRACT_INVALID(false),
    /** 指定的不可变规则版本不存在。 */
    RULE_VERSION_MISSING(false),
    /** 规则在该版本快照中已禁用。 */
    RULE_DISABLED(false),
    /** 节点配置未通过确定性校验。 */
    NODE_CONFIG_INVALID(false),
    /** 不可信脚本返回失败、超时或资源耗尽等确定性结果。 */
    SCRIPT_FAILURE(false),
    /** 安全边界拒绝本次执行。 */
    SECURITY_REJECTED(false),
    /** 租户 UTC 日脚本执行或 CPU 额度已到硬限。 */
    QUOTA_REJECTED(false),
    /** 当前版本不支持该节点或关系类型。 */
    UNSUPPORTED(false),
    /** Kafka 等依赖暂时不可用。 */
    INFRASTRUCTURE_UNAVAILABLE(true),
    /** PostgreSQL 等事实存储出现可恢复故障。 */
    DATABASE_TRANSIENT(true),
    /** 独立脚本 Worker 暂时不可用或队列已满。 */
    SANDBOX_UNAVAILABLE(true),
    /** 有界调度器暂时饱和，后续释放槽位后可能恢复。 */
    QUEUE_SATURATED(true);

    /** 是否允许进入 S8-2B 冻结的有限重试路径。 */
    private final boolean retryable;

    /**
     * @param retryable true 表示该原因自身可能随基础设施恢复而消失
     */
    RuleExecutionFailure(boolean retryable) {
        this.retryable = retryable;
    }

    /** @return 是否允许有限退避重试 */
    public boolean retryable() {
        return retryable;
    }
}
