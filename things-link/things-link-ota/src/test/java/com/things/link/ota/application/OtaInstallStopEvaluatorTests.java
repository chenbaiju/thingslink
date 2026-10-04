package com.things.link.ota.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.things.link.ota.domain.OtaInstallStopOperation;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 独立受控基线与完整日志决定，不把设备声明当作实机资格。 */
class OtaInstallStopEvaluatorTests {
    /** 真实词法和规范编码实现。 */ private final OtaCanonicalJson json = new OtaCanonicalJson();
    /** 正常无授权停止围栏覆盖整个尝试，当前重启不抹掉原耐久接纳。 */
    @Test void stoppedAttemptNeedsNoDownloadAndSurvivesBootChange() throws Exception {
        var operation = operation(); var report = report(body());
        assertThat(operation.authorizationIds()).isEmpty();
        assertThat(report.bootId()).isNotEqualTo(report.evidence().stopOperations().getFirst().acceptedBootId());
        assertThat(OtaInstallStopEvaluator.boundEvidence(operation, report.evidence())).isTrue();
        assertThat(OtaInstallStopEvaluator.accepted(operation, report.evidence())).isTrue();
        assertThat(OtaInstallStopEvaluator.stopped(operation, report)).isTrue();
    }
    /** 已接纳日志与当前静止是两个事实，不能因日志存在就提前取消作业。 */
    @Test void durableAcceptanceDoesNotImplyQuiescenceOrCurrentSuccess() throws Exception {
        var body = body(); evidence(body).put("writeState", "WRITING");
        var report = report(body);
        assertThat(OtaInstallStopEvaluator.accepted(operation(), report.evidence())).isTrue();
        assertThat(OtaInstallStopEvaluator.stopped(operation(), report)).isFalse();
        body.put("status", "UNKNOWN"); evidence(body).put("writeState", "QUIESCENT");
        assertThat(OtaInstallStopEvaluator.accepted(operation(), report(body).evidence())).isTrue();
        assertThat(OtaInstallStopEvaluator.stopped(operation(), report(body))).isFalse();
    }
    /** 两方向都能被识别但绝不能安全取消，空旧授权列表不覆盖后来真实安装事实。 */
    @Test void oppositeDurableDirectionsBlockCancellationEvenWithOriginallyEmptyAuthorizations() throws Exception {
        UUID authorization = UUID.randomUUID();
        var body = body();
        evidence(body).put("installOperations", List.of(installation(authorization, 1)));
        var report = report(body); var operation = operation();
        assertThat(operation.authorizationIds()).isEmpty();
        assertThat(OtaInstallStopEvaluator.accepted(operation, report.evidence())).isTrue();
        assertThat(OtaInstallStopEvaluator.installed(operation, report.evidence(), authorization)).isTrue();
        assertThat(OtaInstallStopEvaluator.stopped(operation, report)).isFalse();
    }
    /** 自报授权不是平台封存授权，但仍不能忽略日志并假装安全取消。 */
    @Test void unknownAuthorizationCannotBecomeTrustedInstallWinner() throws Exception {
        var body = body(); evidence(body).put("installOperations", List.of(installation(UUID.randomUUID(), 1)));
        var report = report(body);
        assertThat(OtaInstallStopEvaluator.installed(operation(), report.evidence(), null)).isFalse();
        assertThat(OtaInstallStopEvaluator.installed(operation(), report.evidence(), UUID.randomUUID())).isFalse();
        assertThat(OtaInstallStopEvaluator.stopped(operation(), report)).isFalse();
    }
    /** 相同Profile不足以弥补硬件、范围、基线或原操作摘要不一致。 */
    @Test void hardwareBaselineAndOperationMustMatchOriginalControlledFacts() throws Exception {
        for (String boundary : List.of("hardware", "baseline", "bootloader", "operation")) {
            var body = body(); var evidence = evidence(body);
            switch (boundary) {
                case "hardware" -> evidence.put("hardware", Map.of("model", "other-board", "boardRevision", 1L));
                case "baseline" -> evidence.put("stopBaselineSha256", "0".repeat(64));
                case "bootloader" -> evidence.put("bootloaderVersion", "1.10.0");
                default -> {
                    var entry = new LinkedHashMap<>(OtaTrustBundleCodec.object(((List<?>) evidence.get("stopOperations")).getFirst()));
                    entry.put("operationSha256", "0".repeat(64)); evidence.put("stopOperations", List.of(entry));
                }
            }
            var report = report(body);
            assertThat(OtaInstallStopEvaluator.accepted(operation(), report.evidence())).as(boundary).isFalse();
            assertThat(OtaInstallStopEvaluator.stopped(operation(), report)).as(boundary).isFalse();
        }
    }
    /** 解析层保留超前修订的矛盾证据，安全判断必须拒绝该日志的接纳资格。 */
    @Test void acceptanceRevisionCannotExceedProtectedJournal() throws Exception {
        var body = body(); var evidence = evidence(body);
        var entry = new LinkedHashMap<>(OtaTrustBundleCodec.object(((List<?>) evidence.get("stopOperations")).getFirst()));
        entry.put("acceptedRevision", 2L); evidence.put("stopOperations", List.of(entry));
        UUID authorization = UUID.randomUUID(); evidence.put("installOperations", List.of(installation(authorization, 2)));
        var report = report(body);
        assertThat(report.evidence().journalRevision()).isEqualTo(1);
        assertThat(OtaInstallStopEvaluator.accepted(operation(), report.evidence())).isFalse();
        assertThat(OtaInstallStopEvaluator.installed(operation(), report.evidence(), authorization)).isFalse();
    }
    /** 持久字节损坏不能冒充设备坏消息进入永久死信，必须保留依赖失败分类。 */
    @Test void persistedCorruptionCannotBeClassifiedAsDeviceProtocolRejection() throws Exception {
        var original=operation();
        OtaInstallStopDeliveryService.validateOperation(original);
        for(boolean brokenCommand:List.of(true,false)) {
            var broken=new OtaInstallStopOperation(original.id(),original.tenantId(),original.projectId(),
                    original.campaignId(),original.jobId(),original.deviceId(),original.attemptNo(),original.credentialVersion(),
                    original.manifestSha256(),original.originHash(),original.cancellationRevision(),original.jobRevision(),
                    brokenCommand?original.parentBaseline():new byte[]{0},original.stopBaseline(),original.authorizationIds(),
                    brokenCommand?new byte[]{0}:original.canonical(),original.payloadHash(),original.createdAt(),original.deadlineAt());
            assertThatThrownBy(()->OtaInstallStopDeliveryService.validateOperation(broken))
                    .isInstanceOf(IllegalStateException.class).hasCauseInstanceOf(IllegalArgumentException.class);
        }
    }
    /** 精确制造快照及原命令来自公开固定字节。 */
    private OtaInstallStopOperation operation() throws Exception {
        var operation = new OtaInstallStopOperationCodec().decode(resource("install-stop-operation-v1.json"));
        var baseline = new OtaInstallStopBaselineCodec().decode(resource("install-stop-baseline-v1.json"));
        var command = operation.value(); var b = baseline.value();
        return new OtaInstallStopOperation(command.operationId(), b.tenantId(), b.projectId(), command.campaignId(),
                command.jobId(), UUID.randomUUID(), command.attemptNo(), 1, command.manifestSha256(), "0".repeat(64),
                command.cancellationRevision(), 1, resource("baseline/type-baseline-v1.json"), baseline.canonical(),
                command.authorizationIds(), operation.canonical(), operation.sha256(), Instant.EPOCH,
                Instant.ofEpochSecond(command.expiresAt()));
    }
    /** 将协议向量硬件实例化为受控父基线，其他原操作字节保持固定。 */
    private Map<String, Object> body() throws Exception {
        var result = new LinkedHashMap<>(json.parseObject(resource("install-stop-operation-report-v1.json")));
        var parent = new OtaTypeBaselineCodec().decode(resource("baseline/type-baseline-v1.json")).value();
        evidence(result).put("hardware", Map.of("model", parent.hardware().model(), "boardRevision", 1L));
        return result;
    }
    /** 修改嵌套证据时先复制再显式写回。 */
    private static Map<String, Object> evidence(Map<String, Object> body) {
        var result = new LinkedHashMap<>(OtaTrustBundleCodec.object(body.get("evidence")));
        body.put("evidence", result); return result;
    }
    /** 完整安装日志词法，不以bool模拟已安装事实。 */
    private static Map<String, Object> installation(UUID authorization, long revision) {
        return Map.of("authorizationId", authorization.toString(), "acceptedBootId", UUID.randomUUID().toString(),
                "acceptedRevision", revision, "state", "INSTALL_ACCEPTED");
    }
    /** 每个反例都实际经过生产闭集解析。 */
    private OtaInstallStopOperationReportCodec.Report report(Map<String, Object> body) {
        return new OtaInstallStopOperationReportCodec().decode(json.writeObject(body)).value();
    }
    /** 无秘密公开向量。 */
    private static byte[] resource(String name) throws Exception {
        try (var input = OtaInstallStopEvaluatorTests.class.getResourceAsStream("/ota/" + name)) {
            if (input == null) throw new IllegalStateException("公开向量缺失");
            return input.readAllBytes();
        }
    }
}
