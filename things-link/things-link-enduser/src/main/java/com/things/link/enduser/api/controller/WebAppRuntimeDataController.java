package com.things.link.enduser.api.controller;

import com.things.link.device.application.RuntimeDeviceQuery;
import com.things.link.device.application.RuntimeModelReference;
import com.things.link.enduser.api.dto.request.WebAppRuntimeDataRequest;
import com.things.link.enduser.api.dto.response.WebAppRuntimeDataResponse;
import com.things.link.enduser.api.support.AppJwtIdentity;
import com.things.link.enduser.api.support.WebAppRuntimeContextReader;
import com.things.link.enduser.api.support.WebAppRuntimeDataRequestParser;
import com.things.link.enduser.application.AppAuthenticatedPrincipal;
import com.things.link.enduser.application.WebAppRuntimeDataService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Dashboard v1五条App运行数据REST入口。
 *
 * <p>控制器只负责严格HTTP语法、JWT身份投影和公开DTO映射；身份、Schema、grant、指定设备绑定与
 * 跨域读取顺序统一由{@link WebAppRuntimeDataService}在同一事务内执行。旧S11设备API保持独立。</p>
 */
@RestController
@Validated
@RequestMapping("/api/v1/app")
@Tag(name = "WebApp 运行数据", description = "精确应用与看板版本绑定的只读数据")
@SecurityRequirement(name = "appAccessBearer")
public class WebAppRuntimeDataController {
    /** 五路单响应最终Jackson UTF-8正文上限。 */ private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    /** byte[]响应显式声明UTF-8，避免被解释为通用二进制。 */
    private static final MediaType APPLICATION_JSON_UTF8 = new MediaType(
            MediaType.APPLICATION_JSON, StandardCharsets.UTF_8);
    /** 三个POST严格信封解析器。 */ private final WebAppRuntimeDataRequestParser parser;
    /** 五条路由统一应用编排。 */ private final WebAppRuntimeDataService service;
    /** 统一HTTP序列化器；计量与最终发送共用同一字节数组。 */ private final ObjectMapper objectMapper;

    /** @param parser 严格JSON解析器 @param service 运行数据编排 @param objectMapper HTTP JSON序列化器 */
    public WebAppRuntimeDataController(WebAppRuntimeDataRequestParser parser, WebAppRuntimeDataService service,
            ObjectMapper objectMapper) {
        this.parser = parser;
        this.service = service;
        this.objectMapper = objectMapper;
    }

    /** 读取设备描述与模型属性元数据快照。 */
    @PostMapping(value = "/devices/snapshots/query", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "queryWebAppDeviceSnapshots", summary = "查询WebApp设备描述快照")
    @ApiResponse(responseCode = "200", description = "设备与模型描述快照",
            headers = @Header(name = HttpHeaders.CACHE_CONTROL,
                    schema = @Schema(type = "string", allowableValues = "no-store")),
            content = @Content(schema = @Schema(implementation = WebAppRuntimeDataResponse.Snapshot.class)))
    @RuntimeHeaders
    @RuntimeFailures
    public ResponseEntity<byte[]> snapshots(@AuthenticationPrincipal Jwt jwt,
            @RequestBody(required = false)
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(schema = @Schema(implementation = WebAppRuntimeDataRequest.Snapshot.class)))
            byte[] body, HttpServletRequest request) {
        requireNoQuery(request);
        WebAppRuntimeDataRequest.Snapshot input = parser.parseSnapshot(body);
        return response(WebAppRuntimeDataResponse.Snapshot.from(service.snapshots(identity(jwt),
                WebAppRuntimeContextReader.read(request),
                input.models().stream().map(model -> new RuntimeModelReference(model.versionId(),
                        model.digestAlgorithm(), model.digest(), model.profile())).toList(),
                input.devices().stream().map(WebAppRuntimeDataController::device).toList())));
    }

    /** 读取稀疏PG当前值及来源模型。 */
    @PostMapping(value = "/devices/current-values/query", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "queryWebAppDeviceCurrentValues", summary = "查询WebApp设备当前值")
    @ApiResponse(responseCode = "200", description = "设备稀疏当前值",
            headers = @Header(name = HttpHeaders.CACHE_CONTROL,
                    schema = @Schema(type = "string", allowableValues = "no-store")),
            content = @Content(schema = @Schema(implementation = WebAppRuntimeDataResponse.Current.class)))
    @RuntimeHeaders
    @RuntimeFailures
    public ResponseEntity<byte[]> currentValues(@AuthenticationPrincipal Jwt jwt,
            @RequestBody(required = false)
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(schema = @Schema(implementation = WebAppRuntimeDataRequest.Current.class)))
            byte[] body, HttpServletRequest request) {
        requireNoQuery(request);
        WebAppRuntimeDataRequest.Current input = parser.parseCurrent(body);
        return response(WebAppRuntimeDataResponse.Current.from(service.currentValues(identity(jwt),
                WebAppRuntimeContextReader.read(request),
                input.devices().stream().map(WebAppRuntimeDataController::currentDevice).toList())));
    }

    /** 读取按精确模型过滤的已授权设备目录。 */
    @GetMapping(value = "/devices/catalog", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listWebAppDeviceCatalog", summary = "分页查询WebApp设备目录")
    @ApiResponse(responseCode = "200", description = "模型过滤的设备目录页",
            headers = @Header(name = HttpHeaders.CACHE_CONTROL,
                    schema = @Schema(type = "string", allowableValues = "no-store")),
            content = @Content(schema = @Schema(implementation = WebAppRuntimeDataResponse.Catalog.class)))
    @RuntimeHeaders
    @RuntimeFailures
    @Parameters({
            @Parameter(name = "modelVersionId", in = ParameterIn.QUERY, required = true,
                    schema = @Schema(type = "string", format = "uuid")),
            @Parameter(name = "cursor", in = ParameterIn.QUERY, schema = @Schema(type = "string", maxLength = 2048)),
            @Parameter(name = "limit", in = ParameterIn.QUERY,
                    schema = @Schema(type = "integer", defaultValue = "20", minimum = "1", maximum = "50"))
    })
    public ResponseEntity<byte[]> catalog(@AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) throws IOException {
        requireEmptyBody(request);
        requireQueryNames(request, Set.of("modelVersionId", "cursor", "limit"), Set.of("modelVersionId"));
        UUID model = uuid(singleQuery(request, "modelVersionId", true));
        String cursor = singleQuery(request, "cursor", false);
        int limit = positiveLimit(singleQuery(request, "limit", false));
        return response(WebAppRuntimeDataResponse.Catalog.from(service.catalog(identity(jwt),
                WebAppRuntimeContextReader.read(request), model, cursor, limit)));
    }

    /** 读取单设备属性完整版本化历史。 */
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "套餐历史窗口不可用（50048）", content = @io.swagger.v3.oas.annotations.media.Content(schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = com.things.link.shared.error.ApiError.class)))
    @GetMapping(value = "/devices/{deviceId}/properties/{propertyKey}/history/versioned",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getWebAppVersionedPropertyHistory", summary = "查询WebApp版本化属性历史", description = "按所属租户套餐窗口裁剪，聚合仅返回完整区间桶，缺投影503/50048")
    @ApiResponse(responseCode = "200", description = "完整有界版本化历史",
            headers = @Header(name = HttpHeaders.CACHE_CONTROL,
                    schema = @Schema(type = "string", allowableValues = "no-store")),
            content = @Content(schema = @Schema(implementation = WebAppRuntimeDataResponse.History.class)))
    @RuntimeHeaders
    @RuntimeFailures
    @Parameters({
            @Parameter(name = "from", in = ParameterIn.QUERY, required = true,
                    schema = @Schema(type = "string", format = "date-time")),
            @Parameter(name = "to", in = ParameterIn.QUERY, required = true,
                    schema = @Schema(type = "string", format = "date-time")),
            @Parameter(name = "granularity", in = ParameterIn.QUERY, required = true,
                    schema = @Schema(type = "string", allowableValues = {"RAW", "ONE_MINUTE", "ONE_HOUR", "ONE_DAY"})),
            @Parameter(name = "aggregation", in = ParameterIn.QUERY, required = true,
                    schema = @Schema(type = "string", allowableValues = {"AVG", "MIN", "MAX", "SUM", "COUNT"})),
            @Parameter(name = "expectedModelVersionId", in = ParameterIn.QUERY, required = true,
                    schema = @Schema(type = "string", format = "uuid"))
    })
    public ResponseEntity<byte[]> history(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String deviceId,
            @PathVariable @Pattern(regexp = "^[A-Za-z0-9_-]{1,64}$") String propertyKey,
            HttpServletRequest request) throws IOException {
        requireEmptyBody(request);
        requireQueryNames(request, Set.of("from", "to", "granularity", "aggregation", "expectedModelVersionId"),
                Set.of("from", "to", "granularity", "aggregation", "expectedModelVersionId"));
        Instant from = instant(singleQuery(request, "from", true));
        Instant to = instant(singleQuery(request, "to", true));
        return response(WebAppRuntimeDataResponse.History.from(service.history(identity(jwt),
                WebAppRuntimeContextReader.read(request), uuid(deviceId), propertyKey,
                uuid(singleQuery(request, "expectedModelVersionId", true)), from, to,
                singleQuery(request, "granularity", true), singleQuery(request, "aggregation", true), Instant.now())));
    }

    /** 读取按指定设备和条件过滤后的告警实例页。 */
    @PostMapping(value = "/alarms/query", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "queryWebAppAlarms", summary = "分页查询WebApp告警实例")
    @ApiResponse(responseCode = "200", description = "过滤后的告警实例页",
            headers = @Header(name = HttpHeaders.CACHE_CONTROL,
                    schema = @Schema(type = "string", allowableValues = "no-store")),
            content = @Content(schema = @Schema(implementation = WebAppRuntimeDataResponse.AlarmPage.class)))
    @RuntimeHeaders
    @RuntimeFailures
    public ResponseEntity<byte[]> alarms(@AuthenticationPrincipal Jwt jwt,
            @RequestBody(required = false)
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(schema = @Schema(implementation = WebAppRuntimeDataRequest.Alarms.class)))
            byte[] body, HttpServletRequest request) {
        requireNoQuery(request);
        WebAppRuntimeDataRequest.Alarms input = parser.parseAlarms(body);
        List<RuntimeDeviceQuery> devices = input.devices().stream().map(item ->
                new RuntimeDeviceQuery(item.deviceId(), item.expectedModelVersionId(), List.of())).toList();
        return response(WebAppRuntimeDataResponse.AlarmPage.from(service.alarms(identity(jwt),
                WebAppRuntimeContextReader.read(request), devices, input.conditionStates(), input.ackStates(),
                input.severities(), input.cursor(), input.limit() == null ? 20 : input.limit())));
    }

    /** 以同一最终UTF-8字节执行4MiB守卫并发送，禁止计量对象后再次用另一配置编码。 */
    private ResponseEntity<byte[]> response(Object value) {
        final byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("App运行数据响应无法序列化", exception);
        }
        if (bytes.length > MAX_RESPONSE_BYTES) throw new IllegalStateException("App运行数据响应超过4MiB上限");
        return ResponseEntity.ok().contentType(APPLICATION_JSON_UTF8).body(bytes);
    }

    /** 从已验签JWT创建不含客户端输入的可信身份值。 */
    private static AppAuthenticatedPrincipal identity(Jwt jwt) {
        return new AppAuthenticatedPrincipal(AppJwtIdentity.tenantId(jwt), AppJwtIdentity.projectId(jwt),
                AppJwtIdentity.appUserId(jwt), AppJwtIdentity.projectGeneration(jwt));
    }

    /** 映射带属性键设备请求。 */
    private static RuntimeDeviceQuery device(WebAppRuntimeDataRequest.Device input) {
        return new RuntimeDeviceQuery(input.deviceId(), input.expectedModelVersionId(), input.propertyKeys());
    }

    /** 映射当前值专用的非空属性设备请求。 */
    private static RuntimeDeviceQuery currentDevice(WebAppRuntimeDataRequest.CurrentDevice input) {
        return new RuntimeDeviceQuery(input.deviceId(), input.expectedModelVersionId(), input.propertyKeys());
    }

    /** POST数据路由不接受query承载第二组过滤。 */
    private static void requireNoQuery(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw invalid();
    }

    /** GET不得带正文。 */
    private static void requireEmptyBody(HttpServletRequest request) throws IOException {
        if (request.getInputStream().read() != -1) throw invalid();
    }

    /** 要求query名称闭集、必填字段存在且所有字段物理单值。 */
    private static void requireQueryNames(HttpServletRequest request, Set<String> allowed, Set<String> required) {
        Map<String, String[]> parameters = request.getParameterMap();
        if (!allowed.containsAll(parameters.keySet()) || !parameters.keySet().containsAll(required)
                || parameters.values().stream().anyMatch(values -> values == null || values.length != 1)) throw invalid();
    }

    /** 读取单值query；可选参数不存在时返回null。 */
    private static String singleQuery(HttpServletRequest request, String name, boolean required) {
        String[] values = request.getParameterValues(name);
        if (values == null) {
            if (required) throw invalid();
            return null;
        }
        if (values.length != 1 || values[0] == null || (required && values[0].isEmpty())) throw invalid();
        return values[0];
    }

    /** 目录limit省略为20，显式值必须是无符号十进制1..50。 */
    private static int positiveLimit(String text) {
        if (text == null) return 20;
        if (!text.matches("[1-9][0-9]?") || text.length() > 1 && text.charAt(0) == '0') throw invalid();
        int value = Integer.parseInt(text);
        if (value > 50) throw invalid();
        return value;
    }

    /** 解析规范小写UUID。 */
    private static UUID uuid(String text) {
        try {
            UUID value = UUID.fromString(text);
            if (!value.toString().equals(text)) throw new IllegalArgumentException("UUID非规范文本");
            return value;
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw invalid();
        }
    }

    /** 解析规范UTC RFC3339文本。 */
    private static Instant instant(String text) {
        try {
            return Instant.parse(text);
        } catch (DateTimeException | NullPointerException exception) {
            throw invalid();
        }
    }

    /** 普通HTTP参数错误统一10001。 */
    private static BusinessException invalid() {
        return new BusinessException(CommonErrorCode.INVALID_PARAMETER);
    }

    /**
     * 四个必填运行头的OpenAPI复用标记；实际单值/重复/逗号检查仍由Servlet原始头读取器执行。
     */
    @Parameters({
            @Parameter(name = WebAppRuntimeContextReader.APPLICATION_KEY, in = ParameterIn.HEADER, required = true,
                    schema = @Schema(type = "string", pattern = "^app_[0-9a-f]{32}$")),
            @Parameter(name = WebAppRuntimeContextReader.APPLICATION_VERSION, in = ParameterIn.HEADER, required = true,
                    schema = @Schema(type = "string", format = "uuid")),
            @Parameter(name = WebAppRuntimeContextReader.APPLICATION_REVISION, in = ParameterIn.HEADER, required = true,
                    schema = @Schema(type = "string", pattern = "^[1-9][0-9]{0,18}$")),
            @Parameter(name = WebAppRuntimeContextReader.DASHBOARD_VERSION, in = ParameterIn.HEADER, required = true,
                    schema = @Schema(type = "string", format = "uuid"))
    })
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    private @interface RuntimeHeaders { }

    /** 五路共同失败合同；no-store由前置过滤器在所有状态实际写入。 */
    @ApiResponses({
            @ApiResponse(responseCode = "400", description = "严格请求或运行计划不合法（10001）",
                    headers = @Header(name = HttpHeaders.CACHE_CONTROL,
                            schema = @Schema(type = "string", allowableValues = "no-store")),
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "App身份或项目代次失效（60009）",
                    headers = @Header(name = HttpHeaders.CACHE_CONTROL,
                            schema = @Schema(type = "string", allowableValues = "no-store")),
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "运行上下文或设备不可见（60023/60010）",
                    headers = @Header(name = HttpHeaders.CACHE_CONTROL,
                            schema = @Schema(type = "string", allowableValues = "no-store")),
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "429", description = "App短窗读取配额拒绝",
                    headers = @Header(name = HttpHeaders.CACHE_CONTROL,
                            schema = @Schema(type = "string", allowableValues = "no-store")),
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "500", description = "数据库、端口完整性或4MiB响应上限失败（90000）",
                    headers = @Header(name = HttpHeaders.CACHE_CONTROL,
                            schema = @Schema(type = "string", allowableValues = "no-store")),
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    private @interface RuntimeFailures { }
}
