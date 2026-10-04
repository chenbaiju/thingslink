package com.things.link.entitlement.application;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.UUID;

/**
 * 自部署申请的确定性字节合同。部署密钥只证明申请者持有公钥对应的私钥，
 * 不证明其有权代表租户；登记结果始终为待审核，不能直接签发权益。
 */
public final class EnrollmentRequestV1 {
    private static final byte[] MAGIC = {'T', 'C', 'S', 'H', 'R', 'E', 'Q', '1', 0};
    private static final byte[] DOMAIN = {'T', 'C', '-', 'S', 'H', '-', 'E', 'N', 'R', 'O', 'L', 'L', '-', 'V', '1', 0};
    private static final int SIGNATURE_LENGTH = 64;
    private static final int MAX_KEY_LENGTH = 128;
    private static final int FIXED_BODY_LENGTH = 16 * 3 + Short.BYTES;
    /** HTTP/文件入口在分配正文前使用的 V1 最大封套长度。 */
    public static final int MAX_ENVELOPE_BYTES = MAGIC.length + FIXED_BODY_LENGTH
            + MAX_KEY_LENGTH + SIGNATURE_LENGTH;

    private EnrollmentRequestV1() { }

    /** 供部署端生成联网或离线均可传输的同一申请；不包含授权或套餐值。 */
    public static byte[] create(UUID requestId, UUID deploymentId, UUID tenantId,
                                PublicKey deploymentPublicKey, PrivateKey deploymentPrivateKey) {
        requireRequestId(requestId);
        requireUuid(deploymentId, "部署 ID");
        requireUuid(tenantId, "租户 ID");
        if (deploymentPublicKey == null || deploymentPrivateKey == null) {
            throw new IllegalArgumentException("部署密钥不能为空");
        }
        byte[] spki = deploymentPublicKey.getEncoded();
        validateKey(spki);
        ByteBuffer body = ByteBuffer.allocate(FIXED_BODY_LENGTH + spki.length);
        writeUuid(body, requestId);
        writeUuid(body, deploymentId);
        writeUuid(body, tenantId);
        body.putShort((short) spki.length).put(spki);
        byte[] canonical = body.array();
        byte[] signature = sign(deploymentPrivateKey, canonical);
        ByteBuffer envelope = ByteBuffer.allocate(MAGIC.length + canonical.length + SIGNATURE_LENGTH);
        return envelope.put(MAGIC).put(canonical).put(signature).array();
    }

    /** 严格解析并证明部署公钥持有；租户业务归属仍须单独人工审核。 */
    public static Verified verify(byte[] envelope) {
        if (envelope == null || envelope.length < MAGIC.length + FIXED_BODY_LENGTH + 1 + SIGNATURE_LENGTH
                || envelope.length > MAX_ENVELOPE_BYTES) {
            throw new IllegalArgumentException("申请封套长度无效");
        }
        ByteBuffer input = ByteBuffer.wrap(envelope);
        byte[] magic = new byte[MAGIC.length];
        input.get(magic);
        if (!MessageDigest.isEqual(magic, MAGIC)) {
            throw new IllegalArgumentException("申请格式版本无效");
        }
        UUID requestId = readUuid(input);
        UUID deploymentId = readUuid(input);
        UUID tenantId = readUuid(input);
        requireRequestId(requestId);
        requireUuid(deploymentId, "部署 ID");
        requireUuid(tenantId, "租户 ID");
        int keyLength = Short.toUnsignedInt(input.getShort());
        if (keyLength < 1 || keyLength > MAX_KEY_LENGTH || input.remaining() != keyLength + SIGNATURE_LENGTH) {
            throw new IllegalArgumentException("部署公钥长度或封套尾部无效");
        }
        byte[] spki = new byte[keyLength];
        input.get(spki);
        byte[] signature = new byte[SIGNATURE_LENGTH];
        input.get(signature);
        PublicKey key = validateKey(spki);
        byte[] canonical = Arrays.copyOfRange(envelope, MAGIC.length, envelope.length - SIGNATURE_LENGTH);
        if (!verifySignature(key, canonical, signature)) {
            throw new IllegalArgumentException("部署申请签名无效");
        }
        return new Verified(requestId, deploymentId, tenantId, spki, sha256(spki), sha256(envelope));
    }

    private static PublicKey validateKey(byte[] spki) {
        if (spki == null || spki.length < 1 || spki.length > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("部署公钥长度无效");
        }
        try {
            PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
            if (!MessageDigest.isEqual(key.getEncoded(), spki)) {
                throw new IllegalArgumentException("部署公钥不是规范 Ed25519 SPKI");
            }
            return key;
        } catch (GeneralSecurityException exception) {
            throw new IllegalArgumentException("部署公钥不是 Ed25519 SPKI", exception);
        }
    }

    private static byte[] sign(PrivateKey key, byte[] canonical) {
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(key);
            signature.update(DOMAIN);
            signature.update(canonical);
            byte[] result = signature.sign();
            if (result.length != SIGNATURE_LENGTH) throw new IllegalArgumentException("部署签名长度无效");
            return result;
        } catch (GeneralSecurityException exception) {
            throw new IllegalArgumentException("部署签名失败", exception);
        }
    }

    private static boolean verifySignature(PublicKey key, byte[] canonical, byte[] signed) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(DOMAIN);
            verifier.update(canonical);
            return verifier.verify(signed);
        } catch (GeneralSecurityException exception) {
            return false;
        }
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("JDK 缺少 SHA-256", exception);
        }
    }

    private static void writeUuid(ByteBuffer buffer, UUID value) {
        buffer.putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(ByteBuffer buffer) {
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static void requireUuid(UUID value, String name) {
        if (value == null || (value.getMostSignificantBits() == 0 && value.getLeastSignificantBits() == 0)) {
            throw new IllegalArgumentException(name + "不能为空");
        }
    }

    private static void requireRequestId(UUID value) {
        requireUuid(value, "申请 ID");
        if (value.version() != 7) throw new IllegalArgumentException("申请 ID 必须为 UUIDv7");
    }

    /** 仅是已验签的申请事实，不是已批准租户身份或可用权益。 */
    public record Verified(UUID requestId, UUID deploymentId, UUID tenantId, byte[] publicKeySpki,
                           byte[] publicKeySha256, byte[] requestSha256) {
        public Verified {
            publicKeySpki = publicKeySpki.clone();
            publicKeySha256 = publicKeySha256.clone();
            requestSha256 = requestSha256.clone();
        }
        @Override public byte[] publicKeySpki() { return publicKeySpki.clone(); }
        @Override public byte[] publicKeySha256() { return publicKeySha256.clone(); }
        @Override public byte[] requestSha256() { return requestSha256.clone(); }
    }
}
