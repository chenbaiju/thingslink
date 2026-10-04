package com.things.link.ingestion.infrastructure;

import com.things.link.ingestion.application.InvalidUplinkMessageException;
import com.things.link.rule.application.engine.RuleMessage;
import com.things.link.shared.message.StandardUplinkMessage;
import com.things.link.shared.message.TransportProtocol;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.DeserializationFeature;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 在标准信封与规则快照之间保留 ADR 0017 全部可信字段。 */
public final class RuleUplinkMessageMapper {

    /** 规则消息原生字段未包含的网关身份；空值使用空串编码。 */
    private static final String GATEWAY_ID = "thingslink.trusted.gatewayId";
    /** 传输协议不可由 tenant 脚本改写。 */
    private static final String PROTOCOL = "thingslink.trusted.protocol";
    /** 平台接收时间与设备时间语义不同，必须独立保存。 */
    private static final String RECEIVED_AT = "thingslink.trusted.receivedAt";
    /** 计费使用原始字节数，不能按脚本输出 JSON 重新估算。 */
    private static final String RAW_BYTES = "thingslink.trusted.rawBytes";
    /** 写入时物模型版本是设备声明后冻结的语义元数据，规则不得改写。 */
    private static final String MODEL_VERSION = "thingslink.trusted.modelVersion";

    /** Boot 统一 JSON 映射器。 */
    private final ObjectMapper objectMapper;

    /** @param objectMapper JSON 树与标准 Map 的统一映射器 */
    public RuleUplinkMessageMapper(ObjectMapper objectMapper) {
        // 规则可信载荷树与Map转换不能把未修改的十进制值降为Double。
        this.objectMapper = objectMapper.rebuild()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    }

    /** @param source 已确权标准上行 @return 只允许脚本派生 payload 的规则快照 */
    public RuleMessage toRuleMessage(StandardUplinkMessage source) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put(GATEWAY_ID, source.gatewayId() == null ? "" : source.gatewayId().toString());
        metadata.put(PROTOCOL, source.protocol().name());
        metadata.put(RECEIVED_AT, source.receivedAt().toString());
        metadata.put(RAW_BYTES, Integer.toString(source.rawBytes()));
        // 存量标量省略 modelVersion 时为 null；规则 metadata 是 Map<String,String> 且 RuleMessage 用 Map.copyOf
        // 冻结，null 值会触发 NPE，因此沿用 GATEWAY_ID 的空串编码，映射回标准信封时再还原为 null。
        metadata.put(MODEL_VERSION, source.modelVersion() == null ? "" : source.modelVersion());
        return new RuleMessage(source.messageId(), source.tenantId(), source.projectId(), source.deviceId(),
                source.traceId(), source.occurredAt(), source.type().name(),
                objectMapper.valueToTree(source.payload()), metadata);
    }

    /**
     * @param original 入队前可信规则快照
     * @param transformed 规则处理器只派生 payload 的最终快照
     * @return 恢复完整可信字段的标准上行
     */
    public StandardUplinkMessage toStandardMessage(RuleMessage original, RuleMessage transformed) {
        requireTrustedIdentity(original, transformed);
        try {
            Map<String, String> metadata = original.metadata();
            String gateway = metadata.get(GATEWAY_ID);
            Map<String, Object> payload = objectMapper.convertValue(
                    transformed.payload(), new TypeReference<Map<String, Object>>() { });
            return new StandardUplinkMessage(original.messageId(), original.tenantId(), original.projectId(),
                    original.deviceId(), gateway == null || gateway.isBlank() ? null : UUID.fromString(gateway),
                    TransportProtocol.valueOf(metadata.get(PROTOCOL)), StandardUplinkMessage.Direction.UP,
                    StandardUplinkMessage.Type.valueOf(original.type()),
                    blankToNull(metadata.get(MODEL_VERSION)),
                    original.occurredAt(),
                    Instant.parse(metadata.get(RECEIVED_AT)), original.traceId(),
                    Integer.parseInt(metadata.get(RAW_BYTES)), payload);
        } catch (RuntimeException exception) {
            throw new InvalidUplinkMessageException("规则结果无法恢复可信标准上行", exception);
        }
    }

    /** 脚本只能改变 payload；metadata 也由 adapter 私有可信键占用，不能接受处理器派生结果。 */
    private static void requireTrustedIdentity(RuleMessage original, RuleMessage transformed) {
        if (!original.messageId().equals(transformed.messageId())
                || !original.tenantId().equals(transformed.tenantId())
                || !original.projectId().equals(transformed.projectId())
                || !original.deviceId().equals(transformed.deviceId())
                || !original.traceId().equals(transformed.traceId())
                || !original.occurredAt().equals(transformed.occurredAt())
                || !original.type().equals(transformed.type())
                || !original.metadata().equals(transformed.metadata())) {
            throw new InvalidUplinkMessageException("规则不得修改已确权的信封身份、时间、traceId 或元数据");
        }
    }

    /** 把规则 metadata 里可空字符串键的空串编码还原为 null，供标准信封可空 modelVersion 使用。 */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
