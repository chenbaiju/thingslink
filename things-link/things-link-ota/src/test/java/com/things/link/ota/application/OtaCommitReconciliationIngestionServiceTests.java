package com.things.link.ota.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.things.link.device.application.OtaDeviceIdentityPort;
import com.things.link.device.application.OtaDeviceReconciliationPort;
import com.things.link.ota.domain.OtaCampaign;
import com.things.link.ota.domain.OtaCampaignRuntime;
import com.things.link.ota.domain.OtaCampaignRuntimeRepository;
import com.things.link.ota.domain.OtaCommitPermit;
import com.things.link.ota.domain.OtaConfirmationRepository;
import com.things.link.ota.domain.OtaFirmware;
import com.things.link.ota.domain.OtaFirmwareRepository;
import com.things.link.ota.domain.OtaJobExecutionOrigin;
import com.things.link.ota.domain.OtaJobProgressReceipt;
import com.things.link.ota.domain.OtaJobProgressRepository;
import com.things.link.ota.domain.OtaReconciliationQuery;
import com.things.link.ota.domain.OtaReconciliationRepository;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import com.things.link.support.audit.AuditLogService;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 已完成活动的真实合同证明只能观察，模拟仓储不替代PG认证与冷却门禁。 */
class OtaCommitReconciliationIngestionServiceTests {
    /** 完整有效目标证明不能借旧查询再次采用模型，也不能重新提交成功转移。 */
    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED", "CANCELLED"})
    void completedSucceededJobRetainsOldQueryProofWithoutModelAdoption(String terminal) throws Exception {
        var json = new OtaCanonicalJson();
        byte[] manifest = new OtaManifestCodec().canonicalize(resource("manifest-v1.json"));
        String hash = OtaTrustBundleCodec.sha256(manifest);
        var fields = new LinkedHashMap<>(json.parseObject(resource("device-report-v1.json")));
        fields.put("trustDomain", "test.example");
        fields.put("hardware", Map.of("model", "board-v1", "boardRevision", 1L));
        fields.put("thingModelVersionId", "018f0000-0000-7000-8000-000000000004");
        var source = new OtaDeviceReportCodec().decode(json.writeObject(fields)).value();
        var body = new LinkedHashMap<>(json.parseObject(resource("commit-reconciliation-report-v1.json")));
        var evidence = new LinkedHashMap<String, Object>();
        var target = json.parseObject(manifest);
        for (String field : List.of("artifactSha256", "artifactSize", "securityVersion", "thingModelVersionId",
                "thingModelSchemaDigestAlgorithm", "thingModelSchemaDigest")) evidence.put(field, target.get(field));
        evidence.put("committedSecurityVersion", target.get("securityVersion"));
        evidence.put("propertyProfile", "TC_PROPERTY_COMPOSITE_V1");
        evidence.put("trustDomain", source.trustDomain()); evidence.put("rootFingerprint", source.rootFingerprint());
        evidence.put("trustBundleVersion", source.trustBundleVersion()); evidence.put("trustBundleSha256", source.trustBundleSha256());
        evidence.put("sourceSlot", "A"); evidence.put("targetSlot", "B"); evidence.put("activeSlot", "B");
        evidence.put("verification", "PASSED"); evidence.put("bootVerified", true);
        evidence.put("selfTestPassed", true); evidence.put("watchdogHealthy", true);
        body.put("evidence", evidence); body.put("manifestSha256", hash);
        byte[] payload = json.writeObject(body);
        var report = new OtaCommitReconciliationReportCodec().decode(payload).value();
        UUID tenant = UUID.randomUUID(), project = UUID.randomUUID(), device = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID(), firmwareId = UUID.randomUUID(), receiptId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-12T01:00:00Z");
        var identity = new AuthenticatedDeviceIdentity(tenant, project, device, 1);
        var job = new OtaJobProgressRepository.Context(tenant, project, campaignId, firmwareId, report.jobId(),
                device, 1, terminal.equals("CANCELLED") ? "CANCELLED" : "SUCCEEDED", 15, 1, hash, report.authorizationId(), null);
        var queryFields = new LinkedHashMap<>(json.parseObject(resource("commit-reconciliation-query-v1.json")));
        queryFields.put("manifestSha256", hash);
        queryFields.put("expiresAt", now.minusSeconds(540).getEpochSecond());
        var queryContract = new OtaCommitReconciliationQueryCodec().decode(json.writeObject(queryFields));
        var query = new OtaReconciliationQuery(report.queryId(), tenant, project, campaignId, report.jobId(), device,
                1, 1, report.recoveryRevision(), report.authorizationId(), report.permitId(), report.queryNonce(),
                report.commitBootId(), hash, queryContract.canonical(), queryContract.sha256(), now.minusSeconds(600), now.minusSeconds(540));
        var queries = mock(OtaReconciliationRepository.class);
        var runtime = mock(OtaCampaignRuntimeRepository.class);
        var progress = mock(OtaJobProgressRepository.class);
        var confirmations = mock(OtaConfirmationRepository.class);
        var origins = mock(OtaExecutionOriginGuard.class);
        var firmwares = mock(OtaFirmwareRepository.class);
        var devices = mock(OtaDeviceReconciliationPort.class);
        var identities = mock(OtaDeviceIdentityPort.class);
        var lifecycle = mock(ProjectLifecycleAccessService.class);
        var rls = mock(TransactionLocalRlsScope.class);
        var directions = mock(OtaRollbackDirectionGuard.class);
        var graph = mock(OtaCampaignRuntime.class); var campaign = mock(OtaCampaign.class);
        var origin = mock(OtaJobExecutionOrigin.class); var first = mock(OtaJobProgressReceipt.class);
        var permit = mock(OtaCommitPermit.class); var firmware = mock(OtaFirmware.class);
        when(lifecycle.lockActiveForWrite(tenant, project)).thenReturn(true);
        when(progress.locate(report.jobId())).thenReturn(Optional.of(job));
        when(runtime.lockRuntime(project, campaignId)).thenReturn(Optional.of(graph));
        when(graph.campaign()).thenReturn(campaign); when(campaign.status()).thenReturn(terminal);
        when(campaign.canonicalManifest()).thenReturn(manifest); when(campaign.firmwareId()).thenReturn(firmwareId);
        when(runtime.currentTime()).thenReturn(now);
        when(queries.findQuery(report.queryId())).thenReturn(Optional.of(query));
        when(queries.hasSendReservation(report.queryId())).thenReturn(true);
        when(confirmations.hasSendReservation(report.permitId())).thenReturn(true);
        when(confirmations.findPermit(report.jobId(), 1)).thenReturn(Optional.of(permit));
        when(permit.id()).thenReturn(report.permitId()); when(permit.bootId()).thenReturn(report.commitBootId());
        when(progress.origin(report.jobId(), 1)).thenReturn(Optional.of(origin));
        when(origins.decode(origin)).thenReturn(source);
        when(progress.find(report.jobId(), 1, 1)).thenReturn(Optional.of(first));
        when(first.bootId()).thenReturn(UUID.randomUUID());
        when(firmwares.find(project, firmwareId, false)).thenReturn(Optional.of(firmware));
        when(firmware.deviceTypeId()).thenReturn(UUID.randomUUID());
        when(identities.credentialValid(identity)).thenReturn(true);
        when(devices.apply(any())).thenAnswer(call -> {
            OtaDeviceReconciliationPort.Command command = call.getArgument(0);
            assertThat(command.identity()).isEqualTo(identity);
            assertThat(command.adoptModel()).isFalse();
            assertThat(command.committedSecurityVersion()).isEqualTo(report.evidence().securityVersion());
            assertThat(command.artifactSha256()).isEqualTo(report.evidence().artifactSha256());
            assertThat(command.originalPermitId()).isEqualTo(report.permitId());
            return new OtaDeviceReconciliationPort.Result(OtaDeviceReconciliationPort.Decision.OBSERVED_ONLY,
                    receiptId, UUID.randomUUID(), null, now, false);
        });
        when(queries.acceptReport(eq(job), any(), eq(false), anyString(), eq(receiptId))).thenReturn(true);
        var service = new OtaCommitReconciliationIngestionService(queries, runtime, progress, confirmations,
                origins, firmwares, devices, identities, lifecycle, rls, mock(AuditLogService.class), directions, mock(OtaInstallStopObservationGuard.class));
        assertThat(service.accept(identity, payload, now)).isEqualTo(OtaCommitReconciliationIngestionService.Outcome.OBSERVED);
        verify(devices).apply(any());
        verify(queries).acceptReport(eq(job), any(), eq(false), eq("COMMITTED_RECONCILIATION_OBSERVATION"), eq(receiptId));
        verify(identities).credentialValid(identity);
        verify(rls).establish(tenant, project);
        verifyNoInteractions(directions);
    }

    /** 读取独立公开黄金合同，不包含生产私钥或模拟硬件证明。 */
    private static byte[] resource(String name) throws Exception {
        try (var input = OtaCommitReconciliationIngestionServiceTests.class.getResourceAsStream("/ota/" + name)) {
            if (input == null) throw new IllegalStateException("公开测试合同缺失");
            return input.readAllBytes();
        }
    }
}
