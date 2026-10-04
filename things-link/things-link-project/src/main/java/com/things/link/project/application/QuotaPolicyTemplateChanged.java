package com.things.link.project.application;

import java.util.Objects;
import java.util.UUID;

/**
 * 可复用配额策略模板更新后的跨实例失效事件。
 *
 * <p>模板事件不枚举引用租户：该枚举既会违反租户 RLS，也会让多租户共享模板时漏失效。每个实例只根据
 * 自己缓存条目的 {@code policyId} 标记命中条目 stale，TTL 继续覆盖丢失 Pub/Sub 消息。
 *
 * @param policyId 被更新模板 ID
 * @param policyVersion 更新后的单调模板版本
 */
public record QuotaPolicyTemplateChanged(UUID policyId, long policyVersion) {

    /** 校验事件不能用空策略或非正版本污染本机版本顺序。 */
    public QuotaPolicyTemplateChanged {
        Objects.requireNonNull(policyId, "配额策略 ID 不能为空");
        if (policyVersion <= 0) {
            throw new IllegalArgumentException("配额策略模板版本必须为正数");
        }
    }
}
