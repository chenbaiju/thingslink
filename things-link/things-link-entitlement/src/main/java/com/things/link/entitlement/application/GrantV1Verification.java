package com.things.link.entitlement.application;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.EdECPublicKey;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** 自部署运行时可复用的 V1 公钥验签合同；此结果尚不能单独授予运行权限。 */
public final class GrantV1Verification {
    private static final byte[] MAGIC = "TCSHC1\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DOMAIN = "TC-SHC-GRANT-V1\0".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_BODY = 65_536;
    private static final int SIGNATURE_BYTES = 64;

    private GrantV1Verification() { }

    public static VerifiedGrant verify(byte[] envelope, Map<String, PublicKey> trustedKeys,
                                       String expectedIssuer, UUID expectedDeployment, UUID expectedTenant)
            throws GeneralSecurityException {
        Objects.requireNonNull(trustedKeys, "trustedKeys");
        Objects.requireNonNull(expectedIssuer, "expectedIssuer");
        Objects.requireNonNull(expectedDeployment, "expectedDeployment");
        Objects.requireNonNull(expectedTenant, "expectedTenant");
        if (envelope == null || envelope.length < MAGIC.length + 4 + 1 + SIGNATURE_BYTES
                || envelope.length > MAGIC.length + 4 + MAX_BODY + SIGNATURE_BYTES) {
            throw invalid();
        }
        try {
            var container = new DataInputStream(new ByteArrayInputStream(envelope));
            byte[] magic = container.readNBytes(MAGIC.length);
            if (!Arrays.equals(magic, MAGIC)) throw invalid();
            int length = container.readInt();
            if (length < 1 || length > MAX_BODY
                    || envelope.length != MAGIC.length + 4 + length + SIGNATURE_BYTES) throw invalid();
            byte[] body = container.readNBytes(length);
            byte[] signed = container.readNBytes(SIGNATURE_BYTES);
            var in = new DataInputStream(new ByteArrayInputStream(body));
            String issuer = readCode(in, false);
            UUID grant = readUuid(in);
            UUID deployment = readUuid(in);
            UUID tenant = readUuid(in);
            String tier = readCode(in, false);
            if (!Set.of("FREE", "STANDARD", "ENTERPRISE", "PROFESSIONAL").contains(tier)) throw invalid();
            String revision = readCode(in, false);
            byte[] revisionDigest = new byte[32];
            in.readFully(revisionDigest);
            long sequence = in.readLong();
            if (sequence < 1) throw invalid();
            Instant issued = readTime(in);
            Instant starts = readTime(in);
            int hasEnd = in.readUnsignedByte();
            if (hasEnd > 1) throw invalid();
            Instant ends = hasEnd == 1 ? readTime(in) : null;
            if (tier.equals("FREE") ? ends != null : ends == null || !ends.isAfter(starts)) throw invalid();
            String keyId = readCode(in, false);
            int quotaCount = in.readUnsignedShort();
            if (quotaCount < 1 || quotaCount > 128) throw invalid();
            Map<String, Long> quotas = new LinkedHashMap<>();
            String previous = null;
            for (int index = 0; index < quotaCount; index++) {
                String code = readCode(in, true);
                if (previous != null && previous.compareTo(code) >= 0) throw invalid();
                long value = in.readLong();
                if (value < 0) throw invalid();
                quotas.put(code, value);
                previous = code;
            }
            int capabilityCount = in.readUnsignedShort();
            if (capabilityCount > 128) throw invalid();
            Set<String> capabilities = new TreeSet<>();
            previous = null;
            for (int index = 0; index < capabilityCount; index++) {
                String code = readCode(in, true);
                if (previous != null && previous.compareTo(code) >= 0) throw invalid();
                capabilities.add(code);
                previous = code;
            }
            if (in.available() != 0) throw invalid();
            PublicKey key = trustedKeys.get(keyId);
            if (!(key instanceof EdECPublicKey edKey)
                    || !"Ed25519".equals(edKey.getParams().getName())) throw invalid();
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(DOMAIN);
            verifier.update(body);
            if (!verifier.verify(signed) || !issuer.equals(expectedIssuer)
                    || !deployment.equals(expectedDeployment) || !tenant.equals(expectedTenant)) throw invalid();
            return new VerifiedGrant(issuer, grant, deployment, tenant, tier, revision, revisionDigest,
                    sequence, issued, starts, ends, keyId, quotas, capabilities);
        } catch (IOException | DateTimeException | IllegalArgumentException malformed) {
            throw new GeneralSecurityException("invalid SHC grant", malformed);
        }
    }

    private static String readCode(DataInputStream in, boolean uppercase) throws IOException {
        int length = in.readUnsignedByte();
        if (length < 1 || length > 64) throw new IOException("invalid code length");
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        for (byte value : bytes) if (value < 32 || value > 126) throw new IOException("non-ASCII code");
        String code = new String(bytes, StandardCharsets.US_ASCII);
        if (!code.matches(uppercase ? "[A-Z][A-Z0-9_]*" : "[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IOException("invalid code");
        }
        return code;
    }

    private static UUID readUuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    private static Instant readTime(DataInputStream in) throws IOException {
        long seconds = in.readLong();
        int nanos = in.readInt();
        if (nanos < 0 || nanos >= 1_000_000_000) throw new IOException("invalid nanoseconds");
        return Instant.ofEpochSecond(seconds, nanos);
    }

    private static GeneralSecurityException invalid() {
        return new GeneralSecurityException("invalid SHC grant");
    }

    /** 仅代表签名、字节和主体绑定已通过，不代表修订、时钟或导入序号已获准。 */
    public static final class VerifiedGrant {
        private final String issuerId;
        private final UUID grantId;
        private final UUID deploymentId;
        private final UUID tenantId;
        private final String tier;
        private final String revisionId;
        private final byte[] revisionSha256;
        private final long sequence;
        private final Instant issuedAt;
        private final Instant startsAt;
        private final Instant endsAt;
        private final String keyId;
        private final Map<String, Long> quotas;
        private final Set<String> capabilities;

        private VerifiedGrant(String issuerId, UUID grantId, UUID deploymentId, UUID tenantId,
                              String tier, String revisionId, byte[] revisionSha256, long sequence,
                              Instant issuedAt, Instant startsAt, Instant endsAt, String keyId,
                              Map<String, Long> quotas, Set<String> capabilities) {
            this.issuerId = issuerId;
            this.grantId = grantId;
            this.deploymentId = deploymentId;
            this.tenantId = tenantId;
            this.tier = tier;
            this.revisionId = revisionId;
            this.revisionSha256 = revisionSha256.clone();
            this.sequence = sequence;
            this.issuedAt = issuedAt;
            this.startsAt = startsAt;
            this.endsAt = endsAt;
            this.keyId = keyId;
            this.quotas = Map.copyOf(quotas);
            this.capabilities = Set.copyOf(capabilities);
        }

        public String issuerId() { return issuerId; }
        public UUID grantId() { return grantId; }
        public UUID deploymentId() { return deploymentId; }
        public UUID tenantId() { return tenantId; }
        public String tier() { return tier; }
        public String revisionId() { return revisionId; }
        public byte[] revisionSha256() { return revisionSha256.clone(); }
        public long sequence() { return sequence; }
        public Instant issuedAt() { return issuedAt; }
        public Instant startsAt() { return startsAt; }
        public Instant endsAt() { return endsAt; }
        public String keyId() { return keyId; }
        public Map<String, Long> quotas() { return quotas; }
        public Set<String> capabilities() { return capabilities; }
    }
}
