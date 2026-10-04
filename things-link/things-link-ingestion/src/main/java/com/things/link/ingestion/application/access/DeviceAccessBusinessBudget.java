package com.things.link.ingestion.application.access;

import com.things.link.ingestion.application.DeviceUplinkRateLimiter;
import com.things.link.project.application.EffectiveQuotaPolicy;
import com.things.link.project.application.EffectiveQuotaPolicyProvider;
import com.things.link.project.application.QuotaRuntimeMetrics;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 设备面业务请求预算的唯一扣减点（接入合同 §6「预算在租户＋设备维度统一、跨协议共享」）。
 *
 * <p>固定为一个组件而不是让各协议各扣一次：HTTP／CoAP 每个请求都会经过设备认证闸门，因此在那里扣；TCP 一次连接
 * 只认证一次，因此在那里按**业务帧**扣。两条路径共用本实现，避免「换个协议换一份额度」这种最隐蔽的配额绕过——
 * 它不会报错，只会让限额悄悄失效。</p>
 *
 * <p>策略不可解析时按安全默认继续（依赖故障不放大为接入中断），这与既有 MQTT 路径同一口径。</p>
 */
@Component
public class DeviceAccessBusinessBudget {

    /** 与 MQTT 共用的上行短窗口限流器。 */
    private final DeviceUplinkRateLimiter rateLimiter;

    /** 有效配额策略端口。 */
    private final EffectiveQuotaPolicyProvider quotaPolicyProvider;

    /** 策略降级指标。 */
    private final QuotaRuntimeMetrics quotaMetrics;

    /**
     * @param rateLimiter 与 MQTT 共用的上行限流器
     * @param quotaPolicyProvider 有效配额策略端口
     * @param quotaMetrics 策略降级指标
     */
    public DeviceAccessBusinessBudget(DeviceUplinkRateLimiter rateLimiter,
                                      EffectiveQuotaPolicyProvider quotaPolicyProvider,
                                      QuotaRuntimeMetrics quotaMetrics) {
        this.rateLimiter = rateLimiter;
        this.quotaPolicyProvider = quotaPolicyProvider;
        this.quotaMetrics = quotaMetrics;
    }

    /**
     * 扣减一次业务请求额度。
     *
     * @param tenantId 已确权租户
     * @param projectId 已确权项目
     * @param deviceId 已确权设备
     * @throws DeviceAccessRateLimitedException 预算耗尽；调用方必须按协议映射为 429／4.29／{@code ERROR(RATE_LIMITED)}
     */
    public void charge(UUID tenantId, UUID projectId, UUID deviceId) {
        EffectiveQuotaPolicy policy;
        try {
            policy = quotaPolicyProvider.resolveTrustedDeviceProject(tenantId, projectId);
        } catch (RuntimeException exception) {
            policy = EffectiveQuotaPolicy.safeDefault(tenantId);
            quotaMetrics.recordCache(QuotaRuntimeMetrics.CacheResult.SAFE_DEFAULT);
        }
        if (!rateLimiter.tryAcquire(tenantId, deviceId, policy)) {
            throw new DeviceAccessRateLimitedException(deviceId);
        }
    }
}
