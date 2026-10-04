package com.things.link.telemetry.application;

import com.things.link.support.tenant.TransactionLocalRlsScope;
import com.things.link.telemetry.domain.DeviceCommandClaimRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证领取用例的租约与条数收敛、RLS 范围建立与结果映射。 */
class DeviceCommandClaimServiceTests {

    /** 认证租户。 */ private static final UUID TENANT = UUID.randomUUID();
    /** 认证项目。 */ private static final UUID PROJECT = UUID.randomUUID();
    /** 认证设备。 */ private static final UUID DEVICE = UUID.randomUUID();

    /** 领取事实仓储替身。 */
    private DeviceCommandClaimRepository repository;
    /** RLS 范围组件替身。 */
    private TransactionLocalRlsScope rlsScope;
    /** 待发预算超限指标注册表。 */
    private SimpleMeterRegistry meterRegistry;

    /** 被测用例。 */
    private DeviceCommandClaimService service;

    /** 每个用例重建替身与可控事务。 */
    @BeforeEach
    void setUp() {
        repository = mock(DeviceCommandClaimRepository.class);
        rlsScope = mock(TransactionLocalRlsScope.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        when(transactionTemplate.execute(any()))
                .thenAnswer(call -> ((TransactionCallback<?>) call.getArgument(0)).doInTransaction(null));
        meterRegistry = new SimpleMeterRegistry();
        service = new DeviceCommandClaimService(repository, rlsScope, transactionTemplate, 100, meterRegistry);
    }

    /** 空租约取冻结默认值，未指定条数取默认 1 条，并在认证项目范围内领取。 */
    @Test
    void appliesFrozenDefaultsAndEstablishesProjectScope() {
        when(repository.claimDue(PROJECT, DEVICE, Duration.ofSeconds(30), 1)).thenReturn(List.of());

        assertThat(service.claim(new DeviceCommandClaimPort.ClaimRequest(TENANT, PROJECT, DEVICE, null, 0)))
                .isEmpty();
        verify(rlsScope).establish(TENANT, PROJECT);
        verify(repository).claimDue(PROJECT, DEVICE, Duration.ofSeconds(30), 1);
    }

    /** 设备请求的越界值必须被夹到冻结区间，不能让设备给自己签发超长租约或一次拉走整队命令。 */
    @Test
    void clampsRequestedLeaseAndLimit() {
        when(repository.claimDue(eq(PROJECT), eq(DEVICE), any(), any(Integer.class))).thenReturn(List.of());

        service.claim(new DeviceCommandClaimPort.ClaimRequest(TENANT, PROJECT, DEVICE, Duration.ofSeconds(1), 50));

        verify(repository).claimDue(PROJECT, DEVICE, Duration.ofSeconds(10), 10);
    }

    /** 待发量超过预算只打点不丢单：命令照常返回给设备，指标记录一次超限。 */
    @Test
    void recordsPendingBudgetWithoutDroppingCommands() {
        UUID commandId = UUID.randomUUID();
        when(repository.claimDue(PROJECT, DEVICE, Duration.ofSeconds(30), 1)).thenReturn(List.of(
                new DeviceCommandClaimRepository.ClaimedCommand(commandId, "reboot", java.util.Map.of(), 1,
                        java.time.Instant.parse("2026-09-18T08:00:30Z"))));
        when(repository.countPending(PROJECT, DEVICE)).thenReturn(101L);

        assertThat(service.claim(new DeviceCommandClaimPort.ClaimRequest(TENANT, PROJECT, DEVICE, null, 1)))
                .as("超预算仍然把命令交给设备，不能丢弃已受理的命令")
                .hasSize(1);
        assertThat(meterRegistry.counter("device.access.command.pending.over_budget").count()).isEqualTo(1.0d);
    }

    /** 预算内的待发量不打点。 */
    @Test
    void staysSilentWithinPendingBudget() {
        when(repository.claimDue(PROJECT, DEVICE, Duration.ofSeconds(30), 1)).thenReturn(List.of(
                new DeviceCommandClaimRepository.ClaimedCommand(UUID.randomUUID(), "reboot", java.util.Map.of(), 1,
                        java.time.Instant.parse("2026-09-18T08:00:30Z"))));
        when(repository.countPending(PROJECT, DEVICE)).thenReturn(100L);

        service.claim(new DeviceCommandClaimPort.ClaimRequest(TENANT, PROJECT, DEVICE, null, 1));

        assertThat(meterRegistry.counter("device.access.command.pending.over_budget").count()).isZero();
    }

    /** 领取结果映射为协议无关视图，参数对象与租约到期时刻原样带出。 */
    @Test
    void mapsClaimedCommandsToPortView() {
        UUID commandId = UUID.randomUUID();
        Instant leaseExpiresAt = Instant.parse("2026-09-18T08:00:30Z");
        when(repository.claimDue(PROJECT, DEVICE, Duration.ofSeconds(300), 2)).thenReturn(List.of(
                new DeviceCommandClaimRepository.ClaimedCommand(commandId, "reboot",
                        java.util.Map.of("speed", 3), 2, leaseExpiresAt)));

        List<DeviceCommandClaimPort.Claimed> claimed = service.claim(new DeviceCommandClaimPort.ClaimRequest(
                TENANT, PROJECT, DEVICE, Duration.ofHours(1), 2));

        assertThat(claimed).hasSize(1);
        assertThat(claimed.getFirst().commandId()).isEqualTo(commandId);
        assertThat(claimed.getFirst().commandKey()).isEqualTo("reboot");
        assertThat(claimed.getFirst().input()).containsEntry("speed", 3);
        assertThat(claimed.getFirst().attempt()).isEqualTo(2);
        assertThat(claimed.getFirst().leaseExpiresAt()).isEqualTo(leaseExpiresAt);
    }

    /** 归属不完整时在构造点失败，绝不进入数据库。 */
    @Test
    void rejectsIncompleteClaimRequest() {
        assertThatThrownBy(() -> new DeviceCommandClaimPort.ClaimRequest(TENANT, PROJECT, null, null, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("领取归属不能为空");
        verifyNoInteractions(repository, rlsScope);
    }
}
