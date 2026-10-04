package com.things.link.ota.api;

import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.StringSchema;
import java.util.ArrayList;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 明确基线数组项类型与闭集，避免注解生成器丢失字符串枚举。 */
@Configuration(proxyBeanMethods = false)
public class OtaTypeBaselineOpenApiConfiguration {
    /** 在真实生成模型中固化有限算法集合，不手改生成物。 */
    @Bean
    public OpenApiCustomizer otaTypeBaselineArrayCustomizer() {
        return api -> {
            if (api.getComponents() == null || api.getComponents().getSchemas() == null) return;
            var body = api.getComponents().getSchemas().get("OtaTypeBaselineBody");
            if (body == null) return;
            body.addProperty("signatureProfiles", array(List.of("TC_OTA_ED25519_V1", "TC_OTA_ES256_P1363_V1"), 2));
            body.addProperty("compressionAlgorithms", array(List.of("NONE"), 1));
            body.addProperty("deltaModes", array(List.of("NONE"), 1));
            // 数组注解的requiredMode未被生成器提升到所属对象，必须保留其余必填项并补齐。
            List<String> required = new ArrayList<>(body.getRequired() == null ? List.of() : body.getRequired());
            for (String property : List.of("signatureProfiles", "compressionAlgorithms", "deltaModes")) {
                if (!required.contains(property)) required.add(property);
            }
            body.setRequired(required);
        };
    }
    /** 构造有界且唯一的字符串枚举数组。 */
    private static ArraySchema array(List<String> values, int maximum) {
        StringSchema item = new StringSchema();
        item.setEnum(values);
        ArraySchema array = new ArraySchema();
        array.setItems(item);
        array.setMinItems(1);
        array.setMaxItems(maximum);
        array.setUniqueItems(true);
        return array;
    }
}
