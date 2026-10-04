package com.things.link.project.domain;

import java.math.BigInteger;

/**
 * 一个指标在当前项目及租户共享池中的只读用量投影。
 *
 * @param metric 指标编码
 * @param limit 租户共享上限；{@code null} 表示未设上限，零则明确禁止继续使用
 * @param projectUsed 当前项目在 UTC 日窗口中的贡献，或存量指标当前值
 * @param tenantUsed 租户共享池已用值；可归属项目的日指标由全部项目行求和
 * @param softLimitBasisPoints 软限阈值基点；8000 表示上限的 80%
 * @param degradeBasisPoints 降级阈值基点；12000 表示上限的 120%
 */
public record QuotaMetricUsage(QuotaMetric metric, Long limit, long projectUsed, long tenantUsed,
                               int softLimitBasisPoints, int degradeBasisPoints) {

    /**
     * 保留存量/测试调用点的首期 80%/120% 基线。
     *
     * @param metric 指标编码
     * @param limit 租户共享上限
     * @param projectUsed 当前项目贡献
     * @param tenantUsed 租户共享池已用值
     */
    public QuotaMetricUsage(QuotaMetric metric, Long limit, long projectUsed, long tenantUsed) {
        this(metric, limit, projectUsed, tenantUsed, 8000, 12000);
    }

    /** 阈值必须保持软限不高于硬限、降级不低于硬限。 */
    public QuotaMetricUsage {
        if (softLimitBasisPoints < 0 || softLimitBasisPoints > 10000 || degradeBasisPoints < 10000) {
            throw new IllegalArgumentException("日额度分级阈值不合法");
        }
    }

    /**
     * 计算共享池剩余量。
     *
     * <p>不把 {@code null} 转成零：零在套餐里有“禁用”这一真实业务含义，二者混用会
     * 让页面误导用户、运行时也无法区分该限流还是不限流。
     *
     * @return 未设置上限时为 {@code null}，否则不小于零的剩余量
     */
    public Long remaining() {
        return limit == null ? null : Math.max(0L, limit - tenantUsed);
    }

    /**
     * 返回供控制台显示的统一状态。
     *
     * @return 正常、软限、硬限或超出降级水位的状态
     */
    public Status status() {
        if (limit == null) return Status.NORMAL;
        // 零上限表达禁用，任何使用量都至少是硬限；不能做 used/limit 除法。
        if (limit == 0) return tenantUsed > 0 ? Status.DEGRADED : Status.HARD_LIMIT;
        // 先判定最高等级，避免 120% 同时满足硬限后被过早返回 HARD_LIMIT。
        if (reachesBasisPoints(tenantUsed, limit, degradeBasisPoints)) {
            return Status.DEGRADED;
        }
        if (tenantUsed >= limit) {
            return Status.HARD_LIMIT;
        }
        return reachesBasisPoints(tenantUsed, limit, softLimitBasisPoints)
                ? Status.SOFT_LIMIT : Status.NORMAL;
    }

    /**
     * 以无溢出的整数比较基点阈值，禁止直接做 {@code used * 10000} 的 long 乘法。
     *
     * @param used 已用绝对值
     * @param quota 套餐上限，必须为正数
     * @param basisPoints 阈值基点
     * @return 已达到或越过阈值时为 {@code true}
     */
    private static boolean reachesBasisPoints(long used, long quota, int basisPoints) {
        BigInteger scaledUsed = BigInteger.valueOf(used).multiply(BigInteger.valueOf(10000));
        BigInteger scaledThreshold = BigInteger.valueOf(quota).multiply(BigInteger.valueOf(basisPoints));
        return scaledUsed.compareTo(scaledThreshold) >= 0;
    }

    /** 共享池状态；字符串会进入 OpenAPI，新增值必须保证控制台兼容。 */
    public enum Status {
        /** 未接近限额。 */
        NORMAL,
        /** 已接近限额但尚未拒绝请求。 */
        SOFT_LIMIT,
        /** 已达到限额；S7-3 由各入口按风险执行拒绝或降级。 */
        HARD_LIMIT,
        /** 已达到策略降级水位；S7-5 由各入口保留核心事实并停止高成本副作用。 */
        DEGRADED
    }
}
