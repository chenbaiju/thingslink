package com.things.link.ota.api;

import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import java.util.ArrayList;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 显式补齐springdoc未提升的数组required与项类型，不手改生成合同。 */
@Configuration(proxyBeanMethods = false)
public class OtaCampaignOpenApiConfiguration {
    /** 只调整活动两个有界数组，保持其他业务模型不变。 */
    @Bean
    public OpenApiCustomizer otaCampaignArrays() {
        return api -> {
            if (api.getComponents() == null || api.getComponents().getSchemas() == null) { return; }
            var schemas = api.getComponents().getSchemas();
            var plan = schemas.get("OtaCampaignPlanBody");
            if (plan != null) {
                StringSchema uuid = new StringSchema(); uuid.setFormat("uuid");
                uuid.setPattern("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
                ArraySchema ids = new ArraySchema(); ids.setItems(uuid); ids.setMinItems(1);
                ids.setMaxItems(1000); ids.setUniqueItems(true);
                plan.addProperty("deviceIds", ids); required(plan, "deviceIds");
            }
            var response = schemas.get("OtaCampaignResponse");
            if (response != null) {
                Schema<Object> job = new Schema<>(); job.set$ref("#/components/schemas/OtaCampaignJobResponse");
                ArraySchema jobs = new ArraySchema(); jobs.setItems(job); jobs.setMinItems(0); jobs.setMaxItems(1000);
                response.addProperty("jobs", jobs); required(response, "jobs");
            }
        };
    }
    /** 不丢弃注解已生成的其他必填字段。 */
    private static void required(Schema<?> schema, String name) {
        List<String> fields = new ArrayList<>(schema.getRequired() == null ? List.of() : schema.getRequired());
        if (!fields.contains(name)) { fields.add(name); }
        schema.setRequired(fields);
    }
}
