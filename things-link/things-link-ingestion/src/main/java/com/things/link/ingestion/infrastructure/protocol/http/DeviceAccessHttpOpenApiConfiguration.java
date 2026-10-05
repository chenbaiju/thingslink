package com.things.link.ingestion.infrastructure.protocol.http;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/** 设备面 HTTP 的冻结线格式文档；不借用管理面身份、幂等头或 ApiError。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true", matchIfMissing = true)
public class DeviceAccessHttpOpenApiConfiguration {
    /**
     * 补充直接读取原始请求字节的三个设备端点。
     *
     * @return 保留原运行协议、只修正设备面文档的修正器
     */
    @Bean
    public OpenApiCustomizer deviceAccessHttpDocumentation() {
        return api -> {
            api.getComponents().addSecuritySchemes("deviceHttpKey", new SecurityScheme()
                    .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER)
                    .name(DeviceAccessHttpAuthenticationFilter.DEVICE_KEY_HEADER)
                    .description("设备身份，值为 projectKey/deviceKey；仅在已开通 HTTP 的设备接入面使用"));
            api.getComponents().addSecuritySchemes("deviceHttpSecret", new SecurityScheme()
                    .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER)
                    .name(DeviceAccessHttpAuthenticationFilter.DEVICE_SECRET_HEADER)
                    .description("该设备的当前密钥；生产必须经 HTTPS 传递，不写入日志或示例"));
            schemas(api);
            document(api, DeviceAccessHttpPropertyReportController.PROPERTY_REPORT_PATH,
                    "DeviceHttpPropertyReport", true, "202", "DeviceHttpAcceptance",
                    "持久受理或同键同载荷重放；不保证遥测投影已经完成");
            document(api, DeviceAccessHttpCommandController.COMMAND_CLAIM_PATH,
                    "DeviceHttpClaimRequest", false, "200", "DeviceHttpClaimResponse",
                    "领取到命令及轮询间隔；设备必须按 commandId 去重，attempt 仅为诊断序号");
            document(api, DeviceAccessHttpCommandController.COMMAND_REPLY_PATH,
                    "DeviceHttpReplyRequest", true, "202", "DeviceHttpReplyAcceptance",
                    "回复已受理；命令业务终态沿命令事实查询");
            Operation claim = operation(api, DeviceAccessHttpCommandController.COMMAND_CLAIM_PATH);
            if (claim != null) claim.getResponses().addApiResponse("204", new ApiResponse()
                    .description("无可领取命令，无响应正文；按 Retry-After 秒数重试")
                    .addHeaderObject("Retry-After", new io.swagger.v3.oas.models.headers.Header()
                            .description("再次轮询前等待的秒数").schema(new IntegerSchema())));
        };
    }

    /**
     * 读取当前进程实际生成的端点；不同部署角色没有该端点时不伪造运行路由。
     *
     * @param api 当前生成文档
     * @param path 设备接入路径
     * @return 当前 POST 操作；未装配时为空
     */
    private static Operation operation(OpenAPI api, String path) {
        return api.getPaths().containsKey(path) ? api.getPaths().get(path).getPost() : null;
    }

    /**
     * 应用双设备凭据、受理状态及原始设备错误体。
     *
     * @param api 当前文档
     * @param path 实际路径
     * @param requestSchema 请求结构名称
     * @param requiredBody 是否要求非空请求体
     * @param successStatus 原成功状态码
     * @param responseSchema 原成功响应结构名称
     * @param resultDescription 中文成功语义
     */
    private static void document(OpenAPI api, String path, String requestSchema, boolean requiredBody,
                                 String successStatus, String responseSchema, String resultDescription) {
        Operation operation = operation(api, path);
        if (operation == null) return;
        operation.setDescription(operation.getDescription()
                + " 由 device-access 角色提供；HTTP 协议须显式开通，生产必须 HTTPS。"
                + "凭据归属来自认证身份；最大正文 64 KiB，messageId 沿设备业务协议去重，"
                + "不使用管理面 Idempotency-Key。错误体为 errorCode/message，不是平台 ApiError。");
        operation.setSecurity(List.of(new SecurityRequirement().addList("deviceHttpKey").addList("deviceHttpSecret")));
        operation.addExtension("x-deployment-role", "device-access");
        operation.setRequestBody(new RequestBody().required(requiredBody)
                .description(requiredBody ? "冻结设备 JSON 载荷；请求体不得为空" : "可选领取参数；空体采用原默认条数和租约")
                .content(json(requestSchema)));
        ApiResponses responses = new ApiResponses().addApiResponse(successStatus,
                new ApiResponse().description(resultDescription).content(json(responseSchema)));
        for (var error : Map.of("400", "载荷格式或字段无效", "401", "设备凭据缺失或无效", "403", "设备未开通该协议或项目当前不接受设备写入",
                "409", "同一业务消息标识与首次载荷冲突", "413", "请求正文超过允许体积",
                "415", "上报或回复的媒体类型不是 application/json", "429", "认证或业务预算超限",
                "503", "可靠接管或依赖暂不可用").entrySet()) {
            if (path.endsWith("/claim") && (error.getKey().equals("409") || error.getKey().equals("415"))) continue;
            responses.addApiResponse(error.getKey(), new ApiResponse().description(error.getValue()).content(json("DeviceHttpError")));
        }
        if (path.endsWith("/reply")) responses.addApiResponse("404", new ApiResponse()
                .description("命令不存在或不属于当前设备").content(json("DeviceHttpError")));
        responses.get("429").addHeaderObject("Retry-After", new io.swagger.v3.oas.models.headers.Header()
                .description("预算恢复前等待的秒数").schema(new IntegerSchema()));
        operation.setResponses(responses);
    }

    /**
     * 构造 JSON 引用，避免设备错误体被管理面错误模型覆盖。
     *
     * @param schema 组件名称
     * @return JSON 媒体类型及结构引用
     */
    private static Content json(String schema) {
        return new Content().addMediaType("application/json", new MediaType()
                .schema(new Schema<>().$ref("#/components/schemas/" + schema)));
    }

    /**
     * 对照共享协议载荷及控制器返回映射登记结构，不参与运行请求解析。
     *
     * @param api 当前生成文档
     */
    private static void schemas(OpenAPI api) {
        api.getComponents().addSchemas("DeviceHttpError", new ObjectSchema()
                .addProperty("errorCode", new StringSchema().description("稳定的设备协议错误码"))
                .addProperty("message", new StringSchema().description("不回显载荷或凭据的错误说明"))
                .required(List.of("errorCode", "message")));
        api.getComponents().addSchemas("DeviceHttpPropertyReport", new ObjectSchema()
                .addProperty("messageId", new StringSchema().format("uuid").description("设备生成的 UUIDv7；重试须保持相同标识和载荷"))
                .addProperty("occurredAt", new StringSchema().format("date-time").description("设备采集时刻，RFC3339 UTC；仍受原未来时间边界校验"))
                .addProperty("modelVersion", nullable(new StringSchema().description("物模型语义版本；省略或空值仅沿原标量兼容窗口推断"), "string"))
                .addProperty("payload", new ObjectSchema().additionalProperties(true).minProperties(1).description("非空属性键值对象，内容继续按绑定物模型校验"))
                .required(List.of("messageId", "occurredAt", "payload")));
        api.getComponents().addSchemas("DeviceHttpClaimRequest", new ObjectSchema()
                .addProperty("limit", nullable(new IntegerSchema().minimum(java.math.BigDecimal.ONE).description("可选正整数条数；空值采用默认，仍沿原领取用例限制"), "integer"))
                .addProperty("leaseSeconds", nullable(new IntegerSchema().format("int64").minimum(java.math.BigDecimal.ONE).description("可选正整数租约秒数；空值采用默认，仍沿原租约限制"), "integer")));
        api.getComponents().addSchemas("DeviceHttpReplyRequest", new ObjectSchema()
                .addProperty("commandId", new StringSchema().format("uuid").description("该设备收到的命令标识"))
                .addProperty("messageId", new StringSchema().format("uuid").description("回复消息标识；重放保持原结果"))
                .addProperty("occurredAt", new StringSchema().format("date-time").description("设备业务结果发生时间"))
                .addProperty("status", new StringSchema()._enum(java.util.Arrays.stream(
                        com.things.link.telemetry.application.DeviceCommandAccessReplyPort.Status.values()).map(Enum::name).toList())
                        .description("原命令回复状态枚举；ACK 是中间态，SUCCESS/FAILED 是执行结果"))
                .addProperty("output", nullable(new ObjectSchema().additionalProperties(true).description("可选业务输出对象，允许为空"), "object"))
                .addProperty("errorCode", nullable(new StringSchema().description("可选失败码，允许为空"), "string"))
                .addProperty("message", nullable(new StringSchema().description("可选失败说明，允许为空"), "string"))
                .required(List.of("commandId", "messageId", "occurredAt", "status")));
        ObjectSchema accepted = new ObjectSchema();
        accepted.addProperty("messageId", new StringSchema().format("uuid").description("原消息标识"));
        accepted.addProperty("receivedAt", new StringSchema().format("date-time").description("首次受理时刻"));
        accepted.addProperty("status", new StringSchema()._enum(List.of("ACCEPTED")).description("持久受理标记，不代表后续业务完成"));
        accepted.setRequired(List.of("messageId", "receivedAt", "status"));
        api.getComponents().addSchemas("DeviceHttpAcceptance", accepted);
        api.getComponents().addSchemas("DeviceHttpReplyAcceptance", new ObjectSchema()
                .addProperty("messageId", new StringSchema().format("uuid").description("原回复标识"))
                .addProperty("receivedAt", new StringSchema().format("date-time").description("回复受理时刻"))
                .addProperty("status", new StringSchema()._enum(List.of("ACCEPTED")).description("回复持久受理标记"))
                .addProperty("commandId", new StringSchema().format("uuid").description("所回复的命令标识"))
                .required(List.of("messageId", "receivedAt", "status", "commandId")));
        ObjectSchema command = new ObjectSchema();
        command.addProperty("commandId", new StringSchema().format("uuid").description("执行去重必须使用此命令标识"));
        command.addProperty("commandKey", new StringSchema().description("物模型命令键"));
        command.addProperty("input", new ObjectSchema().additionalProperties(true).description("原命令输入对象"));
        command.addProperty("attempt", new IntegerSchema().description("仅为诊断序号，不是业务幂等键"));
        Schema<?> expiry = new Schema<>().types(new java.util.LinkedHashSet<>(List.of("string", "null")))
                .format("date-time").description("命令租约到期时刻，允许为空");
        command.addProperty("leaseExpiresAt", expiry);
        command.setRequired(List.of("commandId", "commandKey", "input", "attempt", "leaseExpiresAt"));
        api.getComponents().addSchemas("DeviceHttpClaimResponse", new ObjectSchema()
                .addProperty("commands", new ArraySchema().items(command).description("本次领取到的命令"))
                .addProperty("pollAfterMillis", new IntegerSchema().format("int64").description("本次租约对应的轮询等待毫秒数"))
                .required(List.of("commands", "pollAfterMillis")));
    }

    /**
     * 表达设备协议中明确接受的可空字段，不将可选与禁止空值混为一谈。
     *
     * @param schema 原字段结构与约束
     * @param type 非空值的 JSON 类型
     * @return 接受原类型或空值的结构
     */
    private static Schema<?> nullable(Schema<?> schema, String type) {
        schema.setTypes(new java.util.LinkedHashSet<>(List.of(type, "null")));
        return schema;
    }
}
