package com.things.link.rule.api;
import io.swagger.v3.oas.models.media.*;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.List;
/** 显式描述自动化JSON线协议，避免JsonNode内部Java访问器被当作HTTP字段。 */
@Configuration(proxyBeanMethods=false)
public class AutomationOpenApiConfiguration {
    @Bean public OpenApiCustomizer automationSchemas(){return api->{
        if(api.getComponents()==null||api.getComponents().getSchemas()==null)return;
        var schemas=api.getComponents().getSchemas();
        var node=schemas.get("AutomationNodeRequest");
        if(node!=null){node.addProperty("config",new ObjectSchema().additionalProperties(true));node.setRequired(List.of("nodeType","config"));}
        for(String name:List.of("CreateAutomationRequest","ReviseAutomationRequest","AutomationVersionView")){
            var schema=schemas.get(name);if(schema==null)continue;
            var device=new StringSchema();device.setFormat("uuid");
            var property=new ObjectSchema();property.addProperty("deviceId",device);property.setRequired(List.of("deviceId"));property.setAdditionalProperties(false);
            var once=new ObjectSchema();once.addProperty("deviceId",device);once.addProperty("runAt",new DateTimeSchema());once.addProperty("payload",new ObjectSchema().additionalProperties(true));once.setRequired(List.of("deviceId","runAt"));once.setAdditionalProperties(false);
            var cron=new ObjectSchema();cron.addProperty("deviceId",device);cron.addProperty("cronExpression",new StringSchema());cron.addProperty("timezone",new StringSchema());cron.addProperty("payload",new ObjectSchema().additionalProperties(true));cron.setRequired(List.of("deviceId","cronExpression"));cron.setAdditionalProperties(false);
            schema.addProperty("triggerConfig",new ComposedSchema().oneOf(List.of(property,once,cron)));
            schema.addProperty("triggerType",new StringSchema()._enum(List.of("PROPERTY_REPORTED","ONE_SHOT","CRON")));
            for(String field:List.of("conditions","actions")){
                var item=new Schema<>();item.set$ref("#/components/schemas/AutomationNodeRequest");
                var array=new ArraySchema();array.setItems(item);array.setMinItems(field.equals("actions")?1:0);array.setMaxItems(32);schema.addProperty(field,array);
            }
            if(!name.equals("AutomationVersionView")){
                schema.setAdditionalProperties(false);
                schema.setRequired(name.startsWith("Revise")?List.of("name","expectedVersion","triggerType","triggerConfig","actions"):List.of("name","triggerType","triggerConfig","actions"));
            }
        }
    };}
}
