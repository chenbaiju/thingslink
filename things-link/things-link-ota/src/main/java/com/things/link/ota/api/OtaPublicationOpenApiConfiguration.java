package com.things.link.ota.api;

import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 保留严格manifest的JSON标量类型，修正注解生成器丢失数组类型及布尔枚举字符串化。 */
@Configuration(proxyBeanMethods = false)
public class OtaPublicationOpenApiConfiguration {
    /** 使用模型API明确JSON类型，不手改生成合同，也不改变运行时严格解析。 */
    @Bean
    public OpenApiCustomizer otaPublicationScalarSchemaCustomizer() {
        return api -> {
            if (api.getComponents() == null || api.getComponents().getSchemas() == null) return;
            var schemas = api.getComponents().getSchemas();
            Schema<?> manifest = schemas.get("OtaPublicationManifest");
            if (manifest != null && manifest.getProperties() != null) {
                Schema<?> sources = manifest.getProperties().get("allowedSourceThingModelVersionIds");
                if (sources != null) sources.setItems(new StringSchema().format("uuid"));
            }
            Schema<?> requirements = schemas.get("OtaPublicationRequirements");
            if (requirements != null && requirements.getProperties() != null) {
                BooleanSchema protectedCounter = new BooleanSchema();
                protectedCounter.setEnum(List.of(true));
                requirements.addProperty("requiresProtectedSecurityCounter", protectedCounter);
            }
        };
    }
}
