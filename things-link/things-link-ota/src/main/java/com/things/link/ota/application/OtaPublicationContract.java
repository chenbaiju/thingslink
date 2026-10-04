package com.things.link.ota.application;

import com.things.link.device.application.OtaModelSnapshot;
import com.things.link.device.application.OtaModelSnapshotPort;
import com.things.link.ota.application.OtaTrustService.Grant;
import com.things.link.ota.domain.OtaFirmware;
import com.things.link.ota.domain.OtaUploadSession;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 发布前权威字段合同和完整信任快照；不替代调用者的角色、事务锁及租约检查。 */
@Component
public final class OtaPublicationContract {
    /** 只通过device公开端口核对精确已发布模型。 */
    private final OtaModelSnapshotPort models;
    /** 既有manifest字段与签名验证入口。 */
    private final OtaManifestCodec manifestCodec = new OtaManifestCodec();
    /** 共用有界受限JCS。 */
    private final OtaCanonicalJson json = new OtaCanonicalJson();
    /** 严格公钥资格。 */
    private final OtaReleaseSignatureVerifier verifier = new OtaReleaseSignatureVerifier();
    /** 注入权威模型端口，不以UUID格式替代模型归属。 */
    public OtaPublicationContract(OtaModelSnapshotPort models) { this.models = Objects.requireNonNull(models); }

    /** 核对固件、上传和目标/来源模型全部已冻结业务字段，返回规范manifest副本。 */
    public byte[] validate(byte[] manifest, OtaFirmware firmware, OtaUploadSession upload, Grant grant) {
        if (firmware == null || upload == null || grant == null) throw invalid();
        byte[] canonical = manifestCodec.canonicalize(manifest);
        Map<String, Object> fields = json.parseObject(canonical);
        snapshot(grant);
        if (!"DRAFT".equals(firmware.status()) || !"VERIFIED".equals(upload.status())
                || !firmware.tenantId().equals(upload.tenantId()) || !firmware.projectId().equals(upload.projectId())
                || !firmware.id().equals(upload.firmwareId()) || upload.cancelRequestedAt() != null
                || upload.versionId() == null || upload.versionId().isBlank() || "null".equals(upload.versionId())
                || upload.expectedLength() < 1 || upload.expectedLength() > 67_108_864L
                || !firmware.tenantId().equals(grant.tenantId()) || !firmware.projectId().equals(grant.projectId())
                || !firmware.deviceTypeId().equals(grant.deviceTypeId())) throw invalid();
        equal(fields.get("firmwareId"), firmware.id().toString());
        equal(fields.get("firmwareVersion"), firmware.firmwareVersion());
        equal(fields.get("deviceTypeId"), firmware.deviceTypeId().toString());
        equal(fields.get("productKey"), firmware.productKey());
        equal(fields.get("thingModelVersionId"), firmware.thingModelVersionId().toString());
        equal(fields.get("thingModelSchemaDigestAlgorithm"), firmware.schemaDigestAlgorithm());
        equal(fields.get("thingModelSchemaDigest"), firmware.schemaDigest());
        equal(OtaTrustBundleCodec.object(fields.get("requirements")).get("profile"), firmware.schemaProfile());
        equal(fields.get("artifactSize"), upload.expectedLength());
        equal(fields.get("artifactSha256"), upload.expectedSha256());
        requireGrant(fields, grant);
        OtaModelSnapshot target = requireModel(firmware, firmware.thingModelVersionId());
        equal(target.schemaDigest(), firmware.schemaDigest());
        for (Object id : (List<?>) fields.get("allowedSourceThingModelVersionIds")) {
            requireModel(firmware, OtaTrustBundleCodec.uuid(id));
        }
        return canonical;
    }

    /** 完整Grant规范快照，long修订使用十进制字符串避免JCS安全整数上限丢精度。 */
    public byte[] snapshot(Grant grant) {
        if (grant == null || grant.binding() == null || grant.tenantId() == null || grant.projectId() == null
                || grant.deviceTypeId() == null || grant.binding().profile() == null) throw invalid();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("contractVersion", "tc-ota-publication-grant/v1");
        fields.put("tenantId", grant.tenantId().toString());
        fields.put("projectId", grant.projectId().toString());
        fields.put("deviceTypeId", grant.deviceTypeId().toString());
        fields.put("bundleVersion", Long.toString(grant.bundleVersion()));
        fields.put("policyRevision", Long.toString(grant.policyRevision()));
        fields.put("policyHash", grant.policyHash());
        fields.put("rootFingerprint", grant.rootFingerprint());
        fields.put("trustDomain", grant.binding().trustDomain());
        fields.put("keyVersion", grant.binding().keyVersion());
        fields.put("signatureProfile", grant.binding().profile().value());
        fields.put("fingerprint", grant.binding().fingerprint());
        fields.put("revision", Long.toString(grant.binding().revision()));
        fields.put("spki", Base64.getEncoder().encodeToString(grant.spki()));
        byte[] canonical = json.writeObject(fields);
        restore(canonical);
        return canonical;
    }

    /** 恢复闭集不可变快照；此函数只校验持久编码，不赋予当前信任资格。 */
    public Grant restore(byte[] bytes) {
        Map<String, Object> fields = json.parseObject(bytes);
        OtaTrustBundleCodec.closed(fields, Set.of("contractVersion", "tenantId", "projectId", "deviceTypeId",
                "bundleVersion", "policyRevision", "policyHash", "rootFingerprint", "trustDomain", "keyVersion",
                "signatureProfile", "fingerprint", "revision", "spki"));
        equal(fields.get("contractVersion"), "tc-ota-publication-grant/v1");
        UUID tenant = OtaTrustBundleCodec.uuid(fields.get("tenantId"));
        UUID project = OtaTrustBundleCodec.uuid(fields.get("projectId"));
        UUID type = OtaTrustBundleCodec.uuid(fields.get("deviceTypeId"));
        long bundle = positive(fields.get("bundleVersion"), 9_007_199_254_740_991L);
        long policy = positive(fields.get("policyRevision"), 9_007_199_254_740_991L);
        String hash = OtaTrustBundleCodec.text(fields.get("policyHash"), "[0-9a-f]{64}");
        String root = OtaTrustBundleCodec.text(fields.get("rootFingerprint"), "[0-9a-f]{64}");
        String domain = OtaTrustBundleCodec.text(fields.get("trustDomain"), "[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
        String version = OtaTrustBundleCodec.text(fields.get("keyVersion"), "[A-Za-z0-9._:/-]{1,256}");
        OtaSignatureProfile profile = OtaTrustBundleCodec.profile(fields.get("signatureProfile"));
        String fingerprint = OtaTrustBundleCodec.text(fields.get("fingerprint"), "[0-9a-f]{64}");
        long revision = positive(fields.get("revision"), Long.MAX_VALUE);
        byte[] spki = OtaTrustBundleCodec.base64(fields.get("spki"), 128);
        if (!verifier.validKey(profile, spki, fingerprint) || root.equals(fingerprint)) throw invalid();
        return new Grant(tenant, project, type, bundle, policy, hash, root,
                new OtaSigningTrustSource.Binding(domain, version, profile, fingerprint, revision), spki);
    }

    /** 比较全部字段及公开密钥字节，不能使用record数组引用相等。 */
    public boolean same(Grant first, Grant second) { return Arrays.equals(snapshot(first), snapshot(second)); }

    /** 重新验证稳定请求身份和完整响应，协调器成功不是最终事务采用证明。 */
    public void verifyResult(UUID requestId, byte[] canonical, Grant expected, OtaSigningCoordinator.Result result) {
        if (requestId == null || expected == null || result == null || !requestId.equals(result.requestId())
                || !expected.binding().equals(result.binding()) || !Arrays.equals(canonical, result.canonicalManifest())) throw invalid();
        verifySignature(canonical, expected, result.spki(), result.signature(), result.receipt());
    }

    /** SIGNED恢复重验固定Grant公钥、规范内容和安全回执，不接受替换的供应商公钥。 */
    public void verifySignature(byte[] canonical, Grant expected, byte[] spki, byte[] signature, String receipt) {
        snapshot(expected);
        if (!Arrays.equals(canonical, manifestCodec.canonicalize(canonical))
                || !Arrays.equals(expected.spki(), spki)) throw invalid();
        OtaTrustBundleCodec.text(receipt, "[A-Za-z0-9._:/-]{1,256}");
        requireGrant(json.parseObject(canonical), expected);
        if (!manifestCodec.verifySignature(canonical, spki, expected.binding().fingerprint(), signature)) throw invalid();
    }

    /** 只核对manifest与当前Grant的声明交集，不声称设备已确认bundle。 */
    private static void requireGrant(Map<String, Object> fields, Grant grant) {
        equal(fields.get("trustDomain"), grant.binding().trustDomain());
        equal(fields.get("signatureProfile"), grant.binding().profile().value());
        equal(fields.get("signingKeyFingerprint"), grant.binding().fingerprint());
        if ((Long) fields.get("minimumTrustBundleVersion") > grant.bundleVersion()) throw invalid();
    }

    /** 精确目标或来源版本必须属于同项目/类型且由device端口确认已发布。 */
    private OtaModelSnapshot requireModel(OtaFirmware firmware, UUID modelId) {
        OtaModelSnapshot model = models.find(firmware.projectId(), firmware.deviceTypeId(), modelId).orElseThrow(OtaPublicationContract::invalid);
        if (!firmware.projectId().equals(model.projectId()) || !firmware.deviceTypeId().equals(model.deviceTypeId())
                || !modelId.equals(model.thingModelVersionId()) || !firmware.productKey().equals(model.productKey())
                || !firmware.schemaDigestAlgorithm().equals(model.schemaDigestAlgorithm())
                || !firmware.schemaProfile().equals(model.schemaProfile()) || model.schemaDigest() == null
                || !model.schemaDigest().matches("[0-9a-f]{64}")) throw invalid();
        return model;
    }
    /** 正修订使用规范十进制字符串，不接受溢出或前导零。 */
    private static long positive(Object value, long max) {
        String text = OtaTrustBundleCodec.text(value, "[1-9][0-9]{0,18}");
        try { long number = Long.parseLong(text); if (number > max) throw invalid(); return number; }
        catch (NumberFormatException failure) { throw invalid(); }
    }
    /** 每个身份字段都必须相等，不使用空值或默认值补齐。 */
    private static void equal(Object actual, Object expected) {
        if (actual == null || !actual.equals(expected)) throw invalid();
    }
    /** 固定拒绝不暴露manifest、信任配置或底层异常正文。 */
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("OTA发布合同不匹配"); }
}
