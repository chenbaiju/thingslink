package com.things.link.ota.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import com.things.link.ota.domain.OtaCampaign;
import com.things.link.ota.domain.OtaCampaignAdvancementRepository;
import com.things.link.ota.domain.OtaCampaignBatchProgress;
import com.things.link.ota.domain.OtaCampaignRepository;
import com.things.link.ota.domain.OtaCampaignRuntime;
import com.things.link.ota.domain.OtaCampaignRuntimeRepository;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.audit.AuditLogService;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/** 控制流测试不代替真实PG状态图与设备回执验收。 */
class OtaCampaignAdvancementServiceTests {
    /** 固定租户。 */ private static final UUID TENANT = new UUID(0, 1);
    /** 固定项目。 */ private static final UUID PROJECT = new UUID(0, 2);
    /** 固定活动。 */ private static final UUID CAMPAIGN = new UUID(0, 3);
    /** 精确事实仓储。 */ private final OtaCampaignAdvancementRepository repository = mock(OtaCampaignAdvancementRepository.class);
    /** 锁定图。 */ private final OtaCampaignRuntimeRepository runtime = mock(OtaCampaignRuntimeRepository.class);
    /** 目标保持不变。 */ private final OtaCampaignRepository campaigns = mock(OtaCampaignRepository.class);
    /** 资格当前值。 */ private final OtaRuntimeQualification qualification = mock(OtaRuntimeQualification.class);
    /** 原子审计。 */ private final AuditLogService audit = mock(AuditLogService.class);
    /** 被测服务。 */ private final OtaCampaignAdvancementService service = new OtaCampaignAdvancementService(repository,
            runtime, campaigns, mock(ProjectService.class), mock(ProjectLifecycleAccessService.class),
            mock(TransactionLocalRlsScope.class), qualification, mock(OtaRollbackDirectionGuard.class), audit);

    /** 人工成功中间批不得被后台越权放行。 */
    @Test void manualMiddleBatchDoesNotAdvance() throws IOException {
        ready("RUNNING", "SUCCEEDED", 1, true);
        assertFalse(service.advanceOne());
        verifyNoInteractions(qualification, audit);
        verify(repository, never()).advance(any(), any(), anyLong(), org.mockito.ArgumentMatchers.anyInt(), any(), any());
    }

    /** 暂停和失败均不能被最后批完成逻辑吞掉。 */
    @Test void pausedOrFailedBatchCannotComplete() throws IOException {
        ready("PAUSED", "SUCCEEDED", 2, true);
        assertFalse(service.advanceOne());
        ready("RUNNING", "FAILED", 2, true);
        assertFalse(service.advanceOne());
        verify(repository, never()).complete(any(), any(), anyLong());
    }

    /** 完成只记录原事实，不因签名已过有效期再请求执行资格；审计失败仍传播。 */
    @Test void finalManualBatchCompletesWithoutNewExecutionQualification() throws IOException {
        ready("RUNNING", "SUCCEEDED", 2, true);
        when(repository.complete(PROJECT, CAMPAIGN, 9)).thenReturn(true);
        assertTrue(service.advanceOne());
        verifyNoInteractions(qualification);
        doThrow(new DataAccessResourceFailureException("audit unavailable")).when(audit).record(any());
        assertThrows(DataAccessResourceFailureException.class, service::advanceOne);
    }

    /** 基础设施故障不能被改写为安全失败或完整成功。 */
    @Test void infrastructureFailurePropagatesWhileUnsafeReleasePauses() throws IOException {
        ready("RUNNING", "SUCCEEDED", 1, false);
        when(qualification.validateCurrent(TENANT, PROJECT, CAMPAIGN))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));
        assertThrows(DataAccessResourceFailureException.class, service::advanceOne);
        verify(runtime, never()).securityPause(any(), any(), any(), any());
        org.mockito.Mockito.doThrow(new IllegalArgumentException("unsafe release")).when(qualification)
                .validateCurrent(TENANT, PROJECT, CAMPAIGN);
        when(runtime.securityPause(PROJECT, CAMPAIGN, null, "BATCH_ADVANCEMENT_SECURITY_INVALID")).thenReturn(1);
        assertTrue(service.advanceOne());
        verify(repository, never()).advance(any(), any(), anyLong(), org.mockito.ArgumentMatchers.anyInt(), any(), any());
    }

    /** 继承管理账号时不允许利用候选入口换项目。 */
    @Test void rejectsInheritedTenantBeforeCandidateLookup() {
        TenantContext.set(new TenantScope(TENANT, PROJECT, UUID.randomUUID()));
        try { assertThrows(IllegalStateException.class, service::advanceOne); }
        finally { TenantContext.clear(); }
        verifyNoInteractions(repository);
    }

    /** 真实协议计划只改变冻结审批开关，模拟仓储返回当前完整图。 */
    private void ready(String status, String batchStatus, int batch, boolean manual) throws IOException {
        byte[] plan;
        try (var input = getClass().getResourceAsStream("/ota/campaign-plan-v1.json")) {
            plan = input.readAllBytes();
        }
        if (!manual) plan = new String(plan, java.nio.charset.StandardCharsets.UTF_8)
                .replace("requireManualBatchApproval\":true", "requireManualBatchApproval\":false")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var head = new OtaCampaign(CAMPAIGN, TENANT, PROJECT, CAMPAIGN, CAMPAIGN, CAMPAIGN,
                plan, "0".repeat(64), new byte[] {123, 125}, "0".repeat(64), status, 9, 3, 2,
                Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, null, null);
        var progress = new OtaCampaignBatchProgress(batchStatus, manual, manual && batch == 1,
                batch == 1 ? 2 : null, null, null, 3, 3, 0, 0, 0, 0);
        var current = new OtaCampaignRuntime(head, Instant.EPOCH, batch, null, null, null, null, null,
                0, 0, 0, null, progress);
        when(repository.candidate()).thenReturn(Optional.of(new OtaCampaignAdvancementRepository.Candidate(TENANT, PROJECT, CAMPAIGN, 9)));
        when(runtime.lockRuntime(PROJECT, CAMPAIGN)).thenReturn(Optional.of(current));
    }
}
