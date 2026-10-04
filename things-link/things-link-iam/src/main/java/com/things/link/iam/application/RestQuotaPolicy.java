package com.things.link.iam.application;

import java.util.UUID;

/**
 * 已选项目在 REST 短窗口限流中使用的有效套餐快照。
 *
 * <p>套餐属于项目所属租户而非 JWT 持有人的租户：跨租户协作者访问受邀项目时，其令牌 {@code tid}
 * 指向自身租户，直接拿它作为计费键会把共享额度分裂。S7-3 的 project 适配器必须以项目归属解析
 * {@link #ownerTenantId()}。</p>
 *
 * @param ownerTenantId 项目所属、也就是共享套餐归属的租户 ID
 * @param readPerSecond 读请求的账号秒窗口上限；{@code null} 表示显式不限，0 表示禁用
 * @param writePerSecond 写请求的账号秒窗口上限；{@code null} 表示显式不限，0 表示禁用
 * @param readPerMinute 读请求的项目与所属租户分钟窗口上限；{@code null} 表示显式不限，0 表示禁用
 * @param writePerMinute 写请求的项目与所属租户分钟窗口上限；{@code null} 表示显式不限，0 表示禁用
 */
public record RestQuotaPolicy(UUID ownerTenantId, Long readPerSecond, Long writePerSecond,
                              Long readPerMinute, Long writePerMinute) {

    /**
     * 拒绝错误的负数套餐值，防止 Redis 计数比较在运行中退化为难以定位的全量拒绝。
     */
    public RestQuotaPolicy {
        if (ownerTenantId == null) {
            throw new IllegalArgumentException("REST 配额策略必须绑定项目所属租户");
        }
        validate(readPerSecond, "readPerSecond");
        validate(writePerSecond, "writePerSecond");
        validate(readPerMinute, "readPerMinute");
        validate(writePerMinute, "writePerMinute");
    }

    /**
     * @param readRequest 当前请求是否为安全读方法
     * @return 账号秒窗口上限
     */
    public Long accountPerSecond(boolean readRequest) {
        return readRequest ? readPerSecond : writePerSecond;
    }

    /**
     * @param readRequest 当前请求是否为安全读方法
     * @return 项目与租户分钟窗口上限
     */
    public Long projectAndTenantPerMinute(boolean readRequest) {
        return readRequest ? readPerMinute : writePerMinute;
    }

    /**
     * @param value 待校验的额度值
     * @param field 配置字段名，仅用于服务器端启动/适配器诊断
     */
    private static void validate(Long value, String field) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException("REST 配额策略 " + field + " 不能为负数");
        }
    }
}
