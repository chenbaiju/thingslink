package com.things.link.integration.api;

import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.*;

/** ADR0211：补齐springdoc无法从ResponseEntity推导的201、可空收据和缓存边界。 */
@Configuration(proxyBeanMethods=false)
public class WebhookOpenApiConfiguration {
    @Bean public OpenApiCustomizer webhookManagementContract() {
        return api -> {
            var schemas=api.getComponents().getSchemas();
            for(String name:List.of("WebhookCreate","WebhookUpdate","WebhookChange","WebhookRecover","WebhookSubscriptionView","WebhookOperationView","WebhookRecoveryView","DeliveryView","DeliveryDetail","AttemptView","EventView","Current")) {
                var schema=schemas.get(name);if(schema!=null&&schema.getProperties()!=null)schema.setRequired(new ArrayList<>(schema.getProperties().keySet()));
            }
            nullable(schemas,"WebhookOperationView","current");nullable(schemas,"WebhookRecoveryView","current");
            for(String name:List.of("DeliveryView","Current"))nullable(schemas,name,"terminalAt");
            nullable(schemas,"DeliveryView","reason");
            for(String field:List.of("finishedAt","httpStatus","elapsedMillis","reason"))nullable(schemas,"AttemptView",field);
            for(var entry:api.getPaths().entrySet())if(entry.getKey().startsWith("/api/v1/projects/{projectId}/webhooks")) {
                for(var operation:entry.getValue().readOperations()) {
                    if("createProjectWebhook".equals(operation.getOperationId())) {
                        var response=operation.getResponses().remove("200");if(response!=null)operation.getResponses().addApiResponse("201",response.description("Created"));
                    }
                    for(String status:List.of("400","401","403","404","409","429","500","503"))
                        operation.getResponses().addApiResponse(status,new ApiResponse().description("标准错误码；10014查询原操作，不恢复秘密"));
                    operation.getResponses().values().forEach(response->response.addHeaderObject("Cache-Control",new Header().schema(new StringSchema()._enum(List.of("no-store")))));
                }
            }
        };
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    private static void nullable(Map<String,Schema> schemas,String name,String field) {
        var schema=schemas.get(name);if(schema==null||schema.getProperties()==null)return;
        Schema original=(Schema)schema.getProperties().get(field);if(original==null)return;
        Schema absent=new Schema();absent.setTypes(Set.of("null"));
        schema.addProperty(field,new ComposedSchema().anyOf(List.of(original,absent)));
    }
}
