package com.things.link.dashboard.api.controller;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.PathParameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** 公开静态壳目录；说明既有 GET/HEAD、空错误体及通配路径，不改变发布资格。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true", matchIfMissing = true)
public class DashboardPublicHostOpenApiConfiguration {
    /**
     * 使用标准路径参数表达多段资源路径，保留实际 Spring 模式供覆盖守卫核对。
     *
     * @return 仅维护文档的修正器
     */
    @Bean
    public OpenApiCustomizer applicationStaticContentDocumentation() {
        return api -> {
            PathItem root = api.getPaths().get("/app");
            PathItem resource = api.getPaths().remove("/app/**");
            if (root == null || resource == null) return;
            root.getGet().setOperationId("redirectApplicationStaticRoot");
            root.getGet().setSummary("规范化应用静态入口");
            root.getGet().setDescription("无查询串时将 /app 重定向到 /app/；不读取业务身份，响应不缓存。"
                    + "当前公开部署验收暂停，路径登记不表示已公开上线。");
            root.getGet().setSecurity(List.of());
            root.getGet().setResponses(new ApiResponses()
                    .addApiResponse("308", new ApiResponse().description("永久重定向到 /app/，无响应正文")
                            .addHeaderObject("Location", new Header().description("规范静态入口 /app/").schema(new StringSchema())))
                    .addApiResponse("404", new ApiResponse().description("查询串不符合入口合同，错误正文为空")));
            resource.getGet().setOperationId("getApplicationStaticResource");
            resource.getGet().setSecurity(List.of());
            resource.getGet().addExtension("x-spring-path-pattern", "/app/**");
            resource.getGet().setParameters(List.of(new PathParameter().name("resourcePath").required(true)
                    .description("相对静态资源路径，可含多段；原服务继续拒绝编码、穿越及白名单外路径")
                    .schema(new StringSchema())));
            Content files = new Content();
            for (String type : List.of("text/html", "text/javascript", "text/css", "application/json",
                    "application/manifest+json", "image/png", "image/svg+xml", "font/woff2")) {
                files.addMediaType(type, new MediaType().schema(new StringSchema().format("binary")));
            }
            resource.getGet().setResponses(new ApiResponses()
                    .addApiResponse("200", new ApiResponse().description("同次资格校验后的静态文件字节；缓存规则按资源类型决定").content(files))
                    .addApiResponse("404", new ApiResponse().description("路径、查询或资源不符合合同，错误正文为空"))
                    .addApiResponse("503", new ApiResponse().description("静态发布资格或依赖不可用，错误正文为空")));
            api.path("/app/{resourcePath}", resource);
            root.setHead(head(root.getGet(), "headApplicationStaticRoot"));
            resource.setHead(head(resource.getGet(), "headApplicationStaticResource"));
        };
    }

    /**
     * 登记 Spring MVC 对 GET 提供的 HEAD 行为；只返回相同状态与响应头，不返回文件正文。
     *
     * @param get 对应 GET 操作
     * @param operationId 独立 HEAD 操作标识
     * @return 无响应正文的 HEAD 文档
     */
    private static Operation head(Operation get, String operationId) {
        ApiResponses responses = new ApiResponses();
        get.getResponses().forEach((status, response) -> responses.addApiResponse(status,
                new ApiResponse().description(response.getDescription() + "；HEAD 不返回正文").headers(response.getHeaders())));
        Operation head = new Operation().operationId(operationId).summary("读取应用静态资源响应头")
                .description(get.getDescription() + " HEAD 只读取状态与响应头。")
                .tags(get.getTags()).security(List.of()).parameters(get.getParameters()).responses(responses);
        if (get.getExtensions() != null) head.setExtensions(get.getExtensions());
        return head;
    }
}
