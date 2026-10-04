package com.things.link.ota.application;

import com.things.link.device.application.OtaModelSnapshot;
import com.things.link.device.application.OtaModelSnapshotPort;
import com.things.link.ota.application.OtaTrustService.Grant;
import com.things.link.ota.domain.OtaFirmware;
import com.things.link.ota.domain.OtaUploadSession;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 发布权威字段、完整Grant和固定公开签名反例，不生成生产或测试运行时私钥。 */
class OtaPublicationContractTests {
    /** 独立项目fixture身份。 */
    private static final UUID PROJECT = UUID.fromString("018f0000-0000-7000-8000-000000000010");
    /** 独立租户fixture身份。 */
    private static final UUID TENANT = UUID.fromString("018f0000-0000-7000-8000-000000000011");
    /** 当前测试操作者。 */
    private static final UUID ACCOUNT = UUID.fromString("018f0000-0000-7000-8000-000000000012");
    /** 有界JSON只用于构造失败字段，成功字节来自固定黄金向量。 */
    private final OtaCanonicalJson json = new OtaCanonicalJson();

    /** 全部权威字段一致且目标/来源均经公开端口核对才能接受。 */
    @Test
    void validatesTargetAndSourceAuthoritativeModels() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        OtaPublicationContract contract = new OtaPublicationContract((project, type, model) -> {
            calls.incrementAndGet(); return Optional.of(model(project, type, model));
        });
        assertThat(contract.validate(resource("manifest-v1.json"), firmware(), upload(), grant(1)))
                .containsExactly(resource("manifest-v1.json"));
        assertThat(calls).hasValue(2);
    }

    /** 目标或任一来源缺失、跨项目投影、目标摘要不匹配都会拒绝。 */
    @Test
    void rejectsMissingOrMismatchedAuthoritativeSnapshots() throws Exception {
        for (OtaModelSnapshotPort port : new OtaModelSnapshotPort[] {
                (project, type, id) -> Optional.empty(),
                (project, type, id) -> id.toString().endsWith("0004") ? Optional.empty() : Optional.of(model(project, type, id)),
                (project, type, id) -> Optional.of(model(UUID.randomUUID(), type, id)),
                (project, type, id) -> Optional.of(new OtaModelSnapshot(project, type, id, "product_test",
                        "PG_JSONB_TEXT_V1_SHA256", "c".repeat(64), "TC_PROPERTY_COMPOSITE_V1"))}) {
            OtaPublicationContract contract = new OtaPublicationContract(port);
            assertThatThrownBy(() -> contract.validate(resource("manifest-v1.json"), firmware(), upload(), grant(1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** manifest核心身份、对象摘要和最小包版本不能由客户端自行扩大。 */
    @Test
    void rejectsBusinessFieldsAndTrustBundleMismatch() throws Exception {
        OtaPublicationContract contract = contract();
        for (Map.Entry<String, Object> change : Map.<String, Object>of(
                "firmwareVersion", "other", "artifactSha256", "c".repeat(64), "artifactSize", 1025L,
                "minimumTrustBundleVersion", 2L, "trustDomain", "wrong.domain").entrySet()) {
            Map<String, Object> fields = new LinkedHashMap<>(json.parseObject(resource("manifest-v1.json")));
            fields.put(change.getKey(), change.getValue());
            assertThatThrownBy(() -> contract.validate(json.writeObject(fields), firmware(), upload(), grant(1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** long最大修订和所有Grant字段可精确往返；仅policyHash或revision变化也不能视为同一资格。 */
    @Test
    void roundTripsCompleteGrantWithoutLongPrecisionLoss() throws Exception {
        OtaPublicationContract contract = contract();
        Grant original = grant(Long.MAX_VALUE);
        byte[] snapshot = contract.snapshot(original);
        Grant restored = contract.restore(snapshot);
        assertThat(restored.binding().revision()).isEqualTo(Long.MAX_VALUE);
        assertThat(contract.same(original, restored)).isTrue();
        assertThat(contract.same(original, grant(1))).isFalse();
        Grant changed = new Grant(original.tenantId(), original.projectId(), original.deviceTypeId(),
                original.bundleVersion(), original.policyRevision(), "f".repeat(64), original.rootFingerprint(),
                original.binding(), original.spki());
        assertThat(contract.same(original, changed)).isFalse();
        Map<String, Object> fields = new LinkedHashMap<>(json.parseObject(snapshot));
        fields.put("revision", "01");
        assertThatThrownBy(() -> contract.restore(json.writeObject(fields))).isInstanceOf(IllegalArgumentException.class);
        fields.put("revision", "1"); fields.put("extra", true);
        assertThatThrownBy(() -> contract.restore(json.writeObject(fields))).isInstanceOf(IllegalArgumentException.class);
    }

    /** 固定公开签名必须与准确请求、Grant公钥及安全回执匹配。 */
    @Test
    void verifiesPersistedSignatureAndRejectsRequestOrReceiptSubstitution() throws Exception {
        OtaPublicationContract contract = contract();
        byte[] body = resource("manifest-v1.json");
        Grant grant = grant(1);
        byte[] signature = hex("manifest-v1.signature.hex");
        UUID request = UUID.randomUUID();
        var result = new OtaSigningCoordinator.Result(request, body, grant.binding(), grant.spki(), signature, "receipt:1");
        contract.verifyResult(request, body, grant, result);
        assertThatThrownBy(() -> contract.verifyResult(UUID.randomUUID(), body, grant, result)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> contract.verifySignature(body, grant, grant.spki(), signature, "https://secret?q=x"))
                .isInstanceOf(IllegalArgumentException.class);
        byte[] wrong = grant.spki(); wrong[wrong.length - 1] ^= 1;
        assertThatThrownBy(() -> contract.verifySignature(body, grant, wrong, signature, "receipt:1"))
                .isInstanceOf(IllegalArgumentException.class);
        signature[0] ^= 1;
        assertThatThrownBy(() -> contract.verifySignature(body, grant, grant.spki(), signature, "receipt:1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 建立始终返回精确匹配权威投影的纯合同fixture。 */
    private OtaPublicationContract contract() {
        return new OtaPublicationContract((project, type, id) -> Optional.of(model(project, type, id)));
    }
    /** 固定已发布投影；目标与来源可以有独立摘要，本例使用同一值。 */
    private static OtaModelSnapshot model(UUID project, UUID type, UUID id) {
        return new OtaModelSnapshot(project, type, id, "product_test", "PG_JSONB_TEXT_V1_SHA256",
                "b".repeat(64), "TC_PROPERTY_COMPOSITE_V1");
    }
    /** 固定DRAFT事实与公开manifest身份一致。 */
    private static OtaFirmware firmware() {
        return new OtaFirmware(id(1), TENANT, PROJECT, ACCOUNT, id(2), id(3), "product_test", "固件1.0.0",
                "PG_JSONB_TEXT_V1_SHA256", "b".repeat(64), "TC_PROPERTY_COMPOSITE_V1", "DRAFT", 0, Instant.EPOCH, null);
    }
    /** 固定VERIFIED上传仅证明本例输入字段，不冒充真实对象复验。 */
    private static OtaUploadSession upload() {
        return new OtaUploadSession(id(5), TENANT, PROJECT, id(1), ACCOUNT, id(6), 1, 1024, 2,
                "a".repeat(64), "private-test", "exact/key", "VERIFIED", "fixed-version", null,
                "d".repeat(64), "e".repeat(64), Instant.EPOCH, Instant.EPOCH.plusSeconds(3600), null,
                Instant.EPOCH, Instant.EPOCH, null, null, Instant.EPOCH, null);
    }
    /** 配置测试Grant使用公开manifest公钥，不保存任何私钥。 */
    private static Grant grant(long revision) throws Exception {
        return new Grant(TENANT, PROJECT, id(2), 1, 1, "d".repeat(64), "e".repeat(64),
                new OtaSigningTrustSource.Binding("test.example", "test:key/1", OtaSignatureProfile.ED25519_V1,
                        "b4eddd0f449a035411298999ebbba43a405c3412044f070ea7d9f8a48c7b7a48", revision),
                hex("manifest-v1.spki.hex"));
    }
    /** 固定UUID只用于公开向量身份。 */
    private static UUID id(int suffix) { return UUID.fromString("018f0000-0000-7000-8000-" + String.format("%012d", suffix)); }
    /** 读取公开十六进制资源。 */
    private static byte[] hex(String name) throws Exception { return HexFormat.of().parseHex(new String(resource(name), StandardCharsets.US_ASCII).trim()); }
    /** 缺失向量明确失败。 */
    private static byte[] resource(String name) throws Exception {
        try (var input = OtaPublicationContractTests.class.getResourceAsStream("/ota/" + name)) {
            if (input == null) throw new IllegalStateException("缺少公开向量");
            return input.readAllBytes();
        }
    }
}
