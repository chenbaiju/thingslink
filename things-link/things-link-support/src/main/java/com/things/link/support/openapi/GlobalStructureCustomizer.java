package com.things.link.support.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** G3-LOCAL-4：只校准生成文档，运行授权、幂等与线协议保持原合同。 */
@Component
public final class GlobalStructureCustomizer implements OpenApiCustomizer {
    @Override public void customise(OpenAPI api) {
        api.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
            if (supportsOptionalHeader(method.name(), path)
                    && (operation.getParameters() == null || operation.getParameters().stream()
                    .noneMatch(parameter -> "Idempotency-Key".equalsIgnoreCase(parameter.getName())))) {
                operation.addParametersItem(new HeaderParameter().name("Idempotency-Key").required(false)
                        .description("可选业务写幂等键；按当前身份和接口合同处理，不保证重放成功正文")
                        .schema(new StringSchema()));
            }
            if (!path.startsWith("/api/v1/emqx/") && operation.getResponses() != null) {
                operation.getResponses().forEach((status, response) -> {
                    if (status.matches("[45][0-9Xx]{2}")) {
                        response.setContent(new Content().addMediaType("application/json", new MediaType()
                                .schema(new Schema<>().$ref("#/components/schemas/ApiError"))));
                    }
                });
            }
        }));
        api.getComponents().getSchemas().values().forEach(schema -> {
            if (schema.getProperties() != null && schema.getProperties().containsKey("nextCursor")) {
                // 运行CursorPage及规则管理页的末页均返回null；保留属性必填性与其他分页字段。
                Schema<?> cursor = new Schema<>();
                cursor.setTypes(new LinkedHashSet<>(List.of("string", "null")));
                cursor.setDescription("不透明的下一页游标；末页为null");
                schema.getProperties().put("nextCursor", cursor);
            }
        });
    }

    /** 与冻结逐操作矩阵独立实现；矩阵在测试中拦截新增操作或分类漂移。 */
    private static boolean supportsOptionalHeader(String method, String path) {
        if (!Set.of("POST", "PUT", "PATCH", "DELETE").contains(method)) return false;
        if (!(path.equals("/api/v1/projects") || path.startsWith("/api/v1/projects/")
                || path.startsWith("/api/v1/app/"))) return false;
        if (path.startsWith("/api/v1/app/auth/") || path.startsWith("/api/v1/app/browser-auth/")) return false;
        if (method.equals("PUT") && path.endsWith("/uploads/{sessionId}/content")) return false;
        if (Set.of("/api/v1/app/device-claims", "/api/v1/app/device-shares", "/api/v1/app/device-transfers",
                "/api/v1/app/devices/{deviceId}/binding", "/api/v1/app/push-tokens",
                "/api/v1/app/push-tokens/{installationId}").contains(path)) return false;
        return !method.equals("POST") || !Set.of(
                "/api/v1/projects/{projectId}/devices/current-values/query",
                "/api/v1/projects/{projectId}/devices/current-value-snapshots/query",
                "/api/v1/projects/{projectId}/devices/snapshots/query",
                "/api/v1/projects/{projectId}/alarms/query",
                "/api/v1/app/devices/current-values/query", "/api/v1/app/devices/snapshots/query",
                "/api/v1/app/alarms/query").contains(path);
    }
}
