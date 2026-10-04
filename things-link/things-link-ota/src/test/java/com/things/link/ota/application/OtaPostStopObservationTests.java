package com.things.link.ota.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.things.link.device.application.OtaDeviceCommitPort;
import com.things.link.device.application.OtaDeviceIdentity;
import com.things.link.device.application.OtaDeviceIdentityPort;
import com.things.link.ota.domain.OtaCampaign;
import com.things.link.ota.domain.OtaCampaignRuntime;
import com.things.link.ota.domain.OtaCampaignRuntimeRepository;
import com.things.link.ota.domain.OtaConfirmationRepository;
import com.things.link.ota.domain.OtaFirmwareRepository;
import com.things.link.ota.domain.OtaJobExecutionOrigin;
import com.things.link.ota.domain.OtaJobProgressReceipt;
import com.things.link.ota.domain.OtaJobProgressRepository;
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
import org.junit.jupiter.params.provider.CsvSource;

/** 原停止终态只接纳观察，错元组不能形成安装已发生的安全证明。 */
class OtaPostStopObservationTests {
    /** 原始规范处理。 */ private final OtaCanonicalJson json = new OtaCanonicalJson();
    /** 真实原身份。 */ private final AuthenticatedDeviceIdentity identity = new AuthenticatedDeviceIdentity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1);
    /** 原固定作业。 */ private final UUID jobId = UUID.randomUUID();
    /** 原固定活动。 */ private final UUID campaignId = UUID.randomUUID();
    /** 原授权。 */ private final UUID authorization = UUID.randomUUID();
    /** 当前接收时间。 */ private final Instant now = Instant.parse("2026-09-12T01:00:00Z");
    /** 进度事实仓储。 */ private final OtaJobProgressRepository progress = mock(OtaJobProgressRepository.class);
    /** 运行事务图。 */ private final OtaCampaignRuntimeRepository runtime = mock(OtaCampaignRuntimeRepository.class);
    /** 原身份最终复验。 */ private final OtaDeviceIdentityPort identities = mock(OtaDeviceIdentityPort.class);
    /** 不可变源恢复。 */ private final OtaExecutionOriginGuard origins = mock(OtaExecutionOriginGuard.class);
    /** 活跃许可。 */ private final ProjectLifecycleAccessService lifecycle = mock(ProjectLifecycleAccessService.class);
    /** 原耐久停止围栏。 */ private final OtaInstallStopObservationGuard stops = mock(OtaInstallStopObservationGuard.class);
    /** 原健康事实。 */ private final OtaConfirmationRepository confirmations = mock(OtaConfirmationRepository.class);
    /** 仅测试仓储模拟对象，不替代真实设备能力。 */ private OtaJobProgressRepository.Context job;
    /** 完整可证明的目标字段。 */ private Map<String, Object> evidence;
    /** 固定清单摘要。 */ private String hash;
    /** 新候选启动，原VERIFYING来自另一启动。 */ private final UUID boot = UUID.randomUUID();

    /** 完整安装阶段证明与错目标分别保留合格/不合格观察，next始终为空。 */
    @ParameterizedTest @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void lateProgressChecksFullTupleWithoutReopening(boolean wrongTuple, boolean acceptedBeforeQuiescence) throws Exception {
        setup(acceptedBeforeQuiescence);
        var first = progress.find(jobId, 1, 1).orElseThrow();
        when(first.bootId()).thenReturn(boot);
        evidence.put("activeSlot", "A"); evidence.put("bootVerified", false);
        evidence.put("selfTestPassed", false); evidence.put("watchdogHealthy", false);
        if (wrongTuple) evidence.put("artifactSha256", "0".repeat(64));
        var body = new LinkedHashMap<String, Object>();
        body.put("contractVersion", "tc-ota-job-progress/v1"); body.put("jobId", jobId.toString());
        body.put("attemptNo", 1L); body.put("progressSeq", 2L); body.put("authorizationId", authorization.toString());
        body.put("manifestSha256", hash); body.put("stage", "INSTALLING"); body.put("bootId", boot.toString()); body.put("evidence", evidence);
        when(progress.accept(eq(job), any(), isNull(), anyString())).thenReturn(true);
        var service = new OtaJobProgressIngestionService(progress, runtime, identities, origins, lifecycle,
                mock(TransactionLocalRlsScope.class), mock(AuditLogService.class), confirmations, stops);
        assertThat(service.accept(identity, json.writeObject(body), now)).isEqualTo(OtaJobProgressIngestionService.Outcome.OBSERVED);
        verify(progress).accept(eq(job), any(), isNull(), eq(wrongTuple ? "MANIFEST_TUPLE_MISMATCH" : "POST_STOP_PROGRESS_PROOF"));
        verify(identities).credentialValid(identity);
    }
    /** 首健康尚未登记candidateBoot，仍从原VERIFYING启动和全部目标证明判断；错误摘要不获证明。 */
    @ParameterizedTest @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void lateHealthDoesNotInventCandidateBootOrPermit(boolean wrongTuple, boolean acceptedBeforeQuiescence) throws Exception {
        setup(acceptedBeforeQuiescence); if (wrongTuple) evidence.put("artifactSha256", "0".repeat(64));
        evidence.put("uptimeMillis", 10000L); evidence.put("healthyForMillis", 10000L);
        var body = new LinkedHashMap<String, Object>();
        body.put("contractVersion", "tc-ota-health/v1"); body.put("jobId", jobId.toString()); body.put("attemptNo", 1L);
        body.put("authorizationId", authorization.toString()); body.put("manifestSha256", hash);
        body.put("healthSeq", 1L); body.put("bootId", boot.toString()); body.put("evidence", evidence);
        when(confirmations.acceptHealth(eq(job), any(), isNull(), anyString(), isNull())).thenReturn(true);
        var service = new OtaConfirmationIngestionService(confirmations, progress, runtime, origins, identities,
                mock(OtaDeviceCommitPort.class), mock(OtaFirmwareRepository.class), mock(OtaRuntimeQualification.class),
                lifecycle, mock(TransactionLocalRlsScope.class), mock(AuditLogService.class), mock(OtaKnownSecurityFloor.class),
                mock(OtaRollbackDirectionGuard.class), stops);
        assertThat(service.acceptHealth(identity, json.writeObject(body), now)).isEqualTo(OtaConfirmationIngestionService.Outcome.OBSERVED);
        verify(confirmations).acceptHealth(eq(job), any(), isNull(), eq(wrongTuple ? "MANIFEST_TUPLE_MISMATCH" : "POST_STOP_HEALTH_PROOF"), isNull());
    }
    /** 全部证明由公开真实合同组合，资格结果没有被直接mock为true。 */
    private void setup(boolean accepted) throws Exception {
        byte[] manifest = new OtaManifestCodec().canonicalize(resource("manifest-v1.json"));
        hash = OtaTrustBundleCodec.sha256(manifest);
        var sourceFields = new LinkedHashMap<>(json.parseObject(resource("device-report-v1.json")));
        sourceFields.put("trustDomain", "test.example"); sourceFields.put("hardware", Map.of("model", "board-v1", "boardRevision", 1L));
        sourceFields.put("thingModelVersionId", "018f0000-0000-7000-8000-000000000004");
        var source = new OtaDeviceReportCodec().decode(json.writeObject(sourceFields)).value();
        var target = json.parseObject(manifest); evidence = new LinkedHashMap<>();
        for (String name : List.of("artifactSha256", "artifactSize", "securityVersion", "thingModelVersionId",
                "thingModelSchemaDigestAlgorithm", "thingModelSchemaDigest")) evidence.put(name, target.get(name));
        evidence.put("committedSecurityVersion", source.committedSecurityVersion()); evidence.put("propertyProfile", source.propertyProfile());
        evidence.put("trustDomain", source.trustDomain()); evidence.put("rootFingerprint", source.rootFingerprint());
        evidence.put("trustBundleVersion", source.trustBundleVersion()); evidence.put("trustBundleSha256", source.trustBundleSha256());
        evidence.put("sourceSlot", "A"); evidence.put("targetSlot", "B"); evidence.put("activeSlot", "B");
        evidence.put("verification", "PASSED"); evidence.put("bootVerified", true); evidence.put("selfTestPassed", true); evidence.put("watchdogHealthy", true);
        job = new OtaJobProgressRepository.Context(identity.tenantId(), identity.projectId(), campaignId, UUID.randomUUID(),
                jobId, identity.deviceId(), 1, accepted ? "VERIFYING" : "CANCELLED", 9, 1, hash, authorization, null);
        var graph = mock(OtaCampaignRuntime.class); var campaign = mock(OtaCampaign.class);
        var origin = mock(OtaJobExecutionOrigin.class); var first = mock(OtaJobProgressReceipt.class);
        when(progress.locate(jobId)).thenReturn(Optional.of(job));
        when(runtime.lockRuntime(identity.projectId(), campaignId)).thenReturn(Optional.of(graph));
        when(graph.campaign()).thenReturn(campaign); when(campaign.status()).thenReturn(accepted ? "CANCELLING" : "CANCELLED");
        when(stops.onlyObserve(jobId, 1)).thenReturn(accepted);
        when(campaign.canonicalManifest()).thenReturn(manifest); when(runtime.currentTime()).thenReturn(now);
        when(lifecycle.lockActiveForWrite(identity.tenantId(), identity.projectId())).thenReturn(true);
        when(identities.lockCurrent(identity)).thenReturn(Optional.of(mock(OtaDeviceIdentity.class)));
        when(identities.credentialValid(identity)).thenReturn(true);
        when(progress.origin(jobId, 1)).thenReturn(Optional.of(origin)); when(origins.decode(origin)).thenReturn(source);
        when(progress.find(jobId, 1, 1)).thenReturn(Optional.of(first)); when(first.bootId()).thenReturn(UUID.randomUUID());
    }
    /** 只读公开向量。 */
    private static byte[] resource(String name) throws Exception {
        try (var input = OtaPostStopObservationTests.class.getResourceAsStream("/ota/" + name)) {
            if (input == null) throw new IllegalStateException("公开向量缺失"); return input.readAllBytes();
        }
    }
}
