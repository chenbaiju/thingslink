package com.things.link.iam.application;

import java.util.Optional;
import java.util.UUID;

/**
 * IAM 解析已选项目 REST 有效套餐的内部端口。
 *
 * <p>IAM 只能消费本端口，不能直接查询 {@code sys_project}、{@code sys_tenant} 或
 * {@code sys_quota_policy} 表。这样 project 模块可以在 S7-3 接入带版本缓存的权威策略提供者，
 * 同时避免跨租户协作者被 JWT {@code tid} 错误计费。</p>
 */
public interface RestQuotaPolicyResolver {

    /**
     * @param projectId 已通过认证与授权、当前被 JWT 选中的项目 ID
     * @return 项目所属租户的有效套餐；暂未装配 project 适配器时返回空，由调用方使用代码安全默认
     */
    Optional<RestQuotaPolicy> resolve(UUID projectId);
}
