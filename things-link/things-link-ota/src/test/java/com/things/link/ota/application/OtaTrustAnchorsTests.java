package com.things.link.ota.application;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 受控根配置只能增加明确授权，不从空配置、别名或畸形公开键推断成功。 */
class OtaTrustAnchorsTests {
    /** 公开黄金向量范围。 */
    private static final UUID TENANT = UUID.fromString("018f0000-0000-7000-8000-000000000001");
    /** 公开黄金向量项目。 */
    private static final UUID PROJECT = UUID.fromString("018f0000-0000-7000-8000-000000000002");
    /** 测试变更结构使用固定有界JCS。 */
    private final OtaCanonicalJson json = new OtaCanonicalJson();

    /** 双Profile根指纹和完整配置hash与独立Python向量一致。 */
    @Test
    void acceptsGoldenConfigurationAndFreezesPublicKeyAndTypeSet() throws Exception {
        for (String profile : List.of("ed25519", "es256")) {
            var anchor = new OtaTrustAnchors(resource(profile + "-anchors.json")).require(TENANT, PROJECT, "test.domain");
            assertThat(anchor.policyHash()).isEqualTo(resource(profile + "-policy.sha256").trim());
            byte[] bytes = anchor.rootSpki();
            bytes[0] = 0;
            assertThat(anchor.rootSpki()[0]).isEqualTo((byte) 0x30);
            assertThatThrownBy(() -> anchor.allowedDeviceTypeIds().clear()).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    /** 未配置、错范围与未知域都不返回默认成功绑定。 */
    @Test
    void emptyAndMismatchedScopesFailClosed() throws Exception {
        for (String empty : List.of("", "{\"anchors\":[]}")) {
            var anchors = new OtaTrustAnchors(empty);
            assertThatThrownBy(() -> anchors.require(TENANT, PROJECT, "test.domain")).isInstanceOf(IllegalArgumentException.class);
        }
        var anchors = new OtaTrustAnchors(resource("ed25519-anchors.json"));
        assertThatThrownBy(() -> anchors.require(UUID.randomUUID(), PROJECT, "test.domain")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> anchors.require(TENANT, UUID.randomUUID(), "test.domain")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> anchors.require(TENANT, PROJECT, "unknown")).isInstanceOf(IllegalArgumentException.class);
    }

    /** 类型授权视为集合：列表顺序不改变完整配置摘要，成员变化必须改变摘要。 */
    @Test
    void sortsTypeIdsForPolicyHashWithoutDiscardingPolicyFields() throws Exception {
        Map<String, Object> entry = entry();
        String first = "018f0000-0000-7000-8000-000000000003";
        String second = "018f0000-0000-7000-8000-000000000004";
        entry.put("allowedDeviceTypeIds", List.of(second, first));
        String hash = anchor(entry).policyHash();
        entry.put("allowedDeviceTypeIds", List.of(first, second));
        assertThat(anchor(entry).policyHash()).isEqualTo(hash);
        entry.put("policyRevision", 2L);
        assertThat(anchor(entry).policyHash()).isNotEqualTo(hash);
        entry.put("policyRevision", 1L);
        entry.put("allowedDeviceTypeIds", List.of(first));
        assertThat(anchor(entry).policyHash()).isNotEqualTo(hash);
    }

    /** 配置闭集与Unicode/UUID/Base64/版本输入始终严格拒绝。 */
    @Test
    void rejectsMalformedOrAmbiguousConfiguration() throws Exception {
        for (Consumer<Map<String, Object>> mutation : List.<Consumer<Map<String, Object>>>of(
                entry -> entry.put("privateKey", "forbidden"), entry -> entry.remove("rootSpki"),
                entry -> entry.put("rootFingerprint", "0".repeat(64)), entry -> entry.put("policyRevision", 0L),
                entry -> entry.put("policyRevision", "1"), entry -> entry.put("rootProfile", "Ed25519"),
                entry -> entry.put("projectId", "1-1-1-1-1"), entry -> entry.put("trustDomain", " space"),
                entry -> entry.put("allowedDeviceTypeIds", List.of()),
                entry -> entry.put("allowedDeviceTypeIds", List.of(PROJECT.toString(), PROJECT.toString())),
                entry -> entry.put("rootSpki", ((String) entry.get("rootSpki")).replace("=", "")))) {
            Map<String, Object> entry = entry();
            mutation.accept(entry);
            assertThatThrownBy(() -> anchor(entry)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new OtaTrustAnchors(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OtaTrustAnchors("\ud800")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OtaTrustAnchors("x".repeat(65_537))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OtaTrustAnchors("{\"anchors\":[],\"anchors\":[]}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 全局域不能重复，根配置数量不得超64，失败不保留部分授权。 */
    @Test
    void rejectsDuplicateDomainsAndOversizedAnchorInventory() throws Exception {
        Map<String, Object> entry = entry();
        assertThatThrownBy(() -> new OtaTrustAnchors(string(Map.of("anchors", List.of(entry, entry)))))
                .isInstanceOf(IllegalArgumentException.class);
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int index = 0; index < 65; index++) {
            Map<String, Object> next = new LinkedHashMap<>(entry);
            next.put("trustDomain", "domain" + index);
            entries.add(next);
        }
        assertThatThrownBy(() -> new OtaTrustAnchors(string(Map.of("anchors", entries))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 解析变更后的单个受控项。 */
    private OtaTrustAnchors.Anchor anchor(Map<String, Object> entry) {
        return new OtaTrustAnchors(string(Map.of("anchors", List.of(entry)))).require(TENANT, PROJECT, "test.domain");
    }
    /** 拷贝公开成功项用于构造反例。 */
    private Map<String, Object> entry() throws Exception {
        Map<String, Object> fields = json.parseObject(resource("ed25519-anchors.json").getBytes(StandardCharsets.UTF_8));
        return new LinkedHashMap<>(OtaTrustBundleCodec.object(((List<?>) fields.get("anchors")).getFirst()));
    }
    /** 明确UTF8序列化，不靠平台默认字符集。 */
    private String string(Map<String, Object> fields) {
        return new String(json.writeObject(fields), StandardCharsets.UTF_8);
    }
    /** 读取独立公开配置与摘要黄金向量。 */
    private static String resource(String name) throws Exception {
        try (var input = OtaTrustAnchorsTests.class.getResourceAsStream("/ota/trust/" + name)) {
            if (input == null) throw new IllegalStateException("缺少公开测试向量");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
