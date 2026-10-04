package com.things.link.simulator.application.ota.contract;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 设备侧下载响应合同编解码：{@code tc-ota-download-response/v1}，十五字段秘密报文。
 *
 * <p>字段与平台 {@code OtaDownloadResponseCodec} 完全一致，规范字节逐字节可比。本类只证明
 * 「设备能按平台的线格式读写」，不证明任何信任事实：</p>
 *
 * <ul>
 *   <li><b>不验签。</b>真实验签需要设备持有的信任锚与键状态；模拟器没有信任锚，也不应把
 *       「解析成功」伪装成「发布可信」。因此本类只做结构、闭集与跨字段一致性校验，
 *       把签名原文（{@code signatureBase64}/{@code publicKeySpkiBase64}/{@code keyFingerprint}）
 *       原样保留给后续真正的验签切片。</li>
 *   <li><b>不裁决清单语义。</b>平台解码会用 {@code OtaManifestCodec} 校验清单全部字段；模拟器
 *       不复刻平台清单 Schema（那是平台内部策略），只要求清单是规范 JSON 且外层声明的
 *       firmwareId/signatureProfile/keyFingerprint 与清单内部一致——这正是平台解码器同样强制的
 *       跨字段事实。</li>
 *   <li><b>不泄露地址。</b>{@link Response#toString()} 与平台一样不输出临时下载地址。</li>
 * </ul>
 *
 * <p>与平台一致的地址策略：默认只接受 HTTPS；只有显式开启回环例外时才接受
 * {@code http://localhost|127.0.0.1|[::1]}，用于本机受控联调。</p>
 */
public final class OtaDeviceDownloadResponseCodec {

    /** 冻结合同版本。 */
    public static final String CONTRACT_VERSION = "tc-ota-download-response/v1";

    /** 固定闭集，禁止向设备泄露额外服务字段。 */
    private static final Set<String> FIELDS = Set.of("contractVersion", "authorizationId", "requestId",
            "campaignId", "jobId", "firmwareId", "attemptNo", "manifestSha256", "manifest", "signatureBase64",
            "publicKeySpkiBase64", "signatureProfile", "keyFingerprint", "downloadUrl", "expiresAt");

    /** 平台冻结的签名 Profile 白名单；不接受任意 JCA 算法名。 */
    private static final Set<String> PROFILES = Set.of("TC_OTA_ED25519_V1", "TC_OTA_ES256_P1363_V1");

    /** 精确回环开发例外允许的主机名。 */
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]");

    /** 唯一规范 JSON 实现。 */
    private static final OtaCanonicalJson JSON = new OtaCanonicalJson();

    /** 是否允许显式回环明文地址。 */
    private final boolean allowInsecureLoopback;

    /** 默认只允许 HTTPS。 */
    public OtaDeviceDownloadResponseCodec() {
        this(false);
    }

    /**
     * @param allowInsecureLoopback 是否允许本机回环明文下载地址（仅受控测试）
     */
    public OtaDeviceDownloadResponseCodec(boolean allowInsecureLoopback) {
        this.allowInsecureLoopback = allowInsecureLoopback;
    }

    /**
     * 把类型化响应编码为平台可解码的规范字节。
     *
     * <p>调用方必须提供<b>已规范</b>的清单字节与真实签名材料；非规范清单会在这里失败关闭，
     * 与平台编码器「禁止静默修正输入 manifest」的行为一致。</p>
     *
     * @param value 下载响应
     * @return 规范 UTF-8 字节
     * @throws IllegalArgumentException 清单非规范、字段缺失、地址不安全或跨字段不一致时
     */
    public byte[] encode(Response value) {
        return decode(JSON.writeObject(toMap(value))).canonical();
    }

    /**
     * 严格解析下载响应。
     *
     * @param input 收到的原始 UTF-8 字节
     * @return 类型化响应、规范字节与规范摘要
     * @throws IllegalArgumentException 长度、闭集、版本、编码、地址或跨字段一致性不符时
     */
    public Decoded decode(byte[] input) {
        try {
            if (input == null || input.length == 0) {
                throw OtaContractFields.invalid();
            }
            Map<String, Object> fields = JSON.parseObject(input);
            OtaContractFields.closed(fields, FIELDS);
            if (!CONTRACT_VERSION.equals(fields.get("contractVersion"))) {
                throw OtaContractFields.invalid();
            }
            Map<String, Object> manifest = OtaContractFields.object(fields.get("manifest"));
            byte[] canonicalManifest = JSON.writeObject(manifest);
            String manifestSha256 = OtaContractFields.hex(fields, "manifestSha256");
            String keyFingerprint = OtaContractFields.hex(fields, "keyFingerprint");
            String profile = OtaContractFields.literal(fields, "signatureProfile", PROFILES);
            UUID firmwareId = OtaContractFields.uuid(fields, "firmwareId");
            byte[] signature = OtaContractFields.base64(fields.get("signatureBase64"), 64);
            byte[] publicKeySpki = OtaContractFields.base64(fields.get("publicKeySpkiBase64"), 91);
            // 与平台解码器相同的跨字段一致性；签名密码学验证留待真正的信任锚切片。
            if (!manifestSha256.equals(OtaContractFields.sha256Hex(canonicalManifest))
                    || !firmwareId.toString().equals(manifest.get("firmwareId"))
                    || !profile.equals(manifest.get("signatureProfile"))
                    || !keyFingerprint.equals(manifest.get("signingKeyFingerprint"))) {
                throw OtaContractFields.invalid();
            }
            Response response = new Response(CONTRACT_VERSION,
                    OtaContractFields.uuid(fields, "authorizationId"),
                    OtaContractFields.uuid(fields, "requestId"),
                    OtaContractFields.uuid(fields, "campaignId"),
                    OtaContractFields.uuid(fields, "jobId"),
                    firmwareId,
                    OtaContractFields.intValue(fields, "attemptNo", 1, Integer.MAX_VALUE),
                    manifestSha256,
                    canonicalManifest,
                    signature,
                    publicKeySpki,
                    profile,
                    keyFingerprint,
                    url(fields.get("downloadUrl")),
                    instant(fields.get("expiresAt")));
            byte[] canonical = JSON.writeObject(fields);
            OtaContractFields.requireCanonical(input, canonical);
            return new Decoded(response, canonical, OtaContractFields.sha256Hex(canonical));
        } catch (RuntimeException exception) {
            throw OtaContractFields.invalid();
        }
    }

    /** 仅输出明确白名单字段；清单必须是已规范 JSON。 */
    private Map<String, Object> toMap(Response input) {
        if (input == null) {
            throw OtaContractFields.invalid();
        }
        byte[] manifestBytes = input.manifest();
        Map<String, Object> manifest = JSON.parseObject(manifestBytes);
        if (!Arrays.equals(JSON.writeObject(manifest), manifestBytes)) {
            throw OtaContractFields.invalid();
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("contractVersion", input.contractVersion());
        value.put("authorizationId", String.valueOf(input.authorizationId()));
        value.put("requestId", String.valueOf(input.requestId()));
        value.put("campaignId", String.valueOf(input.campaignId()));
        value.put("jobId", String.valueOf(input.jobId()));
        value.put("firmwareId", String.valueOf(input.firmwareId()));
        value.put("attemptNo", (long) input.attemptNo());
        value.put("manifestSha256", input.manifestSha256());
        value.put("manifest", manifest);
        value.put("signatureBase64", base64(input.signature()));
        value.put("publicKeySpkiBase64", base64(input.publicKeySpki()));
        value.put("signatureProfile", input.signatureProfile());
        value.put("keyFingerprint", input.keyFingerprint());
        value.put("downloadUrl", String.valueOf(input.downloadUrl()));
        value.put("expiresAt", String.valueOf(input.expiresAt()));
        return value;
    }

    /** 标准 Base64 编码，禁止 null 材料。 */
    private static String base64(byte[] material) {
        if (material == null) {
            throw OtaContractFields.invalid();
        }
        return Base64.getEncoder().encodeToString(material);
    }

    /**
     * 校验临时地址：绝对、有主机、无用户信息、无 fragment；HTTPS 之外只允许显式回环例外。
     *
     * @param value 待校验值
     * @return 校验通过的地址
     */
    private URI url(Object value) {
        if (!(value instanceof String text) || text.getBytes(StandardCharsets.UTF_8).length > 8192) {
            throw OtaContractFields.invalid();
        }
        URI uri = URI.create(text);
        if (!uri.isAbsolute() || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
            throw OtaContractFields.invalid();
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return uri;
        }
        if (allowInsecureLoopback && "http".equalsIgnoreCase(uri.getScheme())
                && LOOPBACK_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT))) {
            return uri;
        }
        throw OtaContractFields.invalid();
    }

    /**
     * 只接受四位年份规范 UTC 且不超过微秒的时间，不在纯合同中刷新期限。
     *
     * @param value 待校验值
     * @return 校验通过的时刻
     */
    private static Instant instant(Object value) {
        if (!(value instanceof String text)
                || !text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,6})?Z")) {
            throw OtaContractFields.invalid();
        }
        Instant parsed = Instant.parse(text);
        if (!parsed.toString().equals(text)) {
            throw OtaContractFields.invalid();
        }
        return parsed;
    }

    /** 完整秘密响应，数组只保存规范材料。
     *
     * @param contractVersion 固定合同
     * @param authorizationId 授权身份
     * @param requestId 请求身份
     * @param campaignId 活动身份
     * @param jobId 作业身份
     * @param firmwareId 固件身份
     * @param attemptNo 尝试号
     * @param manifestSha256 清单摘要
     * @param manifest 规范清单字节
     * @param signature 发布签名
     * @param publicKeySpki 完整公钥
     * @param signatureProfile 签名协议
     * @param keyFingerprint 公钥指纹
     * @param downloadUrl 临时秘密地址
     * @param expiresAt 绝对截止
     */
    public record Response(String contractVersion, UUID authorizationId, UUID requestId, UUID campaignId, UUID jobId,
            UUID firmwareId, int attemptNo, String manifestSha256, byte[] manifest, byte[] signature,
            byte[] publicKeySpki, String signatureProfile, String keyFingerprint, URI downloadUrl, Instant expiresAt) {

        /** 冻结所有可变输入。 */
        public Response {
            manifest = manifest.clone();
            signature = signature.clone();
            publicKeySpki = publicKeySpki.clone();
        }

        /**
         * @return 独立清单副本
         */
        @Override
        public byte[] manifest() {
            return manifest.clone();
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

        /**
         * 从已签名清单里读取目标 artifact 摘要。
         *
         * <p>这是设备在执行前必须与下载字节核对的预期身份。本方法只做词法校验，<b>不验签</b>：
         * 清单是否可信由真正的信任锚切片裁决，本片只保证读取的是同一份规范清单。</p>
         *
         * @return 清单声明的 64 位小写十六进制 artifact 摘要
         * @throws IllegalArgumentException 清单缺少该字段或形态不符时
         */
        public String manifestArtifactSha256() {
            return OtaContractFields.hex(manifestFields(), "artifactSha256");
        }

        /**
         * 从已签名清单里读取目标 artifact 总字节数。
         *
         * @return 清单声明的 artifact 尺寸
         * @throws IllegalArgumentException 清单缺少该字段或越界时
         */
        public long manifestArtifactSize() {
            return OtaContractFields.integer(manifestFields(), "artifactSize", 1, 67_108_864);
        }

        /** 解析本响应携带的规范清单字节。 */
        private Map<String, Object> manifestFields() {
            return JSON.parseObject(manifest());
        }

        /** 禁止 record 默认输出秘密地址。 */
        @Override
        public String toString() {
            return "OtaDeviceDownloadResponse[authorizationId=" + authorizationId + "]";
        }
    }

    /** 已解析的不可变合同投影。
     *
     * @param value 类型化响应
     * @param canonical 规范 JSON 字节
     * @param sha256 规范字节摘要
     */
    public record Decoded(Response value, byte[] canonical, String sha256) {

        /** 防止调用者修改内部规范字节。 */
        public Decoded {
            canonical = canonical.clone();
        }

        /**
         * @return 独立字节副本
         */
        @Override
        public byte[] canonical() {
            return canonical.clone();
        }
    }
}
