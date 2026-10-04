package com.things.link.ota.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.things.link.device.application.OtaDeviceIdentity;
import com.things.link.device.application.OtaDeviceIdentityPort;
import com.things.link.ota.domain.OtaCampaign;
import com.things.link.ota.domain.OtaCampaignRuntime;
import com.things.link.ota.domain.OtaCampaignRuntimeCancellation;
import com.things.link.ota.domain.OtaCampaignRuntimeRepository;
import com.things.link.ota.domain.OtaInstallStopOperation;
import com.things.link.ota.domain.OtaInstallStopRepository;
import com.things.link.ota.domain.OtaJobExecutionOrigin;
import com.things.link.ota.domain.OtaJobProgressRepository;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.audit.AuditLogService;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * D-157：安装停止命令必须携带作业的下载授权id，且来源不能是{@code OtaJobProgressRepository#locate}
 * 返回的已封存投影（SQL条件{@code sealed_at IS NOT NULL}）。
 *
 * <p>真实{@code up/ota/download/request}受理会经触发器同事务插入一条<b>未封存</b>授权，此时作业仍停在
 * {@code DISPATCHED}，{@code locate}的{@code authorization_id}为空；而DB函数{@code ota_install_stop_create}
 * 要求命令携带{@code array_agg(id ORDER BY id)}的全量集合。修复前{@code seedOne}据此构造空数组，DB以
 * SQLSTATE 23514（{@code install stop immutable command tuple invalid}）整事务拒绝，紧急停止在该窗口不可用。
 * 本测试证明该窗口下创建成功，并且交给仓储持久化的授权id与规范命令字节里的授权id都等于仓储按数据库
 * {@code ORDER BY id}返回的全量有序集合。</p>
 *
 * <p><b>D-158修复后（本片）：</b>停止命令携带的授权集合收窄到作业<b>当前尝试</b>。跨重试尝试的同一
 * {@code job_id} 仍可并存多条授权（{@code ota_download_request_job_attempt_uk UNIQUE(job_id,attempt_no)}），
 * 但 {@code ota_install_stop_create} 现在按与 {@code ota_download_request} 的共享身份连接并过滤
 * {@code r.attempt_no=j.attempt_no}，仓储查询同步收窄（见 {@code retriedJobCarriesOnlyCurrentAttemptAuthorization}），
 * 因此集合至多一项，与冻结的 ≤1 线格式一致，历史尝试授权不再进入命令。</p>
 */
class OtaInstallStopOperationServiceTests {
    /** 原停止仓储：唯一候选、全量授权id与创建结果。 */ private final OtaInstallStopRepository operations = mock(OtaInstallStopRepository.class);
    /** 当前取消请求与真实控制锁。 */ private final OtaCampaignRuntimeRepository runtime = mock(OtaCampaignRuntimeRepository.class);
    /** 当前作业尝试与已封存授权投影。 */ private final OtaJobProgressRepository progress = mock(OtaJobProgressRepository.class);
    /** 原来源完整性校验。 */ private final OtaExecutionOriginGuard origins = mock(OtaExecutionOriginGuard.class);
    /** 当前受控制造合同。 */ private final OtaInstallStopBaselineQualification baselines = mock(OtaInstallStopBaselineQualification.class);
    /** 当前设备代际。 */ private final OtaDeviceIdentityPort identities = mock(OtaDeviceIdentityPort.class);
    /** ACTIVE持续许可。 */ private final ProjectLifecycleAccessService lifecycle = mock(ProjectLifecycleAccessService.class);
    /** 真实RLS范围。 */ private final TransactionLocalRlsScope rls = mock(TransactionLocalRlsScope.class);
    /** 同事务系统审计。 */ private final AuditLogService audit = mock(AuditLogService.class);
    /** 被测真实事务服务。 */
    private final OtaInstallStopOperationService service = new OtaInstallStopOperationService(
            operations, runtime, progress, origins, baselines, identities, lifecycle, rls, audit);

    /** 后台服务不得继承任何管理账号。 */
    @BeforeEach @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    /**
     * 未封存窗口：{@code locate}返回的授权为空，但仓储全量有序集合含该未封存授权；命令必须携带它。
     *
     * <p>这正是D-157的现场：真实下载申请已受理（存在未封存授权），授权尚未封存，{@code locate}的
     * {@code authorization_id}为空。旧实现据此构造空数组，与DB函数的全量集合不等而整事务被拒。</p>
     */
    @Test
    void unsealedAuthorizationWindowCarriesDatabaseOrderedFullSet() {
        UUID unsealed = UUID.randomUUID();
        UUID job = fixture(null, List.of(unsealed));

        assertThat(service.seedOne()).as("未封存授权窗口内停止操作必须能创建成功").isTrue();

        OtaInstallStopOperation persisted = capturedOperation();
        assertThat(persisted.jobId()).as("持久化操作必须绑定本例真实作业").isEqualTo(job);
        assertThat(persisted.authorizationIds())
                .as("持久化操作的授权id必须等于仓储按id升序返回的全量集合（含未封存），实际=" + persisted.authorizationIds())
                .containsExactly(unsealed);
        OtaInstallStopOperationCodec.Operation command =
                new OtaInstallStopOperationCodec().decode(persisted.canonical()).value();
        assertThat(command.authorizationIds())
                .as("规范命令字节里的authorizationIds必须与持久化事实逐序一致，实际=" + command.authorizationIds())
                .containsExactly(unsealed);
        verify(operations).authorizationIds(job, 1);
        verify(progress).locate(job);
    }

    /**
     * 重试作业（{@code attempt_no=2}，历史尝试与当前尝试各一条授权）：命令只携带当前尝试的单条授权。
     *
     * <p>D-158现场：同一{@code job_id}在重试后可并存两条授权，而仓储与DB函数按当前尝试收窄后只返回
     * {@code attempt_no=2} 的那一条；{@code seedOne} 因此能编码出 ≤1 项的命令并成功创建，历史尝试授权
     * 绝不进入规范字节或持久化事实。</p>
     */
    @Test
    void retriedJobCarriesOnlyCurrentAttemptAuthorization() {
        UUID historical = UUID.randomUUID();
        UUID current = UUID.randomUUID();
        UUID job = fixture(2, null, List.of(current));

        assertThat(service.seedOne()).as("重试作业当前尝试的停止操作必须能创建成功").isTrue();

        OtaInstallStopOperation persisted = capturedOperation();
        assertThat(persisted.jobId()).isEqualTo(job);
        assertThat(persisted.attemptNo()).as("操作必须绑定当前尝试号而不是首次尝试").isEqualTo(2);
        assertThat(persisted.authorizationIds())
                .as("持久化操作必须只含当前尝试单条授权，实际=" + persisted.authorizationIds())
                .containsExactly(current).doesNotContain(historical);
        OtaInstallStopOperationCodec.Operation command =
                new OtaInstallStopOperationCodec().decode(persisted.canonical()).value();
        assertThat(command.authorizationIds())
                .as("规范命令字节里的authorizationIds必须恰为当前尝试的单条授权，实际=" + command.authorizationIds())
                .containsExactly(current);
        verify(operations).authorizationIds(job, 2);
        verify(progress).origin(job, 2);
    }

    /** 捕获交给仓储持久化的唯一操作，断言来自被测服务的实际写出值。 */
    private OtaInstallStopOperation capturedOperation() {
        ArgumentCaptor<OtaInstallStopOperation> captor = ArgumentCaptor.forClass(OtaInstallStopOperation.class);
        verify(operations).create(any(), captor.capture(), eq("CANCELLATION_ATOMIC_STOP_REQUESTED"));
        return captor.getValue();
    }

    /**
     * 构造一条{@code DISPATCHED}且取消责任成立的候选，并按数据库{@code ORDER BY id}给定全量授权id。
     *
     * @param sealedProjection {@code locate}返回的已封存授权投影，未封存窗口为空
     * @param authorizationIds 仓储返回的全部下载授权id（含未封存），顺序即数据库{@code ORDER BY id}
     * @return 本例真实作业身份
     */
    private UUID fixture(UUID sealedProjection, List<UUID> authorizationIds) {
        return fixture(1, sealedProjection, authorizationIds);
    }

    /**
     * 构造指定尝试号的候选，用于覆盖重试作业（{@code attemptNo=2}）的当前尝试授权窗口。
     *
     * @param attemptNo 当前尝试号，{@code locate}/{@code origin} 与候选必须一致
     * @param sealedProjection {@code locate}返回的已封存授权投影，未封存窗口为空
     * @param authorizationIds 仓储按当前尝试返回的下载授权id，顺序即数据库{@code ORDER BY id}
     * @return 本例真实作业身份
     */
    private UUID fixture(int attemptNo, UUID sealedProjection, List<UUID> authorizationIds) {
        UUID tenant = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID campaign = UUID.randomUUID();
        UUID firmware = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        UUID job = UUID.randomUUID();
        UUID deviceType = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-16T00:00:00Z");
        var context = new OtaJobProgressRepository.Context(tenant, project, campaign, firmware, job, device, attemptNo,
                "DISPATCHED", 7L, 3L, "a".repeat(64), sealedProjection, now.plusSeconds(120));
        when(operations.nextCandidate()).thenReturn(Optional.of(context));
        when(lifecycle.lockActiveForWrite(tenant, project)).thenReturn(true);
        when(runtime.lockRuntime(project, campaign)).thenReturn(Optional.of(new OtaCampaignRuntime(
                new OtaCampaign(campaign, tenant, project, firmware, UUID.randomUUID(), UUID.randomUUID(),
                        new byte[]{1}, "p".repeat(64), new byte[]{2}, "m".repeat(64), "CANCELLING", 4L, 1, 1,
                        now, now, now, null, null),
                now, 1, null, null, null, null, null, 0, 0, 0,
                new OtaCampaignRuntimeCancellation(7L, "RUNNING", now, UUID.randomUUID(), "边缘取消", 0, 1, null),
                null)));
        when(progress.locate(job)).thenReturn(Optional.of(context));
        when(progress.origin(job, attemptNo)).thenReturn(Optional.of(new OtaJobExecutionOrigin(tenant, project,
                campaign, job, device, attemptNo, 3L, 2L, 3L, "b".repeat(64), new byte[]{3}, now, now, now, 1L)));
        when(identities.lockCurrent(any())).thenReturn(Optional.of(new OtaDeviceIdentity(tenant, project, device,
                deviceType, "product-key", 3L, null, null, null, null, null)));
        when(baselines.requireCurrent(tenant, project, deviceType)).thenReturn(new OtaInstallStopBaselineQualification.Qualified(
                new OtaTypeBaselineCodec.Decoded(null, new byte[]{4}, "d".repeat(64)),
                new OtaInstallStopBaselineCodec.Decoded(null, new byte[]{5}, "e".repeat(64))));
        when(runtime.currentTime()).thenReturn(now);
        when(identities.credentialValid(any())).thenReturn(true);
        when(operations.deviceConflicted(device)).thenReturn(false);
        when(operations.authorizationIds(job, attemptNo)).thenReturn(authorizationIds);
        when(operations.create(any(), any(), any())).thenReturn(true);
        return job;
    }
}
