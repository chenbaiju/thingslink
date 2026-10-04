package com.things.link.ota.application;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECFieldFp;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * ADR0053第1、2节冻结的发布签名密码学边界，不注册运行时Bean。
 *
 * <p>调用方必须先验证manifest字段并生成JCS UTF-8字节，同时确认公钥的信任和有效状态；
 * 本类只验证这些字节的签名，不证明manifest合法、密钥获授权或设备具备升级资格。
 * P-256互操作边界为命名曲线SPKI及未压缩SEC1点，不接收压缩点或显式曲线参数；
 * 调用方不得在调用期间并发修改输入数组。</p>
 */
public final class OtaReleaseSignatureVerifier {
    /** 固定域分离前缀包含结尾零字节，禁止裸manifest签名跨协议重用。 */
    private static final byte[] DOMAIN =
            "thingslink-ota-release-manifest-v1\u0000".getBytes(StandardCharsets.UTF_8);
    /** RFC8410要求Ed25519参数缺省，严格前缀也排除NULL参数和尾随DER数据。 */
    private static final byte[] ED25519_SPKI_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");
    /** JDK支持的P-256命名曲线SPKI，公钥点使用未压缩SEC1编码。 */
    private static final byte[] P256_SPKI_PREFIX =
            HexFormat.of().parseHex("3059301306072a8648ce3d020106082a8648ce3d03010703420004");
    /** RFC8032的坐标域上界，拒绝非规范y编码而非模p归约。 */
    private static final BigInteger ED25519_FIELD = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19));
    /**
     * Ed25519低阶点的两个八阶y坐标；其余为0、1和p-1，符号位不改变阶。
     * 数学常量来源：libsodium 1.0.18 ed25519_ref10.c的ge25519_has_small_order。
     * 只作公钥编码拒绝，不自行实现点加、签名或曲线乘法。
     */
    private static final BigInteger[] ED25519_ORDER_EIGHT_Y = {
        new BigInteger("2707385501144840649318225287225658788936804267575313519463743609750303402022"),
        new BigInteger("55188659117513257062467267217118295137698188065244968500265048394206261417927")
    };
    /** P-256子群阶用于拒绝越界r/s和ECDSA的high-S可塑性。 */
    private static final BigInteger P256_ORDER =
            new BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16);

    /**
     * 验证调用方已规范化的发布内容，任何无效外部输入统一返回false。
     *
     * @param profile 冻结Profile，不接受任意算法名
     * @param spkiDer 完整公开SPKI DER，不接受证书、裸点或私钥
     * @param expectedFingerprint 完整SPKI的SHA-256小写64位十六进制指纹
     * @param canonicalManifest 调用方已验证并规范化的manifest UTF-8字节
     * @param signatureBytes 原始64字节Ed25519或low-S P1363签名
     * @return 仅当Profile、编码、指纹及域分离签名全部匹配时为true
     */
    public boolean verify(OtaSignatureProfile profile, byte[] spkiDer, String expectedFingerprint,
                          byte[] canonicalManifest, byte[] signatureBytes) {
        return verifyDomain(profile, spkiDer, expectedFingerprint, canonicalManifest, signatureBytes, DOMAIN);
    }

    /** 仅供根签名bundle使用固定独立域，不向外开放任意前缀。 */
    boolean verifyBundle(OtaSignatureProfile profile, byte[] spki, String fingerprint, byte[] canonical, byte[] signature) {
        return verifyDomain(profile, spki, fingerprint, canonical, signature,
                "thingslink-ota-trust-bundle-v1\u0000".getBytes(StandardCharsets.UTF_8));
    }

    /** 两种固定协议共用严格SPKI和签名编码，避免安全检查分叉。 */
    private boolean verifyDomain(OtaSignatureProfile profile, byte[] spkiDer, String expectedFingerprint,
                                 byte[] canonicalManifest, byte[] signatureBytes, byte[] domain) {
        if (profile == null || spkiDer == null || expectedFingerprint == null
                || !expectedFingerprint.matches("[0-9a-f]{64}")
                || canonicalManifest == null || canonicalManifest.length == 0
                || signatureBytes == null || signatureBytes.length != 64) {
            return false;
        }
        if (spkiDer.length != 44 && spkiDer.length != 91) return false;
        byte[] keyBytes = spkiDer.clone();
        if (!validKey(profile, keyBytes, expectedFingerprint)) return false;
        byte[] signature = signatureBytes.clone();
        try {
            if (profile == OtaSignatureProfile.ES256_P1363_V1 && !hasCanonicalScalars(signature)) {
                return false;
            }
            String keyAlgorithm = profile == OtaSignatureProfile.ED25519_V1 ? "Ed25519" : "EC";
            String signatureAlgorithm = profile == OtaSignatureProfile.ED25519_V1
                    ? "Ed25519" : "SHA256withECDSAinP1363Format";
            PublicKey key = KeyFactory.getInstance(keyAlgorithm).generatePublic(new X509EncodedKeySpec(keyBytes));
            Signature verifier = Signature.getInstance(signatureAlgorithm);
            verifier.initVerify(key);
            verifier.update(domain);
            verifier.update(canonicalManifest);
            return verifier.verify(signature);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            // 不退回其他算法或签名编码；外部畸形输入和不可用算法均失败关闭。
            return false;
        }
    }

    /** 配置与bundle入库前验证公开密钥的相同严格编码及指纹，不要求伪造签名。 */
    boolean validKey(OtaSignatureProfile profile, byte[] spki, String fingerprint) {
        if (profile == null || spki == null || fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) return false;
        byte[] prefix = profile == OtaSignatureProfile.ED25519_V1 ? ED25519_SPKI_PREFIX : P256_SPKI_PREFIX;
        int pointLength = profile == OtaSignatureProfile.ED25519_V1 ? 32 : 64;
        if (spki.length != prefix.length + pointLength) return false;
        byte[] keyBytes = spki.clone();
        if (!Arrays.equals(prefix, Arrays.copyOf(keyBytes, prefix.length))
                || profile == OtaSignatureProfile.ED25519_V1 && !hasNondegenerateEd25519Key(keyBytes)) return false;
        try {
            if (!MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(keyBytes),
                    HexFormat.of().parseHex(fingerprint))) return false;
            PublicKey key = KeyFactory.getInstance(profile == OtaSignatureProfile.ED25519_V1 ? "Ed25519" : "EC")
                    .generatePublic(new X509EncodedKeySpec(keyBytes));
            if (profile == OtaSignatureProfile.ES256_P1363_V1 && !validEcPoint((ECPublicKey) key)) return false;
            Signature verifier = Signature.getInstance(profile == OtaSignatureProfile.ED25519_V1
                    ? "Ed25519" : "SHA256withECDSAinP1363Format");
            verifier.initVerify(key);
            return true;
        } catch (GeneralSecurityException | IllegalArgumentException exception) { return false; }
    }

    /** JDK的EC initVerify不检查曲线成员资格，故配置入库前显式检查有限坐标与曲线方程。 */
    private static boolean validEcPoint(ECPublicKey key) {
        BigInteger x = key.getW().getAffineX();
        BigInteger y = key.getW().getAffineY();
        if (!(key.getParams().getCurve().getField() instanceof ECFieldFp field) || x == null || y == null) return false;
        BigInteger p = field.getP();
        if (x.signum() < 0 || y.signum() < 0 || x.compareTo(p) >= 0 || y.compareTo(p) >= 0) return false;
        BigInteger a = key.getParams().getCurve().getA();
        BigInteger b = key.getParams().getCurve().getB();
        return y.multiply(y).mod(p).equals(x.multiply(x).multiply(x).add(a.multiply(x)).add(b).mod(p));
    }

    /** 唯一域分离构造入口，签名请求与本地验签必须使用完全相同的前缀及规范字节。 */
    static byte[] signingInput(byte[] canonical) {
        byte[] input = Arrays.copyOf(DOMAIN, Math.addExact(DOMAIN.length, canonical.length));
        System.arraycopy(canonical, 0, input, DOMAIN.length, canonical.length);
        return input;
    }

    /** RFC8032规范坐标与低阶点拒绝补齐JDK允许单位元验签的边界。 */
    private static boolean hasNondegenerateEd25519Key(byte[] spki) {
        byte[] yBytes = new byte[32];
        // RFC8032公钥为小端y，最高位是x符号；两种符号的低阶点都必须拒绝。
        for (int i = 0; i < 32; i++) {
            yBytes[i] = spki[spki.length - 1 - i];
        }
        yBytes[0] &= 0x7f;
        BigInteger y = new BigInteger(1, yBytes);
        return y.compareTo(BigInteger.ONE) > 0
                && y.compareTo(ED25519_FIELD.subtract(BigInteger.ONE)) < 0
                && !y.equals(ED25519_ORDER_EIGHT_Y[0]) && !y.equals(ED25519_ORDER_EIGHT_Y[1]);
    }

    /** 按ADR0053第2节同时拒绝零值、越界标量和非low-S表示。 */
    private static boolean hasCanonicalScalars(byte[] signature) {
        BigInteger r = new BigInteger(1, Arrays.copyOfRange(signature, 0, 32));
        BigInteger s = new BigInteger(1, Arrays.copyOfRange(signature, 32, 64));
        return r.signum() > 0 && r.compareTo(P256_ORDER) < 0
                && s.signum() > 0 && s.compareTo(P256_ORDER.shiftRight(1)) <= 0;
    }
}
