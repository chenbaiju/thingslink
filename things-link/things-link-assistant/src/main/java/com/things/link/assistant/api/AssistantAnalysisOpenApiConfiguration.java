package com.things.link.assistant.api;

import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Schema;
import java.util.List;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 关闭时调用为空，非首次成功时结果为空；用明确联合类型描述对象引用。 */
@Configuration(proxyBeanMethods = false)
public class AssistantAnalysisOpenApiConfiguration {
    /**
     * 描述调用与结果的空值分支，避免空值与非空引用相交而丢失前端类型。
     * @return 仅修正固定分析响应的契约定制器，不修改业务响应或生成文件
     */
    @Bean
    public OpenApiCustomizer assistantAnalysisNullableCallCustomizer() {
        return api -> {
            if (api.getComponents() == null || api.getComponents().getSchemas() == null) return;
            var view = api.getComponents().getSchemas().get("AssistantAnalysisRunView");
            if (view == null) return;
            view.addProperty("call", nullable("AssistantAnalysisCallView"));
            view.addProperty("result", nullable("AssistantAnalysisResult"));
            view.setDescription("仅首次SUCCEEDED带result；UNAVAILABLE的call和result均为空，其余类别仅有call。REPLAY即使状态已成功也不恢复正文。生产业务仍未准入。");
        };
    }
    /** 用引用或空值的联合描述单一可空对象，保留生成端类型语义。 */
    private static ComposedSchema nullable(String name) {
        Schema<Object> reference = new Schema<>();
        reference.set$ref("#/components/schemas/" + name);
        Schema<Object> absent = new Schema<>();
        absent.setTypes(Set.of("null"));
        ComposedSchema result = new ComposedSchema();
        result.setAnyOf(List.of(reference, absent));
        return result;
    }
}
