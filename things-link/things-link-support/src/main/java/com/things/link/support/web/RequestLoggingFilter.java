package com.things.link.support.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 把每个 HTTP 请求的方法、路径、请求体、状态码与耗时打印到控制台。
 *
 * <p>顺序紧跟在 {@code TraceIdFilter} 之后（HIGHEST_PRECEDENCE + 1）：本过滤器
 * 排在 TraceIdFilter 的内层，打日志时 traceId 仍在 MDC 里，链路 ID 才能落到同一行
 * （TraceIdFilter 在最外层、finally 里清理 MDC，排反了日志就丢了链路 ID）。
 *
 * <p>刻意用 INFO 而不是 DEBUG：本项目的 logback 控制台 appender 用 ThresholdFilter
 * 把级别压在 INFO 以上（见 logback-spring.xml，注释明说「避免 DEBUG 淹没终端」），
 * 所以 DispatcherServlet 的 DEBUG 级「Incoming request」只会进 debug.log 文件、不上控制台。
 *
 * <h2>请求体如何打印</h2>
 * 用 Spring 的 {@link ContentCachingRequestWrapper} 做「事后日志」：它不主动读正文，
 * 而是在下游消费正文时旁路缓存一份，处理完成后再取出来打日志 —— 因此不会抢在
 * {@code @RequestBody} 前面把流读空。不能用项目自建的 {@code CachedBodyHttpServletRequest}：
 * 它在构造时就 eager 读空流，专供幂等哈希这种「必须先读完再交给 Controller」的场景，
 * 用在这里会把所有带正文的写接口搞成 400（详见该类注释）。
 *
 * <h2>隐私提示</h2>
 * 请求体原样打日志意味着密码、密钥这类字段也会进控制台与 info.log。本地开发便利优先，
 * 生产若启用需先做字段脱敏或整体关闭。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestLoggingFilter extends OncePerRequestFilter {

    /** 普通请求诊断日志；一次性分享创建按合同显式退出。 */
    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    /** 可能携带请求体的写方法；只对这些请求做正文旁路缓存，GET 等不缓存、不打印。 */
    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    /** 请求体旁路缓存上限。超过的正文仍会完整交给下游，只是日志截断。 */
    private static final int MAX_BODY_CACHE_BYTES = 8 * 1024;

    /** 日志里正文的最大字符数；超过则截断并打省略号，避免一条日志撑爆终端。 */
    private static final int MAX_BODY_LOG_CHARS = 2048;

    /** ADR0101§2/5：仅一次性secret签发集合端点退出通用原文日志，不扩大到相邻管理写。 */
    private static final Pattern SHARE_CREATION = Pattern.compile(
            "^/api/v1/projects/[^/]+/dashboards/[^/]+/shares$");

    /** ADR0211管理命名空间及其他秘密入口：错误请求也不缓存正文/query。 */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        // 匿名命名空间连拒绝路径也可能携带误放query/body的凭据，改由dashboard独立固定模板事件记录。
        return path.matches("^/api/v1/projects/[^/]+/assistant/knowledge(?:/.*)?$")
                || path.matches("^/api/v1/projects/[^/]+/assistant/fact-reports(?:/.*)?$")
                || path.matches("^/api/v1/projects/[^/]+/assistant/evidence-records(?:/.*)?$")
                || path.matches("^/api/v1/projects/[^/]+/webhooks(?:/.*)?$") || ArtifactUploadRoute.isContent(request) || path.equals("/api/v1/app/browser-auth") || path.startsWith("/api/v1/app/browser-auth/")
                || path.equals("/api/v1/shares") || path.startsWith("/api/v1/shares/")
                || path.equals("/ws/shares") || path.startsWith("/ws/shares/")
                || ("POST".equals(request.getMethod()) && SHARE_CREATION.matcher(path).matches());
    }

    /** 普通请求沿原有有界旁路缓存记录；分享签发已在过滤器入口精确排除。 */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 健康检查与 Prometheus 抓取是周期性噪音，跳过。
        if (request.getRequestURI().startsWith("/actuator/")) {
            filterChain.doFilter(request, response);
            return;
        }

        ContentCachingRequestWrapper cachedRequest = null;
        if (BODY_METHODS.contains(request.getMethod())) {
            cachedRequest = new ContentCachingRequestWrapper(request, MAX_BODY_CACHE_BYTES);
        }
        HttpServletRequest toForward = cachedRequest != null ? cachedRequest : request;

        long start = System.nanoTime();
        try {
            filterChain.doFilter(toForward, response);
        } finally {
            long costMs = (System.nanoTime() - start) / 1_000_000;
            // 未捕获异常的请求这里会先于全局异常处理器打出，status 可能仍读作 200；
            // 排障时以全局异常处理器的日志为准。
            log.info("{} {}{} -> {} ({} ms){}",
                    request.getMethod(),
                    request.getRequestURI(),
                    querySuffix(request),
                    response.getStatus(),
                    costMs,
                    cachedRequest != null ? bodySuffix(cachedRequest) : "");
        }
    }

    /** 查询串（含前导问号），没有则返回空串。 */
    private String querySuffix(HttpServletRequest request) {
        String query = request.getQueryString();
        return query == null ? "" : "?" + query;
    }

    /**
     * 请求体后缀：无正文返回空串，有则 {@code " body=..."}。
     *
     * <p>压平换行符保证单行输出（换行会把一条日志拆成多行、污染控制台与文件滚动），
     * 超长截断并打省略号。
     */
    private String bodySuffix(ContentCachingRequestWrapper request) {
        String body = request.getContentAsString();
        if (body == null || body.isBlank()) {
            return "";
        }
        String flattened = body.replace("\r", " ").replace("\n", " ").trim();
        if (flattened.length() > MAX_BODY_LOG_CHARS) {
            return " body=" + flattened.substring(0, MAX_BODY_LOG_CHARS) + "…";
        }
        return " body=" + flattened;
    }
}
