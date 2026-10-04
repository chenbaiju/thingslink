package com.things.link.ingestion.infrastructure;

import com.things.link.shared.message.StandardUplinkMessage;
import com.things.link.shared.message.GatewayBatchMessage;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.header.Headers;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/**
 * 保留默认Kafka类型头与trusted-packages判定，仅标准遥测及网关批量信封再按精确十进制恢复载荷。
 *
 * <p>先让既有反序列化器完成类型白名单与构造约束，再对已确认StandardUplinkMessage或GatewayBatchMessage的原字节精确读取。
 * 不以客户端自报类型绕开白名单；其他消息类直接保留原解码结果。batch、normalized和processed共用该默认适配。</p>
 */
public final class UplinkPayloadPrecisionDeserializer extends JsonDeserializer<Object> {
    /** 独立reader，不更改Boot或Kafka默认映射器。 */
    private final ObjectReader exactReader = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build()
            .readerFor(StandardUplinkMessage.class);

    /** batch保留既有sealed DEDUCTION形状，递归恢复子设备载荷中的原始十进制。 */
    private final ObjectReader batchReader = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build()
            .readerFor(GatewayBatchMessage.class);

    /** 带类型头路径先执行原有受信包检查。 */
    @Override
    public Object deserialize(String topic, Headers headers, byte[] data) {
        return restore(super.deserialize(topic, headers, data), data);
    }

    /** 无类型头保留默认Object/显式目标配置；不按正文形状猜测消息身份。 */
    @Override
    public Object deserialize(String topic, byte[] data) {
        return restore(super.deserialize(topic, data), data);
    }

    /** 仅已通过默认类型与构造验证的两种上行信封读取原始十进制字节。 */
    private Object restore(Object decoded, byte[] data) {
        ObjectReader reader = decoded instanceof StandardUplinkMessage ? exactReader
                : decoded instanceof GatewayBatchMessage ? batchReader : null;
        if (reader == null) return decoded;
        try {
            return reader.readValue(data);
        } catch (RuntimeException cause) {
            throw new SerializationException("上行载荷信封精确数值解码失败", cause);
        }
    }
}
