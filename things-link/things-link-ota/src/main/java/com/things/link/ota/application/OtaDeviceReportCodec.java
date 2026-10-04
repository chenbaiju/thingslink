package com.things.link.ota.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 设备报告闭集规范化，只表示经认证运行声明，不证明实机能力。 */
public final class OtaDeviceReportCodec {
    /** 跨语言安全整数上限。 */
    private static final long MAX = 9_007_199_254_740_991L;
    /** 有界规范JSON。 */
    private final OtaCanonicalJson json = new OtaCanonicalJson();
    /** 顶层精确字段集合，禁止正文注入身份。 */
    private static final Set<String> FIELDS = Set.of("contractVersion", "reportSequence", "hardware", "bootloaderVersion", "currentFirmwareVersion", "currentFirmwareSha256", "currentSecurityVersion", "committedSecurityVersion", "thingModelVersionId", "thingModelSchemaDigestAlgorithm", "thingModelSchemaDigest", "propertyProfile", "trustDomain", "rootFingerprint", "trustBundleVersion", "trustBundleSha256", "signatureProfiles", "maximumArtifactBytes", "availableRamBytes", "availableFlashBytes", "supportsAbSlots", "supportsRangeDownload", "supportsResumeDownload", "protectedSecurityCounterBits", "compressionAlgorithms", "deltaModes", "activeSlot", "bootState");
    /** 严格验真报告结构、范围及内部一致性。 */
    public Decoded decode(byte[] input) {
        Map<String,Object> v = json.parseObject(input);
        OtaTrustBundleCodec.closed(v, FIELDS);
        literal(v,"contractVersion","tc-ota-device-report/v1");
        literal(v,"thingModelSchemaDigestAlgorithm","PG_JSONB_TEXT_V1_SHA256");
        literal(v,"propertyProfile","TC_PROPERTY_COMPOSITE_V1");
        var hw = OtaTrustBundleCodec.object(v.get("hardware"));
        OtaTrustBundleCodec.closed(hw,Set.of("model","boardRevision"));
        Hardware hardware = new Hardware(identifier(hw.get("model")),integer(hw,"boardRevision",0,MAX));
        String bootloaderVersion = version(v.get("bootloaderVersion"));
        String display = OtaTrustBundleCodec.text(v.get("currentFirmwareVersion"),"(?s).+");
        if (display.isBlank() || display.codePointCount(0,display.length()) > 128
                || display.codePoints().anyMatch(Character::isISOControl)) throw invalid();
        long current = integer(v,"currentSecurityVersion",0,MAX);
        long committed = integer(v,"committedSecurityVersion",0,MAX);
        if (current < committed) throw invalid();
        List<OtaSignatureProfile> profiles = new ArrayList<>();
        for (Object item : OtaTrustBundleCodec.list(v.get("signatureProfiles"),2)) {
            var profile = OtaTrustBundleCodec.profile(item);
            if (profiles.contains(profile)) throw invalid();
            profiles.add(profile);
        }
        profiles.sort(Comparator.comparing(OtaSignatureProfile::value));
        boolean ab = bool(v,"supportsAbSlots"), range = bool(v,"supportsRangeDownload"), resume = bool(v,"supportsResumeDownload");
        if (resume && !range) throw invalid();
        for (String name : List.of("compressionAlgorithms","deltaModes"))
            if (!List.of("NONE").equals(v.get(name))) throw invalid();
        String slot = OtaTrustBundleCodec.text(v.get("activeSlot"),"A|B|SINGLE");
        if (ab == "SINGLE".equals(slot)) throw invalid();
        String state = OtaTrustBundleCodec.text(v.get("bootState"),"HEALTHY|TESTING|RECOVERY_REQUIRED");
        var report = new Report("tc-ota-device-report/v1",integer(v,"reportSequence",1,MAX),hardware,
                bootloaderVersion,display,hex(v,"currentFirmwareSha256"),current,committed,
                OtaTrustBundleCodec.uuid(v.get("thingModelVersionId")),"PG_JSONB_TEXT_V1_SHA256",
                hex(v,"thingModelSchemaDigest"),"TC_PROPERTY_COMPOSITE_V1",identifier(v.get("trustDomain")),
                hex(v,"rootFingerprint"),integer(v,"trustBundleVersion",1,MAX),hex(v,"trustBundleSha256"),profiles,
                integer(v,"maximumArtifactBytes",1,67_108_864),integer(v,"availableRamBytes",0,MAX),
                integer(v,"availableFlashBytes",0,MAX),ab,range,resume,
                (int)integer(v,"protectedSecurityCounterBits",0,53),List.of("NONE"),List.of("NONE"),slot,state);
        var normalized = new LinkedHashMap<>(v);
        normalized.put("signatureProfiles",profiles.stream().map(OtaSignatureProfile::value).toList());
        byte[] canonical = json.writeObject(normalized);
        return new Decoded(report,canonical,OtaTrustBundleCodec.sha256(canonical));
    }
    /** 真实JSON整数及边界。 */
    private static long integer(Map<String,Object> v,String name,long min,long max) { return OtaTrustBundleCodec.integer(v.get(name),min,max); }
    /** 固定字面量。 */
    private static void literal(Map<String,Object> v,String name,String value) { if (!value.equals(v.get(name))) throw invalid(); }
    /** 摘要不折叠大小写。 */
    private static String hex(Map<String,Object> v,String name) { return OtaTrustBundleCodec.text(v.get(name),"[0-9a-f]{64}"); }
    /** 原始标识不trim或替换。 */
    private static String identifier(Object value) { return OtaTrustBundleCodec.text(value,"[A-Za-z0-9][A-Za-z0-9._-]{0,63}"); }
    /** 不接受布尔字符串或数字。 */
    private static boolean bool(Map<String,Object> v,String name) { if (!(v.get(name) instanceof Boolean b)) throw invalid(); return b; }
    /** 三段非负int，无前导零。 */
    private static String version(Object value) {
        String result = OtaTrustBundleCodec.text(value,"(0|[1-9][0-9]{0,9})\\.(0|[1-9][0-9]{0,9})\\.(0|[1-9][0-9]{0,9})");
        try { for (String part : result.split("\\.")) Integer.parseInt(part); }
        catch (NumberFormatException failure) { throw invalid(); }
        return result;
    }
    /** 固定失败不返回原始正文。 */
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("OTA设备报告合同不合法"); }
    /** 规范身份防御复制。
     * @param value 完整报告
     * @param canonical 规范字节
     * @param sha256 规范摘要
     */
    public record Decoded(Report value,byte[] canonical,String sha256) {
        /** 冻结字节。 */ public Decoded { canonical = canonical.clone(); }
        /** 返回独立副本。 */ @Override public byte[] canonical() { return canonical.clone(); }
    }
    /** 硬件运行声明。
     * @param model 型号
     * @param boardRevision 实际板级序号
     */
    public record Hardware(String model,long boardRevision) { }
    /** 完整设备运行声明，不携带认证身份。
     * @param contractVersion 报告字段contractVersion
     * @param reportSequence 报告字段reportSequence
     * @param hardware 报告字段hardware
     * @param bootloaderVersion 报告字段bootloaderVersion
     * @param currentFirmwareVersion 报告字段currentFirmwareVersion
     * @param currentFirmwareSha256 报告字段currentFirmwareSha256
     * @param currentSecurityVersion 报告字段currentSecurityVersion
     * @param committedSecurityVersion 报告字段committedSecurityVersion
     * @param thingModelVersionId 报告字段thingModelVersionId
     * @param thingModelSchemaDigestAlgorithm 报告字段thingModelSchemaDigestAlgorithm
     * @param thingModelSchemaDigest 报告字段thingModelSchemaDigest
     * @param propertyProfile 报告字段propertyProfile
     * @param trustDomain 报告字段trustDomain
     * @param rootFingerprint 报告字段rootFingerprint
     * @param trustBundleVersion 报告字段trustBundleVersion
     * @param trustBundleSha256 报告字段trustBundleSha256
     * @param signatureProfiles 报告字段signatureProfiles
     * @param maximumArtifactBytes 报告字段maximumArtifactBytes
     * @param availableRamBytes 报告字段availableRamBytes
     * @param availableFlashBytes 报告字段availableFlashBytes
     * @param supportsAbSlots 报告字段supportsAbSlots
     * @param supportsRangeDownload 报告字段supportsRangeDownload
     * @param supportsResumeDownload 报告字段supportsResumeDownload
     * @param protectedSecurityCounterBits 报告字段protectedSecurityCounterBits
     * @param compressionAlgorithms 报告字段compressionAlgorithms
     * @param deltaModes 报告字段deltaModes
     * @param activeSlot 报告字段activeSlot
     * @param bootState 报告字段bootState
     */
    public record Report(String contractVersion,
            long reportSequence,
            Hardware hardware,
            String bootloaderVersion,
            String currentFirmwareVersion,
            String currentFirmwareSha256,
            long currentSecurityVersion,
            long committedSecurityVersion,
            UUID thingModelVersionId,
            String thingModelSchemaDigestAlgorithm,
            String thingModelSchemaDigest,
            String propertyProfile,
            String trustDomain,
            String rootFingerprint,
            long trustBundleVersion,
            String trustBundleSha256,
            List<OtaSignatureProfile> signatureProfiles,
            long maximumArtifactBytes,
            long availableRamBytes,
            long availableFlashBytes,
            boolean supportsAbSlots,
            boolean supportsRangeDownload,
            boolean supportsResumeDownload,
            int protectedSecurityCounterBits,
            List<String> compressionAlgorithms,
            List<String> deltaModes,
            String activeSlot,
            String bootState) {
        /** 冻结列表，规范身份计算后不能修改能力。 */
        public Report { signatureProfiles = List.copyOf(signatureProfiles); compressionAlgorithms = List.copyOf(compressionAlgorithms); deltaModes = List.copyOf(deltaModes); }
    }
}
