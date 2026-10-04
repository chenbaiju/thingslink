package com.things.link.ota.api;

import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.ComposedSchema;
import java.util.List;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 明确OpenAPI3.1可空对象联合，避免非空ref与nullable类型并列仍排除null。 */
@Configuration(proxyBeanMethods = false)
public class OtaFirmwareLifecycleOpenApiConfiguration {
    /** 仅修正本域两个必填可空事件，不改变共享Transition定义或其他接口。 */
    @Bean
    public OpenApiCustomizer otaFirmwareLifecycleNullableTransitions() {
        return openApi -> {
            if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) return;
            Schema<?> response = openApi.getComponents().getSchemas().get("OtaFirmwareLifecycleResponse");
            if (response == null || response.getProperties() == null) return;
            for (String property : List.of("deprecation", "revocation")) {
                ComposedSchema nullable = new ComposedSchema();
                Schema<Object> reference = new Schema<>();
                reference.set$ref("#/components/schemas/OtaFirmwareLifecycleTransition");
                Schema<Object> nullValue = new Schema<>();
                nullValue.setTypes(Set.of("null"));
                nullable.setAnyOf(List.of(reference, nullValue));
                response.addProperty(property, nullable);
            }
        };
    }
}
