package com.things.link.ota.application;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 只读安装前停止预检的受控扩展；配置声明不等同于硬件互斥或执行授权。 */
public final class OtaInstallStopBaselineCodec {
    /** 跨语言安全整数上限。 */
    private static final long SAFE = 9_007_199_254_740_991L;
    /** 唯一已冻结的原子安装停止互斥协议。 */
    public static final String PROFILE = "TC_OTA_INSTALL_STOP_JOURNAL_V1";
    /** 十一个完整字段，不接受自带摘要或未知能力。 */
    private static final Set<String> FIELDS = Set.of("contractVersion", "tenantId", "projectId", "deviceTypeId",
            "productKey", "typeBaselineVersion", "typeBaselineSha256", "stopBaselineVersion",
            "atomicOperationProfile", "bootloader", "evidenceReference");
    /** 有界严格JSON规范化。 */
    private final OtaCanonicalJson json = new OtaCanonicalJson();

    /** 只解析受控声明；父基线资格必须在真实事务内另行核对。 */
    public Decoded decode(byte[] bytes) {
        Map<String, Object> fields = json.parseObject(bytes);
        OtaTrustBundleCodec.closed(fields, FIELDS);
        if (!"tc-ota-install-stop-baseline/v1".equals(fields.get("contractVersion"))
                || !PROFILE.equals(fields.get("atomicOperationProfile"))) throw invalid();
        UUID tenant = OtaTrustBundleCodec.uuid(fields.get("tenantId"));
        UUID project = OtaTrustBundleCodec.uuid(fields.get("projectId"));
        UUID type = OtaTrustBundleCodec.uuid(fields.get("deviceTypeId"));
        String product = OtaTrustBundleCodec.text(fields.get("productKey"), "[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
        long parentVersion = OtaTrustBundleCodec.integer(fields.get("typeBaselineVersion"), 1, SAFE);
        String parentHash = OtaTrustBundleCodec.text(fields.get("typeBaselineSha256"), "[0-9a-f]{64}");
        long extensionVersion = OtaTrustBundleCodec.integer(fields.get("stopBaselineVersion"), 1, SAFE);
        var bootloader = OtaTrustBundleCodec.object(fields.get("bootloader"));
        OtaTrustBundleCodec.closed(bootloader, Set.of("minimumVersion", "maximumVersion"));
        String minimum = version(bootloader.get("minimumVersion"));
        String maximum = version(bootloader.get("maximumVersion"));
        if (compareVersions(minimum, maximum) > 0) throw invalid();
        String reference = evidence(fields.get("evidenceReference"));
        byte[] canonical = json.writeObject(fields);
        return new Decoded(new Baseline("tc-ota-install-stop-baseline/v1", tenant, project, type, product,
                parentVersion, parentHash, extensionVersion, PROFILE, new Bootloader(minimum, maximum), reference),
                canonical, OtaTrustBundleCodec.sha256(canonical));
    }
    /** bootloader三段按int边界解析，语法不接收厂商别名或前导零。 */
    private static String version(Object value) {
        String text = OtaTrustBundleCodec.text(value, "(0|[1-9][0-9]{0,9})\\.(0|[1-9][0-9]{0,9})\\.(0|[1-9][0-9]{0,9})");
        try { for (String axis : text.split("\\.")) Integer.parseInt(axis); }
        catch (NumberFormatException failure) { throw invalid(); }
        return text;
    }
    /** 比较数值元组，禁止用字典序把10判在2之前。 */
    static int compareVersions(String a, String b) {
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
    /** 固定配置错误不回显受控正文。 */
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("OTA安装前停止扩展基线合同不合法"); }
    /** 完整配置身份，包含全部字段而非仅版本号。
     * @param value 严格声明
     * @param canonical 规范字节
     * @param sha256 完整规范摘要
     */
    public record Decoded(Baseline value, byte[] canonical, String sha256) {
        /** 构造冻结字节。 */ public Decoded { canonical = canonical.clone(); }
        /** 每次返回独立副本。 */ @Override public byte[] canonical() { return canonical.clone(); }
    }
    /** 只允许收窄父基线版本区间。
     * @param minimumVersion 最低版本
     * @param maximumVersion 最高版本
     */
    public record Bootloader(String minimumVersion, String maximumVersion) { }
    /** 固定十一字段扩展，不自包含派生摘要。
     * @param contractVersion 合同版本
     * @param tenantId 租户
     * @param projectId 项目
     * @param deviceTypeId 类型
     * @param productKey 产品标识
     * @param typeBaselineVersion 父基线版本
     * @param typeBaselineSha256 父基线完整摘要
     * @param stopBaselineVersion 扩展版本
     * @param atomicOperationProfile 原子互斥协议
     * @param bootloader 受控版本区间
     * @param evidenceReference 受控证据索引
     */
    public record Baseline(String contractVersion, UUID tenantId, UUID projectId, UUID deviceTypeId,
            String productKey, long typeBaselineVersion, String typeBaselineSha256, long stopBaselineVersion,
            String atomicOperationProfile, Bootloader bootloader, String evidenceReference) { }
}
