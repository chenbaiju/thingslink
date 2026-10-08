package com.things.link.ingestion.infrastructure;

import com.things.link.shared.message.StandardUplinkMessage;
import com.things.link.shared.message.EventUplinkMessage;
import com.things.link.shared.message.GatewayBatchMessage;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.header.Headers;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.JacksonUtils;

import java.io.IOException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/**
 * 保留默认Kafka类型头与trusted-packages判定，仅标准遥测、网关批量及独立事件信封再按精确数值恢复载荷。
 *
 * <p>先让既有反序列化器完成类型白名单与构造约束，再对已确认的标准、批量或事件类型原字节精确读取。
 * 不以客户端自报类型绕开白名单；其他消息类直接保留原解码结果。batch、normalized、processed和事件主题共用该默认适配。</p>
 */
public final class UplinkPayloadPrecisionDeserializer extends JsonDeserializer<Object> {
    /** 受信类型检查仍由父类执行，事件构造前则必须避免默认Double中间值改变边界。 */
    public UplinkPayloadPrecisionDeserializer() {
        super(eventAwareMapper());
    }

    /** @return 只为事件类型注册精确读取器的Kafka局部映射器，不修改其他类或Boot配置 */
    private static com.fasterxml.jackson.databind.ObjectMapper eventAwareMapper() {
        var mapper = JacksonUtils.enhancedObjectMapper();
        var exactEvent = mapper.copy().readerFor(EventUplinkMessage.class)
                .with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
        var module = new com.fasterxml.jackson.databind.module.SimpleModule();
        module.addDeserializer(EventUplinkMessage.class,
                new com.fasterxml.jackson.databind.deser.std.StdDeserializer<EventUplinkMessage>(EventUplinkMessage.class) {
                    @Override
                    public EventUplinkMessage deserialize(com.fasterxml.jackson.core.JsonParser parser,
                            com.fasterxml.jackson.databind.DeserializationContext context) throws IOException {
                        return exactEvent.readValue(parser);
                    }
                });
        return mapper.registerModule(module);
    }
    /** 独立reader，不更改Boot或Kafka默认映射器。 */
    private final ObjectReader exactReader = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build()
            .readerFor(StandardUplinkMessage.class);

    /** batch保留既有sealed DEDUCTION形状，递归恢复子设备载荷中的原始十进制。 */
    private final ObjectReader batchReader = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build()
            .readerFor(GatewayBatchMessage.class);

    /** 事件保留完整私有语义中的十进制scale与大整数，不能舍入后比较重放摘要。 */
    private final ObjectReader eventReader = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).build()
            .readerFor(EventUplinkMessage.class);

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

    /** 仅已通过默认类型与构造验证的三种上行信封读取原始精确数值字节。 */
    private Object restore(Object decoded, byte[] data) {
        ObjectReader reader = decoded instanceof StandardUplinkMessage ? exactReader
                : decoded instanceof GatewayBatchMessage ? batchReader
                : decoded instanceof EventUplinkMessage ? eventReader : null;
        if (reader == null) return decoded;
        try {
            return reader.readValue(data);
        } catch (RuntimeException cause) {
            throw new SerializationException("上行载荷信封精确数值解码失败", cause);
        }
    }
}
