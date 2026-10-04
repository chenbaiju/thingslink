package com.things.link.enduser.api.controller;

import com.things.link.enduser.api.dto.response.WebAppApplicationCurrentResponse;
import com.things.link.enduser.api.support.AppJwtIdentity;
import com.things.link.enduser.application.WebAppApplicationCurrentService;
import com.things.link.shared.error.ApiError;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 已认证WebApp当前应用运行描述接口。
 *
 * <p>S12-2a4b从App access Bearer取得实际身份与项目代次，领域服务在同一事务重验身份、应用current、
 * 看板运行状态及READ grant。控制器只封闭HTTP输入、投影九个根字段并执行最终UTF-8大小守卫。</p>
 */
@RestController
@Validated
@RequestMapping("/api/v1/app/applications")
@Tag(name = "WebApp 应用运行", description = "已认证WebApp应用运行描述")
@SecurityScheme(name = "appAccessBearer", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT",
        description = "WebApp登录后签发的专用App access令牌")
public class WebAppApplicationCurrentController {

    /** 发布元数据合同§5.2以最终HTTP JSON字节计量64KiB上限。 */
    static final int MAX_CURRENT_RESPONSE_BYTES = 64 * 1024;

    /** byte[]正文显式声明UTF-8，避免消息转换器退化为无字符集的二进制响应。 */
    private static final MediaType APPLICATION_JSON_UTF8 = new MediaType(
            MediaType.APPLICATION_JSON, StandardCharsets.UTF_8);

    /** App身份、当前应用与grant交集编排。 */
    private final WebAppApplicationCurrentService currentService;

    /** 统一HTTP Jackson配置；大小判断必须基于它最终生成的字节。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建当前应用运行控制器。
     *
     * @param currentService 当前应用授权编排
     * @param objectMapper 统一HTTP JSON序列化器
     */
    public WebAppApplicationCurrentController(
            WebAppApplicationCurrentService currentService, ObjectMapper objectMapper) {
        this.currentService = Objects.requireNonNull(currentService, "currentService");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * 返回当前App身份可运行的应用版本与看板交集。
     *
     * @param jwt 已通过App安全链验签与有效期校验的access Bearer
     * @param appKey 规范应用公开键
     * @param request 用于拒绝合同外query和GET正文
     * @return 最终编码且不超过64KiB的九字段JSON响应
     * @throws IOException 读取请求正文失败时保留内部首因
     */
    @GetMapping(value = "/{appKey}/current", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getCurrentWebAppApplication", summary = "读取WebApp当前应用",
            description = "使用App access Bearer重验当前应用版本、看板运行状态与READ grant交集。")
    @SecurityRequirement(name = "appAccessBearer")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "当前应用可运行",
                    headers = @Header(name = HttpHeaders.CACHE_CONTROL, description = "禁止缓存运行事实",
                            schema = @Schema(type = "string", allowableValues = "no-store")),
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = WebAppApplicationCurrentResponse.class))),
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
    public ResponseEntity<byte[]> current(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable
            @Pattern(regexp = "^app_[0-9a-f]{32}$", message = "应用公开键格式不合法") String appKey,
            HttpServletRequest request) throws IOException {
        requireClosedRequest(request);
        WebAppApplicationCurrentResponse response = WebAppApplicationCurrentResponse.from(currentService.current(
                AppJwtIdentity.tenantId(jwt), AppJwtIdentity.projectId(jwt), AppJwtIdentity.appUserId(jwt),
                AppJwtIdentity.projectGeneration(jwt), appKey));
        byte[] body = encodeWithinLimit(response);
        return ResponseEntity.ok().contentType(APPLICATION_JSON_UTF8).body(body);
    }

    /** 合同不允许query或GET正文成为隐藏授权输入。 */
    private static void requireClosedRequest(HttpServletRequest request) throws IOException {
        if (request.getQueryString() != null || request.getInputStream().read() != -1) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
        }
    }

    /** 以最终Jackson UTF-8字节执行64KiB守卫，等于上限仍允许返回。 */
    private byte[] encodeWithinLimit(WebAppApplicationCurrentResponse response) {
        final byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(response);
        } catch (JacksonException exception) {
            throw new IllegalStateException("WebApp当前应用响应无法序列化", exception);
        }
        if (bytes.length > MAX_CURRENT_RESPONSE_BYTES) {
            throw new IllegalStateException("WebApp当前应用响应超过64KiB上限");
        }
        return bytes;
    }
}
