package com.things.link.ota.application;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 认证进度闭集词法合同，安全证据匹配及阶段采用由事务服务决定。 */
public final class OtaJobProgressCodec {
    /** 跨语言安全整数上限。 */
    private static final long MAX = 9007199254740991L;
    /** 平台可识别的槽标识，不在解析层把单槽误作语法异常。 */
    private static final Set<String> SLOTS = Set.of("A", "B", "SINGLE");
    /** 顶层完整九字段。 */
    private static final Set<String> FIELDS = Set.of("contractVersion", "jobId", "attemptNo", "progressSeq",
            "authorizationId", "manifestSha256", "stage", "bootId", "evidence");
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
    /** 唯一规范JSON实现。 */
    private final OtaCanonicalJson json = new OtaCanonicalJson();

    /** 原输入独立限制16KiB，拒绝重复字段/null/浮点，但不裁决阶段flag组合。 */
    public Decoded decode(byte[] input) {
        if (input == null || input.length == 0 || input.length > 16384) throw invalid();
        var value = json.parseObject(input);
        OtaTrustBundleCodec.closed(value, FIELDS);
        literal(value, "contractVersion", Set.of("tc-ota-job-progress/v1"));
        if (!(value.get("evidence") instanceof Map<?, ?>)) throw invalid();
        @SuppressWarnings("unchecked") Map<String, Object> e = (Map<String, Object>) value.get("evidence");
        OtaTrustBundleCodec.closed(e, EVIDENCE);
        Evidence evidence = new Evidence(hex(e, "artifactSha256"),
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
        var progress = new Progress("tc-ota-job-progress/v1", OtaTrustBundleCodec.uuid(value.get("jobId")),
                (int) integer(value, "attemptNo", 1, Integer.MAX_VALUE), integer(value, "progressSeq", 1, MAX),
                OtaTrustBundleCodec.uuid(value.get("authorizationId")), hex(value, "manifestSha256"),
                literal(value, "stage", Set.of("VERIFYING", "INSTALLING", "REBOOTING", "HEALTH_CHECKING")),
                OtaTrustBundleCodec.uuid(value.get("bootId")), evidence);
        byte[] canonical = json.writeObject(value);
        return new Decoded(progress, canonical, OtaTrustBundleCodec.sha256(canonical));
    }

    /** 精确文本枚举，不接受未知协议扩展。 */
    private static String literal(Map<String, Object> map, String key, Set<String> allowed) {
        if (!(map.get(key) instanceof String text) || !allowed.contains(text)) throw invalid();
        return text;
    }
    /** 所有整数必须是真实JSON整数。 */
    private static long integer(Map<String, Object> map, String key, long min, long max) {
        return OtaTrustBundleCodec.integer(map.get(key), min, max);
    }
    /** 完整小写摘要。 */
    private static String hex(Map<String, Object> map, String key) {
        return OtaTrustBundleCodec.text(map.get(key), "[0-9a-f]{64}");
    }
    /** 真正的JSON布尔值，不转换字符串或数字。 */
    private static boolean flag(Map<String, Object> map, String key) {
        if (!(map.get(key) instanceof Boolean value)) throw invalid();
        return value;
    }
    /** 固定协议错误不携带原文。 */
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("OTA进度合同不合法"); }

    /** 完整设备进度，不包含自报设备身份。
     * @param contractVersion 固定合同
     * @param jobId 原作业
     * @param attemptNo 原尝试
     * @param progressSeq 单调进度序号
     * @param authorizationId 原封存授权
     * @param manifestSha256 原发布清单摘要
     * @param stage 声明阶段
     * @param bootId 当前启动身份
     * @param evidence 完整观察证据
     */
    public record Progress(String contractVersion, UUID jobId, int attemptNo, long progressSeq, UUID authorizationId,
            String manifestSha256, String stage, UUID bootId, Evidence evidence) { }

    /** 证据词法投影，语义矛盾仍交给真实安全裁决。
     * @param artifactSha256 设备观察的artifactSha256字段
     * @param artifactSize 设备观察的artifactSize字段
     * @param securityVersion 设备观察的securityVersion字段
     * @param committedSecurityVersion 设备观察的committedSecurityVersion字段
     * @param thingModelVersionId 设备观察的thingModelVersionId字段
     * @param thingModelSchemaDigestAlgorithm 设备观察的thingModelSchemaDigestAlgorithm字段
     * @param thingModelSchemaDigest 设备观察的thingModelSchemaDigest字段
     * @param propertyProfile 设备观察的propertyProfile字段
     * @param trustDomain 设备观察的trustDomain字段
     * @param rootFingerprint 设备观察的rootFingerprint字段
     * @param trustBundleVersion 设备观察的trustBundleVersion字段
     * @param trustBundleSha256 设备观察的trustBundleSha256字段
     * @param sourceSlot 设备观察的sourceSlot字段
     * @param targetSlot 设备观察的targetSlot字段
     * @param activeSlot 设备观察的activeSlot字段
     * @param verification 设备观察的verification字段
     * @param bootVerified 设备观察的bootVerified字段
     * @param selfTestPassed 设备观察的selfTestPassed字段
     * @param watchdogHealthy 设备观察的watchdogHealthy字段
     */
    public record Evidence(String artifactSha256,
            long artifactSize,
            long securityVersion,
            long committedSecurityVersion,
            UUID thingModelVersionId,
            String thingModelSchemaDigestAlgorithm,
            String thingModelSchemaDigest,
            String propertyProfile,
            String trustDomain,
            String rootFingerprint,
            long trustBundleVersion,
            String trustBundleSha256,
            String sourceSlot,
            String targetSlot,
            String activeSlot,
            String verification,
            boolean bootVerified,
            boolean selfTestPassed,
            boolean watchdogHealthy) { }

    /** 不可变规范化进度。
     * @param value 类型化合同
     * @param canonical 完整规范字节
     * @param sha256 规范摘要
     */
    public record Decoded(Progress value, byte[] canonical, String sha256) {
        /** 冻结规范字节。 */ public Decoded { canonical = canonical.clone(); }
        /** 输出独立副本。 */ @Override public byte[] canonical() { return canonical.clone(); }
    }
}
