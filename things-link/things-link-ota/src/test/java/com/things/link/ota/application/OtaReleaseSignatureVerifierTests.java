package com.things.link.ota.application;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR0053发布签名边界的独立公开黄金向量与失败反例。
 *
 * <p>向量由OpenSSL 3.6.3离线genpkey/pkey生成公钥，Ed25519使用pkeyutl -sign -rawin，
 * P-256使用dgst -sha256 -sign后独立解析DER并将s映射为min(s,n-s)，转成r||s。
 * 消息为下列固定UTF-8文本加协议零结尾域前缀；withoutDomain仅签文本。
 * 一次性私钥在生成后随临时目录删除，仓库仅保留公开公钥、签名与指纹；
 * 测试不调用Java签名实现生成预期值，也不宣称该最小文本满足完整manifest字段合同。</p>
 */
class OtaReleaseSignatureVerifierTests {
    /** 黄金向量消息刻意只测字节合同，不替代字段验证。 */
    private static final byte[] MANIFEST =
            "{\"contractVersion\":\"tc-ota-manifest/v1\",\"firmwareVersion\":\"test-only\"}"
                    .getBytes(StandardCharsets.UTF_8);
    /** 每个测试使用无状态验签边界。 */
    private final OtaReleaseSignatureVerifier verifier = new OtaReleaseSignatureVerifier();
    /** OpenSSL独立生成的公开测试数据，不含生产凭据。 */
    private static final byte[] ED_KEY = hex(
            "302a300506032b65700321005addcf6a0cbb876aa3ab64e71a98aa0ff4a36433c64e4c0b721667c37a38126a");
    /** OpenSSL独立生成的公开测试数据，不含生产凭据。 */
    private static final String ED_FINGERPRINT = "b9577f2135b7e472cde6d6ae985b6f3e5a810494d14b52dbf70e4af2f36652ea";
    /** OpenSSL独立生成的公开测试数据，不含生产凭据。 */
    private static final byte[] ED_SIGNATURE = hex(
            "e551278bcb440c4e90261780a13e7dbd5913c2194c574569c75c96c0e218d3c37a29779080d34ede76a213426229b0b8da43a0e3399d2a5414b9a9a98bfa4201");
    /** OpenSSL独立生成的公开测试数据，不含生产凭据。 */
    private static final byte[] ED_BARE_SIGNATURE = hex(
            "91de0d44375f1186fc77f343afccf73aaf15a7b55d3c2b8677c345205b8855ffc3e9cf1bda2ae29740a1e32c0654ef144f865fb3288ec26d9a8766328bf7d70b");
    /** OpenSSL独立生成的公开测试数据，不含生产凭据。 */
    private static final byte[] EC_KEY = hex(
            "3059301306072a8648ce3d020106082a8648ce3d03010703420004274356347325e51dce1abc489d0a6b760c68f44313565522db5e34e8d019f1cebcdccc1368bcbc9978e1b26bdc887eadfde4b485067f544aa8aaaa807489888f");
    /** OpenSSL独立生成的公开测试数据，不含生产凭据。 */
    private static final String EC_FINGERPRINT = "103701ac86bb5311b6b4ef715a7cbefdf2f180f2dc62d996a334d9178e22c492";
    /** OpenSSL独立生成的公开测试数据，不含生产凭据。 */
    private static final byte[] EC_SIGNATURE = hex(
            "e7d9f370e9e447273fbf4561bcb0d130847a125634d66427842104e079c154e5647cacf7887403dc93ca34f6a096f0ae546add88d0b408ecf422e08cadb24142");
    /** OpenSSL独立生成的公开测试数据，不含生产凭据。 */
    private static final byte[] EC_BARE_SIGNATURE = hex(
            "c083279e44cbd365896e2d7fed71e5c2748eb2ae6820addc86e6c9a191a9a75f55b08b2af407f9f63bf77cd33701bac70c3e89808cf777333b0e50c57085470d");
    /** OpenSSL独立生成的公开测试数据，不含生产凭据。 */
    private static final byte[] P384_KEY = hex(
            "3076301006072a8648ce3d020106052b81040022036200045ab5557e361b9f5e31af58d9cd45f351376a04b688ff75674e2a4d3573ec77578c3847dd42fefd8c2f52c9a07890ec374a7b799c53bd32726af2fa6adf6a8575afdec9d360e2ecba9af0c7103d5d88d5e8166ff662ca0b158b9da996ba0da944");
    /** OpenSSL独立生成的公开测试数据，不含生产凭据。 */
    private static final String P384_FINGERPRINT = "52e9622146f49da1119d848d2663d5ae79d3ec7418baac35510503876bbf8034";

    /** 两个Profile分别验证独立工具生成的固定签名。 */
    @Test
    void acceptsIndependentGoldenVectors() {
        assertThat(verifyEd(ED_KEY, ED_FINGERPRINT, MANIFEST, ED_SIGNATURE)).isTrue();
        assertThat(verifyEc(EC_KEY, EC_FINGERPRINT, MANIFEST, EC_SIGNATURE)).isTrue();
    }

    /** 裸manifest即使由同一合法密钥签名也必须被域分离拒绝。 */
    @Test
    void rejectsSignaturesWithoutDomain() {
        assertThat(verifyEd(ED_KEY, ED_FINGERPRINT, MANIFEST, ED_BARE_SIGNATURE)).isFalse();
        assertThat(verifyEc(EC_KEY, EC_FINGERPRINT, MANIFEST, EC_BARE_SIGNATURE)).isFalse();
    }

    /** 字节篡改、换密钥Profile与错误指纹不能复用黄金向量。 */
    @Test
    void rejectsTamperingAndMismatchedIdentity() {
        byte[] changedManifest = MANIFEST.clone();
        changedManifest[changedManifest.length - 3] ^= 1;
        assertThat(verifyEd(ED_KEY, ED_FINGERPRINT, changedManifest, ED_SIGNATURE)).isFalse();
        assertThat(verifyEc(EC_KEY, EC_FINGERPRINT, changedManifest, EC_SIGNATURE)).isFalse();
        byte[] changedSignature = ED_SIGNATURE.clone();
        changedSignature[0] ^= 1;
        assertThat(verifyEd(ED_KEY, ED_FINGERPRINT, MANIFEST, changedSignature)).isFalse();
        changedSignature = EC_SIGNATURE.clone();
        changedSignature[0] ^= 1;
        assertThat(verifyEc(EC_KEY, EC_FINGERPRINT, MANIFEST, changedSignature)).isFalse();
        assertThat(verifyEd(EC_KEY, EC_FINGERPRINT, MANIFEST, ED_SIGNATURE)).isFalse();
        assertThat(verifyEc(ED_KEY, ED_FINGERPRINT, MANIFEST, EC_SIGNATURE)).isFalse();
        assertThat(verifyEc(P384_KEY, P384_FINGERPRINT, MANIFEST, EC_SIGNATURE)).isFalse();
        assertThat(verifyEd(ED_KEY, EC_FINGERPRINT, MANIFEST, ED_SIGNATURE)).isFalse();
    }

    /** 同一合法ECDSA签名的n-s等价形式也必须失败，不能仅依赖JCA验签结果。 */
    @Test
    void rejectsHighSAndOutOfRangeScalars() {
        BigInteger order = new BigInteger(
                "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16);
        BigInteger s = new BigInteger(1, Arrays.copyOfRange(EC_SIGNATURE, 32, 64));
        byte[] highS = EC_SIGNATURE.clone();
        copyScalar(order.subtract(s), highS, 32);
        assertThat(verifyEc(EC_KEY, EC_FINGERPRINT, MANIFEST, highS)).isFalse();
        for (int offset : new int[]{0, 32}) {
            for (BigInteger scalar : new BigInteger[]{BigInteger.ZERO, order, order.add(BigInteger.ONE)}) {
                byte[] invalid = EC_SIGNATURE.clone();
                copyScalar(scalar, invalid, offset);
                assertThat(verifyEc(EC_KEY, EC_FINGERPRINT, MANIFEST, invalid)).isFalse();
            }
        }
    }

    /** 指纹必须匹配完整、精确SPKI；即使重新计算指纹也不接受畸形编码。 */
    @Test
    void rejectsMalformedKeysEvenWithMatchingFingerprint() throws Exception {
        byte[] trailing = Arrays.copyOf(ED_KEY, ED_KEY.length + 1);
        byte[] nullParameters = hex("302c300706032b65700500032100");
        nullParameters = Arrays.copyOf(nullParameters, nullParameters.length + 32);
        System.arraycopy(ED_KEY, ED_KEY.length - 32, nullParameters, nullParameters.length - 32, 32);
        for (byte[] malformed : new byte[][]{trailing, nullParameters, new byte[44]}) {
            assertThat(verifyEd(malformed, fingerprint(malformed), MANIFEST, ED_SIGNATURE)).isFalse();
        }
        byte[] invalidPoint = EC_KEY.clone();
        Arrays.fill(invalidPoint, invalidPoint.length - 64, invalidPoint.length, (byte) 0);
        assertThat(verifyEc(invalidPoint, fingerprint(invalidPoint), MANIFEST, EC_SIGNATURE)).isFalse();
        byte[] wrongOid = EC_KEY.clone();
        wrongOid[22] ^= 1;
        assertThat(verifyEc(wrongOid, fingerprint(wrongOid), MANIFEST, EC_SIGNATURE)).isFalse();
    }

    /** 空值、错误长度与非小写完整指纹统一失败关闭，不抛出外部输入异常。 */
    @Test
    void rejectsMissingOrMalformedInputs() {
        assertThat(verifier.verify(null, ED_KEY, ED_FINGERPRINT, MANIFEST, ED_SIGNATURE)).isFalse();
        assertThat(verifyEd(null, ED_FINGERPRINT, MANIFEST, ED_SIGNATURE)).isFalse();
        assertThat(verifyEd(ED_KEY, ED_FINGERPRINT, null, ED_SIGNATURE)).isFalse();
        assertThat(verifyEd(ED_KEY, ED_FINGERPRINT, new byte[0], ED_SIGNATURE)).isFalse();
        for (String fingerprint : new String[]{null, "", ED_FINGERPRINT.toUpperCase(),
                ED_FINGERPRINT.substring(1), "g".repeat(64), " " + ED_FINGERPRINT}) {
            assertThat(verifyEd(ED_KEY, fingerprint, MANIFEST, ED_SIGNATURE)).isFalse();
        }
        for (byte[] signature : new byte[][]{null, new byte[0], new byte[63], new byte[65], new byte[72]}) {
            assertThat(verifyEd(ED_KEY, ED_FINGERPRINT, MANIFEST, signature)).isFalse();
            assertThat(verifyEc(EC_KEY, EC_FINGERPRINT, MANIFEST, signature)).isFalse();
        }
    }

    /** Ed25519单位元公钥不能借助零标量构造无私钥的伪造签名。 */
    @Test
    void rejectsDegenerateEd25519Points() throws Exception {
        // libsodium 1.0.18公开低阶编码，覆盖两种符号和p/p+1非规范别名。
        String[] encodings = {
            "00".repeat(32), "01" + "00".repeat(31),
            "26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05",
            "c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac037a",
            "ec" + "ff".repeat(30) + "7f", "ed" + "ff".repeat(30) + "7f",
            "ee" + "ff".repeat(30) + "7f", "ff".repeat(31) + "7f"
        };
        byte[] forged = new byte[64];
        forged[0] = 1;
        for (String encoding : encodings) {
            for (int sign : new int[]{0, 128}) {
                byte[] key = ED_KEY.clone();
                byte[] point = hex(encoding);
                point[31] |= (byte) sign;
                System.arraycopy(point, 0, key, key.length - 32, 32);
                assertThat(verifyEd(key, fingerprint(key), MANIFEST, forged)).isFalse();
            }
        }
    }

    /** 同一有效r/s的ASN.1 DER形式仍不满足固定64字节P1363合同。 */
    @Test
    void rejectsDerEncodingOfValidEcdsaSignature() {
        byte[] der = hex(
                "3045022100e7d9f370e9e447273fbf4561bcb0d130847a125634d66427842104e079c154e50220647cacf7887403dc93ca34f6a096f0ae546add88d0b408ecf422e08cadb24142");
        assertThat(verifyEc(EC_KEY, EC_FINGERPRINT, MANIFEST, der)).isFalse();
    }

    /** 固定Ed25519 Profile，避免测试隐藏算法选择。 */
    private boolean verifyEd(byte[] key, String fingerprint, byte[] manifest, byte[] signature) {
        return verifier.verify(OtaSignatureProfile.ED25519_V1, key, fingerprint, manifest, signature);
    }

    /** 固定P-256 Profile，避免测试隐藏算法选择。 */
    private boolean verifyEc(byte[] key, String fingerprint, byte[] manifest, byte[] signature) {
        return verifier.verify(OtaSignatureProfile.ES256_P1363_V1, key, fingerprint, manifest, signature);
    }

    /** 畸形公钥重新计算完整指纹，确保失败来自编码而非摘要不匹配。 */
    private static String fingerprint(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** 将无符号标量填入固定32字节P1363槽位。 */
    private static void copyScalar(BigInteger value, byte[] target, int offset) {
        byte[] encoded = value.toByteArray();
        Arrays.fill(target, offset, offset + 32, (byte) 0);
        int length = Math.min(encoded.length, 32);
        System.arraycopy(encoded, encoded.length - length, target, offset + 32 - length, length);
    }

    /** 解码固定公开测试向量。 */
    private static byte[] hex(String value) {
        return HexFormat.of().parseHex(value);
    }
}
