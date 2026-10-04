package com.things.link.enduser.api.controller;

import com.things.link.enduser.api.dto.response.WebAppApplicationResolutionResponse;
import com.things.link.enduser.application.WebAppApplicationResolutionService;
import com.things.link.shared.error.ApiError;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
 * WebApp 登录前应用公开定位接口。
 *
 * <p>S12-2a2b 的匿名入口只用于取得登录页所需的公开键与展示名称，不建立用户身份、数据授权
 * 或看板读取资格。安全链只公开规范 GET 路径，领域层继续负责应用发布与项目生命周期重验。</p>
 */
@RestController
@Validated
@RequestMapping("/api/v1/app/applications")
@Tag(name = "WebApp 应用运行", description = "登录前公开应用定位")
public class WebAppApplicationController {

    /** 冻结合同以最终 JSON 的 UTF-8 字节数计量，不能用 Java 字符数近似。 */
    static final int MAX_RESOLUTION_RESPONSE_BYTES = 2 * 1024;

    /** byte[]响应必须显式声明UTF-8，不能依赖消息转换器为JSON补默认字符集。 */
    private static final MediaType APPLICATION_JSON_UTF8 = new MediaType(
            MediaType.APPLICATION_JSON, StandardCharsets.UTF_8);

    /** 两阶段应用与项目公开事实编排。 */
    private final WebAppApplicationResolutionService resolutionService;

    /** 最终 HTTP JSON 序列化器；先得到实际字节再执行上限守卫。 */
    private final ObjectMapper objectMapper;

    /**
     * 创建公开应用定位控制器。
     *
     * @param resolutionService 应用公开定位编排
     * @param objectMapper 统一 HTTP JSON 序列化器
     */
    public WebAppApplicationController(WebAppApplicationResolutionService resolutionService,
                                       ObjectMapper objectMapper) {
        this.resolutionService = Objects.requireNonNull(resolutionService, "resolutionService");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * 解析公开应用键并返回登录前最小展示结果。
     *
     * @param appKey 规范应用公开键
     * @param request 用于拒绝合同外 query 与请求正文
     * @return 最终编码且不超过2KiB的JSON响应
     * @throws IOException 读取请求正文失败时保留内部首因
     */
    @GetMapping(value = "/{appKey}/resolve", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "resolveWebAppApplication", summary = "解析WebApp公开应用",
            description = "按规范appKey取得登录页最小公开信息；该结果不授予任何数据访问资格。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "应用可运行",
                    headers = @Header(name = HttpHeaders.CACHE_CONTROL, description = "禁止缓存运行事实",
                            schema = @Schema(type = "string", allowableValues = "no-store")),
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = WebAppApplicationResolutionResponse.class))),
            @ApiResponse(responseCode = "400", description = "路径、query或body不符合封闭合同（10001）",
                    headers = @Header(name = HttpHeaders.CACHE_CONTROL, description = "禁止缓存参数拒绝",
                            schema = @Schema(type = "string", allowableValues = "no-store")),
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "应用运行入口不可用或未授权（60023）",
                    headers = @Header(name = HttpHeaders.CACHE_CONTROL, description = "禁止缓存运行资格拒绝",
                            schema = @Schema(type = "string", allowableValues = "no-store")),
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "500", description = "持久事实、序列化或正文大小不变量失败（90000）",
                    headers = @Header(name = HttpHeaders.CACHE_CONTROL, description = "禁止缓存系统错误",
                            schema = @Schema(type = "string", allowableValues = "no-store")),
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<byte[]> resolve(
            @PathVariable
            @Pattern(regexp = "^app_[0-9a-f]{32}$", message = "应用公开键格式不合法") String appKey,
            HttpServletRequest request) throws IOException {
        requireClosedRequest(request);
        WebAppApplicationResolutionResponse response = WebAppApplicationResolutionResponse.from(
                resolutionService.resolve(appKey));
        byte[] body = encodeWithinLimit(response);
        return ResponseEntity.ok()
                .contentType(APPLICATION_JSON_UTF8)
                .body(body);
    }

    /**
     * 拒绝会改变缓存键或形成隐藏输入的 query 与 GET 正文。
     *
     * @param request 原始HTTP请求
     * @throws IOException 读取请求流失败
     * @throws BusinessException 存在任一合同外输入时返回10001
     */
    private static void requireClosedRequest(HttpServletRequest request) throws IOException {
        if (request.getQueryString() != null || request.getInputStream().read() != -1) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
        }
    }

    /**
     * 以实际交付的Jackson字节执行2KiB上限，序列化或越界均保持内部故障语义。
     *
     * @param response 三字段公开响应
     * @return 最终UTF-8 JSON字节
     */
    private byte[] encodeWithinLimit(WebAppApplicationResolutionResponse response) {
        final byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(response);
        } catch (JacksonException exception) {
            throw new IllegalStateException("应用公开定位响应无法序列化", exception);
        }
        if (bytes.length > MAX_RESOLUTION_RESPONSE_BYTES) {
            throw new IllegalStateException("应用公开定位响应超过2KiB上限");
        }
        return bytes;
    }
}
