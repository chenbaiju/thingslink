package com.things.link.ota.application;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.things.link.device.application.OtaDeviceIdentity;
import com.things.link.device.application.OtaDeviceIdentityPort;
import com.things.link.ota.domain.OtaCampaign;
import com.things.link.ota.domain.OtaCampaignRuntime;
import com.things.link.ota.domain.OtaCampaignRuntimeRepository;
import com.things.link.ota.domain.OtaDownloadRequestRepository;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import com.things.link.support.audit.AuditLogService;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** 模拟完整资格返回后的凭据到期，仅证明控制流，不代替真实数据库或密码学验证。 */
class OtaDownloadRequestIngestionServiceTests {
    /** 安全暂停和正常接纳都不能越过最终凭据到期围栏。 */
    @ParameterizedTest
    @EnumSource(value = OtaRuntimeQualification.Disposition.class, names = {"SECURITY", "ELIGIBLE"})
    void expiredAfterQualificationCannotPersistAnyOutcome(OtaRuntimeQualification.Disposition disposition) {
        var repository = mock(OtaDownloadRequestRepository.class);
        var runtime = mock(OtaCampaignRuntimeRepository.class);
        var devices = mock(OtaDeviceIdentityPort.class);
        var qualification = mock(OtaRuntimeQualification.class);
        var lifecycle = mock(ProjectLifecycleAccessService.class);
        var rls = mock(TransactionLocalRlsScope.class);
        var audit = mock(AuditLogService.class);
        UUID tenant = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        UUID job = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();
        UUID firmware = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        Instant now = Instant.now();
        String hash = "a".repeat(64);
        var identity = new AuthenticatedDeviceIdentity(tenant, project, device, 3);
        var jobContext = new OtaDownloadRequestRepository.JobContext(tenant, project, device, campaignId,
                firmware, job, "DISPATCHED", 2, 1, 3, hash, now.plusSeconds(60));
        var graph = mock(OtaCampaignRuntime.class);
        var campaign = mock(OtaCampaign.class);
        var currentDevice = mock(OtaDeviceIdentity.class);
        when(graph.campaign()).thenReturn(campaign);
        when(campaign.status()).thenReturn("RUNNING");
        when(lifecycle.lockActiveForWrite(tenant, project)).thenReturn(true);
        when(repository.locate(job)).thenReturn(Optional.of(jobContext));
        when(runtime.lockRuntime(project, campaignId)).thenReturn(Optional.of(graph));
        when(runtime.currentTime()).thenReturn(now);
        when(devices.lockCurrent(identity)).thenReturn(Optional.of(currentDevice));
        when(devices.credentialValid(identity)).thenReturn(false);
        var outcome = new OtaRuntimeQualification.Outcome(disposition, "TEST_OUTCOME", 3L, 1L, hash, now);
        when(qualification.check(tenant, project, device, firmware)).thenReturn(outcome);
        var service = new OtaDownloadRequestIngestionService(repository,
                mock(com.things.link.ota.domain.OtaDownloadAuthorizationRepository.class), runtime, devices,
                qualification, lifecycle, rls, audit, mock(OtaExecutionOriginGuard.class));
        byte[] payload = ("{\"contractVersion\":\"tc-ota-download-request/v1\",\"requestId\":\"" + request
                + "\",\"jobId\":\"" + job + "\",\"attemptNo\":1,\"manifestSha256\":\"" + hash + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> service.accept(identity, payload, now));
        verify(qualification).check(tenant, project, device, firmware);
        verify(devices).credentialValid(identity);
        verify(repository, never()).safetyPause(any(), anyLong(), any());
        verify(repository, never()).create(any(), anyLong());
        verifyNoInteractions(audit);
    }
}
