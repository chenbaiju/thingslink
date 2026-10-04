package com.things.link.ota.application;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 启动时受控类型基线快照，不接受设备报告或Console正文改变能力。 */
@Component
public final class OtaTypeBaselineSource {
    /** 三重范围索引，任何配置错误均不保留部分成功。 */
    private final Map<Identity, OtaTypeBaselineCodec.Decoded> baselines;
    /** 空配置没有基线；畸形或超预算配置立即启动失败。 */
    public OtaTypeBaselineSource(@Value("${things-link.ota.type-baselines-json:}") String configuration) {
        if (configuration == null || configuration.isEmpty()) { baselines = Map.of(); return; }
        if (configuration.length() > 65_536) throw invalid();
        byte[] bytes;
        try {
            ByteBuffer buffer = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(configuration));
            bytes = new byte[buffer.remaining()]; buffer.get(bytes);
        } catch (CharacterCodingException failure) { throw invalid(); }
        OtaCanonicalJson json = new OtaCanonicalJson();
        Map<String, Object> root = json.parseObject(bytes);
        OtaTrustBundleCodec.closed(root, Set.of("baselines"));
        if (!(root.get("baselines") instanceof List<?> list) || list.size() > 64) throw invalid();
        OtaTypeBaselineCodec codec = new OtaTypeBaselineCodec();
        Map<Identity, OtaTypeBaselineCodec.Decoded> parsed = new HashMap<>();
        for (Object item : list) {
            var decoded = codec.decode(json.writeObject(OtaTrustBundleCodec.object(item)));
            var value = decoded.value();
            Identity identity = new Identity(value.tenantId(), value.projectId(), value.deviceTypeId());
            if (parsed.putIfAbsent(identity, decoded) != null) throw invalid();
        }
        baselines = Map.copyOf(parsed);
    }
    /** 精确范围缺配置即拒绝，不从数据库旧值或设备声明补齐。 */
    public OtaTypeBaselineCodec.Decoded require(UUID tenant, UUID project, UUID type) {
        if (tenant == null || project == null || type == null) throw invalid();
        var result = baselines.get(new Identity(tenant, project, type));
        if (result == null) throw invalid();
        return result;
    }
    /** 固定配置异常，不返回任何受控来源正文。 */
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("OTA类型基线未配置或配置不合法"); }
    /**
     * 精确配置索引，不假设type UUID可代替租户项目范围。
     * @param tenant 租户
     * @param project 项目
     * @param type 类型
     */
    private record Identity(UUID tenant, UUID project, UUID type) { }
}
