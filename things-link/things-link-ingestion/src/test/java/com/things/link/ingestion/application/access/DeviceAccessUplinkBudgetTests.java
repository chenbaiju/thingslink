package com.things.link.ingestion.application.access;

import com.things.link.device.application.DeviceAccessSessionPort;
import com.things.link.device.application.DeviceAuthenticationPort;
import com.things.link.ingestion.application.DeviceUplinkRateLimiter;
import com.things.link.ingestion.application.access.DeviceAccessBusinessBudget;
import com.things.link.project.application.EffectiveQuotaPolicy;
import com.things.link.project.application.EffectiveQuotaPolicyProvider;
import com.things.link.project.application.ProjectAccessPolicy;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.QuotaRuntimeMetrics;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.testing.AbstractKafkaIntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 真实 Redis 下验证新协议请求与 MQTT 共用同一份上行预算。
 *
 * <p>这条不变量只能靠真实 Redis 证明：限流状态在 Redis 里，用替身跑绿等于没验证。用例先用设备面认证入口
 * 把设备令牌桶耗尽，再用 MQTT 路径读取同一设备的桶，必须同样被拒——换协议不能换来一份新额度。</p>
 */
class DeviceAccessUplinkBudgetTests extends AbstractKafkaIntegrationTest {

    /** 真实限流器，与 MQTT 路径同一个 Bean。 */
    @Autowired
    private DeviceUplinkRateLimiter rateLimiter;

    /** 设备面认证入口（业务预算的唯一扣减点）。 */
    private DefaultDeviceAccessDeviceAuthenticator authenticator;

    /** 凭据校验与接入配置替身：本用例只验证预算。 */
    private final DeviceAuthenticationPort authenticationPort = mock(DeviceAuthenticationPort.class);

    /** 接入配置端口替身：默认放开平面资格。 */
    private final DeviceAccessSessionPort sessionPort = mock(DeviceAccessSessionPort.class);

    /** 用显式测试策略与真实限流器组装被测入口。 */
    void setUpAuthenticator(UUID tenantId, UUID projectId, UUID deviceId, EffectiveQuotaPolicy policy) {
        when(authenticationPort.authenticate(any(), any(), any())).thenReturn(Optional.of(
                new AuthenticatedDeviceIdentity(tenantId, projectId, deviceId, 1)));
        when(sessionPort.planeEnabled(any(), any(), any(), any())).thenReturn(true);
        when(sessionPort.recordAuthenticatedActivity(any(),any())).thenReturn(DeviceAccessSessionPort.ActivityResult.ACCEPTED);
        ProjectLifecycleAccessService lifecycle = mock(ProjectLifecycleAccessService.class);
        when(lifecycle.snapshot(any(), any())).thenReturn(new ProjectAccessPolicy(true, true, 0L));
        EffectiveQuotaPolicyProvider provider = mock(EffectiveQuotaPolicyProvider.class);
        when(provider.resolveTrustedDeviceProject(any(), any())).thenReturn(policy);
        authenticator = new DefaultDeviceAccessDeviceAuthenticator(authenticationPort, sessionPort, lifecycle,
                new DeviceAccessBusinessBudget(rateLimiter, provider, new QuotaRuntimeMetrics(new SimpleMeterRegistry())));
    }

    /** 设备面耗尽设备桶后，MQTT 路径读取同一设备必须被拒：预算跨协议共享，且每个已认证请求都扣一次。 */
    @Test
    void devicePlaneAndMqttShareSingleDeviceBucket() {
        UUID tenantId = Uuid7.generate();
        UUID projectId = Uuid7.generate();
        UUID deviceId = Uuid7.generate();
        EffectiveQuotaPolicy policy = sharedBudgetPolicy(tenantId);
        setUpAuthenticator(tenantId, projectId, deviceId, policy);
        int capacity = Math.toIntExact(policy.uplinkDeviceBurstCapacity());

        boolean devicePlaneRejected = false;
        for (int index = 0; index < capacity * 2 && !devicePlaneRejected; index++) {
            try {
                authenticator.authenticate(TransportProtocol.HTTP, "project", "device", "secret");
            } catch (DeviceAccessRateLimitedException expected) {
                devicePlaneRejected = true;
            }
        }

        assertThat(devicePlaneRejected)
                .as("即使执行期间发生按时补充，设备面也必须在有界请求数内耗尽突发容量")
                .isTrue();
        assertThat(rateLimiter.tryAcquire(tenantId, deviceId, policy))
                .as("MQTT 路径读取的是同一份令牌，不能因换协议获得新额度")
                .isFalse();
    }

    /** 不同设备各自计数：一台设备耗尽不得牵连同租户的其他设备。 */
    @Test
    void budgetIsPerDeviceNotGlobal() {
        UUID tenantId = Uuid7.generate();
        UUID projectId = Uuid7.generate();
        UUID noisy = Uuid7.generate();
        UUID quiet = Uuid7.generate();
        EffectiveQuotaPolicy policy = sharedBudgetPolicy(tenantId);
        setUpAuthenticator(tenantId, projectId, noisy, policy);
        int capacity = Math.toIntExact(policy.uplinkDeviceBurstCapacity());

        for (int index = 0; index <= capacity; index++) {
            try {
                authenticator.authenticate(TransportProtocol.HTTP, "project", "device", "secret");
            } catch (DeviceAccessRateLimitedException expected) {
                // 噪声设备被拒是本用例的前置，不是失败。
            }
        }

        assertThat(rateLimiter.tryAcquire(tenantId, quiet, policy)).as("安静设备仍应有额度").isTrue();
    }

    /**
     * 用小突发、慢补充隔离跨协议共享断言，避免默认每秒十个令牌在两个请求间正常补满。
     * 认证入口与 MQTT 路径使用同一快照，其余安全阈值沿用默认策略；补充行为由限流器专项覆盖。
     */
    private static EffectiveQuotaPolicy sharedBudgetPolicy(UUID tenantId) {
        EffectiveQuotaPolicy baseline = EffectiveQuotaPolicy.safeDefault(tenantId);
        return new EffectiveQuotaPolicy(tenantId, baseline.policyId(), baseline.assignmentVersion(),
                baseline.policyVersion(), 1L, 2L,
                baseline.uplinkTenantPerSecondLimit(), baseline.uplinkTenantPerMinuteLimit(),
                baseline.restApiReadRatePerSecond(), baseline.restApiWriteRatePerSecond(),
                baseline.restApiReadRatePerMinute(), baseline.restApiWriteRatePerMinute(),
                baseline.websocketConnectionLimit(), baseline.taskProjectDispatchPerSecond(),
                baseline.taskTenantDispatchPerSecond(), baseline.ruleTenantConcurrencyLimit(),
                baseline.ruleTenantQueueCapacity(), baseline.ruleProjectQueueCapacity(),
                baseline.dailySoftLimitBasisPoints(), baseline.dailyDegradeBasisPoints(), baseline.planQuota());
    }

}
