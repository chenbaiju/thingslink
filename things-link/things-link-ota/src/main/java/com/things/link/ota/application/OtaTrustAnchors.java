package com.things.link.ota.application;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 仅从受控启动配置读取不可变离线根公钥，不提供HTTP修改或默认成功信任。 */
@Component
public final class OtaTrustAnchors {
    /** 按全局唯一信任域查找的不可变配置快照。 */
    private final Map<String, Anchor> anchors;

    /** 空配置不授权任何域；非空畸形配置立即阻止启动。 */
    public OtaTrustAnchors(@Value("${things-link.ota.trust.anchors-json:}") String configuration) {
        if (configuration == null || configuration.isEmpty()) { anchors = Map.of(); return; }
        if (configuration.length() > 65_536) throw OtaTrustBundleCodec.invalid();
        OtaCanonicalJson json = new OtaCanonicalJson();
        byte[] bytes;
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(configuration));
            bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
        } catch (CharacterCodingException exception) { throw OtaTrustBundleCodec.invalid(); }
        Map<String, Object> fields = json.parseObject(bytes);
        OtaTrustBundleCodec.closed(fields, Set.of("anchors"));
        Object items = fields.get("anchors");
        if (!(items instanceof List<?> list) || list.size() > 64) throw OtaTrustBundleCodec.invalid();
        Map<String, Anchor> parsed = new HashMap<>();
        OtaReleaseSignatureVerifier verifier = new OtaReleaseSignatureVerifier();
        for (Object item : list) {
            Map<String, Object> entry = OtaTrustBundleCodec.object(item);
            OtaTrustBundleCodec.closed(entry, Set.of("tenantId", "projectId", "trustDomain", "allowedDeviceTypeIds",
                    "rootProfile", "rootSpki", "rootFingerprint", "policyRevision"));
            UUID tenant = OtaTrustBundleCodec.uuid(entry.get("tenantId"));
            UUID project = OtaTrustBundleCodec.uuid(entry.get("projectId"));
            String domain = OtaTrustBundleCodec.text(entry.get("trustDomain"), "[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
            Set<UUID> types = new HashSet<>();
            for (Object type : OtaTrustBundleCodec.list(entry.get("allowedDeviceTypeIds"), 64)) {
                if (!types.add(OtaTrustBundleCodec.uuid(type))) throw OtaTrustBundleCodec.invalid();
            }
            OtaSignatureProfile profile = OtaTrustBundleCodec.profile(entry.get("rootProfile"));
            byte[] spki = OtaTrustBundleCodec.base64(entry.get("rootSpki"), 128);
            String fingerprint = OtaTrustBundleCodec.text(entry.get("rootFingerprint"), "[0-9a-f]{64}");
            if (!verifier.validKey(profile, spki, fingerprint)) throw OtaTrustBundleCodec.invalid();
            long revision = OtaTrustBundleCodec.integer(entry.get("policyRevision"), 1, 9_007_199_254_740_991L);
            Map<String, Object> normalized = new LinkedHashMap<>(entry);
            normalized.put("allowedDeviceTypeIds", new ArrayList<>(types.stream().map(UUID::toString).sorted().toList()));
            String hash = OtaTrustBundleCodec.sha256(json.writeObject(normalized));
            Anchor anchor = new Anchor(tenant, project, domain, types, profile, spki, fingerprint, revision, hash);
            if (parsed.putIfAbsent(domain, anchor) != null) throw OtaTrustBundleCodec.invalid();
        }
        anchors = Map.copyOf(parsed);
    }

    /** 范围不匹配或未配置均拒绝，不从请求manifest创建受信根。 */
    public Anchor require(UUID tenant, UUID project, String domain) {
        Anchor anchor = domain == null ? null : anchors.get(domain);
        if (anchor == null || !anchor.tenantId().equals(tenant) || !anchor.projectId().equals(project)) {
            throw OtaTrustBundleCodec.invalid();
        }
        return anchor;
    }

    /**
     * 启动时复制的根身份与授权配置。
     * @param tenantId 真实租户范围
     * @param projectId 真实项目范围
     * @param trustDomain 唯一域名标识
     * @param allowedDeviceTypeIds 受控配置授权的类型集合
     * @param rootProfile 离线根签名Profile
     * @param rootSpki 离线根公开SPKI
     * @param rootFingerprint 根完整SPKI摘要
     * @param policyRevision 单调配置修订
     * @param policyHash 含排序类型列表的完整配置项摘要
     */
    public record Anchor(UUID tenantId, UUID projectId, String trustDomain, Set<UUID> allowedDeviceTypeIds,
                         OtaSignatureProfile rootProfile, byte[] rootSpki, String rootFingerprint,
                         long policyRevision, String policyHash) {
        /** 复制所有可变集合和数组，防止启动后修改授权。 */
        public Anchor { allowedDeviceTypeIds = Set.copyOf(allowedDeviceTypeIds); rootSpki = rootSpki.clone(); }
        /** 返回独立根公钥，不暴露内部字节。 */
        @Override public byte[] rootSpki() { return rootSpki.clone(); }
    }
}
