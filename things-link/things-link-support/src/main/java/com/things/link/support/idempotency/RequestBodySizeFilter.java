package com.things.link.support.idempotency;

import com.things.link.shared.error.ApiError;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.support.trace.TraceContext;
import com.things.link.support.web.ArtifactUploadRoute;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * 为业务写请求实施 1 MiB 请求体硬上限。
 *
 * <p>Servlet 容器的表单上限不覆盖 JSON，单看 Content-Length 又会漏掉 chunked 编码。该过滤器在 JSON 解析和幂等哈希前有界读取，
 * 因而不会让恶意请求先占满堆内存。未来文件上传必须走独立流式端点并显式排除，不能放宽所有管理 API。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 19)
public class RequestBodySizeFilter extends OncePerRequestFilter {

    /** 管理 API 单次写请求体上限；当前最大合法对象远低于 1 MiB。 */
    static final int MAXIMUM_REQUEST_BYTES = 1024 * 1024;

    /** 只有可能携带业务正文的写方法需要预读。 */
    private static final Set<String> GUARDED_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    /** 标准错误响应序列化器。 */
    private final ObjectMapper objectMapper;

    /** @param objectMapper 标准 JSON 序列化器 */
    public RequestBodySizeFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (ArtifactUploadRoute.isContent(request) || !request.getRequestURI().startsWith("/api/")
                || !GUARDED_METHODS.contains(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }
        if (request.getContentLengthLong() > MAXIMUM_REQUEST_BYTES) {
            writeTooLarge(response);
            return;
        }
        try {
            filterChain.doFilter(
                    new CachedBodyHttpServletRequest(request, MAXIMUM_REQUEST_BYTES), response);
        } catch (RequestBodyTooLargeException exception) {
            writeTooLarge(response);
        }
    }

    /** 输出与 Controller 异常一致的错误形状；过滤器异常不经过 GlobalExceptionHandler。 */
    private void writeTooLarge(HttpServletResponse response) throws IOException {
        CommonErrorCode errorCode = CommonErrorCode.PAYLOAD_TOO_LARGE;
        response.setStatus(errorCode.httpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(new ApiError(
                errorCode.code(), errorCode.defaultMessage(), TraceContext.current(), List.of())));
    }
}
