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
            if (operation.getRequestBody() != null && !hasChinese(operation.getRequestBody().getDescription())) {
                operation.getRequestBody().setDescription(operation.getSummary()
                        + "的请求数据；结构、必填字段和校验边界见请求 Schema");
            }
            if (operation.getParameters() != null) operation.getParameters().forEach(parameter -> {
                if (!hasChinese(parameter.getDescription())) {
                    parameter.setDescription(switch (parameter.getName()) {
                        case "Idempotency-Key" -> "业务写幂等键；是否必填及重放语义以本接口合同为准";
                        case "Authorization" -> "本接口认证链要求的身份凭据；不要写入日志或查询串";
                        default -> "接口" + parameter.getIn() + "参数「" + parameter.getName()
                                + "」；含义见接口说明，类型、必填性和边界见参数 Schema";
                    });
                }
            });
            if (operation.getResponses() != null) operation.getResponses().forEach((status, response) -> {
                if (!hasChinese(response.getDescription())) response.setDescription(switch (status) {
                    case "200" -> "请求成功；返回结构见响应 Schema";
                    case "201" -> "资源创建成功；返回结构见响应 Schema";
                    case "202" -> "请求已受理；后续处理终态需沿对应业务合同确认";
                    case "204" -> "请求成功，无响应正文";
                    case "400" -> "请求格式或参数无效";
                    case "401" -> "身份认证失败";
                    case "403" -> "当前身份无权执行本操作";
                    case "404" -> "目标资源不存在或对当前身份不可见";
                    case "409" -> "请求与当前资源或幂等状态冲突";
                    case "429" -> "请求超过当前适用额度或速率限制";
                    case "503" -> "依赖暂不可用或当前不具备执行条件";
                    default -> "接口响应；状态码为 " + status;
                });
            });
            if (supportsOptionalHeader(method.name(), path)
                    && (operation.getParameters() == null || operation.getParameters().stream()
                    .noneMatch(parameter -> "Idempotency-Key".equalsIgnoreCase(parameter.getName())))) {
                operation.addParametersItem(new HeaderParameter().name("Idempotency-Key").required(false)
                        .description("可选业务写幂等键；按当前身份和接口合同处理，不保证重放成功正文")
                        .schema(new StringSchema()));
            }
            if (!path.startsWith("/api/v1/emqx/") && !path.startsWith("/device-access/")
                    && !path.startsWith("/actuator") && !path.startsWith("/simulations/") && !path.startsWith("/app")
                    && !"websocket-upgrade".equals(operation.getExtensions() == null ? null
                            : operation.getExtensions().get("x-transport")) && operation.getResponses() != null) {
                operation.getResponses().forEach((status, response) -> {
                    if (status.matches("[45][0-9Xx]{2}")) {
                        response.setContent(new Content().addMediaType("application/json", new MediaType()
                                .schema(new Schema<>().$ref("#/components/schemas/ApiError"))));
                    }
                });
            }
            // 事件历史精确两条GET在认证前已禁止缓存；文档所有实际失败状态保持同一头部。
            if ("GET".equals(method.name()) && Set.of(
                    "/api/v1/projects/{projectId}/devices/{deviceId}/events",
                    "/api/v1/projects/{projectId}/devices/{deviceId}/events/{messageId}").contains(path)) {
                for (String status : List.of("400", "401", "403", "404", "429", "500", "503")) {
                    if (!operation.getResponses().containsKey(status)) operation.getResponses().addApiResponse(status,
                            new io.swagger.v3.oas.models.responses.ApiResponse().description("事件历史读取失败；业务分类见错误码")
                                    .content(new Content().addMediaType("application/json", new MediaType()
                                            .schema(new Schema<>().$ref("#/components/schemas/ApiError")))));
                }
                operation.getResponses().values().forEach(response -> response.addHeaderObject("Cache-Control",
                        new io.swagger.v3.oas.models.headers.Header().schema(new StringSchema()._enum(List.of("no-store")))));
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
        // 数组注解在生成器中不总是提升所属对象必填性；只补事件页既定items字段。
        Schema<?> eventPage = api.getComponents().getSchemas().get("DeviceEventPageResponse");
        if (eventPage != null) {
            var required = new java.util.ArrayList<>(eventPage.getRequired() == null ? List.<String>of() : eventPage.getRequired());
            if (!required.contains("items")) required.add("items");
            eventPage.setRequired(required);
            // 注解allowableValues会把int枚举写成字符串；显式数值Schema与实际JSON整数保持一致。
            var retention = new io.swagger.v3.oas.models.media.IntegerSchema();
            retention.setEnum(List.of(90));
            retention.setMinimum(java.math.BigDecimal.valueOf(90));
            retention.setMaximum(java.math.BigDecimal.valueOf(90));
            retention.setDescription("事件发生时间可读保留天数，固定整数90；仍与当前套餐窗口求交");
            eventPage.addProperty("retentionDays", retention);
        }
    }

    /** 只补缺失的中文说明，不覆盖模块已经冻结的契约文字。 */
    private static boolean hasChinese(String value) {
        return value != null && value.codePoints().anyMatch(point -> point >= 0x4e00 && point <= 0x9fff);
    }

    /** 与冻结逐操作矩阵独立实现；矩阵在测试中拦截新增操作或分类漂移。 */
    private static boolean supportsOptionalHeader(String method, String path) {
        if (!Set.of("POST", "PUT", "PATCH", "DELETE").contains(method)) return false;
        if (!(path.equals("/api/v1/projects") || path.startsWith("/api/v1/projects/")
                || path.startsWith("/api/v1/app/"))) return false;
        if (method.equals("POST") && path.equals("/api/v1/projects/{projectId}/assistant/model-probes/{sampleIndex}")) return false;
        if (path.startsWith("/api/v1/app/auth/") || path.startsWith("/api/v1/app/browser-auth/")) return false;
        if (method.equals("PUT") && path.endsWith("/uploads/{sessionId}/content")) return false;
        if (Set.of("/api/v1/app/device-claims", "/api/v1/app/device-shares", "/api/v1/app/device-transfers",
                "/api/v1/app/devices/{deviceId}/binding", "/api/v1/app/push-tokens",
                "/api/v1/app/push-tokens/{installationId}", "/api/v1/app/push-installations",
                "/api/v1/app/push-installations/{installationId}").contains(path)) return false;
        return !method.equals("POST") || !Set.of(
                "/api/v1/projects/{projectId}/assistant/fact-reports/collection",
                "/api/v1/projects/{projectId}/assistant/knowledge/search",
                "/api/v1/projects/{projectId}/devices/current-values/query",
                "/api/v1/projects/{projectId}/devices/current-value-snapshots/query",
                "/api/v1/projects/{projectId}/devices/snapshots/query",
                "/api/v1/projects/{projectId}/alarms/query",
                "/api/v1/app/devices/current-values/query", "/api/v1/app/devices/snapshots/query",
                "/api/v1/app/alarms/query").contains(path);
    }
}
