package com.things.link.support.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.parameters.PathParameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.tags.Tag;

import java.util.List;

/** 补充非 Controller 的 HTTP 升级契约；只描述握手，不将双向消息伪装成 REST 响应。 */
public final class HttpTransportOpenApiDocumentation {
    /** 实时传输统一功能分组。 */
    private static final String TAG = "实时 WebSocket";

    /** 文档工具不需要实例。 */
    private HttpTransportOpenApiDocumentation() { }

    /**
     * 登记实际注册的 WebSocket 握手；认证由原拦截器执行，不增加运行路由。
     *
     * @param api 当前生成文档
     * @param pattern Spring 注册的路径模式
     * @param operationId 稳定的握手操作标识
     * @param summary 中文用途摘要
     * @param description 原身份、来源及协议边界
     * @param protocolHeader 公开子协议与私有凭据的传递规则，不包含真实凭据
     * @param requiredOrigin 当前握手是否强制要求来源头
     */
    public static void webSocket(OpenAPI api, String pattern, String operationId, String summary,
                                 String description, String protocolHeader, boolean requiredOrigin) {
        String path = pattern.replace("/shares/*/", "/shares/{shareId}/");
        Operation operation = new Operation().operationId(operationId).summary(summary)
                .description(description + " 成功后升级为 WebSocket；消息帧及关闭码沿所属实时协议合同，"
                        + "不能按普通 JSON 接口调用。登记不表示当前公开部署验收恢复。")
                .tags(List.of(TAG)).security(List.of())
                .addParametersItem(new HeaderParameter().name("Sec-WebSocket-Protocol").required(true)
                        .description(protocolHeader).schema(new StringSchema()))
                .addParametersItem(new HeaderParameter().name("Origin").required(requiredOrigin)
                        .description("来源须符合该握手配置的同源或精确允许来源规则").schema(new StringSchema()));
        if (path.contains("{shareId}")) {
            operation.addParametersItem(new PathParameter().name("shareId").required(true)
                    .description("规范分享标识；通配符注册仍由原握手校验 UUID 和分享状态")
                    .schema(new StringSchema().format("uuid")));
        }
        operation.addExtension("x-spring-path-pattern", pattern);
        operation.addExtension("x-transport", "websocket-upgrade");
        operation.setResponses(new ApiResponses()
                .addApiResponse("101", new ApiResponse().description("协议升级成功，后续内容为 WebSocket 消息帧"))
                .addApiResponse("400", new ApiResponse().description("握手格式或升级头无效，错误体不保证为平台 ApiError"))
                .addApiResponse("401", new ApiResponse().description("握手身份、能力凭据或实时票据无效"))
                .addApiResponse("403", new ApiResponse().description("来源、项目或设备范围不允许本次握手"))
                .addApiResponse("503", new ApiResponse().description("握手依赖不可用，未取得升级资格")));
        if (pattern.contains("/shares/")) {
            operation.getResponses().remove("401");
            operation.getResponses().addApiResponse("404", new ApiResponse().description("分享不存在、过期或当前不可用"));
            operation.getResponses().addApiResponse("429", new ApiResponse().description("分享保护预算或连接租约达到上限")
                    .addHeaderObject("Retry-After", new io.swagger.v3.oas.models.headers.Header()
                            .description("再次握手前等待的秒数").schema(new StringSchema())));
        }
        if (api.getPaths().containsKey(path)) {
            throw new IllegalStateException("重复的实时握手文档路径：" + path);
        }
        api.path(path, new PathItem().get(operation));
        if (api.getTags() == null || api.getTags().stream().noneMatch(tag -> TAG.equals(tag.getName()))) {
            api.addTagsItem(new Tag().name(TAG).description("原生 HTTP 升级入口，按各自身份和消息协议接入"));
        }
    }
}
