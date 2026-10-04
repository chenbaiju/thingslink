package com.things.link.ota.application;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** ADR0118根签名信任包严格解析与验真，不推断当前有效期或设备确认事实。 */
public final class OtaTrustBundleCodec {
    /** 共用有界受限JCS。 */
    private final OtaCanonicalJson json = new OtaCanonicalJson();
    /** 共用双Profile严格公开密钥资格。 */
    private final OtaReleaseSignatureVerifier verifier = new OtaReleaseSignatureVerifier();

    /** 验证完整字段和受控根签名，返回防御复制的不可变包。 */
    public Bundle verify(byte[] input, byte[] signature, OtaTrustAnchors.Anchor root) {
        if (root == null) throw invalid();
        Bundle bundle = parse(input);
        if (!bundle.trustDomain().equals(root.trustDomain())
                || bundle.keys().stream().anyMatch(key -> key.fingerprint().equals(root.rootFingerprint()))
                || !verifier.verifyBundle(root.rootProfile(), root.rootSpki(), root.rootFingerprint(),
                bundle.canonical(), signature)) throw invalid();
        return bundle;
    }

    /** 只完成字段结构规范化，不把规范字节称为已获信任。 */
    public byte[] canonicalize(byte[] input) { return parse(input).canonical(); }

    /**
     * 已持久化、导入时已根验真的当前包之只读公开清单。
     *
     * <p><b>不重验根签名</b>：入参只能来自{@code ota_trust_bundle.canonical_bundle}，该表仅由
     * {@code OtaTrustService.importBundle}写入，而写入路径在落库前已完成根验真与字段闭集校验，
     * 且{@code thingslink_app}对该表只有SELECT/INSERT（UPDATE/DELETE已REVOKE）。因此本方法
     * 只把<b>已登记事实</b>投影给成员可读的密钥表，<b>不产生</b>任何密码学发布资格，
     * 也不得用于导入前的信任判断（那是{@link #verify}的职责）。
     *
     * <p>返回类型本身不含{@code spki}、规范字节或签名字节，调用方在类型层面就不可能把公钥材料
     * 误带进成员可读响应。{@code bundleSha256}由本方法对规范字节重算，用于调用方核对持久指针一致。
     *
     * @param persistedCanonicalBundle 已持久化的规范包字节
     * @return 只含域、包版本、包摘要与逐键公开元数据的清单
     */
    public PublicInventory inspect(byte[] persistedCanonicalBundle) {
        Bundle bundle = parse(persistedCanonicalBundle);
        return new PublicInventory(bundle.trustDomain(), bundle.bundleVersion(), bundle.sha256(),
                bundle.keys().stream().map(key -> new PublicKeyMetadata(key.keyVersion(), key.profile(),
                        key.fingerprint(), key.state(), key.notBefore(), key.notAfter())).toList());
    }

    /** 逐层闭集核验，不容许重复身份、未知枚举或无ACTIVE键的完整包。 */
    private Bundle parse(byte[] input) {
        Map<String, Object> fields = json.parseObject(input);
        closed(fields, Set.of("contractVersion", "trustDomain", "bundleVersion", "keys"));
        if (!"tc-ota-trust-bundle/v1".equals(fields.get("contractVersion"))) throw invalid();
        String domain = text(fields.get("trustDomain"), "[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
        long version = integer(fields.get("bundleVersion"), 1, 9_007_199_254_740_991L);
        List<?> entries = list(fields.get("keys"), 64);
        List<Key> keys = new ArrayList<>();
        Set<String> versions = new HashSet<>();
        Set<String> fingerprints = new HashSet<>();
        int active = 0;
        for (Object item : entries) {
            Map<String, Object> key = object(item);
            closed(key, Set.of("keyVersion", "signatureProfile", "spki", "fingerprint", "state", "notBefore", "notAfter"));
            String keyVersion = text(key.get("keyVersion"), "[A-Za-z0-9._:/-]{1,256}");
            OtaSignatureProfile profile = profile(key.get("signatureProfile"));
            byte[] spki = base64(key.get("spki"), 128);
            String fingerprint = text(key.get("fingerprint"), "[0-9a-f]{64}");
            if (!verifier.validKey(profile, spki, fingerprint) || !versions.add(keyVersion)
                    || !fingerprints.add(fingerprint)) throw invalid();
            String state = text(key.get("state"), "PREPARED|ACTIVE|VERIFY_ONLY|REVOKED");
            if (state.equals("ACTIVE")) active++;
            long before = integer(key.get("notBefore"), 0, 253_402_300_799L);
            long after = integer(key.get("notAfter"), 0, 253_402_300_799L);
            if (after <= before) throw invalid();
            keys.add(new Key(keyVersion, profile, spki, fingerprint, state, before, after));
        }
        if (active != 1) throw invalid();
        byte[] canonical = json.writeObject(fields);
        return new Bundle(domain, version, keys, canonical, sha256(canonical));
    }

    /** 闭集同时拒绝缺失和未知字段。 */
    static void closed(Map<String, Object> object, Set<String> names) {
        if (!object.keySet().equals(names)) throw invalid();
    }
    /** 严格类型读取字符串并检查完整正则匹配。 */
    static String text(Object value, String pattern) {
        if (!(value instanceof String string) || !string.matches(pattern)) throw invalid();
        return string;
    }
    /** 仅接受解析器明确的安全整数。 */
    static long integer(Object value, long min, long max) {
        if (!(value instanceof Long number) || number < min || number > max) throw invalid();
        return number;
    }
    /** 每种非空列表都有明确数量上限。 */
    static List<?> list(Object value, int max) {
        if (!(value instanceof List<?> list) || list.isEmpty() || list.size() > max) throw invalid();
        return list;
    }
    /** JSON解析器只生成字符串键对象，转换前逐键检查。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.keySet().stream().anyMatch(key -> !(key instanceof String))) throw invalid();
        return (Map<String, Object>) map;
    }
    /** Profile只匹配既有稳定枚举，不接受JCA算法文本。 */
    static OtaSignatureProfile profile(Object value) {
        for (OtaSignatureProfile profile : OtaSignatureProfile.values()) {
            if (profile.value().equals(value)) return profile;
        }
        throw invalid();
    }
    /** 必须为标准规范Base64，含必要填充；拒绝URL字母表及非零填充位。 */
    static byte[] base64(Object value, int maxBytes) {
        if (!(value instanceof String string) || string.length() > ((maxBytes + 2) / 3) * 4) throw invalid();
        try {
            byte[] bytes = Base64.getDecoder().decode(string);
            if (bytes.length == 0 || bytes.length > maxBytes || !Base64.getEncoder().encodeToString(bytes).equals(string)) throw invalid();
            return bytes;
        } catch (IllegalArgumentException exception) { throw invalid(); }
    }
    /** 标准UUID必须逐字符与小写规范形式相同。 */
    static UUID uuid(Object value) {
        if (!(value instanceof String string)) throw invalid();
        try {
            UUID uuid = UUID.fromString(string);
            if (!uuid.toString().equals(string)) throw invalid();
            return uuid;
        } catch (IllegalArgumentException exception) { throw invalid(); }
    }
    /** 完整规范字节的SHA256，不用数据库JSON重新打印替代。 */
    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("缺少SHA256"); }
    }
    /** 固定错误不包含配置、签名正文或原始异常。 */
    static IllegalArgumentException invalid() { return new IllegalArgumentException("OTA信任合同不合法"); }

    /**
     * 根验签后的完整不可变包，不等价于当前发布资格。
     * @param trustDomain 精确信任域
     * @param bundleVersion 单调安全版本
     * @param keys 完整发布键集合
     * @param canonical 精确JCS字节
     * @param sha256 精确字节摘要
     */
    public record Bundle(String trustDomain, long bundleVersion, List<Key> keys, byte[] canonical, String sha256) {
        /** 防止调用方改变持久包快照。 */
        public Bundle { keys = List.copyOf(keys); canonical = canonical.clone(); }
        /** 返回独立规范字节。 */
        @Override public byte[] canonical() { return canonical.clone(); }
    }
    /**
     * 根签名授权的单个公开发布键。
     * @param keyVersion 不可变版本标识
     * @param profile 固定签名Profile
     * @param spki 完整公开SPKI
     * @param fingerprint 完整SPKI摘要
     * @param state 四态枚举文本
     * @param notBefore 包含的有效起点epoch秒
     * @param notAfter 不包含的有效终点epoch秒
     */
    public record Key(String keyVersion, OtaSignatureProfile profile, byte[] spki, String fingerprint,
                      String state, long notBefore, long notAfter) {
        /** 构造时冻结公开密钥字节。 */
        public Key { spki = spki.clone(); }
        /** 返回独立公开密钥字节。 */
        @Override public byte[] spki() { return spki.clone(); }
    }
    /**
     * 已登记根签名包的公开清单，只回答「哪个域、哪一版包、包摘要是什么、有哪些发布键」。
     *
     * <p>这是成员可读密钥表能拿到的最宽事实：逐键只到{@link PublicKeyMetadata}，包级只有
     * 域/版本/摘要。规范字节、签名与根SPKI都不在其中。
     *
     * @param trustDomain 精确信任域
     * @param bundleVersion 当前不可变包版本
     * @param bundleSha256 当前包规范字节SHA256，由本类重算
     * @param keys 逐键公开元数据，顺序与包内一致
     */
    public record PublicInventory(String trustDomain, long bundleVersion, String bundleSha256,
                                  List<PublicKeyMetadata> keys) {
        /** 冻结键清单，调用方不能改变已登记快照。 */
        public PublicInventory { keys = List.copyOf(keys); }
    }
    /**
     * 单个发布键的公开元数据白名单。
     *
     * <p>刻意<b>不含</b>{@code spki}：指纹已足够标识键身份，成员可读的密钥表不需要公钥字节，
     * 少一个字段就少一条将来被误用的分发面；真要核对公钥应走受控发布/设备侧证据。
     * 也刻意<b>不含</b>逐键创建/退役时刻：{@code tc-ota-trust-bundle/v1}是冻结的七字段闭集，
     * 没有这两个字段，从包登记时刻反推单个键的生命周期只会编造事实。
     * {@code notBefore}/{@code notAfter}保持冻结合同的UTC epoch秒语义。
     *
     * @param keyVersion 不可变版本标识
     * @param profile 固定签名Profile
     * @param fingerprint 完整SPKI摘要
     * @param state 四态生命周期
     * @param notBefore 包含的有效起点epoch秒
     * @param notAfter 不包含的有效终点epoch秒
     */
    public record PublicKeyMetadata(String keyVersion, OtaSignatureProfile profile, String fingerprint,
                                    String state, long notBefore, long notAfter) { }
}
