package com.things.link.entitlement.application;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GrantV1VerificationTests {
    private static final UUID GRANT = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID DEPLOYMENT = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID TENANT = UUID.fromString("00000000-0000-4000-8000-000000000003");
    private static final String KEY_ID = "issuer-2026-a";

    @Test
    void paidAndFreeEnvelopesVerifyWithoutExposingMutableGrantState() throws Exception {
        KeyPair key = key();
        var paid = GrantV1Verification.verify(sign(body("STANDARD", false), key), trust(key),
                "thingslink", DEPLOYMENT, TENANT);
        assertThat(paid.grantId()).isEqualTo(GRANT);
        assertThat(paid.tier()).isEqualTo("STANDARD");
        assertThat(paid.sequence()).isEqualTo(7);
        assertThat(paid.quotas()).containsEntry("COLLABORATOR_SEAT_LIMIT", 0L)
                .containsEntry("DEVICE_LIMIT", 3L);
        assertThat(paid.capabilities()).containsOnly("OBJECT_STORAGE");
        byte[] digest = paid.revisionSha256();
        digest[0] = 42;
        assertThat(paid.revisionSha256()[0]).isZero();
        assertThatThrownBy(() -> paid.quotas().put("DEVICE_LIMIT", 10L))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(Arrays.stream(GrantV1Verification.VerifiedGrant.class.getDeclaredConstructors())
                .noneMatch(constructor -> Modifier.isPublic(constructor.getModifiers()))).isTrue();

        var free = GrantV1Verification.verify(sign(body("FREE", true), key), trust(key),
                "thingslink", DEPLOYMENT, TENANT);
        assertThat(free.endsAt()).isNull();
    }

    @Test
    void tamperingUnknownKeyWrongSubjectAndMalformedSignedBodyFailClosed() throws Exception {
        KeyPair key = key();
        byte[] valid = sign(body("STANDARD", false), key);
        reject(valid, Map.of(), "thingslink", DEPLOYMENT, TENANT);
        reject(valid, trust(key()), "thingslink", DEPLOYMENT, TENANT);
        reject(valid, trust(key), "other", DEPLOYMENT, TENANT);
        reject(valid, trust(key), "thingslink", UUID.randomUUID(), TENANT);
        reject(valid, trust(key), "thingslink", DEPLOYMENT, UUID.randomUUID());
        byte[] tampered = valid.clone();
        tampered[40] ^= 1;
        reject(tampered, trust(key), "thingslink", DEPLOYMENT, TENANT);
        reject(Arrays.copyOf(valid, valid.length + 1), trust(key), "thingslink", DEPLOYMENT, TENANT);
        byte[] unsignedBody = body("STANDARD", false);
        reject(sign(Arrays.copyOf(unsignedBody, unsignedBody.length + 1), key), trust(key),
                "thingslink", DEPLOYMENT, TENANT);

        byte[] unknownTier = body("STANDARD", false);
        unknownTier[indexOf(unknownTier, "STANDARD") + 7] = 'X';
        reject(sign(unknownTier, key), trust(key), "thingslink", DEPLOYMENT, TENANT);
        byte[] invalidQuota = body("STANDARD", false);
        int quota = indexOf(invalidQuota, "DEVICE_LIMIT") + "DEVICE_LIMIT".length();
        Arrays.fill(invalidQuota, quota, quota + Long.BYTES, (byte) 0xff);
        reject(sign(invalidQuota, key), trust(key), "thingslink", DEPLOYMENT, TENANT);
        byte[] duplicateCapability = body("STANDARD", false);
        int capability = indexOf(duplicateCapability, "OBJECT_STORAGE");
        duplicateCapability[capability] = 'o';
        reject(sign(duplicateCapability, key), trust(key), "thingslink", DEPLOYMENT, TENANT);
        reject(sign(body("FREE", false), key), trust(key), "thingslink", DEPLOYMENT, TENANT);
    }

    private static void reject(byte[] envelope, Map<String, PublicKey> keys,
                               String issuer, UUID deployment, UUID tenant) {
        assertThatThrownBy(() -> GrantV1Verification.verify(envelope, keys, issuer, deployment, tenant))
                .isInstanceOf(GeneralSecurityException.class);
    }

    private static KeyPair key() throws GeneralSecurityException {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static Map<String, PublicKey> trust(KeyPair pair) {
        return Map.of(KEY_ID, pair.getPublic());
    }

    private static byte[] body(String tier, boolean free) throws Exception {
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        code(out, "thingslink");
        uuid(out, GRANT);
        uuid(out, DEPLOYMENT);
        uuid(out, TENANT);
        code(out, tier);
        code(out, "selfhost.r1");
        out.write(new byte[32]);
        out.writeLong(7);
        time(out, Instant.parse("2026-09-27T00:00:00Z"));
        time(out, Instant.parse("2024-02-29T12:00:00.123456789Z"));
        out.writeByte(free ? 0 : 1);
        if (!free) time(out, Instant.parse("2025-02-28T12:00:00.123456789Z"));
        code(out, KEY_ID);
        out.writeShort(2);
        code(out, "COLLABORATOR_SEAT_LIMIT");
        out.writeLong(0);
        code(out, "DEVICE_LIMIT");
        out.writeLong(3);
        out.writeShort(1);
        code(out, "OBJECT_STORAGE");
        return bytes.toByteArray();
    }

    private static byte[] sign(byte[] body, KeyPair pair) throws Exception {
        var signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update("TC-SHC-GRANT-V1\0".getBytes(StandardCharsets.US_ASCII));
        signer.update(body);
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        out.write("TCSHC1\0".getBytes(StandardCharsets.US_ASCII));
        out.writeInt(body.length);
        out.write(body);
        out.write(signer.sign());
        return bytes.toByteArray();
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

    private static int indexOf(byte[] bytes, String value) {
        byte[] needle = value.getBytes(StandardCharsets.US_ASCII);
        for (int index = 0; index <= bytes.length - needle.length; index++) {
            if (Arrays.equals(bytes, index, index + needle.length, needle, 0, needle.length)) return index;
        }
        throw new AssertionError("fixture field missing: " + value);
    }
}
