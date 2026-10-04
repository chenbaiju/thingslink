package com.things.link.ota.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 联合类型的生成顺序是稳定输出合同，不能依赖JVM随机集合次序。 */
class OtaCampaignRuntimeOpenApiConfigurationTests {
    /** 同一业务合同在独立上下文仍保持非空类型在前，且不删除nullable信息。 */
    @Test void nullableScalarTypesHaveStableWireOrder() {
        var components=new Components();
        for(String name:List.of("OtaCampaignExecutionResponse","OtaCampaignRuntimeCancellationResponse",
                "OtaCampaignBatchProgressResponse")) components.addSchemas(name,new Schema<>());
        var api=new OpenAPI().components(components);
        new OtaCampaignRuntimeOpenApiConfiguration().otaCampaignRuntimeNullableCustomizer().customise(api);
        int nullableCount=0;
        for(Schema<?> schema:components.getSchemas().values()) {
            for(var property:schema.getProperties().values()) {
                if(property.getTypes()==null||property.getTypes().size()!=2) continue;
                String scalar="integer".equals(property.getType())||property.getTypes().contains("integer")?"integer":"string";
                assertThat(property.getTypes()).containsExactly(scalar,"null");
                nullableCount++;
            }
        }
        assertThat(nullableCount).isEqualTo(12);
    }
}
