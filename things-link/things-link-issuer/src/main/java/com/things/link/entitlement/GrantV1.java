package com.things.link.entitlement;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/** 发行方使用的 SHC V1 确定性签名内核；不加载密钥，也不执行正式签发。 */
public final class GrantV1 {
    private static final byte[] DOMAIN = "TC-SHC-GRANT-V1\0".getBytes(StandardCharsets.US_ASCII);
    static final byte[] MAGIC = "TCSHC1\0".getBytes(StandardCharsets.US_ASCII);
    static final int MAX_BODY = 65_536;
    static final int SIGNATURE_BYTES = 64;

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

    public GrantV1(String issuerId, UUID grantId, UUID deploymentId, UUID tenantId, String tier,
                   String revisionId, byte[] revisionSha256, long sequence,
                   Instant issuedAt, Instant startsAt, Instant endsAt, String keyId,
                   Map<String, Long> quotas, Set<String> capabilities) {
        this.issuerId = checkedCode(issuerId, false);
        this.grantId = Objects.requireNonNull(grantId, "grantId");
        this.deploymentId = Objects.requireNonNull(deploymentId, "deploymentId");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        if (!Set.of("FREE", "STANDARD", "ENTERPRISE", "PROFESSIONAL").contains(tier)) {
            throw new IllegalArgumentException("unknown tier");
        }
        this.tier = tier;
        this.revisionId = checkedCode(revisionId, false);
        if (revisionSha256 == null || revisionSha256.length != 32) {
            throw new IllegalArgumentException("revision digest must be SHA-256");
        }
        this.revisionSha256 = revisionSha256.clone();
        if (sequence < 1) throw new IllegalArgumentException("sequence must be positive");
        this.sequence = sequence;
        this.issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
        this.startsAt = Objects.requireNonNull(startsAt, "startsAt");
        if (tier.equals("FREE")) {
            if (endsAt != null) throw new IllegalArgumentException("FREE must not expire");
        } else if (endsAt == null || !endsAt.isAfter(startsAt)) {
            throw new IllegalArgumentException("paid grant needs an end after its start");
        }
        this.endsAt = endsAt;
        this.keyId = checkedCode(keyId, false);
        if (quotas == null || quotas.isEmpty() || quotas.size() > 128) {
            throw new IllegalArgumentException("quota set must be bounded and nonempty");
        }
        var sortedQuotas = new TreeMap<String, Long>();
        for (var entry : quotas.entrySet()) {
            String code = checkedCode(entry.getKey(), true);
            Long limit = Objects.requireNonNull(entry.getValue(), "quota limit");
            if (limit < 0) throw new IllegalArgumentException("negative quota");
            sortedQuotas.put(code, limit);
        }
        this.quotas = Map.copyOf(sortedQuotas);
        if (capabilities == null || capabilities.size() > 128) {
            throw new IllegalArgumentException("capability set too large");
        }
        var sortedCapabilities = new TreeSet<String>();
        for (String code : capabilities) sortedCapabilities.add(checkedCode(code, true));
        this.capabilities = Set.copyOf(sortedCapabilities);
    }

    private static String checkedCode(String value, boolean uppercase) {
        if (value == null || value.isEmpty() || value.length() > 64
                || !value.matches(uppercase ? "[A-Z][A-Z0-9_]*" : "[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid code");
        }
        return value;
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

    private static void writeCode(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        out.writeByte(bytes.length);
        out.write(bytes);
    }

    private static void writeUuid(DataOutputStream out, UUID value) throws IOException {
        out.writeLong(value.getMostSignificantBits());
        out.writeLong(value.getLeastSignificantBits());
    }

    private static void writeTime(DataOutputStream out, Instant value) throws IOException {
        out.writeLong(value.getEpochSecond());
        out.writeInt(value.getNano());
    }

    /** 字段的精确字节顺序见 SHC V1 线格式合同。 */
    public byte[] canonicalBody() {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            writeCode(out, issuerId);
            writeUuid(out, grantId);
            writeUuid(out, deploymentId);
            writeUuid(out, tenantId);
            writeCode(out, tier);
            writeCode(out, revisionId);
            out.write(revisionSha256);
            out.writeLong(sequence);
            writeTime(out, issuedAt);
            writeTime(out, startsAt);
            out.writeBoolean(endsAt != null);
            if (endsAt != null) writeTime(out, endsAt);
            writeCode(out, keyId);
            out.writeShort(quotas.size());
            for (var entry : new TreeMap<>(quotas).entrySet()) {
                writeCode(out, entry.getKey());
                out.writeLong(entry.getValue());
            }
            out.writeShort(capabilities.size());
            for (String code : new TreeSet<>(capabilities)) writeCode(out, code);
            out.flush();
            if (bytes.size() > MAX_BODY) throw new IllegalArgumentException("grant body too large");
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** 私钥由调用方提供；本类不读取或写入私钥。 */
    public byte[] sign(PrivateKey issuerKey) throws GeneralSecurityException {
        Objects.requireNonNull(issuerKey, "issuerKey");
        byte[] body = canonicalBody();
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(issuerKey);
        signer.update(DOMAIN);
        signer.update(body);
        byte[] signed = signer.sign();
        if (signed.length != SIGNATURE_BYTES) throw new GeneralSecurityException("unexpected signature size");
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.write(MAGIC);
            out.writeInt(body.length);
            out.write(body);
            out.write(signed);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** 仅供独立格式测试验签；字段解析和主体绑定由 SHC-2a 负责。 */
    public static boolean verifySignatureOnly(byte[] envelope, PublicKey issuerKey)
            throws GeneralSecurityException {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(issuerKey, "issuerKey");
        try {
            var in = new DataInputStream(new ByteArrayInputStream(envelope));
            if (!Arrays.equals(MAGIC, in.readNBytes(MAGIC.length))) return false;
            int size = in.readInt();
            if (size < 1 || size > MAX_BODY || envelope.length != MAGIC.length + 4 + size + SIGNATURE_BYTES) {
                return false;
            }
            byte[] body = in.readNBytes(size);
            byte[] signed = in.readNBytes(SIGNATURE_BYTES);
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(issuerKey);
            verifier.update(DOMAIN);
            verifier.update(body);
            return verifier.verify(signed);
        } catch (IOException | RuntimeException malformed) {
            return false;
        }
    }
}
