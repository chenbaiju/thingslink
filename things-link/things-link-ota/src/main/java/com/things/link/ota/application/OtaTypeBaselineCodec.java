package com.things.link.ota.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 受控类型基线闭集及有界JCS身份，不从能力声明推导制造证据或设备资格。 */
public final class OtaTypeBaselineCodec {
    /** 跨语言精确整数上限。 */
    private static final long SAFE = 9_007_199_254_740_991L;
    /** 唯一有界规范化实现。 */
    private final OtaCanonicalJson json = new OtaCanonicalJson();
    /** 完整字段名闭集，不允许配置透传任意额外能力。 */
    private static final Set<String> FIELDS = Set.of("contractVersion", "tenantId", "projectId", "deviceTypeId",
            "productKey", "baselineVersion", "trustDomain", "rootFingerprint", "hardware", "bootloader",
            "signatureProfiles", "maximumArtifactBytes", "availableRamBytes", "availableFlashBytes",
            "supportsAbSlots", "supportsRangeDownload", "supportsResumeDownload", "protectedSecurityCounterBits",
            "compressionAlgorithms", "deltaModes", "propertyProfile", "evidenceReference");

    /** 严格解码并排序Profile集合；其他文本和数组不做静默变换。 */
    public Decoded decode(byte[] bytes) {
        Map<String, Object> fields = json.parseObject(bytes);
        OtaTrustBundleCodec.closed(fields, FIELDS);
        exact(fields.get("contractVersion"), "tc-ota-type-baseline/v1");
        UUID tenant = OtaTrustBundleCodec.uuid(fields.get("tenantId"));
        UUID project = OtaTrustBundleCodec.uuid(fields.get("projectId"));
        UUID type = OtaTrustBundleCodec.uuid(fields.get("deviceTypeId"));
        String product = OtaTrustBundleCodec.text(fields.get("productKey"), "[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
        long version = OtaTrustBundleCodec.integer(fields.get("baselineVersion"), 1, SAFE);
        String domain = identifier(fields.get("trustDomain"));
        String root = OtaTrustBundleCodec.text(fields.get("rootFingerprint"), "[0-9a-f]{64}");
        Map<String, Object> hardware = OtaTrustBundleCodec.object(fields.get("hardware"));
        OtaTrustBundleCodec.closed(hardware, Set.of("model", "boardRevisionMin", "boardRevisionMax"));
        Hardware target = new Hardware(identifier(hardware.get("model")),
                OtaTrustBundleCodec.integer(hardware.get("boardRevisionMin"), 0, SAFE),
                OtaTrustBundleCodec.integer(hardware.get("boardRevisionMax"), 0, SAFE));
        if (target.boardRevisionMin() > target.boardRevisionMax()) throw invalid();
        Map<String, Object> bootloader = OtaTrustBundleCodec.object(fields.get("bootloader"));
        OtaTrustBundleCodec.closed(bootloader, Set.of("minimumVersion", "maximumVersion"));
        String minimum = version(bootloader.get("minimumVersion"));
        String maximum = version(bootloader.get("maximumVersion"));
        if (compareVersions(minimum, maximum) > 0) throw invalid();
        List<OtaSignatureProfile> profiles = new ArrayList<>();
        for (Object value : OtaTrustBundleCodec.list(fields.get("signatureProfiles"), 2)) {
            OtaSignatureProfile profile = OtaTrustBundleCodec.profile(value);
            if (profiles.contains(profile)) throw invalid();
            profiles.add(profile);
        }
        profiles.sort(java.util.Comparator.comparing(OtaSignatureProfile::value));
        long artifact = OtaTrustBundleCodec.integer(fields.get("maximumArtifactBytes"), 1, 67_108_864);
        long ram = OtaTrustBundleCodec.integer(fields.get("availableRamBytes"), 0, SAFE);
        long flash = OtaTrustBundleCodec.integer(fields.get("availableFlashBytes"), 0, SAFE);
        boolean ab = bool(fields.get("supportsAbSlots"));
        boolean range = bool(fields.get("supportsRangeDownload"));
        boolean resume = bool(fields.get("supportsResumeDownload"));
        if (resume && !range) throw invalid();
        int bits = (int) OtaTrustBundleCodec.integer(fields.get("protectedSecurityCounterBits"), 0, 53);
        none(fields.get("compressionAlgorithms"));
        none(fields.get("deltaModes"));
        exact(fields.get("propertyProfile"), "TC_PROPERTY_COMPOSITE_V1");
        String evidence = evidence(fields.get("evidenceReference"));
        Map<String, Object> normalized = new LinkedHashMap<>(fields);
        normalized.put("signatureProfiles", profiles.stream().map(OtaSignatureProfile::value).toList());
        byte[] canonical = json.writeObject(normalized);
        Baseline value = new Baseline("tc-ota-type-baseline/v1", tenant, project, type, product, version, domain, root,
                target, new Bootloader(minimum, maximum), profiles, artifact, ram, flash, ab, range, resume, bits,
                List.of("NONE"), List.of("NONE"), "TC_PROPERTY_COMPOSITE_V1", evidence);
        return new Decoded(value, canonical, OtaTrustBundleCodec.sha256(canonical));
    }
    /** 固定类型标识，不trim不折叠大小写。 */
    private static String identifier(Object value) { return OtaTrustBundleCodec.text(value, "[A-Za-z0-9][A-Za-z0-9._-]{0,63}"); }
    /** 必须是真实JSON布尔，不接受字符串或数字替代。 */
    private static boolean bool(Object value) { if (!(value instanceof Boolean bool)) throw invalid(); return bool; }
    /** 本V1只支持唯一NONE，不接受空列表、重复或未实现算法。 */
    private static void none(Object value) { if (!List.of("NONE").equals(value)) throw invalid(); }
    /** 精确固定合同值。 */
    private static void exact(Object value, String expected) { if (!expected.equals(value)) throw invalid(); }
    /** bootloader三段按int边界解析，语法不接收厂商别名或前导零。 */
    private static String version(Object value) {
        String text = OtaTrustBundleCodec.text(value, "(0|[1-9][0-9]{0,9})\\.(0|[1-9][0-9]{0,9})\\.(0|[1-9][0-9]{0,9})");
        try { for (String axis : text.split("\\.")) Integer.parseInt(axis); }
        catch (NumberFormatException failure) { throw invalid(); }
        return text;
    }
    /** 比较数值元组，禁止用字典序把10判在2之前。 */
    private static int compareVersions(String a, String b) {
        String[] left = a.split("\\."); String[] right = b.split("\\.");
        for (int i = 0; i < 3; i++) {
            int difference = Integer.compare(Integer.parseInt(left[i]), Integer.parseInt(right[i]));
            if (difference != 0) return difference;
        }
        return 0;
    }
    /** 来源标识只作索引，限制码点和不可见控制字符，不声称自动验真。 */
    private static String evidence(Object value) {
        if (!(value instanceof String text) || text.isEmpty() || text.codePointCount(0, text.length()) > 256
                || whitespace(text.codePointAt(0)) || whitespace(text.codePointBefore(text.length()))
                || text.codePoints().anyMatch(c -> Character.isISOControl(c) || c >= 0xd800 && c <= 0xdfff)) throw invalid();
        return text;
    }
    /** 同时拒绝Java空白和Unicode间隔空格。 */
    private static boolean whitespace(int codePoint) { return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint); }
    /** 固定失败不外泄配置正文。 */
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("OTA类型基线合同不合法"); }
    /**
     * 完整规范基线与不可变身份。
     * @param value 强类型基线
     * @param canonical 精确规范字节
     * @param sha256 完整规范字节摘要
     */
    public record Decoded(Baseline value, byte[] canonical, String sha256) {
        /** 冻结规范字节。 */ public Decoded { canonical = canonical.clone(); }
        /** 返回独立副本。 */ @Override public byte[] canonical() { return canonical.clone(); }
    }
    /**
     * 硬件范围声明，不等价于制造实测。
     * @param model 型号标识
     * @param boardRevisionMin 最小板级整数序号
     * @param boardRevisionMax 最大板级整数序号
     */
    public record Hardware(String model, long boardRevisionMin, long boardRevisionMax) { }
    /**
     * 可比较bootloader范围。
     * @param minimumVersion 最低三轴版本
     * @param maximumVersion 最高三轴版本
     */
    public record Bootloader(String minimumVersion, String maximumVersion) { }
    /**
     * 受控来源完整基线，不隐式添加设备能力。
     * @param contractVersion 固定合同
     * @param tenantId 租户
     * @param projectId 项目
     * @param deviceTypeId 设备类型
     * @param productKey 产品标识
     * @param baselineVersion 基线版本
     * @param trustDomain 信任域
     * @param rootFingerprint 离线根指纹
     * @param hardware 硬件范围
     * @param bootloader 引导版本范围
     * @param signatureProfiles 受控算法集合
     * @param maximumArtifactBytes 最大固件长度
     * @param availableRamBytes RAM容量
     * @param availableFlashBytes Flash容量
     * @param supportsAbSlots 是否支持AB槽
     * @param supportsRangeDownload 是否支持Range
     * @param supportsResumeDownload 是否支持断点续传
     * @param protectedSecurityCounterBits 受保护计数器位数，0表示缺失
     * @param compressionAlgorithms 压缩能力
     * @param deltaModes 差分能力
     * @param propertyProfile 完整物模型Profile
     * @param evidenceReference 受控来源索引
     */
    public record Baseline(String contractVersion, UUID tenantId, UUID projectId, UUID deviceTypeId, String productKey,
            long baselineVersion, String trustDomain, String rootFingerprint, Hardware hardware, Bootloader bootloader,
            List<OtaSignatureProfile> signatureProfiles, long maximumArtifactBytes, long availableRamBytes,
            long availableFlashBytes, boolean supportsAbSlots, boolean supportsRangeDownload, boolean supportsResumeDownload,
            int protectedSecurityCounterBits, List<String> compressionAlgorithms, List<String> deltaModes,
            String propertyProfile, String evidenceReference) {
        /** 列表防御复制，防止解码后改写能力声明。 */
        public Baseline { signatureProfiles = List.copyOf(signatureProfiles); compressionAlgorithms = List.copyOf(compressionAlgorithms); deltaModes = List.copyOf(deltaModes); }
    }
}
