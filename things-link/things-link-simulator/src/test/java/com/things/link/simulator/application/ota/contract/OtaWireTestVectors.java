package com.things.link.simulator.application.ota.contract;

import com.things.link.ota.application.OtaDownloadResponseCodec;
import com.things.link.ota.application.OtaManifestCodec;
import com.things.link.ota.application.OtaSignatureProfile;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 跨模块测试向量：只负责造出「平台一定接受」的输入，让线格式比较有意义。
 *
 * <p>这里刻意使用平台自己的 {@link OtaManifestCodec} 生成清单，并用 JDK 的 Ed25519 现签一次：
 * 只有真签名才能让平台解码器走完整个校验路径，否则「设备发出的字节平台能解开」会被一个
 * 假签名轻易证伪。</p>
 *
 * <p>私钥在测试内即时生成、不落盘，也不进入任何生产代码。</p>
 */
public final class OtaWireTestVectors {

    /** 响应截止时刻固定，保证字节可复现。 */
    public static final Instant EXPIRES_AT = Instant.parse("2026-09-12T12:00:00Z");

    /** 发布清单签名的域分离前缀，取自 ADR0053 第 2 节固定值。 */
    public static final String SIGNATURE_DOMAIN = "thingslink-ota-release-manifest-v1\u0000";

    /** 工具类不允许实例化。 */
    private OtaWireTestVectors() {
    }

    /**
     * 生成确定性 artifact。
     *
     * @param size 字节数
     * @return 可重复的字节序列
     */
    public static byte[] artifact(int size) {
        byte[] bytes = new byte[size];
        for (int index = 0; index < size; index++) {
            bytes[index] = (byte) ((index * 31 + 7) & 0xFF);
        }
        return bytes;
    }

    /**
     * 计算 SHA-256 小写十六进制。
     *
     * @param bytes 待摘要字节
     * @return 小写十六进制摘要
     */
    public static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行时不提供 SHA-256", exception);
        }
    }

    /**
     * 为一组 artifact 字节生成真实的 Ed25519 签名清单。
     *
     * @param artifact 目标 artifact 字节
     * @return 规范清单、摘要与签名材料
     * @throws GeneralSecurityException 运行时不提供 Ed25519 时
     */
    public static SignedManifest signedManifest(byte[] artifact) throws GeneralSecurityException {
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] publicKeySpki = keyPair.getPublic().getEncoded();
        String keyFingerprint = sha256Hex(publicKeySpki);
        UUID firmwareId = UUID.randomUUID();
        byte[] canonical = new OtaManifestCodec()
                .canonicalize(new OtaCanonicalJson().writeObject(manifest(firmwareId, artifact, keyFingerprint)));
        Signature signing = Signature.getInstance("Ed25519");
        signing.initSign(keyPair.getPrivate());
        signing.update(SIGNATURE_DOMAIN.getBytes(StandardCharsets.UTF_8));
        signing.update(canonical);
        return new SignedManifest(firmwareId, canonical, sha256Hex(canonical), signing.sign(), publicKeySpki,
                keyFingerprint);
    }

    /**
     * 用已签名清单造一份下载响应；设备侧与平台侧使用完全相同的字段值。
     *
     * @param manifest 已签名清单
     * @param requestId 设备发出的请求标识
     * @param jobId 平台作业标识
     * @param attemptNo 尝试号
     * @param downloadUrl 下载地址
     * @return 两份等值投影与共同身份
     */
    public static DownloadPair downloadResponse(SignedManifest manifest, UUID requestId, UUID jobId, int attemptNo,
                                                URI downloadUrl) {
        return downloadResponse(manifest, requestId, jobId, attemptNo, downloadUrl, false);
    }

    /**
     * 用已签名清单造一份下载响应，并显式声明是否允许本机回环明文地址。
     *
     * <p>两侧编解码器必须使用同一个地址策略：本地真实 HTTP 测试服务器只能绑定 {@code http://127.0.0.1}，
     * 因此平台侧编码器也必须被显式允许回环例外，否则「平台解码不了设备收到的字节」会被误读成设备侧
     * 合同错误。</p>
     *
     * @param manifest 已签名清单
     * @param requestId 设备发出的请求标识
     * @param jobId 平台作业标识
     * @param attemptNo 尝试号
     * @param downloadUrl 下载地址
     * @param allowInsecureLoopback 是否允许本机回环明文地址
     * @return 两份等值投影与共同身份
     */
    public static DownloadPair downloadResponse(SignedManifest manifest, UUID requestId, UUID jobId, int attemptNo,
                                                URI downloadUrl, boolean allowInsecureLoopback) {
        UUID authorizationId = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();
        OtaDeviceDownloadResponseCodec.Response device = new OtaDeviceDownloadResponseCodec.Response(
                OtaDeviceDownloadResponseCodec.CONTRACT_VERSION, authorizationId, requestId, campaignId, jobId,
                manifest.firmwareId(), attemptNo, manifest.sha256(), manifest.canonical(), manifest.signature(),
                manifest.publicKeySpki(), OtaSignatureProfile.ED25519_V1.value(), manifest.keyFingerprint(),
                downloadUrl, EXPIRES_AT);
        OtaDownloadResponseCodec.Response platform = new OtaDownloadResponseCodec.Response(
                "tc-ota-download-response/v1", authorizationId, requestId, campaignId, jobId, manifest.firmwareId(),
                attemptNo, manifest.sha256(), manifest.canonical(), manifest.signature(),
                manifest.publicKeySpki(), OtaSignatureProfile.ED25519_V1, manifest.keyFingerprint(), downloadUrl,
                EXPIRES_AT);
        return new DownloadPair(device, platform, authorizationId, campaignId, allowInsecureLoopback);
    }

    /**
     * 构造阶段相符的十九字段证据。
     *
     * <p>取值遵循 ADR0131：VERIFYING 为 NOT_STARTED 与三个 false；INSTALLING/REBOOTING 为 PASSED
     * 与三个 false；HEALTH_CHECKING 为 PASSED 与三个 true，且活动槽切到目标槽。</p>
     *
     * @param stage 平台闭集内的阶段名
     * @param artifactSha256 目标 artifact 摘要
     * @param artifactSize 目标 artifact 尺寸
     * @return 该阶段的证据
     */
    public static OtaDeviceJobProgressCodec.Evidence evidenceForStage(String stage, String artifactSha256,
                                                                      long artifactSize) {
        boolean passed = !"VERIFYING".equals(stage);
        boolean healthy = "HEALTH_CHECKING".equals(stage);
        String activeSlot = healthy ? "B" : "A";
        return new OtaDeviceJobProgressCodec.Evidence(artifactSha256, artifactSize, 1L, 1L,
                UUID.randomUUID(), "PG_JSONB_TEXT_V1_SHA256", sha256Hex("schema".getBytes(StandardCharsets.UTF_8)),
                "TC_PROPERTY_COMPOSITE_V1", "trust.example", sha256Hex("root".getBytes(StandardCharsets.UTF_8)),
                1L, sha256Hex("bundle".getBytes(StandardCharsets.UTF_8)), "A", "B", activeSlot,
                passed ? "PASSED" : "NOT_STARTED", healthy, healthy, healthy);
    }

    /**
     * 构造<b>已提交</b>证据：与健康阶段同一份目标元组，但 {@code committedSecurityVersion} 等于
     * {@code securityVersion}。
     *
     * <p>提交回执不是预提交进度：平台 {@code OtaCommitReceiptCodec} 的消费方要求
     * {@code committedSecurityVersion == securityVersion}，而预提交进度保留原来源下限。
     * 这里显式提供一份「设备已提交」的向量，避免测试用错证据形态。</p>
     *
     * @param artifactSha256 目标 artifact 摘要
     * @param artifactSize 目标 artifact 尺寸
     * @return 已提交阶段（健康形态）的十九字段证据
     */
    public static OtaDeviceJobProgressCodec.Evidence committedEvidence(String artifactSha256, long artifactSize) {
        OtaDeviceJobProgressCodec.Evidence target = evidenceForStage("HEALTH_CHECKING", artifactSha256, artifactSize);
        return new OtaDeviceJobProgressCodec.Evidence(target.artifactSha256(), target.artifactSize(),
                target.securityVersion(), target.securityVersion(), target.thingModelVersionId(),
                target.thingModelSchemaDigestAlgorithm(), target.thingModelSchemaDigest(), target.propertyProfile(),
                target.trustDomain(), target.rootFingerprint(), target.trustBundleVersion(),
                target.trustBundleSha256(), target.sourceSlot(), target.targetSlot(), target.activeSlot(),
                target.verification(), target.bootVerified(), target.selfTestPassed(), target.watchdogHealthy());
    }

    /**
     * 构造停止证据：硬件、停止基线与一条已接纳停止日志，不含安装日志。
     *
     * @param operationId 停止操作身份
     * @param operationSha256 停止操作摘要
     * @param acceptedBootId 接受停止时的启动身份
     * @return 停止证据
     */
    public static OtaDeviceInstallStopReportCodec.Evidence stopEvidence(UUID operationId, String operationSha256,
                                                                        UUID acceptedBootId) {
        return new OtaDeviceInstallStopReportCodec.Evidence(
                sha256Hex("baseline".getBytes(StandardCharsets.UTF_8)), "1.0.0",
                new OtaDeviceInstallStopReportCodec.Hardware("TC-SIM-01", 3L),
                OtaDeviceInstallStopReportCodec.ATOMIC_OPERATION_PROFILE, 7L, "QUIESCENT",
                List.of(new OtaDeviceInstallStopReportCodec.StopOperation(operationId, operationSha256,
                        acceptedBootId, 5L, "STOPPED")),
                List.of());
    }

    /** 构造平台清单全部必填字段；字段值全部满足 {@link OtaManifestCodec} 的闭集校验。 */
    private static Map<String, Object> manifest(UUID firmwareId, byte[] artifact, String keyFingerprint) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("contractVersion", "tc-ota-manifest/v1");
        manifest.put("firmwareId", firmwareId.toString());
        manifest.put("firmwareVersion", "1.2.3");
        manifest.put("trustDomain", "trust.example");
        manifest.put("deviceTypeId", UUID.randomUUID().toString());
        manifest.put("productKey", "product-key");
        Map<String, Object> hardware = new LinkedHashMap<>();
        hardware.put("model", "TC-SIM-01");
        hardware.put("boardRevisionMin", 1L);
        hardware.put("boardRevisionMax", 4L);
        manifest.put("hardware", hardware);
        manifest.put("bootloaderMinimumVersion", "1.0.0");
        manifest.put("artifactSize", (long) artifact.length);
        manifest.put("artifactSha256", sha256Hex(artifact));
        manifest.put("compression", "NONE");
        manifest.put("delta", Map.of("mode", "NONE"));
        manifest.put("securityVersion", 1L);
        manifest.put("thingModelVersionId", UUID.randomUUID().toString());
        manifest.put("thingModelSchemaDigestAlgorithm", "PG_JSONB_TEXT_V1_SHA256");
        manifest.put("thingModelSchemaDigest", sha256Hex("schema".getBytes(StandardCharsets.UTF_8)));
        manifest.put("allowedSourceThingModelVersionIds", List.of(UUID.randomUUID().toString()));
        Map<String, Object> requirements = new LinkedHashMap<>();
        requirements.put("profile", "TC_PROPERTY_COMPOSITE_V1");
        requirements.put("minimumRamBytes", 65_536L);
        requirements.put("minimumFlashBytes", 262_144L);
        requirements.put("requiresAbSlots", true);
        requirements.put("requiresRangeDownload", true);
        requirements.put("requiresProtectedSecurityCounter", true);
        manifest.put("requirements", requirements);
        manifest.put("signatureProfile", OtaSignatureProfile.ED25519_V1.value());
        manifest.put("signingKeyFingerprint", keyFingerprint);
        manifest.put("minimumTrustBundleVersion", 1L);
        return manifest;
    }

    /**
     * 一份真实的 Ed25519 签名清单。
     *
     * @param firmwareId 固件身份
     * @param canonical 规范清单字节
     * @param sha256 规范清单摘要
     * @param signature 64 字节 Ed25519 签名
     * @param publicKeySpki 完整 SPKI 公钥
     * @param keyFingerprint 公钥指纹
     */
    public record SignedManifest(UUID firmwareId, byte[] canonical, String sha256, byte[] signature,
                                 byte[] publicKeySpki, String keyFingerprint) {

        /** 冻结可变数组。 */
        public SignedManifest {
            canonical = canonical.clone();
            signature = signature.clone();
            publicKeySpki = publicKeySpki.clone();
        }

        /**
         * @return 独立清单副本
         */
        @Override
        public byte[] canonical() {
            return canonical.clone();
        }

        /**
         * @return 独立签名副本
         */
        @Override
        public byte[] signature() {
            return signature.clone();
        }

        /**
         * @return 独立公钥副本
         */
        @Override
        public byte[] publicKeySpki() {
            return publicKeySpki.clone();
        }
    }

    /**
     * 同一份下载响应的设备侧与平台侧等值投影。
     *
     * @param device 设备侧记录
     * @param platform 平台侧记录
     * @param authorizationId 授权身份
     * @param campaignId 活动身份
     * @param allowInsecureLoopback 本向量是否声明了本机回环明文地址
     */
    public record DownloadPair(OtaDeviceDownloadResponseCodec.Response device,
                               OtaDownloadResponseCodec.Response platform, UUID authorizationId, UUID campaignId,
                               boolean allowInsecureLoopback) {
    }
}
