package com.things.link.ota.application;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.doThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.things.link.device.application.OtaDeviceIdentityPort;
import com.things.link.device.application.OtaDeviceNotificationRoute;
import com.things.link.device.application.OtaDeviceNotificationRoutePort;
import com.things.link.ota.domain.OtaCampaign;
import com.things.link.ota.domain.OtaCampaignRuntime;
import com.things.link.ota.domain.OtaCampaignRuntimeRepository;
import com.things.link.ota.domain.OtaDownloadAuthorizationRepository;
import com.things.link.ota.domain.OtaDownloadRequestRepository;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.audit.AuditLogService;
import com.things.link.support.storage.VersionedPrivateObjectStorage;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.ota.domain.OtaRelease;
import org.springframework.beans.factory.ObjectProvider;

/** 只证明应用围栏控制流，真实租约、RLS和公开签名另有整合证据。 */
class OtaDownloadAuthorizationServiceTests {
    /** 本类仅模拟事务回调上下文以观察控制流；真实提交/审计回滚由整合测试证明。 */
    @org.junit.jupiter.api.BeforeEach void synchronizationContext() {
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
    }
    /** 不把本例模拟上下文泄漏到下一例。 */
    @org.junit.jupiter.api.AfterEach void clearSynchronizationContext() {
        org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    /** 管理账号不得伪装后台授权，不允许先调用仓储取得scope。 */
    @Test void managementContextCannotClaimOrPrepare() {
        var f = fixture("WAITING");
        TenantContext.set(new TenantScope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        try {
            assertThrows(IllegalStateException.class, () -> f.service().claimOne());
            assertThrows(IllegalStateException.class, () -> f.service().prepareSigning(f.id(), f.token()));
            verify(f.repository(), never()).claimOne();
            verify(f.repository(), never()).authoritativeClaim(any(), any());
            verifyNoInteractions(f.rls());
        } finally { TenantContext.clear(); }
    }

    /** 资格完成后凭据到期，安全异常也不能绕过最终设备认证围栏暂停活动。 */
    @ParameterizedTest
    @EnumSource(value = OtaRuntimeQualification.Disposition.class, names = {"SECURITY", "ELIGIBLE"})
    void finalCredentialExpiryPreventsBudgetAndPause(OtaRuntimeQualification.Disposition disposition) {
        var f = fixture("WAITING");
        var outcome = new OtaRuntimeQualification.Outcome(disposition, "TEST_OUTCOME", 1L, 1L, "a".repeat(64), Instant.now());
        when(f.qualification().check(any(), any(), any(), any())).thenReturn(outcome);
        when(f.devices().credentialValid(any())).thenReturn(false);
        assertTrue(f.service().prepareSigning(f.id(), f.token()).isEmpty());
        verify(f.repository(), never()).reserveSigning(any(), anyLong());
        verify(f.repository(), never()).pauseSecurity(any(), any());
        verifyNoInteractions(f.audit());
    }

    /** 缺加密配置不得消耗唯一签址或额度，也不查当前发布输出。 */
    @Test void missingCipherRejectsBeforeSigningBudget() {
        var f = fixture("WAITING");
        when(f.cipher().configured()).thenReturn(false);
        assertTrue(f.service().prepareSigning(f.id(), f.token()).isEmpty());
        verify(f.repository(), never()).reserveSigning(any(), anyLong());
        verify(f.qualification(), never()).validateCurrent(any(), any(), any());
        verify(f.repository()).pauseSecurity(any(), eq("DOWNLOAD_DEPENDENCY_UNAVAILABLE"));
    }

    /** 封存后路由改变不能将旧秘密发送到新的设备短标识。 */
    @Test void changedRouteRejectsBeforeDecryptionAndTransportReservation() {
        var f = fixture("SEALED");
        when(f.claim().topic()).thenReturn("tc/v1/project/old-device/down/ota/download/response");
        assertTrue(f.service().prepareSend(f.id(), f.token()).isEmpty());
        verify(f.cipher(), never()).decrypt(any(), any());
        verify(f.repository(), never()).reserveSend(any());
        verify(f.repository()).pauseSecurity(any(), eq("DOWNLOAD_RESPONSE_INTEGRITY"));
    }

    /** AEAD认证失败不预留发送，且错误正文不会传入持久暂停理由。 */
    @Test void corruptEnvelopeCannotReserveTransmission() {
        var f = fixture("SEALED");
        when(f.cipher().decrypt(any(), any())).thenThrow(new IllegalArgumentException("secret-url-test"));
        assertTrue(f.service().prepareSend(f.id(), f.token()).isEmpty());
        verify(f.repository(), never()).reserveSend(any());
        verify(f.repository()).pauseSecurity(any(), eq("DOWNLOAD_RESPONSE_INTEGRITY"));
        verify(f.publisher(), never()).publish(any(), any(), any(), any());
    }

    /** 既有SIGNING只能走未知恢复，不能重新分配签址能力。 */
    @Test void previouslySigningOnlyRecoversWithoutNewSigning() {
        var f = fixture("SIGNING");
        assertTrue(f.service().prepareSigning(f.id(), f.token()).isEmpty());
        assertTrue(f.service().prepareSigning(f.id(), f.token()).isEmpty());
        verify(f.repository(), never()).reserveSigning(any(), anyLong());
        verifyNoInteractions(f.qualification());
    }

    /** 清租约后的最终SQL退避或耗尽仍需同事务SYSTEM审计，审计失败必须向外传播。 */
    @ParameterizedTest
    @ValueSource(strings = {"EXHAUSTED", "WAITING"})
    void finalReservationOutcomeIsAuditedAndAuditFailurePropagates(String finalStatus) {
        var f = fixture("WAITING");
        var original = f.claim().request();
        byte[] publicManifest = "public-canonical-test-manifest".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var request = new OtaDownloadRequestRepository.Request(original.id(), original.tenantId(), original.projectId(),
                original.deviceId(), original.credentialVersion(), original.requestId(), original.jobId(), original.campaignId(),
                original.firmwareId(), original.attemptNo(), OtaTrustBundleCodec.sha256(publicManifest), original.canonical(),
                original.canonicalSha256(), original.originalDeadline(), original.brokerReceivedAt(), original.acceptedAt(),
                original.reportRevision(), original.reportHash());
        when(f.claim().request()).thenReturn(request);
        var release = mock(OtaRelease.class);
        when(release.canonicalManifest()).thenReturn(publicManifest);
        var facts = new OtaReleaseDownloadService.ReleaseFacts(null, null, null, release, null);
        when(f.qualification().validateCurrent(any(), any(), any())).thenReturn(facts);
        when(f.repository().reserveSigning(any(), anyLong())).thenReturn(Optional.empty());
        var after = mock(OtaDownloadAuthorizationRepository.Claim.class);
        when(after.authorizationId()).thenReturn(f.id());
        when(after.request()).thenReturn(request);
        when(after.revision()).thenReturn(1L);
        when(after.status()).thenReturn(finalStatus);
        when(f.repository().findCurrent(f.id())).thenReturn(Optional.of(after));
        assertTrue(f.service().prepareSigning(f.id(), f.token()).isEmpty());
        var event = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(f.audit()).record(event.capture());
        assertEquals("SYSTEM", event.getValue().details().get("actorKind"));
        assertEquals("EXHAUSTED".equals(finalStatus) ? "EXHAUSTED" : "BUDGET_DEFERRED",
                event.getValue().details().get("action"));
        assertEquals(request.projectId(), event.getValue().projectId());
        assertEquals(f.id(), event.getValue().targetId());
        var failure = new IllegalStateException("测试审计数据库故障");
        doThrow(failure).when(f.audit()).record(any());
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> f.service().prepareSigning(f.id(), f.token())));
    }

    /** 仅提供满足前置条件的固定mock图，避免实际解密或网络混入控制流测试。 */
    @SuppressWarnings("unchecked")
    private static Fixture fixture(String status) {
        var repository = mock(OtaDownloadAuthorizationRepository.class);
        var runtime = mock(OtaCampaignRuntimeRepository.class);
        var lifecycle = mock(ProjectLifecycleAccessService.class);
        var rls = mock(TransactionLocalRlsScope.class);
        var routes = mock(OtaDeviceNotificationRoutePort.class);
        var devices = mock(OtaDeviceIdentityPort.class);
        var qualification = mock(OtaRuntimeQualification.class);
        var projects = mock(ProjectService.class);
        ObjectProvider<VersionedPrivateObjectStorage> storages = mock(ObjectProvider.class);
        var storage = mock(VersionedPrivateObjectStorage.class);
        var cipher = mock(OtaDownloadResponseCipher.class);
        var publisher = mock(OtaDownloadResponsePublisher.class);
        var audit = mock(AuditLogService.class);
        var claim = mock(OtaDownloadAuthorizationRepository.Claim.class);
        UUID id = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        Instant now = Instant.now();
        var request = new OtaDownloadRequestRepository.Request(id, tenant, project, device, 1, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, "a".repeat(64), new byte[] {1},
                "b".repeat(64), now.plusSeconds(100), now, now, 1, "c".repeat(64));
        when(claim.request()).thenReturn(request);
        when(claim.authorizationId()).thenReturn(id);
        when(claim.leaseToken()).thenReturn(token);
        when(claim.status()).thenReturn(status);
        when(claim.responseExpiresAt()).thenReturn(now.plusSeconds(50));
        when(claim.topic()).thenReturn("tc/v1/project/device/down/ota/download/response");
        when(claim.keyVersion()).thenReturn("v1");
        when(claim.nonce()).thenReturn(new byte[12]);
        when(claim.ciphertext()).thenReturn(new byte[17]);
        when(repository.authoritativeClaim(id, token)).thenReturn(Optional.of(claim));
        when(lifecycle.lockActiveForWrite(tenant, project)).thenReturn(true);
        var graph = mock(OtaCampaignRuntime.class);
        var campaign = mock(OtaCampaign.class);
        when(graph.campaign()).thenReturn(campaign);
        when(campaign.status()).thenReturn("RUNNING");
        when(runtime.lockRuntime(project, request.campaignId())).thenReturn(Optional.of(graph));
        when(runtime.read(project, request.campaignId())).thenReturn(Optional.of(graph));
        when(runtime.currentTime()).thenReturn(now);
        var route = new OtaDeviceNotificationRoute(tenant, project, device, 1, "device");
        when(routes.lockCurrent(any())).thenReturn(Optional.of(route));
        var outcome = new OtaRuntimeQualification.Outcome(OtaRuntimeQualification.Disposition.ELIGIBLE,
                "ELIGIBLE", 1L, 1L, "a".repeat(64), now);
        when(qualification.check(any(), any(), any(), any())).thenReturn(outcome);
        when(devices.credentialValid(any())).thenReturn(true);
        when(cipher.configured()).thenReturn(true);
        when(publisher.configured()).thenReturn(true);
        when(storages.orderedStream()).thenAnswer(call -> Stream.of(storage));
        when(projects.requireRoutingContext(project)).thenReturn(new ProjectService.ProjectRoutingContext(tenant, "project"));
        var mqttRoutes = mock(com.things.link.device.application.DeviceMqttDownlinkRoutePort.class);
        when(mqttRoutes.lockCurrent(tenant, project, device)).thenReturn(Optional.of(
                new com.things.link.device.application.DeviceMqttDownlinkRoute(tenant, project, device, 7, "project", "device")));
        var service = new OtaDownloadAuthorizationService(repository, runtime, lifecycle, rls, routes, devices,
                qualification, projects, storages, cipher, publisher, audit, mock(OtaExecutionOriginGuard.class), mqttRoutes, false);
        return new Fixture(service, repository, qualification, devices, cipher, publisher, audit, rls, claim, id, token);
    }

    /** 控制流夹具，只包含本测试需要观察的端口。
     * @param service 实际服务
     * @param repository 模拟持久能力
     * @param qualification 模拟当前资格
     * @param devices 当前凭据端口
     * @param cipher 密文端口
     * @param publisher 秘密传输端口
     * @param audit 审计端口
     * @param rls 范围端口
     * @param claim 固定能力
     * @param id 授权ID
     * @param token 当前租约
     */
    private record Fixture(OtaDownloadAuthorizationService service, OtaDownloadAuthorizationRepository repository,
            OtaRuntimeQualification qualification, OtaDeviceIdentityPort devices, OtaDownloadResponseCipher cipher,
            OtaDownloadResponsePublisher publisher, AuditLogService audit, TransactionLocalRlsScope rls,
            OtaDownloadAuthorizationRepository.Claim claim, UUID id, UUID token) { }
}
