package com.things.link.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.things.link.assistant.domain.AnalysisCall;
import com.things.link.assistant.domain.AnalysisContent;
import com.things.link.assistant.domain.AnalysisUsage;
import com.things.link.assistant.domain.ModelCredential;
import com.things.link.assistant.infrastructure.transport.InternalAnalysisRequestEncoder;
import com.things.link.assistant.infrastructure.transport.ModelRequestFingerprint;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 无网络、无数据库的合成夹具资格验证，不认领真实供应商或模型质量。 */
class SyntheticReviewedTransportTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID TENANT = UUID.randomUUID(), PROJECT = UUID.randomUUID();
    private static final UUID DEVICE = UUID.randomUUID(), UNKNOWN = UUID.randomUUID();
    private static final Map<String, UUID> ACTORS = Map.of("OWNER", UUID.randomUUID(),
            "ADMIN", UUID.randomUUID(), "OPERATOR", UUID.randomUUID(), "VIEWER", UUID.randomUUID());

    @Test
    void fixedReviewHasOriginalExpiryAndNeverChangesPendingProductionResource() throws Exception {
        byte[] before = resource();
        Instant start = Instant.parse("2026-10-06T12:00:00.987654321Z");
        var frozen = FrozenSyntheticReview.createOnce(start);
        var transport = transport(frozen);
        assertThat(frozen.expiresAt()).isEqualTo(Instant.parse("2026-10-06T13:00:00Z"));
        assertThat(transport.review().orElseThrow()).isSameAs(frozen.approval());
        assertThat(transport.review().orElseThrow()).isSameAs(frozen.approval());
        assertThat(frozen.approval().validAt(frozen.expiresAt().minusNanos(1))).isTrue();
        assertThat(frozen.approval().validAt(frozen.expiresAt())).isFalse();
        assertThat(frozen.approval().validAt(start.minusSeconds(6))).isFalse();
        assertThat(JSON.readTree(before).path("decision").asString()).isEqualTo("PENDING");
        assertThat(resource()).isEqualTo(before);
        assertThat(AnalysisReleaseReview.forTransport(frozen.candidateSha256(), frozen.counterSha256())).isEmpty();
        assertThat(frozen.qualification()).isEqualTo("SYNTHETIC_REVIEWED_INTERACTION");
    }

    @Test
    void allThreePaidRolesUseRealIssuerAndPermitCannotBeConsumedTwice() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var transport = transport(FrozenSyntheticReview.createOnce(now));
        for (String role : List.of("OWNER", "ADMIN", "OPERATOR")) {
            var call = call(now, TENANT, PROJECT, DEVICE, ACTORS.get(role), AnalysisCall.Status.DISPATCHED);
            byte[] credential = "synthetic-only-credential".getBytes(StandardCharsets.UTF_8);
            var receipt = (AnalysisTransport.Receipt.Releasable) transport.execute(call, input(now), credential);
            assertThat(credential).containsOnly((byte) 0);
            var permit = receipt.permit();
            var context = context(call);
            permit.consume(context, now);
            permit.verifyReturn(context, now);
            assertThat(permit.content().summary()).contains("<b>仅合成展示</b>");
            assertThat(permit.content().findings()).extracting(AnalysisContent.Finding::kind)
                    .containsExactly(AnalysisContent.Kind.FACT, AnalysisContent.Kind.HYPOTHESIS,
                            AnalysisContent.Kind.RECOMMENDATION);
            assertThat(permit.content().limitations()).containsExactly("SYNTHETIC_REVIEW_ONLY_NO_PROVIDER_NO_MODEL_QUALITY");
            assertThat(permit.usage()).isEqualTo(new AnalysisUsage(10, 2, 12, 0, 10));
            assertThat(permit.model()).isEqualTo("deepseek-flash");
            assertThat(permit.promptVersion()).isEqualTo("thingslink-agent-single-analysis-v1");
            assertThatThrownBy(() -> permit.consume(context, now)).hasMessage("INVALID_ANALYSIS_RESULT_PERMIT");
            assertThat(receipt.toString() + permit + transport).doesNotContain("synthetic-only-credential", "<b>");
        }
    }

    @Test
    void foreignTenantProjectDeviceActorAndViewerRejectAndClearCredential() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var transport = transport(FrozenSyntheticReview.createOnce(now));
        for (var call : List.of(
                call(now, UUID.randomUUID(), PROJECT, DEVICE, ACTORS.get("OWNER"), AnalysisCall.Status.DISPATCHED),
                call(now, TENANT, UUID.randomUUID(), DEVICE, ACTORS.get("OWNER"), AnalysisCall.Status.DISPATCHED),
                call(now, TENANT, PROJECT, UUID.randomUUID(), ACTORS.get("OWNER"), AnalysisCall.Status.DISPATCHED),
                call(now, TENANT, PROJECT, DEVICE, UUID.randomUUID(), AnalysisCall.Status.DISPATCHED),
                call(now, TENANT, PROJECT, DEVICE, ACTORS.get("VIEWER"), AnalysisCall.Status.DISPATCHED))) {
            byte[] credential = {11, 22, 33};
            assertThatThrownBy(() -> transport.execute(call, input(now), credential))
                    .hasMessage("SYNTHETIC_SCOPE_OR_REVIEW_REJECTED").hasNoCause();
            assertThat(credential).containsOnly((byte) 0);
        }
    }

    @Test
    void expiredAndFutureReviewDoNotRenewWhenStatusIsReadOrExecuteIsAttempted() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        for (Instant issued : List.of(now.minusSeconds(7200), now.plusSeconds(7200))) {
            var frozen = FrozenSyntheticReview.createOnce(issued);
            var transport = transport(frozen);
            assertThat(transport.ready()).isTrue();
            assertThat(transport.review().orElseThrow()).isSameAs(frozen.approval());
            assertThat(transport.review().orElseThrow().isCurrent()).isFalse();
            byte[] credential = {1};
            assertThatThrownBy(() -> transport.execute(call(now, TENANT, PROJECT, DEVICE,
                    ACTORS.get("OWNER"), AnalysisCall.Status.DISPATCHED), input(now), credential))
                    .hasMessage("SYNTHETIC_SCOPE_OR_REVIEW_REJECTED").hasNoCause();
            assertThat(credential).containsOnly((byte) 0);
            assertThat(frozen.expiresAt()).isEqualTo(issued.plusSeconds(3600));
        }
    }

    @Test
    void controlledUnknownFaultClearsCredentialAndDoesNotReturnAFakePermit() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        byte[] credential = {8, 9};
        assertThatThrownBy(() -> transport(FrozenSyntheticReview.createOnce(now)).execute(
                call(now, TENANT, PROJECT, UNKNOWN, ACTORS.get("ADMIN"), AnalysisCall.Status.DISPATCHED),
                input(now), credential)).hasMessage("SYNTHETIC_TRANSPORT_UNKNOWN").hasNoCause();
        assertThat(credential).containsOnly((byte) 0);
    }

    @Test
    void actualEncoderRejectsUnclaimedCallAndCredentialStillClears() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        byte[] credential = {1, 2};
        assertThatThrownBy(() -> transport(FrozenSyntheticReview.createOnce(now)).execute(
                call(now, TENANT, PROJECT, DEVICE, ACTORS.get("OWNER"), AnalysisCall.Status.RESERVED),
                input(now), credential)).hasMessage("INVALID_INTERNAL_ANALYSIS_REQUEST").hasNoCause();
        assertThat(credential).containsOnly((byte) 0);
    }

    @Test
    void originalCallAndDeadlineStillFenceAnActuallyIssuedPermit() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var call = call(now, TENANT, PROJECT, DEVICE, ACTORS.get("OWNER"), AnalysisCall.Status.DISPATCHED);
        var permit = ((AnalysisTransport.Receipt.Releasable) transport(FrozenSyntheticReview.createOnce(now))
                .execute(call, input(now), new byte[]{1})).permit();
        var other = call(now, TENANT, PROJECT, UNKNOWN, ACTORS.get("OWNER"), AnalysisCall.Status.DISPATCHED);
        assertThatThrownBy(() -> permit.consume(context(other), now)).hasMessage("INVALID_ANALYSIS_RESULT_PERMIT");
        permit.consume(context(call), now);
        assertThatThrownBy(() -> permit.verifyReturn(context(call), call.deadline()))
                .hasMessage("INVALID_ANALYSIS_RESULT_PERMIT");
    }

    @Test
    void independentlyEncodedExpectationRejectsReceiptSelfReportedDigestAndConfiguration() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var call = call(now, TENANT, PROJECT, DEVICE, ACTORS.get("OWNER"), AnalysisCall.Status.DISPATCHED);
        var evidence = input(now);
        var frozen = FrozenSyntheticReview.createOnce(now);
        byte[] envelope = InternalAnalysisRequestEncoder.encode(call, evidence), decoded = null;
        try {
            var body = JSON.readTree(envelope);
            String inputDigest = body.path("inputSha256").asString();
            decoded = Base64.getDecoder().decode(body.path("inputBase64").asString());
            String requestDigest = ModelRequestFingerprint.fingerprint(decoded);
            var binding = new AnalysisResultIssuer.Binding(inputDigest, requestDigest,
                    frozen.counterSha256(), frozen.candidateSha256(), evidence.evidenceIds());
            for (int mismatch = 0; mismatch < 3; mismatch++) {
                var issuer = new AnalysisResultIssuer(call, binding, frozen.approval(), Clock.fixed(now, ZoneOffset.UTC));
                var receipt = new AnalysisResultIssuer.Receipt(call.id(), mismatch == 2 ? 3 : 2,
                        mismatch == 0 ? "0".repeat(64) : inputDigest,
                        mismatch == 1 ? "0".repeat(64) : requestDigest,
                        frozen.counterSha256(), frozen.candidateSha256(), frozen.approval().reviewSha256(),
                        "REVIEWED_EXECUTION", "VALIDATED", frozen.providerFingerprintSha256(),
                        new AnalysisContent("synthetic", List.of(), List.of()), new AnalysisUsage(1, 0, 1, 0, 1));
                assertThatThrownBy(() -> issuer.issue(receipt)).hasMessage("ANALYSIS_RELEASE_NOT_ADMITTED");
            }
        } finally {
            Arrays.fill(envelope, (byte) 0);
            if (decoded != null) Arrays.fill(decoded, (byte) 0);
        }
    }

    @Test
    void targetIsImmutableAndCannotExpandFaultOrRoleScope() {
        var actors = new HashMap<>(ACTORS);
        var target = new SyntheticReviewedTransport.Target(TENANT, PROJECT, actors, Set.of(DEVICE), Set.of());
        actors.put("VIEWER", UUID.randomUUID());
        assertThat(target.actors()).isEqualTo(ACTORS);
        assertThatThrownBy(() -> target.deviceIds().add(UNKNOWN)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new SyntheticReviewedTransport.Target(TENANT, PROJECT, ACTORS,
                Set.of(DEVICE), Set.of(UNKNOWN))).hasMessage("INVALID_SYNTHETIC_SCOPE");
        var bad = new HashMap<>(ACTORS);
        bad.put("VIEWER", bad.get("OWNER"));
        assertThatThrownBy(() -> new SyntheticReviewedTransport.Target(TENANT, PROJECT, bad,
                Set.of(DEVICE), Set.of())).hasMessage("INVALID_SYNTHETIC_SCOPE");
        assertThatThrownBy(() -> new SyntheticReviewedTransport(FrozenSyntheticReview.createOnce(Instant.now()),
                List.of(target, target))).hasMessage("INVALID_SYNTHETIC_SCOPE");
        assertThatThrownBy(() -> FrozenSyntheticReview.createOnce(null)).hasMessage("INVALID_SYNTHETIC_REVIEW").hasNoCause();
    }

    private static SyntheticReviewedTransport transport(FrozenSyntheticReview frozen) {
        return new SyntheticReviewedTransport(frozen, List.of(new SyntheticReviewedTransport.Target(
                TENANT, PROJECT, ACTORS, Set.of(DEVICE, UNKNOWN), Set.of(UNKNOWN))));
    }
    private static AnalysisCall call(Instant now, UUID tenant, UUID project, UUID device, UUID actor, AnalysisCall.Status status) {
        return new AnalysisCall(UUID.fromString("019c1234-5678-7890-8123-456789abcdef"), tenant, project,
                actor, device, UUID.randomUUID(), "k".repeat(64), "r".repeat(64), 2, status,
                now, now.plusSeconds(60), now.plusSeconds(86400), now, null);
    }
    private static AnalysisExecutionContext context(AnalysisCall call) {
        return new AnalysisExecutionContext(call, new ModelCredential(UUID.randomUUID(), call.tenantId(),
                call.projectId(), 2, 1, true, new byte[]{1}, new byte[]{2}, "synthetic", call.createdBy(), call.createdAt()));
    }
    private static PreparedModelEvidence.Input input(Instant now) {
        return new PreparedModelEvidence.Input(PreparedModelEvidence.Template.STATUS_SUMMARY, "device-1", now, now,
                new PreparedModelEvidence.Device("e-device", PreparedModelEvidence.DeviceStatus.INACTIVE, null, now),
                new PreparedModelEvidence.Alarm("e-alarm", PreparedModelEvidence.AlarmState.NORMAL, now),
                List.of(new PreparedModelEvidence.Reading("e-property-1", OutboundPropertyPolicy.Semantic.TEMPERATURE,
                        OutboundPropertyPolicy.Unit.CELSIUS, new PreparedModelEvidence.NumberValue(new BigDecimal("23.75")), now, now)));
    }
    private static byte[] resource() throws Exception {
        try (var stream = FrozenSyntheticReview.class.getResourceAsStream("/com/things/link/assistant/analysis-release-review.json")) {
            return stream.readAllBytes();
        }
    }
}
