package com.things.link.ota.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 扩展闭集、配置身份和父基线收窄的纯合同，不冒称实际设备互斥验收。 */
class OtaRollbackBaselineTests {
    /** 唯一规范JSON实现。 */ private final OtaCanonicalJson json = new OtaCanonicalJson();
    /** 被测闭集解析器。 */ private final OtaRollbackBaselineCodec codec = new OtaRollbackBaselineCodec();

    /** 完整摘要和防御复制，不以版本号替代配置身份。 */
    @Test void canonicalIdentityIncludesEveryFieldAndProtectsBytes() throws Exception {
        var value = body(); byte[] bytes = json.writeObject(value); var decoded = codec.decode(bytes);
        assertThat(decoded.canonical()).containsExactly(bytes);
        assertThat(decoded.sha256()).isEqualTo(OtaTrustBundleCodec.sha256(bytes));
        bytes[0] = 0; byte[] exposed = decoded.canonical(); exposed[0] = 0;
        assertThat(decoded.canonical()[0]).isEqualTo((byte) '{');
        value.put("evidenceReference", "test-only:changed"); var changed = codec.decode(json.writeObject(value));
        assertThat(changed.value().rollbackBaselineVersion()).isEqualTo(decoded.value().rollbackBaselineVersion());
        assertThat(changed.sha256()).isNotEqualTo(decoded.sha256());
    }

    /** 未知字段、能力和缺失字段均不能成为隐式默认值。 */
    @Test void rejectsOpenObjectsAndUnknownAtomicProfile() throws Exception {
        var value = body(); value.put("unexpected", true); reject(value);
        value = body(); value.remove("evidenceReference"); reject(value);
        value = body(); value.put("atomicOperationProfile", "BEST_EFFORT"); reject(value);
        value = body(); value.put("bootloader", Map.of("minimumVersion", "1.2.0", "maximumVersion", "1.9.0", "extra", true)); reject(value);
    }

    /** 数值必须规范且处于跨语言安全边界，摘要不可大写。 */
    @Test void rejectsUnsafeVersionsAndInvalidIdentity() throws Exception {
        for (Object invalid : List.of(0L, -1L, 9_007_199_254_740_992L, "1")) {
            var value = body(); value.put("rollbackBaselineVersion", invalid); reject(value);
        }
        var value = body(); value.put("typeBaselineSha256", "A".repeat(64)); reject(value);
        value = body(); value.put("tenantId", "00000000-0000-0000-0000-00000000000A"); reject(value);
    }

    /** 三轴版本按数值比较且证据索引禁止首尾空白和控制字符。 */
    @Test void rejectsMalformedRangeAndEvidenceReference() throws Exception {
        for (String minimum : List.of("1.10.0", "01.2.0", "2147483648.0.0")) {
            var value = body(); value.put("bootloader", Map.of("minimumVersion", minimum, "maximumVersion", "1.9.0")); reject(value);
        }
        for (String invalid : List.of("", " leading", "trailing\u00a0", "bad\nline", "x".repeat(257))) {
            var value = body(); value.put("evidenceReference", invalid); reject(value);
        }
    }

    /** 默认和显式空集合均不可借用其他类型配置，重复范围立即失败。 */
    @Test void sourceRejectsMissingDuplicateAndOversizedConfiguration() throws Exception {
        var p = parent().value();
        for (String empty : List.of("", "{\"baselines\":[]}"))
            assertThatThrownBy(() -> new OtaRollbackBaselineSource(empty).require(p.tenantId(), p.projectId(), p.deviceTypeId()))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> source(List.of(body(), body()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> source(java.util.Collections.nCopies(65, body()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OtaRollbackBaselineSource(" ".repeat(65_537))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OtaRollbackBaselineSource("\ud800")).isInstanceOf(IllegalArgumentException.class);
        var configured = source(List.of(body()));
        assertThat(configured.require(p.tenantId(), p.projectId(), p.deviceTypeId()).sha256()).isEqualTo(codec.decode(json.writeObject(body())).sha256());
        assertThatThrownBy(() -> configured.require(p.tenantId(), UUID.randomUUID(), p.deviceTypeId())).isInstanceOf(IllegalArgumentException.class);
    }

    /** 父基线摘要、版本和产品全部精确，不能仅凭同版本复用旧扩展。 */
    @Test void parentIdentityMustMatchCompletely() throws Exception {
        var parent = parent(); assertThat(OtaRollbackBaselineQualification.matches(parent, codec.decode(json.writeObject(body())))).isTrue();
        for (String field : List.of("tenantId", "projectId", "deviceTypeId", "productKey", "typeBaselineVersion", "typeBaselineSha256")) {
            var value = body(); value.put(field, switch (field) {
                case "productKey" -> "other_product";
                case "typeBaselineVersion" -> 2L;
                case "typeBaselineSha256" -> "0".repeat(64);
                default -> UUID.randomUUID().toString();
            });
            assertThat(OtaRollbackBaselineQualification.matches(parent, codec.decode(json.writeObject(value)))).as(field).isFalse();
        }
    }

    /** 扩展只允许父范围子集，不能授予父基线未声明的AB或保护计数器。 */
    @Test void extensionOnlyNarrowsParentBootloaderAndProtectedCapabilities() throws Exception {
        for (Map<String, String> range : List.of(Map.of("minimumVersion", "1.1.0", "maximumVersion", "1.9.0"),
                Map.of("minimumVersion", "1.2.0", "maximumVersion", "1.11.0"))) {
            var value = body(); value.put("bootloader", range);
            assertThat(OtaRollbackBaselineQualification.matches(parent(), codec.decode(json.writeObject(value)))).isFalse();
        }
        for (String field : List.of("supportsAbSlots", "protectedSecurityCounterBits")) {
            var raw = new LinkedHashMap<>(json.parseObject(parent().canonical())); raw.put(field, field.equals("supportsAbSlots") ? false : 0L);
            var parent = new OtaTypeBaselineCodec().decode(json.writeObject(raw));
            var value = body(); value.put("typeBaselineSha256", parent.sha256());
            assertThat(OtaRollbackBaselineQualification.matches(parent, codec.decode(json.writeObject(value)))).isFalse();
        }
    }

    /** 固定公开父基线向量，不读取受控环境配置。 */
    private OtaTypeBaselineCodec.Decoded parent() throws Exception {
        try (var input = getClass().getResourceAsStream("/ota/baseline/type-baseline-v1.json")) {
            return new OtaTypeBaselineCodec().decode(input.readAllBytes());
        }
    }
    /** 每次返回可变独立配置，仅供负例修改。 */
    private Map<String, Object> body() throws Exception {
        var parent = parent(); var p = parent.value(); var body = new LinkedHashMap<String, Object>();
        body.put("contractVersion", "tc-ota-rollback-baseline/v1"); body.put("tenantId", p.tenantId().toString());
        body.put("projectId", p.projectId().toString()); body.put("deviceTypeId", p.deviceTypeId().toString());
        body.put("productKey", p.productKey()); body.put("typeBaselineVersion", p.baselineVersion()); body.put("typeBaselineSha256", parent.sha256());
        body.put("rollbackBaselineVersion", 1L); body.put("atomicOperationProfile", OtaRollbackBaselineCodec.PROFILE);
        body.put("bootloader", Map.of("minimumVersion", "1.2.0", "maximumVersion", "1.9.0"));
        body.put("evidenceReference", "test-only:atomic-journal/1"); return body;
    }
    /** 用同一封闭容器构造不可变配置源。 */
    private OtaRollbackBaselineSource source(List<?> values) { return new OtaRollbackBaselineSource(new String(json.writeObject(Map.of("baselines", values)), StandardCharsets.UTF_8)); }
    /** 负例必须被严格合同拒绝。 */
    private void reject(Map<String, Object> value) { assertThatThrownBy(() -> codec.decode(json.writeObject(value))).isInstanceOf(IllegalArgumentException.class); }
}
