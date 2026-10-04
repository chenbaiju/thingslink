package com.things.link.project.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 配额共享池最小投影的持久化端口。
 *
 * <p>实现只能调用迁移中受限的 {@code project_quota_overview(date)}，不能直接查询
 * 计费表；后者会在跨租户协作者场景被 tenant RLS 拦住，或者诱使调用方绕过隔离。
 */
public interface QuotaOverviewRepository {

    /**
     * 读取当前数据库会话已选项目的 UTC 日用量投影。
     *
     * @param usageDate UTC 计量日期
     * @return 每个日计量指标一行，附带同一策略版本和共享池合计
     */
    List<QuotaMetricUsageRow> findDailyUsage(LocalDate usageDate);

    /**
     * 用设备接入层已经确权的owner二元组读取设备存量策略。
     *
     * <p>该端口不依赖账号或HTTP {@code TenantContext}，但每次都必须由数据库重新核对项目归属、
     * 项目/租户有效性和策略绑定；缓存及安全默认值不能参与设备存量硬限。</p>
     *
     * @param trustedTenantId 已认证设备项目的owner租户ID
     * @param trustedProjectId 已认证设备项目ID
     * @return 二元组有效时的设备上限与统一水位，否则为空
     */
    Optional<DeviceQuotaPolicyRow> findDeviceQuotaPolicy(UUID trustedTenantId, UUID trustedProjectId);

    /**
     * 数据库函数返回的原始行。
     *
     * @param policyCode 策略编码
     * @param policyVersion 策略版本
     * @param deviceLimit 租户共享设备存量上限；null 为未设上限
     * @param metric 日计量指标
     * @param limit 策略上限；null 为未设上限
     * @param projectUsed 当前项目贡献
     * @param tenantUsed 租户全部项目贡献的合计
     * @param softLimitBasisPoints 软限阈值基点
     * @param degradeBasisPoints 降级阈值基点
     */
    record QuotaMetricUsageRow(String policyCode, long policyVersion, Long deviceLimit, QuotaMetric metric,
                               Long limit, long projectUsed, long tenantUsed,
                               int softLimitBasisPoints, int degradeBasisPoints) {
    }

    /**
     * 无HTTP上下文的设备存量策略最小投影。
     *
     * @param deviceLimit 租户共享设备上限；{@code null}表示不限
     * @param softLimitBasisPoints 软限水位基点
     * @param degradeBasisPoints 降级水位基点
     */
    record DeviceQuotaPolicyRow(Long deviceLimit, int softLimitBasisPoints, int degradeBasisPoints) {
    }
}
