package com.things.link.ota.application;

import java.util.Map;
import java.util.Set;

/** 新健康与提交协议的独立闭集词法工具，保持旧进度协议不变。 */
final class OtaConfirmationJson {
    /** 跨语言安全整数上限。 */
    private static final long MAX = 9007199254740991L;
    /** 平台可识别的槽标识，不在解析层把单槽误作语法异常。 */
    private static final Set<String> SLOTS = Set.of("A", "B", "SINGLE");
    /** 证据闭集，不接收设备指定的平台期限或状态修订。 */
    private static final Set<String> EVIDENCE = Set.of("artifactSha256",
            "artifactSize",
            "securityVersion",
            "committedSecurityVersion",
            "thingModelVersionId",
            "thingModelSchemaDigestAlgorithm",
            "thingModelSchemaDigest",
            "propertyProfile",
            "trustDomain",
            "rootFingerprint",
            "trustBundleVersion",
            "trustBundleSha256",
            "sourceSlot",
            "targetSlot",
            "activeSlot",
            "verification",
            "bootVerified",
            "selfTestPassed",
            "watchdogHealthy");
    /** 工具类不创建实例。 */
    private OtaConfirmationJson() { }
    /** 新协议原始输入上限及严格JSON解析。 */
    static Map<String, Object> parse(byte[] input, Set<String> fields, String version) {
        if (input == null || input.length == 0 || input.length > 16384) throw invalid();
        var value = new OtaCanonicalJson().parseObject(input);
        OtaTrustBundleCodec.closed(value, fields);
        literal(value, "contractVersion", Set.of(version));
        return value;
    }
    /** 原十九字段证据使用独立闭集解析，不判断安全语义组合。 */
    static OtaJobProgressCodec.Evidence evidence(Map<String, Object> e) {
        OtaTrustBundleCodec.closed(e, EVIDENCE);
        return new OtaJobProgressCodec.Evidence(hex(e, "artifactSha256"),
                integer(e, "artifactSize", 1, 67108864),
                integer(e, "securityVersion", 0, MAX),
                integer(e, "committedSecurityVersion", 0, MAX),
                OtaTrustBundleCodec.uuid(e.get("thingModelVersionId")),
                literal(e, "thingModelSchemaDigestAlgorithm", Set.of("PG_JSONB_TEXT_V1_SHA256")),
                hex(e, "thingModelSchemaDigest"),
                literal(e, "propertyProfile", Set.of("TC_PROPERTY_COMPOSITE_V1")),
                OtaTrustBundleCodec.text(e.get("trustDomain"), "[A-Za-z0-9][A-Za-z0-9._-]{0,63}"),
                hex(e, "rootFingerprint"),
                integer(e, "trustBundleVersion", 1, MAX),
                hex(e, "trustBundleSha256"),
                literal(e, "sourceSlot", SLOTS),
                literal(e, "targetSlot", SLOTS),
                literal(e, "activeSlot", SLOTS),
                literal(e, "verification", Set.of("NOT_STARTED", "PASSED")),
                flag(e, "bootVerified"),
                flag(e, "selfTestPassed"),
                flag(e, "watchdogHealthy"));
    }
    /** 精确文本枚举，不接受未知协议扩展。 */
    static String literal(Map<String, Object> map, String key, Set<String> allowed) {
        if (!(map.get(key) instanceof String text) || !allowed.contains(text)) throw invalid();
        return text;
    }
    /** 所有整数必须是真实JSON整数。 */
    static long integer(Map<String, Object> map, String key, long min, long max) {
        return OtaTrustBundleCodec.integer(map.get(key), min, max);
    }
    /** 完整小写摘要。 */
    static String hex(Map<String, Object> map, String key) {
        return OtaTrustBundleCodec.text(map.get(key), "[0-9a-f]{64}");
    }
    /** 真正的JSON布尔值，不转换字符串或数字。 */
    static boolean flag(Map<String, Object> map, String key) {
        if (!(map.get(key) instanceof Boolean value)) throw invalid();
        return value;
    }
    /** 固定协议错误不携带原文。 */
    static IllegalArgumentException invalid() { return new IllegalArgumentException("OTA进度合同不合法"); }

}
