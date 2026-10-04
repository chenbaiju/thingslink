package com.things.link.integration.api;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.*;
import java.util.Map;
/** 公开资源的统一错误及缓存合同；只修饰实际Controller生成的公开路径。 */
@Configuration(proxyBeanMethods=false)
public class OpenRestApiConfiguration {
    @Bean OpenApiCustomizer publicRestErrors(){return api->{
        if(api.getPaths()==null)return;
        api.getPaths().forEach((path,item)->{
            if(!path.startsWith("/api/open/v1/")&&!path.endsWith("/realtime-tickets"))return;
            item.readOperations().forEach(op->{
                Map.of("400","10001参数或预算无效","401","80003凭据无效","403","80004权限不足",
                        "404","资源不存在","429","10029共享限流","500","90000内部故障","503","80002未启用")
                    .forEach((code,message)->op.getResponses().putIfAbsent(code,new ApiResponse().description(message)
                        .content(new Content().addMediaType("application/json",new MediaType().schema(new Schema<>().$ref("#/components/schemas/ApiError"))))));
                op.getResponses().values().forEach(response->response.addHeaderObject("Cache-Control",new Header().description("所有响应禁止缓存")
                    .schema(new Schema<String>().type("string")._const("no-store"))));
            });
        });
    };}
}
