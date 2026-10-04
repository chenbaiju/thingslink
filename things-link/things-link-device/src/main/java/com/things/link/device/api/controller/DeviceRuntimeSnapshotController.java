package com.things.link.device.api.controller;

import com.things.link.device.api.dto.request.DeviceCurrentValueSnapshotQueryRequest;
import com.things.link.device.api.dto.request.DeviceSnapshotQueryRequest;
import com.things.link.device.api.dto.response.DeviceRuntimeCurrentValuesResponse;
import com.things.link.device.api.dto.response.DeviceRuntimeSnapshotResponse;
import com.things.link.device.api.dto.response.DeviceRuntimeCatalogResponse;
import com.things.link.device.api.support.DeviceApiAuthorization;
import com.things.link.device.api.support.DeviceRuntimeQueryRequestParser;
import com.things.link.device.application.RuntimeDeviceQueryParser.SnapshotRequest;
import com.things.link.device.application.ConsoleDeviceRuntimeDataService;
import com.things.link.shared.error.ApiError;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Console看板使用的设备描述快照与权威PG当前值只读查询。 */
@Tag(name = "设备运行快照", description = "读取精确模型约束的设备元信息与PG当前值")
@RestController
@RequestMapping("/api/v1/projects/{projectId}/devices")
public class DeviceRuntimeSnapshotController {

    /** 单响应解压后UTF-8 JSON上限；服务端直接编码结果，超过时不返回截断正文。 */
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    /** 明确声明UTF-8，避免byte[]响应被推断为二进制。 */
    private static final MediaType APPLICATION_JSON_UTF8 =
            new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8);

    /** 设备运行数据application端口。 */
    private final ConsoleDeviceRuntimeDataService service;
    /** Console项目成员授权守卫。 */
    private final DeviceApiAuthorization authorization;
    /** 严格原始JSON信封解析器。 */
    private final DeviceRuntimeQueryRequestParser parser;
    /** 最终HTTP字节映射器。 */
    private final ObjectMapper objectMapper;

    /**
     * @param service 设备运行数据服务
     * @param authorization 项目读授权
     * @param parser 严格请求解析器
     * @param objectMapper 最终响应编码器
     */
    public DeviceRuntimeSnapshotController(ConsoleDeviceRuntimeDataService service, DeviceApiAuthorization authorization,
                                           DeviceRuntimeQueryRequestParser parser, ObjectMapper objectMapper) {
        this.service = Objects.requireNonNull(service, "service");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * 返回设备三态与成功设备所需的不可变模型属性描述。
     * @param projectId Console已选择项目
     * @param body 原始UTF-8严格JSON
     * @param servletRequest 用于拒绝合同外query
     * @return 无缓存的完整快照JSON字节
     */
    @PostMapping(value = "/snapshots/query", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "queryDeviceRuntimeSnapshots", summary = "查询设备与精确模型描述快照",
            description = "以单次PG事实读取返回设备三态与不可变模型描述；最终UTF-8 JSON不得超过4MiB。",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(schema = @Schema(implementation = DeviceSnapshotQueryRequest.class))))
    @SecurityRequirement(name = "consoleAccessBearer")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "设备与模型快照，完整响应不超过4MiB",
                    headers = @Header(name = HttpHeaders.CACHE_CONTROL,
                            schema = @Schema(type = "string", allowableValues = "no-store")),
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = DeviceRuntimeSnapshotResponse.class))),
            @ApiResponse(responseCode = "400", description = "请求结构、预算、模型或属性不合法（10001）",
                    headers = @Header(name = HttpHeaders.CACHE_CONTROL,
                            schema = @Schema(type = "string", allowableValues = "no-store")),
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "Console access Bearer无效",
                    headers = @Header(name = HttpHeaders.CACHE_CONTROL,
                            schema = @Schema(type = "string", allowableValues = "no-store")),
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "项目不可见",
                    headers = @Header(name = HttpHeaders.CACHE_CONTROL,
                            schema = @Schema(type = "string", allowableValues = "no-store")),
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "429", description = "当前主体或客户端超过只读查询频率预算",
                    headers = @Header(name = HttpHeaders.CACHE_CONTROL,
                            schema = @Schema(type = "string", allowableValues = "no-store")),
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "500", description = "数据库、持久模型或响应预算异常（90000）",
                    headers = @Header(name = HttpHeaders.CACHE_CONTROL,
                            schema = @Schema(type = "string", allowableValues = "no-store")),
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<byte[]> snapshots(@PathVariable UUID projectId,
                                            @RequestBody(required = false) byte[] body,
                                            HttpServletRequest servletRequest) {
        authorization.requireRead(projectId);
        requireNoQuery(servletRequest);
        SnapshotRequest parsed = parser.parseSnapshots(body);
        byte[] response = encode(DeviceRuntimeSnapshotResponse.from(
                service.querySnapshots(projectId, parsed.devices(), parsed.models())));
        return noStore(response);
    }

    /**
     * 返回每个请求属性的PG值、无值或来源/合同失配状态。
     * @param projectId Console已选择项目
     * @param body 原始UTF-8严格JSON
     * @param servletRequest 用于拒绝合同外query
     * @return 无缓存的完整当前值JSON字节
     */
    @PostMapping(value = "/current-value-snapshots/query", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "queryDeviceCurrentValueSnapshots", summary = "查询权威PG当前值快照",
            description = "从同一PG属性行返回值、时刻与来源模型；最终UTF-8 JSON不得超过4MiB。",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(schema = @Schema(implementation = DeviceCurrentValueSnapshotQueryRequest.class))))
    @SecurityRequirement(name = "consoleAccessBearer")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "设备稀疏当前值快照，完整响应不超过4MiB",
                    headers = @Header(name = HttpHeaders.CACHE_CONTROL,
                            schema = @Schema(type = "string", allowableValues = "no-store")),
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = DeviceRuntimeCurrentValuesResponse.class))),
            @ApiResponse(responseCode = "400", description = "请求结构、预算、模型或属性不合法（10001）",
                    headers = @Header(name = HttpHeaders.CACHE_CONTROL,
                            schema = @Schema(type = "string", allowableValues = "no-store")),
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "Console access Bearer无效",
                    headers = @Header(name = HttpHeaders.CACHE_CONTROL,
                            schema = @Schema(type = "string", allowableValues = "no-store")),
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "项目不可见",
                    headers = @Header(name = HttpHeaders.CACHE_CONTROL,
                            schema = @Schema(type = "string", allowableValues = "no-store")),
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "429", description = "当前主体或客户端超过只读查询频率预算",
                    headers = @Header(name = HttpHeaders.CACHE_CONTROL,
                            schema = @Schema(type = "string", allowableValues = "no-store")),
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "500", description = "数据库、持久模型或响应预算异常（90000）",
                    headers = @Header(name = HttpHeaders.CACHE_CONTROL,
                            schema = @Schema(type = "string", allowableValues = "no-store")),
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<byte[]> currentValues(@PathVariable UUID projectId,
                                                @RequestBody(required = false) byte[] body,
                                                HttpServletRequest servletRequest) {
        authorization.requireRead(projectId);
        requireNoQuery(servletRequest);
        byte[] response = encode(DeviceRuntimeCurrentValuesResponse.from(
                service.queryCurrentValues(projectId, parser.parseCurrentValues(body))));
        return noStore(response);
    }

    /**
     * S12-4b设计器从所选设备发现精确模型，运行时仍通过快照端点重新验证。
     * @param projectId 已选择的Console项目
     * @param deviceId 明确选择的设备
     * @param servletRequest 拒绝未冻结查询参数
     * @return 一台设备和一个完整顶层属性模型，不持久缓存
     */
    @GetMapping(value = "/{deviceId}/binding-metadata", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getDeviceBindingMetadata", summary = "读取设备当前模型绑定元数据",
            description = "返回单设备当前精确物模型引用及最多200个顶层属性；未绑定或读取期间换模返回10001。")
    @SecurityRequirement(name = "consoleAccessBearer")
    @ApiResponse(responseCode = "200", description = "单设备和完整模型描述，最终JSON不超过4MiB",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = DeviceRuntimeSnapshotResponse.class)))
    public ResponseEntity<byte[]> bindingMetadata(@PathVariable UUID projectId, @PathVariable UUID deviceId,
                                                   HttpServletRequest servletRequest) {
        authorization.requireRead(projectId);
        requireNoQuery(servletRequest);
        return noStore(encode(DeviceRuntimeSnapshotResponse.from(service.bindingMetadata(projectId, deviceId))));
    }

    /**
     * @param projectId 已选项目
     * @param modelVersionId 变量声明的精确模型
     * @param cursor 可选签名游标
     * @param limit 1..50页大小
     * @param request 检查封闭query和重复参数
     * @return 完整有界目录；不返回凭据、排序时间或模型原文
     */
    @GetMapping(value = "/catalog", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getConsoleDeviceCatalog", summary = "查询Console精确模型设备目录",
            description = "普通项目成员，授权、精确模型及软删过滤先于分页；游标绑定主体/项目/模型/limit，完整JSON最多4MiB。")
    @SecurityRequirement(name = "consoleAccessBearer")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "当前完整页，Cache-Control:no-store",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = DeviceRuntimeCatalogResponse.class))),
            @ApiResponse(responseCode = "400", description = "参数或签名游标不合法"),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "404", description = "项目不可见")
    })
    public ResponseEntity<byte[]> catalog(@PathVariable UUID projectId,
            @RequestParam UUID modelVersionId, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit, HttpServletRequest request) {
        authorization.requireRead(projectId);
        if (!java.util.Set.of("modelVersionId", "cursor", "limit").containsAll(request.getParameterMap().keySet())
                || request.getParameterMap().values().stream().anyMatch(values -> values.length != 1)) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
        }
        return noStore(encode(DeviceRuntimeCatalogResponse.from(service.catalog(projectId, modelVersionId, cursor, limit))));
    }

    /** 最终序列化字节是4MiB预算的唯一计量依据。 */
    private byte[] encode(Object response) {
        byte[] bytes = objectMapper.writeValueAsBytes(response);
        if (bytes.length > MAX_RESPONSE_BYTES) throw new IllegalStateException("设备运行查询响应超过4MiB上限");
        return bytes;
    }

    /** POST信封已冻结全部过滤字段，额外query不能被MVC静默忽略。 */
    private static void requireNoQuery(HttpServletRequest request) {
        if (request.getQueryString() != null) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
        }
    }

    /** 所有成功运行事实不进入浏览器或代理缓存。 */
    private static ResponseEntity<byte[]> noStore(byte[] response) {
        return ResponseEntity.ok().contentType(APPLICATION_JSON_UTF8)
                .cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache").body(response);
    }
}
