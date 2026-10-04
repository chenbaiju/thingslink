package com.things.link.device.api.dto.request;

import com.things.link.shared.message.TransportProtocol;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * ADR0193/0197：已知字段严格类型，未知字段沿ADR0042忽略。
 * @param protocol 新协议 @param enabled 新开关 @param expectedConfigVersion 规范十进制期望版本
 */
@JsonDeserialize(using = ChangeDeviceAccessConfigurationRequest.Deserializer.class)
public record ChangeDeviceAccessConfigurationRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"MQTT", "HTTP", "COAP", "TCP"}) String protocol,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean enabled,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^(0|[1-9][0-9]{0,18})$",
                description = "规范十进制字符串，最大9223372036854775807") String expectedConfigVersion) {

    /** 专用解析器避免Jackson将数字、字符串布尔值或空值默认为合法输入。 */
    public static final class Deserializer extends ValueDeserializer<ChangeDeviceAccessConfigurationRequest> {
        /** @param parser 原JSON输入 @param context 解析上下文 @return 严格校验后的三个字段 */
        @Override public ChangeDeviceAccessConfigurationRequest deserialize(JsonParser parser, DeserializationContext context) {
            JsonNode root = context.readTree(parser);
            if (root == null || !root.isObject() || !root.path("protocol").isString()
                    || !root.path("enabled").isBoolean() || !root.path("expectedConfigVersion").isString()) {
                return context.reportInputMismatch(ChangeDeviceAccessConfigurationRequest.class, "接入配置字段类型错误");
            }
            String protocol = root.path("protocol").asString(), version = root.path("expectedConfigVersion").asString();
            try {
                TransportProtocol.valueOf(protocol);
                if (!version.matches("0|[1-9][0-9]{0,18}") || Long.parseLong(version) < 0) throw new IllegalArgumentException();
            } catch (IllegalArgumentException invalid) {
                return context.reportInputMismatch(ChangeDeviceAccessConfigurationRequest.class, "接入协议或配置版本格式错误");
            }
            return new ChangeDeviceAccessConfigurationRequest(protocol, root.path("enabled").booleanValue(), version);
        }
    }
}
