package com.things.link.ota.api;

import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.ComposedSchema;
import java.util.List;
import java.util.LinkedHashSet;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 显式运行投影的nullable标量，枚举同样允许真实null。 */
@Configuration(proxyBeanMethods = false)
public class OtaCampaignRuntimeOpenApiConfiguration {
    /** 修正生成器nullable或枚举推导，不修改生成产物。 */
    @Bean
    public OpenApiCustomizer otaCampaignRuntimeNullableCustomizer() {
        return api -> {
            if (api.getComponents() == null || api.getComponents().getSchemas() == null) return;
            var response = api.getComponents().getSchemas().get("OtaCampaignExecutionResponse");
            if (response == null) return;
            response.addProperty("startedAt", nullable("string", "date-time"));
            response.addProperty("pausedAt", nullable("string", "date-time"));
            response.addProperty("pauseActorId", nullable("string", "uuid"));
            response.addProperty("pauseJobId", nullable("string", "uuid"));
            Schema<Object> batch = nullable("integer", "int32");
            batch.setMinimum(BigDecimal.ONE);
            batch.setMaximum(BigDecimal.valueOf(1000));
            response.addProperty("currentBatch", batch);
            Schema<Object> kind = nullable("string", null);
            kind.setEnum(Arrays.asList("MANUAL", "AUTO", "SECURITY", null));
            response.addProperty("pauseKind", kind);
            Schema<Object> reason = nullable("string", null);
            reason.setMinLength(1);
            reason.setMaxLength(256);
            response.addProperty("pauseReason", reason);
            Schema<Object> reference = new Schema<>();
            reference.set$ref("#/components/schemas/OtaCampaignRuntimeCancellationResponse");
            Schema<Object> absent = new Schema<>();
            absent.setTypes(Set.of("null"));
            ComposedSchema cancellation = new ComposedSchema();
            cancellation.setAnyOf(List.of(reference, absent));
            response.addProperty("runtimeCancellation", cancellation);
            var details = api.getComponents().getSchemas().get("OtaCampaignRuntimeCancellationResponse");
            if (details != null) details.addProperty("completedAt", nullable("string", "date-time"));
            var progress = api.getComponents().getSchemas().get("OtaCampaignBatchProgressResponse");
            if (progress != null) {
                Schema<Object> state = nullable("string", null);
                state.setEnum(Arrays.asList("PENDING", "RUNNING", "PAUSED", "DRAINING", "CANCELLING", "SUCCEEDED", "FAILED", "CANCELLED", null));
                progress.addProperty("currentBatchStatus", state);
                Schema<Object> next = nullable("integer", "int32");
                next.setMinimum(BigDecimal.ONE);
                next.setMaximum(BigDecimal.valueOf(1000));
                progress.addProperty("nextBatchNumber", next);
                progress.addProperty("completedAt", nullable("string", "date-time"));
                Schema<Object> outcome = nullable("string", null);
                outcome.setEnum(Arrays.asList("SUCCESS", "PARTIAL_FAILURE", "FAILED", null));
                progress.addProperty("outcome", outcome);
            }
        };
    }
    /** 3.1联合标量类型不产生与非空ref相交的错误语义。 */
    private static Schema<Object> nullable(String type, String format) {
        Schema<Object> schema = new Schema<>();
        // Set.of的迭代顺序跨JVM随机，联合类型固定非空类型在前，避免全量合同误报。
        schema.setTypes(new LinkedHashSet<>(List.of(type, "null")));
        schema.setFormat(format);
        return schema;
    }
}
