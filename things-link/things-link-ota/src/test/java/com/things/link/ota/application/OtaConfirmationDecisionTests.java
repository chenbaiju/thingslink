package com.things.link.ota.application;

import com.things.link.device.application.OtaDeviceCommitPort;
import com.things.link.device.application.OtaDeviceIdentityPort;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 模型采用前后跨过新鲜时间边界时，设备与OTA必须共用同一采用裁决。 */
class OtaConfirmationDecisionTests {
    /** 旧实现第二次fresh会把已采用改成恢复，或把只观察改成成功；两方向均需拒绝翻转。 */
    @ParameterizedTest
    @CsvSource({"false,false,false", "true,false,false", "false,true,false", "false,false,true"})
    void retainsSingleDecisionAcrossFreshnessBoundary(boolean futureBoundary, boolean quarantined, boolean cancelled) throws Exception {
        Instant decisionAt=Instant.parse("2026-09-12T00:10:00Z");
        var clock=new AtomicReference<>(decisionAt);
        Instant brokerAt=futureBoundary?decisionAt.plusSeconds(30).plusMillis(1):decisionAt.minusSeconds(300).plusMillis(1);
        UUID tenant=UUID.randomUUID(),project=UUID.randomUUID(),device=UUID.randomUUID(),jobId=UUID.randomUUID();
        UUID campaignId=UUID.randomUUID(),firmwareId=UUID.randomUUID(),authorization=UUID.randomUUID(),permitId=UUID.randomUUID();
        UUID boot=UUID.randomUUID(),oldBoot=UUID.randomUUID(),deviceReceipt=UUID.randomUUID();
        var json=new OtaCanonicalJson();byte[] manifest=new OtaManifestCodec().canonicalize(resource("manifest-v1.json"));
        var fields=new LinkedHashMap<>(json.parseObject(resource("device-report-v1.json")));
        fields.put("trustDomain","test.example");fields.put("hardware",Map.of("model","board-v1","boardRevision",1L));
        fields.put("thingModelVersionId","018f0000-0000-7000-8000-000000000004");
        var source=new OtaDeviceReportCodec().decode(json.writeObject(fields)).value();
        String hash=OtaTrustBundleCodec.sha256(manifest);
        var job=new OtaJobProgressRepository.Context(tenant,project,campaignId,firmwareId,jobId,device,1,cancelled?"CANCELLED":"CONFIRMING",9,1,hash,authorization,decisionAt.plusSeconds(600));
        var identity=new AuthenticatedDeviceIdentity(tenant,project,device,1);
        var confirmations=mock(OtaConfirmationRepository.class);var progress=mock(OtaJobProgressRepository.class);
        var runtime=mock(OtaCampaignRuntimeRepository.class);var origins=mock(OtaExecutionOriginGuard.class);
        var identities=mock(OtaDeviceIdentityPort.class);var commits=mock(OtaDeviceCommitPort.class);
        var firmwares=mock(OtaFirmwareRepository.class);var lifecycle=mock(ProjectLifecycleAccessService.class);
        var graph=mock(OtaCampaignRuntime.class);var campaign=mock(OtaCampaign.class);var origin=mock(OtaJobExecutionOrigin.class);
        var permit=mock(OtaCommitPermit.class);var first=mock(OtaJobProgressReceipt.class);var firmware=mock(OtaFirmware.class);
        when(lifecycle.lockActiveForWrite(tenant,project)).thenReturn(true);
        when(progress.locate(jobId)).thenReturn(Optional.of(job));when(runtime.lockRuntime(project,campaignId)).thenReturn(Optional.of(graph));
        when(graph.campaign()).thenReturn(campaign);when(campaign.status()).thenReturn(cancelled?"CANCELLED":"RUNNING");
        when(campaign.firmwareId()).thenReturn(firmwareId);when(campaign.canonicalManifest()).thenReturn(manifest);
        when(confirmations.findCommitReceipt(eq(device),any())).thenReturn(Optional.empty());
        when(confirmations.findPermit(jobId,1)).thenReturn(Optional.of(permit));when(permit.id()).thenReturn(permitId);
        when(permit.bootId()).thenReturn(boot);when(permit.createdAt()).thenReturn(decisionAt.minusSeconds(600));
        when(permit.deadlineAt()).thenReturn(decisionAt.plusSeconds(600));when(confirmations.hasSendReservation(permitId)).thenReturn(true);
        when(progress.origin(jobId,1)).thenReturn(Optional.of(origin));when(origins.decode(origin)).thenReturn(source);
        when(confirmations.candidateBoot(jobId,1)).thenReturn(Optional.of(boot));when(progress.find(jobId,1,1)).thenReturn(Optional.of(first));
        when(first.bootId()).thenReturn(oldBoot);when(firmwares.find(project,firmwareId,false)).thenReturn(Optional.of(firmware));
        when(firmware.deviceTypeId()).thenReturn(UUID.randomUUID());when(runtime.currentTime()).thenAnswer(invocation->clock.get());
        when(identities.credentialValid(identity)).thenReturn(true);
        when(commits.apply(any())).thenAnswer(invocation->{
            OtaDeviceCommitPort.Command command=invocation.getArgument(0);
            assertThat(command.adoptModel()).isEqualTo(!futureBoundary && !quarantined && !cancelled);
            clock.set(decisionAt.plusMillis(2));
            return new OtaDeviceCommitPort.Result(command.adoptModel()?OtaDeviceCommitPort.Decision.CHANGED:OtaDeviceCommitPort.Decision.OBSERVED_ONLY,
                    deviceReceipt,command.adoptModel()?UUID.randomUUID():null,decisionAt,
                    new OtaDeviceCommitPort.SecurityFloor(command.committedSecurityVersion(),command.artifactSha256(),deviceReceipt),false);
        });
        when(confirmations.acceptCommit(eq(job),any(),org.mockito.ArgumentMatchers.nullable(String.class),anyString(),eq(deviceReceipt))).thenReturn(true);
        var operations = mock(com.things.link.ota.domain.OtaRollbackRepository.class);
        when(operations.deviceConflicted(device)).thenReturn(quarantined);
        var directions = new OtaRollbackDirectionGuard(operations);
        var service=new OtaConfirmationIngestionService(confirmations,progress,runtime,origins,identities,commits,firmwares,
                mock(OtaRuntimeQualification.class),lifecycle,mock(TransactionLocalRlsScope.class),mock(AuditLogService.class),mock(OtaKnownSecurityFloor.class),directions,mock(OtaInstallStopObservationGuard.class));
        byte[] payload=payload(json,manifest,source,jobId,authorization,permitId,boot,hash);
        var outcome=service.acceptCommit(identity,payload,brokerAt);
        String expected=cancelled?null:futureBoundary||quarantined?"RECOVERY_REQUIRED":"SUCCEEDED";
        assertThat(outcome.name()).isEqualTo(cancelled?"OBSERVED":expected);
        verify(confirmations).acceptCommit(eq(job),any(),eq(expected),anyString(),eq(deviceReceipt));
        verify(identities).credentialValid(identity);
        assertThat(OtaDeviceReportIngestionService.fresh(brokerAt,clock.get())).isEqualTo(futureBoundary);
    }

    /** 使用真实词法合同构造目标证据，避免时间反例被不相关字段失败遮盖。 */
    private static byte[] payload(OtaCanonicalJson json,byte[] manifest,OtaDeviceReportCodec.Report source,
            UUID job,UUID authorization,UUID permit,UUID boot,String hash){
        var m=json.parseObject(manifest);var e=new LinkedHashMap<String,Object>();
        for(String name:List.of("artifactSha256","artifactSize","securityVersion","thingModelVersionId","thingModelSchemaDigestAlgorithm","thingModelSchemaDigest"))e.put(name,m.get(name));
        e.put("committedSecurityVersion",m.get("securityVersion"));e.put("propertyProfile","TC_PROPERTY_COMPOSITE_V1");
        e.put("trustDomain",source.trustDomain());e.put("rootFingerprint",source.rootFingerprint());
        e.put("trustBundleVersion",source.trustBundleVersion());e.put("trustBundleSha256",source.trustBundleSha256());
        e.put("sourceSlot","A");e.put("targetSlot","B");e.put("activeSlot","B");e.put("verification","PASSED");
        e.put("bootVerified",true);e.put("selfTestPassed",true);e.put("watchdogHealthy",true);
        return json.writeObject(Map.of("contractVersion","tc-ota-commit-receipt/v1","jobId",job.toString(),"attemptNo",1L,
                "authorizationId",authorization.toString(),"manifestSha256",hash,"receiptId",UUID.randomUUID().toString(),
                "permitId",permit.toString(),"bootId",boot.toString(),"evidence",e));
    }
    /** 公开黄金合同只用于软件单测，不包含私钥或实机证明。 */
    private static byte[] resource(String name) throws Exception {
        try(var input=OtaConfirmationDecisionTests.class.getResourceAsStream("/ota/"+name)){
            if(input==null)throw new IllegalStateException("公开测试向量缺失");return input.readAllBytes();
        }
    }
}
