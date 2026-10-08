package com.things.link.assistant.application;

import com.things.link.assistant.domain.AnalysisCall;
import com.things.link.assistant.domain.AnalysisContent;
import com.things.link.assistant.domain.AnalysisUsage;
import com.things.link.assistant.infrastructure.transport.InternalAnalysisRequestEncoder;
import com.things.link.assistant.infrastructure.transport.ModelRequestFingerprint;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/** 测试专用无网络端口；公开平台流程和最终确权照常执行，任何结果仅为合成结构资格。 */
public final class SyntheticReviewedTransport implements AnalysisTransport {
    /** 公开受控身份范围；来源是根事先绑定的新库夹具，不由分析请求扩大。 */
    public record Target(UUID tenantId, UUID projectId, Map<String, UUID> actors,
                         Set<UUID> deviceIds, Set<UUID> unknownDeviceIds) {
        public Target {
            if (tenantId == null || projectId == null || actors == null || deviceIds == null || unknownDeviceIds == null
                    || !actors.keySet().equals(Set.of("OWNER", "ADMIN", "OPERATOR", "VIEWER"))
                    || actors.values().stream().anyMatch(value -> value == null)
                    || Set.copyOf(actors.values()).size() != 4 || deviceIds.isEmpty() || deviceIds.size() > 3
                    || !deviceIds.containsAll(unknownDeviceIds)) throw new IllegalArgumentException("INVALID_SYNTHETIC_SCOPE");
            actors = Map.copyOf(actors); deviceIds = Set.copyOf(deviceIds); unknownDeviceIds = Set.copyOf(unknownDeviceIds);
        }
        boolean accepts(AnalysisCall call) {
            return tenantId.equals(call.tenantId()) && projectId.equals(call.projectId())
                    && deviceIds.contains(call.deviceId()) && actors.values().contains(call.createdBy())
                    && !actors.get("VIEWER").equals(call.createdBy());
        }
    }

    private final FrozenSyntheticReview frozen;
    private final List<Target> targets;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 绑定启动时固定资格和公开受控范围，不能逐请求扩展。 */
    public SyntheticReviewedTransport(FrozenSyntheticReview frozen, List<Target> targets) {
        if (frozen == null || targets == null || targets.isEmpty() || targets.size() > 2
                || targets.stream().map(Target::projectId).distinct().count() != targets.size())
            throw new IllegalArgumentException("INVALID_SYNTHETIC_SCOPE");
        this.frozen = frozen; this.targets = List.copyOf(targets);
    }
    /** @return 测试端口已装配；到期仍由真实准入检查拒绝 */
    @Override public boolean ready() { return true; }
    /** @return 不续期的原资格，到期由真实状态读取和签发器分别拒绝 */
    @Override public Optional<AnalysisReleaseReview.Approval> review() { return Optional.of(frozen.approval()); }

    /** 原调用及实际白名单输入独立建期望，回执不能自证摘要或配置版本；任何路径清除交付的凭据。 */
    @Override public Receipt execute(AnalysisCall call, PreparedModelEvidence.Input input, byte[] credential) {
        byte[] envelopeBytes = null, evidenceBytes = null;
        try {
            if (call == null || input == null || !frozen.approval().isCurrent()) throw invalid();
            Target target = targets.stream().filter(value -> value.accepts(call)).findFirst().orElseThrow(SyntheticReviewedTransport::invalid);
            // 不查看、复制、比较或输出凭据；只允许根新库合成配置通过真实cipher交付。
            if (target.unknownDeviceIds().contains(call.deviceId()))
                throw new IllegalStateException("SYNTHETIC_TRANSPORT_UNKNOWN");
            envelopeBytes = InternalAnalysisRequestEncoder.encode(call, input);
            var envelope = JSON.readTree(envelopeBytes);
            String inputDigest = envelope.get("inputSha256").asString();
            evidenceBytes = Base64.getDecoder().decode(envelope.get("inputBase64").asString());
            String requestDigest = ModelRequestFingerprint.fingerprint(evidenceBytes);
            var binding = new AnalysisResultIssuer.Binding(inputDigest, requestDigest, frozen.counterSha256(),
                    frozen.candidateSha256(), input.evidenceIds());
            var issuer = AnalysisResultIssuer.bind(call, binding, frozen.approval());
            var content = new AnalysisContent("synthetic-reviewed-summary <b>仅合成展示</b>", List.of(
                    new AnalysisContent.Finding(AnalysisContent.Kind.FACT, "synthetic-fact-not-real-device-quality", List.of("e-device")),
                    new AnalysisContent.Finding(AnalysisContent.Kind.HYPOTHESIS, "synthetic-hypothesis", List.of("e-alarm")),
                    new AnalysisContent.Finding(AnalysisContent.Kind.RECOMMENDATION, "synthetic-recommendation", List.of("e-device"))),
                    List.of("SYNTHETIC_REVIEW_ONLY_NO_PROVIDER_NO_MODEL_QUALITY"));
            var usage = new AnalysisUsage(10, 2, 12, 0, 10);
            // 此封闭合成回执来自测试端口；期望已在此前用实际请求冻结，测试不认领计数真实性。
            var receipt = new AnalysisResultIssuer.Receipt(call.id(), call.configurationRevision(), inputDigest,
                    requestDigest, frozen.counterSha256(), frozen.candidateSha256(), frozen.approval().reviewSha256(),
                    "REVIEWED_EXECUTION", "VALIDATED", frozen.providerFingerprintSha256(), content, usage);
            return new Receipt.Releasable(issuer.issue(receipt));
        } finally {
            if (credential != null) Arrays.fill(credential, (byte) 0);
            if (envelopeBytes != null) Arrays.fill(envelopeBytes, (byte) 0);
            if (evidenceBytes != null) Arrays.fill(evidenceBytes, (byte) 0);
        }
    }
    private static IllegalStateException invalid() { return new IllegalStateException("SYNTHETIC_SCOPE_OR_REVIEW_REJECTED"); }
    @Override public String toString() { return "无网络合成分析端口[内容已隐藏]"; }
}
