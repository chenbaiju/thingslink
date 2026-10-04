package com.things.link.entitlement.application;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApprovedSelfHostedRevisionTests {
    private static final UUID GRANT = UUID.fromString("00000000-0000-4000-8000-000000000011");
    private static final UUID DEPLOYMENT = UUID.fromString("00000000-0000-4000-8000-000000000012");
    private static final UUID TENANT = UUID.fromString("00000000-0000-4000-8000-000000000013");
    private static final String KEY_ID = "issuer-test-a";

    @Test
    void bundledRevisionMatchesExactSignedTierAndRejectsDrift() throws Exception {
        ApprovedSelfHostedRevision revision = ApprovedSelfHostedRevision.loadApproved();
        assertThat(revision.revisionId()).isEqualTo("self-hosted-product-revision-1");
        assertThat(revision.tier("FREE").quotas()).hasSize(19).containsEntry("DEVICES_MAX", 3L);
        assertThat(revision.tier("STANDARD").quotas()).hasSize(19)
                .containsEntry("UPLINK_MESSAGE_DAILY", 105_000L)
                .containsEntry("DOWNLINK_MESSAGE_DAILY", 45_000L);
        assertThat(revision.tier("STANDARD").capabilities()).hasSize(13);
        byte[] escapedDigest = revision.sha256();
        escapedDigest[0] ^= 1;
        assertThat(revision.sha256()).isNotEqualTo(escapedDigest);
        assertThatThrownBy(() -> revision.tier("UNKNOWN")).isInstanceOf(GeneralSecurityException.class);
        assertThatThrownBy(() -> revision.tier("FREE").quotas().put("DEVICES_MAX", 10L))
                .isInstanceOf(UnsupportedOperationException.class);

        KeyPair key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var standard = revision.tier("STANDARD");
        revision.requireMatches(verified(key, "STANDARD", revision.revisionId(), revision.sha256(),
                standard.quotas(), standard.capabilities()));
        var free = revision.tier("FREE");
        revision.requireMatches(verified(key, "FREE", revision.revisionId(), revision.sha256(),
                free.quotas(), free.capabilities()));
        reject(revision, verified(key, "ENTERPRISE", revision.revisionId(), revision.sha256(),
                standard.quotas(), standard.capabilities()));

        byte[] wrongDigest = revision.sha256();
        wrongDigest[0] ^= 1;
        reject(revision, verified(key, "STANDARD", revision.revisionId(), wrongDigest,
                standard.quotas(), standard.capabilities()));
        reject(revision, verified(key, "STANDARD", "self-hosted-product-revision-2", revision.sha256(),
                standard.quotas(), standard.capabilities()));

        Map<String, Long> lessDevices = new HashMap<>(standard.quotas());
        lessDevices.put("DEVICES_MAX", 99L);
        reject(revision, verified(key, "STANDARD", revision.revisionId(), revision.sha256(),
                lessDevices, standard.capabilities()));
        Map<String, Long> missingQuota = new HashMap<>(standard.quotas());
        missingQuota.remove("DEVICES_MAX");
        reject(revision, verified(key, "STANDARD", revision.revisionId(), revision.sha256(),
                missingQuota, standard.capabilities()));
        Map<String, Long> extraQuota = new HashMap<>(standard.quotas());
        extraQuota.put("UNKNOWN_LIMIT", 1L);
        reject(revision, verified(key, "STANDARD", revision.revisionId(), revision.sha256(),
                extraQuota, standard.capabilities()));
        Set<String> extraCapability = new HashSet<>(standard.capabilities());
        extraCapability.add("SMS_CHANNEL");
        reject(revision, verified(key, "STANDARD", revision.revisionId(), revision.sha256(),
                standard.quotas(), extraCapability));
        Set<String> missingCapability = new HashSet<>(standard.capabilities());
        missingCapability.remove("OPEN_API");
        reject(revision, verified(key, "STANDARD", revision.revisionId(), revision.sha256(),
                standard.quotas(), missingCapability));
    }

    @Test
    void changedResourceBytesCannotBeLoadedAsApproved() throws Exception {
        byte[] resource;
        try (var stream = ApprovedSelfHostedRevision.class.getResourceAsStream(
                "/self-hosted/revisions/self-hosted-product-revision-1.json")) {
            assertThat(stream).isNotNull();
            resource = stream.readAllBytes();
        }
        resource[resource.length - 2] ^= 1;
        assertThatThrownBy(() -> ApprovedSelfHostedRevision.parseApproved(resource))
                .isInstanceOf(GeneralSecurityException.class);
    }

    private static void reject(ApprovedSelfHostedRevision revision,
                               GrantV1Verification.VerifiedGrant grant) {
        assertThatThrownBy(() -> revision.requireMatches(grant))
                .isInstanceOf(GeneralSecurityException.class);
    }

    private static GrantV1Verification.VerifiedGrant verified(KeyPair key, String tier, String revisionId,
                                                                byte[] digest, Map<String, Long> quotas,
                                                                Set<String> capabilities) throws Exception {
        var body = new ByteArrayOutputStream();
        var out = new DataOutputStream(body);
        code(out, "thingslink");
        uuid(out, GRANT);
        uuid(out, DEPLOYMENT);
        uuid(out, TENANT);
        code(out, tier);
        code(out, revisionId);
        out.write(digest);
        out.writeLong(7);
        time(out, Instant.parse("2026-09-28T00:00:00Z"));
        time(out, Instant.parse("2026-09-28T00:00:00Z"));
        out.writeByte(tier.equals("FREE") ? 0 : 1);
        if (!tier.equals("FREE")) time(out, Instant.parse("2027-09-28T00:00:00Z"));
        code(out, KEY_ID);
        out.writeShort(quotas.size());
        for (var quota : new TreeMap<>(quotas).entrySet()) {
            code(out, quota.getKey());
            out.writeLong(quota.getValue());
        }
        out.writeShort(capabilities.size());
        for (String capability : new TreeSet<>(capabilities)) code(out, capability);
        byte[] canonical = body.toByteArray();
        var signer = Signature.getInstance("Ed25519");
        signer.initSign(key.getPrivate());
        signer.update("TC-SHC-GRANT-V1\0".getBytes(StandardCharsets.US_ASCII));
        signer.update(canonical);
        var envelope = new ByteArrayOutputStream();
        var container = new DataOutputStream(envelope);
        container.write("TCSHC1\0".getBytes(StandardCharsets.US_ASCII));
        container.writeInt(canonical.length);
        container.write(canonical);
        container.write(signer.sign());
        return GrantV1Verification.verify(envelope.toByteArray(), Map.of(KEY_ID, key.getPublic()),
                "thingslink", DEPLOYMENT, TENANT);
    }

    private static void code(DataOutputStream out, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        out.writeByte(bytes.length);
        out.write(bytes);
    }

    private static void uuid(DataOutputStream out, UUID value) throws Exception {
        out.writeLong(value.getMostSignificantBits());
        out.writeLong(value.getLeastSignificantBits());
    }

    private static void time(DataOutputStream out, Instant value) throws Exception {
        out.writeLong(value.getEpochSecond());
        out.writeInt(value.getNano());
    }
}
