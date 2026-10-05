package com.things.link;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.PathParameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.tags.Tag;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.webmvc.actuate.endpoint.web.WebMvcEndpointHandlerMapping;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** 从当前框架实际注册表描述运维 HTTP 入口，不开放新端点、不改变健康或指标配置。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true", matchIfMissing = true)
public class ManagementHttpOpenApiConfiguration {
    /**
     * 在文档生成时读取现有运维映射；生产关闭文档时不装配。
     *
     * @param mappings 当前进程已有的框架运维注册表；不存在时不编造接口
     * @return 只修改目录的定制器
     */
    @Bean
    OpenApiCustomizer managementHttpCatalog(ObjectProvider<WebMvcEndpointHandlerMapping> mappings) {
        return api -> mappings.ifAvailable(mapping -> {
            api.addTagsItem(new Tag().name("运行健康与指标")
                    .description("已配置暴露的框架运维入口；须由内部网络、来源白名单与抓取规则保护"));
            mapping.getHandlerMethods().forEach((registration, handler) -> {
                if (registration.getMethodsCondition().getMethods().isEmpty()) return;
                for (String pattern : registration.getPatternValues()) {
                    String path = pattern.replace("/**", "/{componentPath}");
                    boolean health = path.contains("/health");
                    boolean metrics = path.endsWith("/prometheus");
                    boolean discovery = handler.getMethod().getName().equals("links");
                    if (!health && !metrics && !discovery) {
                        throw new IllegalStateException("新增运维 HTTP 入口须先补中文合同：" + pattern);
                    }
                    for (var method : registration.getMethodsCondition().getMethods()) {
                        Operation operation = new Operation().operationId("management_" + method.name().toLowerCase(java.util.Locale.ROOT)
                                + "_" + path.replaceAll("[^a-zA-Z0-9]", "_"))
                                .summary(health ? "查询运行健康" : metrics ? "抓取运行指标" : "查询运维入口索引")
                                .description(health ? "由框架计算聚合或指定组件的健康状态，降级时可返回503；详细信息沿当前健康可见性配置。"
                                        : metrics ? "由框架按协商媒体类型输出 Prometheus 指标；只供受保护的内部抓取，不用于业务数据查询。"
                                        : "由框架列举当前运维端点链接；不增加端点暴露，不代表链接对当前身份可用。")
                                .tags(List.of("运行健康与指标"))
                                .security(discovery ? List.of(new SecurityRequirement().addList("consoleAccessBearer")) : List.of());
                        operation.addExtension("x-deployment-role", "management");
                        operation.addExtension("x-spring-path-pattern", pattern);
                        if (path.contains("{componentPath}")) operation.addParametersItem(new PathParameter()
                                .name("componentPath").required(true).schema(new StringSchema())
                                .description("健康组或组件的多段相对路径；是否存在与可见性由当前健康配置决定"));
                        Content content = new Content();
                        registration.getProducesCondition().getProducibleMediaTypes().forEach(media -> content.addMediaType(
                                media.toString(), new MediaType().schema(metrics ? new StringSchema()
                                        : new Schema<>().type("object").additionalProperties(true))));
                        ApiResponses responses = new ApiResponses().addApiResponse("200", new ApiResponse()
                                .description(health ? "运行健康；内容沿框架健康合同" : metrics ? "指标抓取成功；正文为协商的指标文本" : "返回运维链接索引")
                                .content(content));
                        if (health) responses.addApiResponse("503", new ApiResponse().description("运行健康降级或不可用；正文为框架健康状态").content(content));
                        responses.addApiResponse("404", new ApiResponse().description("运维资源、组件或路径不存在；不保证平台 ApiError 结构"));
                        if (discovery) {
                            responses.addApiResponse("401", new ApiResponse().description("运维索引要求平台认证，身份未通过"));
                            responses.addApiResponse("403", new ApiResponse().description("当前身份不能访问运维索引"));
                        }
                        operation.setResponses(responses);
                        PathItem item = api.getPaths().get(path);
                        if (item == null) { item = new PathItem(); api.path(path, item); }
                        item.operation(PathItem.HttpMethod.valueOf(method.name()), operation);
                    }
                }
            });
        });
    }
}
