package com.things.link.ota.application;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 十五字段秘密响应，仅证明合同和发布签名，不替代当前授权。 */
public final class OtaDownloadResponseCodec {
    /** 固定闭集，禁止向设备泄露额外服务字段。 */
    private static final Set<String> FIELDS = Set.of("contractVersion", "authorizationId", "requestId",
            "campaignId", "jobId", "firmwareId", "attemptNo", "manifestSha256", "manifest", "signatureBase64",
            "publicKeySpkiBase64", "signatureProfile", "keyFingerprint", "downloadUrl", "expiresAt");
    /** 严格规范JSON。 */
    private final OtaCanonicalJson json = new OtaCanonicalJson();
    /** 发布合同校验器。 */
    private final OtaManifestCodec manifests = new OtaManifestCodec();
    /** 显式回环开发例外。 */
    private final boolean allowInsecureLoopback;

    /** 默认只允许HTTPS。 */
    public OtaDownloadResponseCodec() { this(false); }
    /** 由受控配置显式开启本机明文例外。 */
    public OtaDownloadResponseCodec(boolean allowInsecureLoopback) {
        this.allowInsecureLoopback = allowInsecureLoopback;
    }

    /** 从完整公开签名材料和临时地址生成规范正文，禁止静默修正输入manifest。 */
    public byte[] encode(Response response) {
        try {
            byte[] canonical = manifests.canonicalize(response.manifest());
            if (!Arrays.equals(canonical, response.manifest())) throw invalid();
            var values = new java.util.LinkedHashMap<String, Object>();
            values.put("contractVersion", response.contractVersion());
            values.put("authorizationId", response.authorizationId().toString());
            values.put("requestId", response.requestId().toString());
            values.put("campaignId", response.campaignId().toString());
            values.put("jobId", response.jobId().toString());
            values.put("firmwareId", response.firmwareId().toString());
            values.put("attemptNo", (long) response.attemptNo());
            values.put("manifestSha256", response.manifestSha256());
            values.put("manifest", json.parseObject(canonical));
            values.put("signatureBase64", Base64.getEncoder().encodeToString(response.signature()));
            values.put("publicKeySpkiBase64", Base64.getEncoder().encodeToString(response.publicKeySpki()));
            values.put("signatureProfile", response.signatureProfile().value());
            values.put("keyFingerprint", response.keyFingerprint());
            values.put("downloadUrl", response.downloadUrl().toString());
            values.put("expiresAt", response.expiresAt().toString());
            byte[] output = json.writeObject(values);
            decode(output);
            return output;
        } catch (RuntimeException failure) { throw invalid(); }
    }

    /** 恢复嵌套manifest的唯一签名字节，并严格验证相互身份。 */
    public Response decode(byte[] input) {
        try {
            var fields = json.parseObject(input);
            OtaTrustBundleCodec.closed(fields, FIELDS);
            if (!"tc-ota-download-response/v1".equals(fields.get("contractVersion"))) throw invalid();
            Object nested = fields.get("manifest");
            if (!(nested instanceof Map<?, ?>)) throw invalid();
            @SuppressWarnings("unchecked") Map<String, Object> object = (Map<String, Object>) nested;
            byte[] canonical = manifests.canonicalize(json.writeObject(object));
            String hash = OtaTrustBundleCodec.text(fields.get("manifestSha256"), "[0-9a-f]{64}");
            String fingerprint = OtaTrustBundleCodec.text(fields.get("keyFingerprint"), "[0-9a-f]{64}");
            var profile = OtaTrustBundleCodec.profile(fields.get("signatureProfile"));
            UUID firmware = OtaTrustBundleCodec.uuid(fields.get("firmwareId"));
            byte[] signature = base64(fields.get("signatureBase64"), 64);
            byte[] spki = base64(fields.get("publicKeySpkiBase64"), 91);
            if (!hash.equals(OtaTrustBundleCodec.sha256(canonical))
                    || !firmware.toString().equals(object.get("firmwareId"))
                    || !profile.value().equals(object.get("signatureProfile"))
                    || !fingerprint.equals(object.get("signingKeyFingerprint"))
                    || !manifests.verifySignature(canonical, spki, fingerprint, signature)) throw invalid();
            return new Response("tc-ota-download-response/v1", OtaTrustBundleCodec.uuid(fields.get("authorizationId")),
                    OtaTrustBundleCodec.uuid(fields.get("requestId")), OtaTrustBundleCodec.uuid(fields.get("campaignId")),
                    OtaTrustBundleCodec.uuid(fields.get("jobId")), firmware,
                    (int) OtaTrustBundleCodec.integer(fields.get("attemptNo"), 1, Integer.MAX_VALUE), hash,
                    canonical, signature, spki, profile, fingerprint, url(fields.get("downloadUrl")),
                    instant(fields.get("expiresAt")));
        } catch (RuntimeException failure) { throw invalid(); }
    }

    /** 精确固定下行路由，字段不能注入多层Topic或通配符。 */
    public static String topic(String projectKey, String deviceKey) {
        String available = OtaNotificationCodec.topic(projectKey, deviceKey);
        return available.substring(0, available.length() - "available".length()) + "download/response";
    }

    /** Base64必须规范且原始材料有界。 */
    private static byte[] base64(Object value, int max) {
        if (!(value instanceof String text) || text.length() > 128) throw invalid();
        byte[] decoded = Base64.getDecoder().decode(text);
        if (decoded.length > max || !Base64.getEncoder().encodeToString(decoded).equals(text)) throw invalid();
        return decoded;
    }

    /** 地址限制按UTF8字节计量，拒绝用户信息、fragment及非绝对地址。 */
    private URI url(Object value) {
        if (!(value instanceof String text) || text.getBytes(StandardCharsets.UTF_8).length > 8192) throw invalid();
        URI uri = URI.create(text);
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null) throw invalid();
        if ("https".equalsIgnoreCase(uri.getScheme())) return uri;
        String host = uri.getHost();
        if (allowInsecureLoopback && "http".equalsIgnoreCase(uri.getScheme())
                && Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(host.toLowerCase(java.util.Locale.ROOT))) return uri;
        throw invalid();
    }

    /** 只接纳四位年份规范UTC及不超过微秒的时间，不在纯合同中刷新期限。 */
    static Instant instant(Object value) {
        if (!(value instanceof String text) || !text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,6})?Z")) throw invalid();
        Instant parsed = Instant.parse(text);
        if (!parsed.toString().equals(text)) throw invalid();
        return parsed;
    }

    /** 不附原异常或地址，避免秘密进入诊断。 */
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("OTA下载响应合同不合法"); }

    /** 完整秘密响应，数组只保存规范材料。
     * @param contractVersion 固定合同
     * @param authorizationId 授权身份
     * @param requestId 请求身份
     * @param campaignId 活动身份
     * @param jobId 作业身份
     * @param firmwareId 固件身份
     * @param attemptNo 尝试号
     * @param manifestSha256 清单摘要
     * @param manifest 规范清单
     * @param signature 发布签名
     * @param publicKeySpki 完整公钥
     * @param signatureProfile 签名协议
     * @param keyFingerprint 公钥指纹
     * @param downloadUrl 临时秘密地址
     * @param expiresAt 绝对截止
     */
    public record Response(String contractVersion, UUID authorizationId, UUID requestId, UUID campaignId, UUID jobId,
            UUID firmwareId, int attemptNo, String manifestSha256, byte[] manifest, byte[] signature,
            byte[] publicKeySpki, OtaSignatureProfile signatureProfile, String keyFingerprint, URI downloadUrl, Instant expiresAt) {
        /** 冻结所有可变输入。 */
        public Response { manifest = manifest.clone(); signature = signature.clone(); publicKeySpki = publicKeySpki.clone(); }
        /** 返回独立清单。 */ @Override public byte[] manifest() { return manifest.clone(); }
        /** 返回独立签名。 */ @Override public byte[] signature() { return signature.clone(); }
        /** 返回独立公钥。 */ @Override public byte[] publicKeySpki() { return publicKeySpki.clone(); }
        /** 禁止record默认输出秘密地址。 */
        @Override public String toString() { return "OtaDownloadResponse[authorizationId=" + authorizationId + "]"; }
    }
}
