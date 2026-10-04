package com.things.link.entitlement;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** 严格的纯函数验签器；部署持久状态、产品修订批准和时钟状态另行处理。 */
public final class GrantV1Verifier {
    private GrantV1Verifier() { }

    public static GrantV1 verify(byte[] envelope, Map<String, PublicKey> trustedKeys,
                                 String expectedIssuer, UUID expectedDeployment, UUID expectedTenant)
            throws GeneralSecurityException {
        Objects.requireNonNull(trustedKeys, "trustedKeys");
        Objects.requireNonNull(expectedIssuer, "expectedIssuer");
        Objects.requireNonNull(expectedDeployment, "expectedDeployment");
        Objects.requireNonNull(expectedTenant, "expectedTenant");
        if (envelope == null || envelope.length < GrantV1.MAGIC.length + 4 + 1 + GrantV1.SIGNATURE_BYTES
                || envelope.length > GrantV1.MAGIC.length + 4 + GrantV1.MAX_BODY + GrantV1.SIGNATURE_BYTES) {
            throw invalid();
        }
        try {
            var container = new DataInputStream(new ByteArrayInputStream(envelope));
            byte[] magic = new byte[GrantV1.MAGIC.length];
            container.readFully(magic);
            if (!Arrays.equals(magic, GrantV1.MAGIC)) throw invalid();
            int length = container.readInt();
            if (length < 1 || length > GrantV1.MAX_BODY
                    || envelope.length != GrantV1.MAGIC.length + 4 + length + GrantV1.SIGNATURE_BYTES) {
                throw invalid();
            }
            byte[] body = container.readNBytes(length);
            var in = new DataInputStream(new ByteArrayInputStream(body));
            String issuer = readCode(in);
            UUID grant = readUuid(in);
            UUID deployment = readUuid(in);
            UUID tenant = readUuid(in);
            String tier = readCode(in);
            String revision = readCode(in);
            byte[] digest = new byte[32];
            in.readFully(digest);
            long sequence = in.readLong();
            Instant issued = readTime(in);
            Instant starts = readTime(in);
            int hasEnd = in.readUnsignedByte();
            if (hasEnd > 1) throw invalid();
            Instant ends = hasEnd == 1 ? readTime(in) : null;
            String keyId = readCode(in);
            int quotaCount = in.readUnsignedShort();
            if (quotaCount < 1 || quotaCount > 128) throw invalid();
            Map<String, Long> quotas = new LinkedHashMap<>();
            String previous = null;
            for (int index = 0; index < quotaCount; index++) {
                String code = readCode(in);
                if (previous != null && previous.compareTo(code) >= 0) throw invalid();
                quotas.put(code, in.readLong());
                previous = code;
            }
            int capabilityCount = in.readUnsignedShort();
            if (capabilityCount > 128) throw invalid();
            Set<String> capabilities = new TreeSet<>();
            previous = null;
            for (int index = 0; index < capabilityCount; index++) {
                String code = readCode(in);
                if (previous != null && previous.compareTo(code) >= 0) throw invalid();
                capabilities.add(code);
                previous = code;
            }
            if (in.available() != 0) throw invalid();
            var parsed = new GrantV1(issuer, grant, deployment, tenant, tier, revision,
                    digest, sequence, issued, starts, ends, keyId, quotas, capabilities);
            if (!Arrays.equals(body, parsed.canonicalBody())) throw invalid();
            PublicKey key = trustedKeys.get(keyId);
            if (key == null || !GrantV1.verifySignatureOnly(envelope, key)) throw invalid();
            if (!issuer.equals(expectedIssuer) || !deployment.equals(expectedDeployment)
                    || !tenant.equals(expectedTenant)) throw invalid();
            return parsed;
        } catch (IOException | DateTimeException | IllegalArgumentException malformed) {
            throw new GeneralSecurityException("invalid SHC grant", malformed);
        }
    }

    private static String readCode(DataInputStream in) throws IOException {
        int length = in.readUnsignedByte();
        if (length < 1 || length > 64) throw new EOFException("invalid code length");
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        for (byte value : bytes) if (value < 32 || value > 126) throw new EOFException("non-ASCII code");
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private static UUID readUuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    private static Instant readTime(DataInputStream in) throws IOException {
        long seconds = in.readLong();
        int nanos = in.readInt();
        if (nanos < 0 || nanos >= 1_000_000_000) throw new EOFException("invalid nanoseconds");
        return Instant.ofEpochSecond(seconds, nanos);
    }

    private static GeneralSecurityException invalid() {
        return new GeneralSecurityException("invalid SHC grant");
    }
}
