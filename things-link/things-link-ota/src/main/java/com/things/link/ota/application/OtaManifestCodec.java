package com.things.link.ota.application;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ADR0113的发布manifest字段合同；不替代租户归属、对象校验或设备资格。
 * 规范化必须先经过本类字段校验，不直接把任意JSON送到发布signer。
 */
public final class OtaManifestCodec {
    /** ADR0053安全整数上限，避免跨语言签名字节不一致。 */
    private static final long MAX_INTEGER = 9_007_199_254_740_991L;
    /** 根合同精确白名单，新增字段必须显式版本审计。 */
    private static final Set<String> FIELDS = Set.of(
            "contractVersion", "firmwareId", "firmwareVersion", "trustDomain", "deviceTypeId", "productKey",
            "hardware", "bootloaderMinimumVersion", "artifactSize", "artifactSha256", "compression", "delta",
            "securityVersion", "thingModelVersionId", "thingModelSchemaDigestAlgorithm", "thingModelSchemaDigest",
            "allowedSourceThingModelVersionIds", "requirements", "signatureProfile", "signingKeyFingerprint",
            "minimumTrustBundleVersion");
    /** 受限JSON边界先于字段和密码学验证。 */
    private final OtaCanonicalJson json = new OtaCanonicalJson();
    /** 复用固定域和Profile，不能由输入选择算法。 */
    private final OtaReleaseSignatureVerifier verifier = new OtaReleaseSignatureVerifier();

    /** 校验完整字段后输出ADR0053受限JCS字节；无效输入不回显原文。 */
    public byte[] canonicalize(byte[] input) {
        Map<String, Object> fields = json.parseObject(input);
        validate(fields);
        return json.writeObject(fields);
    }

    /**
     * 同时核对完整字段、调用方可信指纹与签名；调用方仍负责key的信任、状态与租户授权。
     * 不使用manifest自行声明的指纹替代外部可信指纹。
     */
    public boolean verifySignature(byte[] input, byte[] trustedSpki, String trustedFingerprint, byte[] signature) {
        try {
            Map<String, Object> fields = json.parseObject(input);
            validate(fields);
            if (!fields.get("signingKeyFingerprint").equals(trustedFingerprint)) {
                return false;
            }
            return verifier.verify(profile(fields), trustedSpki, trustedFingerprint, json.writeObject(fields), signature);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /** 所有层使用精确字段集合，拒绝隐含派发事实、未知策略或算法。 */
    private static void validate(Map<String, Object> value) {
        exact(value, FIELDS, "root");
        literal(value, "contractVersion", OtaManifestContractVersion.V1.value());
        for (String name : List.of("firmwareId", "deviceTypeId", "thingModelVersionId")) {
            uuid(text(value, name), name);
        }
        String display = text(value, "firmwareVersion");
        require(!display.isBlank() && display.codePointCount(0, display.length()) <= 128
                && display.codePoints().noneMatch(Character::isISOControl), "firmwareVersion");
        identifier(text(value, "trustDomain"), true, "trustDomain");
        identifier(text(value, "productKey"), false, "productKey");
        Map<String, Object> hardware = object(value, "hardware");
        exact(hardware, Set.of("model", "boardRevisionMin", "boardRevisionMax"), "hardware");
        identifier(text(hardware, "model"), true, "hardware.model");
        require(integer(hardware, "boardRevisionMin", 0) <= integer(hardware, "boardRevisionMax", 0), "hardware.range");
        String bootloader = text(value, "bootloaderMinimumVersion");
        require(bootloader.matches("(0|[1-9][0-9]{0,9})\\.(0|[1-9][0-9]{0,9})\\.(0|[1-9][0-9]{0,9})"), "bootloaderMinimumVersion");
        for (String part : bootloader.split("\\.")) {
            require(Long.parseLong(part) <= Integer.MAX_VALUE, "bootloaderMinimumVersion");
        }
        integer(value, "artifactSize", 1);
        integer(value, "securityVersion", 0);
        integer(value, "minimumTrustBundleVersion", 1);
        for (String name : List.of("artifactSha256", "thingModelSchemaDigest", "signingKeyFingerprint")) {
            require(text(value, name).matches("[0-9a-f]{64}"), name);
        }
        literal(value, "compression", "NONE");
        Map<String, Object> delta = object(value, "delta");
        exact(delta, Set.of("mode"), "delta");
        literal(delta, "mode", "NONE");
        literal(value, "thingModelSchemaDigestAlgorithm", "PG_JSONB_TEXT_V1_SHA256");
        Object sources = value.get("allowedSourceThingModelVersionIds");
        require(sources instanceof List<?>, "allowedSourceThingModelVersionIds");
        List<?> versions = (List<?>) sources;
        require(!versions.isEmpty() && versions.size() <= 256, "allowedSourceThingModelVersionIds");
        String previous = null;
        for (Object element : versions) {
            require(element instanceof String, "allowedSourceThingModelVersionIds");
            String id = (String) element;
            uuid(id, "allowedSourceThingModelVersionIds");
            require(previous == null || previous.compareTo(id) < 0, "allowedSourceThingModelVersionIds.order");
            previous = id;
        }
        Map<String, Object> requirements = object(value, "requirements");
        exact(requirements, Set.of("profile", "minimumRamBytes", "minimumFlashBytes", "requiresAbSlots",
                "requiresRangeDownload", "requiresProtectedSecurityCounter"), "requirements");
        literal(requirements, "profile", "TC_PROPERTY_COMPOSITE_V1");
        integer(requirements, "minimumRamBytes", 0);
        integer(requirements, "minimumFlashBytes", 0);
        require(requirements.get("requiresAbSlots") instanceof Boolean, "requirements.requiresAbSlots");
        require(requirements.get("requiresRangeDownload") instanceof Boolean, "requirements.requiresRangeDownload");
        require(Boolean.TRUE.equals(requirements.get("requiresProtectedSecurityCounter")), "requirements.requiresProtectedSecurityCounter");
        profile(value);
    }

    /** Profile只从冻结白名单解析，不透传JCA名称。 */
    private static OtaSignatureProfile profile(Map<String, Object> value) {
        String text = text(value, "signatureProfile");
        for (OtaSignatureProfile profile : OtaSignatureProfile.values()) {
            if (profile.value().equals(text)) {
                return profile;
            }
        }
        throw invalid("signatureProfile");
    }

    /** 返回严格字符串，不转换数字或null。 */
    private static String text(Map<String, Object> map, String name) {
        require(map.get(name) instanceof String, name);
        return (String) map.get(name);
    }

    /** 安全整数必须来自受限JSON解析，拒绝浮点及溢出。 */
    private static long integer(Map<String, Object> map, String name, long minimum) {
        require(map.get(name) instanceof Long, name);
        long number = (Long) map.get(name);
        require(number >= minimum && number <= MAX_INTEGER, name);
        return number;
    }

    /** 解析器保证映射键均为字符串，本处仅做嵌套对象检查。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Map<String, Object> map, String name) {
        require(map.get(name) instanceof Map<?, ?>, name);
        return (Map<String, Object>) map.get(name);
    }

    /** 拒绝缺项与未知项，避免静默忽略签名策略。 */
    private static void exact(Map<String, Object> map, Set<String> fields, String name) {
        require(map.keySet().equals(fields), name);
    }

    /** 冻结字面量必须精确匹配，不接受别名。 */
    private static void literal(Map<String, Object> map, String name, String expected) {
        require(expected.equals(map.get(name)), name);
    }

    /** UUID必须已是标准小写文本，避免同一身份多种表示。 */
    private static void uuid(String value, String name) {
        require(value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), name);
        require(UUID.fromString(value).toString().equals(value), name);
    }

    /** 产品标识复用注册规则，信任域与硬件允许点号。 */
    private static void identifier(String value, boolean allowDot, String name) {
        require(value.matches(allowDot ? "[A-Za-z0-9][A-Za-z0-9._-]{0,63}" : "[A-Za-z0-9][A-Za-z0-9_-]{0,63}"), name);
    }

    /** 字段资格失败统一报告静态字段名。 */
    private static void require(boolean condition, String field) {
        if (!condition) {
            throw invalid(field);
        }
    }

    /** 错误不得携带原始manifest或外部输入。 */
    private static IllegalArgumentException invalid(String field) {
        return new IllegalArgumentException("Invalid OTA manifest field: " + field);
    }
}
