package com.things.link.dashboard.api.controller;

import com.things.link.dashboard.api.dto.request.DashboardShareDataRequest;
import com.things.link.dashboard.api.dto.response.DashboardShareDataResponse;
import com.things.link.dashboard.api.support.DashboardShareDataRequestParser;
import com.things.link.dashboard.application.DashboardShareDataService;
import com.things.link.dashboard.application.DashboardSharePrincipal;
import com.things.link.dashboard.domain.DashboardErrorCode;
import com.things.link.device.application.RuntimeDeviceQuery;
import com.things.link.device.application.RuntimeModelReference;
import com.things.link.shared.error.ApiError;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** ADR0101五路匿名数据HTTP：仅投影Share身份，严格有界输入、公开DTO和4MiB编码；授权由应用事务统一执行。 */
@RestController
@RequestMapping("/api/v1/shares/{shareId}")
@Tag(name = "匿名看板分享数据", description = "冻结变量scope与实际Schema绑定限制的只读数据")
@SecurityRequirement(name = "shareCapability")
@ApiResponse(responseCode = "400", description = "严格请求或计划不合法（10001）",
        headers = @Header(name = "Cache-Control", schema = @Schema(type = "string", allowableValues = "no-store")),
        content = @Content(schema = @Schema(implementation = ApiError.class)))
@ApiResponse(responseCode = "403", description = "请求超出分享scope或Referer不匹配（60054）",
        headers = @Header(name = "Cache-Control", schema = @Schema(type = "string", allowableValues = "no-store")),
        content = @Content(schema = @Schema(implementation = ApiError.class)))
@ApiResponse(responseCode = "404", description = "分享或精确资源不可用（60053）",
        headers = @Header(name = "Cache-Control", schema = @Schema(type = "string", allowableValues = "no-store")),
        content = @Content(schema = @Schema(implementation = ApiError.class)))
@ApiResponse(responseCode = "429", description = "保护预算耗尽（60056）；错误正文也无字节额度时为空",
        headers = {@Header(name = "Retry-After", schema = @Schema(type = "string")),
                @Header(name = "Cache-Control", schema = @Schema(type = "string", allowableValues = "no-store"))},
        content = @Content(schema = @Schema(implementation = ApiError.class)))
@ApiResponse(responseCode = "503", description = "依赖或单次输出保护失败（60055），历史套餐窗口不可用（50048）；无法计量错误时为空",
        headers = @Header(name = "Cache-Control", schema = @Schema(type = "string", allowableValues = "no-store")),
        content = @Content(schema = @Schema(implementation = ApiError.class)))
public class DashboardShareDataController {
    /** 三条POST有界原文解析，不借跨模块API DTO或宽松Jackson绑定。 */
    private final DashboardShareDataRequestParser parser;
    /** 每次数据读取重新复验capability、DB时间与scope的应用端口。 */
    private final DashboardShareDataService service;
    /** 同一编码结果交给前置响应预算结算，不重复编码后计量。 */
    private final ObjectMapper mapper;
    /** 0b2单响应编码后4MiB上限，过滤器与Controller分别保护缓存和编码分配。 */
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    /** 二进制返回体显式保持UTF-8 JSON。 */
    private static final MediaType JSON_UTF8 = new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8);
    /** 分享历史不能接受任意from/to，固定时长仅用已验证的preset和anchor派生响应窗口。 */
    private static final Map<String, Duration> PRESETS = Map.of("LAST_1_HOUR", Duration.ofHours(1),
            "LAST_24_HOURS", Duration.ofHours(24), "LAST_7_DAYS", Duration.ofDays(7));

    /** 装配完整HTTP边界，保持领域与公开DTO分离。 */
    public DashboardShareDataController(DashboardShareDataRequestParser parser, DashboardShareDataService service, ObjectMapper mapper) {
        this.parser = parser; this.service = service; this.mapper = mapper;
    }

    /** 元信息只由已声明的模型和每设备实际绑定属性投影。 */
    @PostMapping(value = "/devices/snapshots/query", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "queryDashboardShareDeviceSnapshots", summary = "查询匿名分享设备描述快照",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(schema = @Schema(implementation = DashboardShareDataRequest.Snapshot.class))))
    @ApiResponse(responseCode = "200", description = "设备状态与精确模型描述",
            headers = @Header(name = "Cache-Control", schema = @Schema(type = "string", allowableValues = "no-store")),
            content = @Content(schema = @Schema(implementation = DashboardShareDataResponse.Snapshot.class)))
    public ResponseEntity<byte[]> snapshots(@PathVariable String shareId, HttpServletRequest request) throws IOException {
        DashboardSharePrincipal principal = principal(shareId, request);
        DashboardShareDataRequest.Snapshot input = parser.parseSnapshot(postBody(request));
        return guarded(() -> DashboardShareDataResponse.Snapshot.from(service.snapshots(principal,
                input.models().stream().map(model -> new RuntimeModelReference(model.versionId(), model.digestAlgorithm(),
                        model.digest(), model.profile())).toList(), input.devices().stream().map(DashboardShareDataController::device).toList())));
    }

    /** 当前值按PG权威来源版本返回五态，scope外错误不得降为NO_VALUE。 */
    @PostMapping(value = "/devices/current-values/query", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "queryDashboardShareCurrentValues", summary = "查询匿名分享设备当前值",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(schema = @Schema(implementation = DashboardShareDataRequest.Current.class))))
    @ApiResponse(responseCode = "200", description = "设备当前值与来源模型",
            headers = @Header(name = "Cache-Control", schema = @Schema(type = "string", allowableValues = "no-store")),
            content = @Content(schema = @Schema(implementation = DashboardShareDataResponse.Current.class)))
    public ResponseEntity<byte[]> currentValues(@PathVariable String shareId, HttpServletRequest request) throws IOException {
        DashboardSharePrincipal principal = principal(shareId, request);
        DashboardShareDataRequest.Current input = parser.parseCurrent(postBody(request));
        return guarded(() -> DashboardShareDataResponse.Current.from(service.currentValues(principal,
                input.devices().stream().map(value -> new RuntimeDeviceQuery(value.deviceId(), value.expectedModelVersionId(), value.propertyKeys())).toList())));
    }

    /** 目录只选一个冻结变量候选，不接受客户端模型或全项目设备查询。 */
    @GetMapping(value = "/devices/catalog", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getDashboardShareDeviceCatalog", summary = "分页读取分享变量设备目录")
    @ApiResponse(responseCode = "200", description = "变量scope内仍匹配模型的设备目录",
            headers = @Header(name = "Cache-Control", schema = @Schema(type = "string", allowableValues = "no-store")),
            content = @Content(schema = @Schema(implementation = DashboardShareDataResponse.Catalog.class)))
    @Parameters({
            @Parameter(name = "variableKey", in = ParameterIn.QUERY, required = true, schema = @Schema(type = "string")),
            @Parameter(name = "cursor", in = ParameterIn.QUERY, schema = @Schema(type = "string", maxLength = 2048)),
            @Parameter(name = "limit", in = ParameterIn.QUERY, schema = @Schema(type = "integer", defaultValue = "20", minimum = "1", maximum = "50"))
    })
    public ResponseEntity<byte[]> catalog(@PathVariable String shareId, HttpServletRequest request) throws IOException {
        DashboardSharePrincipal principal = principal(shareId, request);
        emptyBody(request);
        queries(request, Set.of("variableKey", "cursor", "limit"), Set.of("variableKey"));
        String variable = query(request, "variableKey", true);
        if (!variable.matches("[a-z][a-z0-9_]{0,63}")) throw invalid();
        String cursor = query(request, "cursor", false);
        if (cursor != null && (cursor.isEmpty() || cursor.length() > 2048 || !cursor.chars().allMatch(value -> value >= 0x21 && value <= 0x7e))) throw invalid();
        int limit = limit(query(request, "limit", false));
        return guarded(() -> DashboardShareDataResponse.Catalog.from(service.catalog(principal, variable, cursor, limit)));
    }

    /** 历史只接受声明preset及本轮DB anchor，不把设备时间或本机now当授权窗口。 */
    @GetMapping(value = "/devices/{deviceId}/properties/{propertyKey}/history", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getDashboardSharePropertyHistory", summary = "读取匿名分享声明窗口属性历史", description = "声明窗口还须与所属租户套餐窗口求交；聚合仅返回完整区间桶，缺投影503/50048")
    @ApiResponse(responseCode = "200", description = "含实际from/to的完整版本化历史",
            headers = @Header(name = "Cache-Control", schema = @Schema(type = "string", allowableValues = "no-store")),
            content = @Content(schema = @Schema(implementation = DashboardShareDataResponse.History.class)))
    @Parameters({
            @Parameter(name = "expectedModelVersionId", in = ParameterIn.QUERY, required = true, schema = @Schema(type = "string", format = "uuid")),
            @Parameter(name = "windowPreset", in = ParameterIn.QUERY, required = true, schema = @Schema(type = "string", allowableValues = {"LAST_1_HOUR", "LAST_24_HOURS", "LAST_7_DAYS"})),
            @Parameter(name = "anchorAt", in = ParameterIn.QUERY, required = true, schema = @Schema(type = "string", format = "date-time")),
            @Parameter(name = "granularity", in = ParameterIn.QUERY, required = true, schema = @Schema(type = "string", allowableValues = {"RAW", "ONE_MINUTE", "ONE_HOUR", "ONE_DAY"})),
            @Parameter(name = "aggregation", in = ParameterIn.QUERY, required = true, schema = @Schema(type = "string", allowableValues = {"AVG", "MIN", "MAX", "SUM", "COUNT"}))
    })
    public ResponseEntity<byte[]> history(@PathVariable String shareId, @PathVariable String deviceId,
            @PathVariable String propertyKey, HttpServletRequest request) throws IOException {
        DashboardSharePrincipal principal = principal(shareId, request);
        emptyBody(request);
        Set<String> fields = Set.of("expectedModelVersionId", "windowPreset", "anchorAt", "granularity", "aggregation");
        queries(request, fields, fields);
        if (!propertyKey.matches("[A-Za-z0-9_-]{1,64}")) throw invalid();
        UUID device = uuid(deviceId);
        UUID model = uuid(query(request, "expectedModelVersionId", true));
        String preset = query(request, "windowPreset", true);
        if (!PRESETS.containsKey(preset)) throw invalid();
        Instant anchor = instant(query(request, "anchorAt", true));
        String granularity = query(request, "granularity", true);
        String aggregation = query(request, "aggregation", true);
        if (!Set.of("RAW", "ONE_MINUTE", "ONE_HOUR", "ONE_DAY").contains(granularity)
                || !Set.of("AVG", "MIN", "MAX", "SUM", "COUNT").contains(aggregation)) throw invalid();
        return guarded(() -> DashboardShareDataResponse.History.from(service.history(principal, device, propertyKey,
                model, preset, anchor, granularity, aggregation), anchor.minus(PRESETS.get(preset)), anchor));
    }

    /** 告警的整次设备集合与三组过滤必须由服务证明来自同一实际绑定。 */
    @PostMapping(value = "/alarms/query", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "queryDashboardShareAlarms", summary = "分页读取匿名分享告警实例",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(schema = @Schema(implementation = DashboardShareDataRequest.Alarms.class))))
    @ApiResponse(responseCode = "200", description = "同一绑定过滤后的告警页",
            headers = @Header(name = "Cache-Control", schema = @Schema(type = "string", allowableValues = "no-store")),
            content = @Content(schema = @Schema(implementation = DashboardShareDataResponse.AlarmPage.class)))
    public ResponseEntity<byte[]> alarms(@PathVariable String shareId, HttpServletRequest request) throws IOException {
        DashboardSharePrincipal principal = principal(shareId, request);
        DashboardShareDataRequest.Alarms input = parser.parseAlarms(postBody(request));
        return guarded(() -> DashboardShareDataResponse.AlarmPage.from(service.alarms(principal,
                input.devices().stream().map(value -> new RuntimeDeviceQuery(value.deviceId(), value.expectedModelVersionId(), List.of())).toList(),
                input.conditionStates(), input.ackStates(), input.severities(), input.cursor(), input.limit() == null ? 20 : input.limit())));
    }

    /** 只消费独立安全链确立的身份，不从参数、Cookie或session猜测capability。 */
    private static DashboardSharePrincipal principal(String shareId, HttpServletRequest request) {
        Object value = request.getAttribute(DashboardSharePrincipal.class.getName());
        if (!(value instanceof DashboardSharePrincipal principal) || !principal.shareId().toString().equals(shareId)) {
            throw new BusinessException(DashboardErrorCode.SHARE_NOT_FOUND);
        }
        return principal;
    }
    /** 在Spring byte[]消息转换器之前限界读取，避免非法输入先触发无界内存分配。 */
    private static byte[] postBody(HttpServletRequest request) throws IOException {
        if (request.getQueryString() != null) throw invalid();
        byte[] body = request.getInputStream().readNBytes(DashboardShareDataRequestParser.MAX_REQUEST_BYTES + 1);
        if (body.length > DashboardShareDataRequestParser.MAX_REQUEST_BYTES) throw invalid();
        return body;
    }
    /** GET即便Content-Length缺失也不得实际携带正文。 */
    private static void emptyBody(HttpServletRequest request) throws IOException { if (request.getInputStream().read() != -1) throw invalid(); }
    /** query闭集且物理单值，禁止第二组过滤或客户端secret混入。 */
    private static void queries(HttpServletRequest request, Set<String> allowed, Set<String> required) {
        Map<String, String[]> values = request.getParameterMap();
        if (!allowed.containsAll(values.keySet()) || !values.keySet().containsAll(required)
                || values.values().stream().anyMatch(array -> array == null || array.length != 1)) throw invalid();
    }
    /** 可选query不存在才使用默认，显式空值保持非法。 */
    private static String query(HttpServletRequest request, String name, boolean required) {
        String[] values = request.getParameterValues(name);
        if (values == null) { if (required) throw invalid(); return null; }
        if (values.length != 1 || values[0] == null || values[0].isEmpty()) throw invalid();
        return values[0];
    }
    /** limit的词法拒绝前导零、正负号或空白。 */
    private static int limit(String value) {
        if (value == null) return 20;
        if (!value.matches("[1-9][0-9]?") || Integer.parseInt(value) > 50) throw invalid();
        return Integer.parseInt(value);
    }
    /** 规范小写UUID拒绝Java宽松短段解析。 */
    private static UUID uuid(String value) {
        try { UUID id = UUID.fromString(value); if (!id.toString().equals(value)) throw invalid(); return id; }
        catch (IllegalArgumentException failure) { throw invalid(); }
    }
    /** UTC RFC3339不接受无时区文本或任意偏移别名。 */
    private static Instant instant(String value) {
        try { if (!value.endsWith("Z")) throw invalid(); return Instant.parse(value); }
        catch (DateTimeException failure) { throw invalid(); }
    }
    /** 事务代理与DTO映射异常保持cause并归一503，不由通用MVC500伪装运行合同。 */
    private ResponseEntity<byte[]> guarded(Supplier<Object> value) {
        try {
            DashboardShareRuntimeController.BoundedJsonOutput output = new DashboardShareRuntimeController.BoundedJsonOutput(MAX_RESPONSE_BYTES);
            mapper.writeValue(output, value.get());
            return ResponseEntity.ok().contentType(JSON_UTF8).cacheControl(CacheControl.noStore()).body(output.bytes());
        } catch (BusinessException failure) { throw failure; }
        catch (RuntimeException failure) {
            BusinessException mapped = new BusinessException(DashboardErrorCode.SHARE_DEPENDENCY_UNAVAILABLE);
            mapped.initCause(failure); throw mapped;
        }
    }
    /** 语法及通用输入10001，scope越权由领域明确60054。 */
    private static BusinessException invalid() { return new BusinessException(CommonErrorCode.INVALID_PARAMETER); }
    /** 公开DTO映射到device应用端口，绝不跨模块消费API DTO。 */
    private static RuntimeDeviceQuery device(DashboardShareDataRequest.Device value) {
        return new RuntimeDeviceQuery(value.deviceId(), value.expectedModelVersionId(), value.propertyKeys());
    }
}
