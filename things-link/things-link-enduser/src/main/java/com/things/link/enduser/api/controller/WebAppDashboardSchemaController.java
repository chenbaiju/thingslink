package com.things.link.enduser.api.controller;

import com.things.link.enduser.api.dto.response.WebAppDashboardSchemaResponse;
import com.things.link.enduser.api.support.AppJwtIdentity;
import com.things.link.enduser.application.WebAppDashboardSchemaService;
import com.things.link.shared.error.ApiError;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * 运行访问冻结§3.4/3.5的单看板Schema接口；可信App身份与精确版本由应用服务重新核验。
 * HTTP只接受封闭路径/query，最终编码一次并以同一字节数组发送不超过768KiB的正文。
 */
@RestController
@Validated
@RequestMapping("/api/v1/app/applications")
@Tag(name = "WebApp 应用运行", description = "已认证WebApp应用运行描述")
public class WebAppDashboardSchemaController {

    /** 发布元数据合同§5.3以最终HTTP JSON字节计量768KiB上限。 */
    static final int MAX_SCHEMA_RESPONSE_BYTES = 768 * 1024;

    /** byte[]正文显式声明UTF-8，避免消息转换器退化为无字符集的二进制响应。 */
    private static final MediaType APPLICATION_JSON_UTF8 = new MediaType(
            MediaType.APPLICATION_JSON, StandardCharsets.UTF_8);

    /** App身份、精确Schema与单目标grant编排。 */
    private final WebAppDashboardSchemaService schemaService;

    /** 统一HTTP Jackson配置；大小判断必须基于它最终生成的字节。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建单看板Schema控制器。
     *
     * @param schemaService 单看板Schema授权编排
     * @param objectMapper 统一HTTP JSON序列化器
     */
    public WebAppDashboardSchemaController(
            WebAppDashboardSchemaService schemaService, ObjectMapper objectMapper) {
        this.schemaService = Objects.requireNonNull(schemaService, "schemaService");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * 返回重验当前入口上下文后的单看板Schema包。
     * @param jwt App安全链已验签的access Bearer
     * @param appKey 规范应用公开键
     * @param applicationVersionId 规范小写应用版本UUID
     * @param dashboardVersionId 规范小写看板版本UUID
     * @param expectedPublicationRevision 正十进制Long字符串
     * @param request 用于拒绝未知/重复query及GET正文
     * @return 同一次最终UTF-8编码的十一字段JSON包
     * @throws IOException 请求正文读取失败保留内部首因
     */
    @GetMapping(value = "/{appKey}/versions/{applicationVersionId}/dashboards/{dashboardVersionId}/schema", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getWebAppDashboardSchema", summary = "读取WebApp单看板Schema",
            description = "使用App access Bearer重验精确应用版本、发布代次、看板版本与READ grant。")
    @SecurityRequirement(name = "appAccessBearer")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "单目标Schema及精确上下文可运行",
                    headers = @Header(name = HttpHeaders.CACHE_CONTROL, description = "禁止缓存运行事实",
                            schema = @Schema(type = "string", allowableValues = "no-store")),
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = WebAppDashboardSchemaResponse.class))),
            @ApiResponse(responseCode = "400", description = "路径、query或body不符合封闭合同（10001）",
                    headers = @Header(name = HttpHeaders.CACHE_CONTROL, description = "禁止缓存参数拒绝",
                            schema = @Schema(type = "string", allowableValues = "no-store")),
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "App access Bearer或项目身份代次失效（60009）",
                    headers = @Header(name = HttpHeaders.CACHE_CONTROL, description = "禁止缓存身份拒绝",
                            schema = @Schema(type = "string", allowableValues = "no-store")),
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "应用、当前版本、看板运行状态或grant不可用（60023）",
                    headers = @Header(name = HttpHeaders.CACHE_CONTROL, description = "禁止缓存运行资格拒绝",
                            schema = @Schema(type = "string", allowableValues = "no-store")),
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "500", description = "持久事实、序列化或正文大小不变量失败（90000）",
                    headers = @Header(name = HttpHeaders.CACHE_CONTROL, description = "禁止缓存系统错误",
                            schema = @Schema(type = "string", allowableValues = "no-store")),
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<byte[]> schema(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable @Pattern(regexp = "^app_[0-9a-f]{32}$", message = "应用公开键格式不合法") String appKey,
            @PathVariable @Parameter(schema = @Schema(type = "string", format = "uuid")) String applicationVersionId,
            @PathVariable @Parameter(schema = @Schema(type = "string", format = "uuid")) String dashboardVersionId,
            @RequestParam(required = false) @Parameter(required = true,
                    schema = @Schema(type = "string", pattern = "^[1-9][0-9]{0,18}$"))
            String expectedPublicationRevision,
            HttpServletRequest request) throws IOException {
        // MVC允许缺参进入此处，由闭集守卫统一返回10001；公开合同仍必填，避免框架缺参异常落入通用500。
        requireClosedRequest(request);
        UUID applicationVersion = canonicalUuid(applicationVersionId);
        UUID dashboardVersion = canonicalUuid(dashboardVersionId);
        long publicationRevision = positiveRevision(expectedPublicationRevision);
        WebAppDashboardSchemaResponse response = WebAppDashboardSchemaResponse.from(schemaService.schema(
                AppJwtIdentity.tenantId(jwt), AppJwtIdentity.projectId(jwt), AppJwtIdentity.appUserId(jwt),
                AppJwtIdentity.projectGeneration(jwt), appKey, applicationVersion, publicationRevision, dashboardVersion));
        return ResponseEntity.ok().contentType(APPLICATION_JSON_UTF8).body(encodeWithinLimit(response));
    }

    /** 参数map已由Servlet按URL解码，因而重复编码名也不能变成第二个授权输入。 */
    private static void requireClosedRequest(HttpServletRequest request) throws IOException {
        String[] revisions = request.getParameterValues("expectedPublicationRevision");
        if (request.getParameterMap().size() != 1 || revisions == null || revisions.length != 1
                || request.getInputStream().read() != -1) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
        }
    }

    /** 元数据合同§2拒绝UUID宽松缩写、大写及隐式trim，错误仍沿10001。 */
    private static UUID canonicalUuid(String text) {
        try {
            UUID value = UUID.fromString(text);
            if (!value.toString().equals(text)) throw new IllegalArgumentException("UUID不是规范小写文本");
            return value;
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
        }
    }

    /** 正发布代次必须精确可表示为Long，禁止前导零、符号、空白、小数、指数和溢出。 */
    private static long positiveRevision(String text) {
        if (text == null || !text.matches("[1-9][0-9]{0,18}")) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException exception) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
        }
    }

    /** 以最终Jackson UTF-8字节执行768KiB守卫，等于上限仍允许返回。 */
    private byte[] encodeWithinLimit(WebAppDashboardSchemaResponse response) {
        final byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(response);
        } catch (JacksonException exception) {
            throw new IllegalStateException("WebApp看板Schema响应无法序列化", exception);
        }
        if (bytes.length > MAX_SCHEMA_RESPONSE_BYTES) {
            throw new IllegalStateException("WebApp看板Schema响应超过768KiB上限");
        }
        return bytes;
    }
}
