package com.things.link.dashboard.infrastructure.security;

import com.things.link.dashboard.application.DashboardSharePrincipal;
import com.things.link.dashboard.application.DashboardShareRuntimeService;
import com.things.link.dashboard.application.sharing.DashboardShareProtectionService;
import com.things.link.dashboard.application.sharing.DashboardShareRuntimeProperties;
import com.things.link.dashboard.application.sharing.DashboardShareSecurityEvents;
import com.things.link.dashboard.domain.DashboardErrorCode;
import com.things.link.shared.error.ApiError;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.error.ErrorCode;
import com.things.link.support.trace.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * ADR0101§3～5：来源保护先于查库，真实capability后才扣已知身份预算，全部字节结算前不发给客户端。
 * 仅承担匿名HTTP边界，不复制领域资源有效性，也不使用Console/App身份或Session。
 */
public final class DashboardShareAuthenticationFilter extends OncePerRequestFilter {
    /** 路径身份只接受规范小写UUID，不把查询参数当capability选择器。 */
    private static final String UUID_PATH = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    /** 本片七条精确路径；历史中的设备与属性有固定语法，仍不开放任意shares或WS。 */
    private static final Pattern ROUTE = Pattern.compile("^/api/v1/shares/(" + UUID_PATH + ")/(context|schema|"
            + "devices/snapshots/query|devices/current-values/query|devices/catalog|alarms/query|devices/"
            + UUID_PATH + "/properties/[A-Za-z0-9_-]{1,64}/history)$");
    /** POST原始编码最多64KiB；实际流仍由Controller按limit+1读取，不能只信Content-Length。 */
    private static final int MAX_REQUEST_BYTES = 64 * 1024;
    /** 32随机字节的唯一无padding外部编码。 */
    private static final Pattern SECRET = Pattern.compile("sh_[A-Za-z0-9_-]{43}");
    /** 领域每请求回库校验，不在过滤器中缓存capability有效性。 */
    private final DashboardShareRuntimeService runtime;
    /** Redis共享额度与本机16在途，不接受未知token派生键。 */
    private final DashboardShareProtectionService protection;
    /** 默认禁用与精确受管origin。 */
    private final DashboardShareRuntimeProperties properties;
    /** 独立有界安全诊断，不记录原始输入。 */
    private final DashboardShareSecurityEvents events;
    /** 安全链之外也使用统一ApiError。 */
    private final ObjectMapper mapper;

    /** 所有依赖由唯一匿名Security链构造，避免Servlet自动二次注册。 */
    public DashboardShareAuthenticationFilter(DashboardShareRuntimeService runtime, DashboardShareProtectionService protection,
            DashboardShareRuntimeProperties properties, DashboardShareSecurityEvents events, ObjectMapper mapper) {
        this.runtime = runtime;
        this.protection = protection;
        this.properties = properties;
        this.events = events;
        this.mapper = mapper;
    }

    /** 共享给链的精确允许条件；Cookie/JWT不能扩大方法或路由。 */
    public static boolean isReadableRoute(HttpServletRequest request) {
        var match = ROUTE.matcher(path(request));
        if (!match.matches()) return false;
        boolean post = match.group(2).endsWith("/query");
        return (post ? "POST" : "GET").equals(request.getMethod());
    }

    /** 请求全程持有在途许可，错误同样不会绕过固定来源桶后先访问数据库。 */
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                            FilterChain chain) throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        if (!isReadableRoute(request)) {
            writeError(response, DashboardErrorCode.SHARE_SCOPE_FORBIDDEN, null);
            return;
        }
        var match = ROUTE.matcher(path(request));
        if (!match.matches()) throw new IllegalStateException("分享路由匹配不一致");
        UUID shareId = UUID.fromString(match.group(1));
        var route = route(match.group(2));
        var outcome = DashboardShareSecurityEvents.Outcome.ERROR;
        var reason = DashboardShareSecurityEvents.Reason.INTERNAL_ERROR;
        DashboardSharePrincipal principal = null;
        DashboardShareProtectionService.SourcePermit source = null;
        long started = System.nanoTime();
        long bytes = 0;
        try {
            if (!properties.enabled()) throw new BusinessException(DashboardErrorCode.SHARE_DEPENDENCY_UNAVAILABLE);
            source = protection.acquireSource(request.getRemoteAddr());
            requireTransport(request, route);
            String hash = credentialHash(request);
            {
                principal = runtime.authenticate(shareId, hash);
                requireReferer(request, principal);
                protection.requireKnown(principal.shareId(), principal.projectId(), principal.tenantId());
                int maximum = switch (route) {
                    case CONTEXT -> 16 * 1024;
                    case SCHEMA -> 768 * 1024;
                    default -> 4 * 1024 * 1024;
                };
                try (var reservation = protection.reserve(principal.shareId(), principal.projectId(), principal.tenantId(), maximum)) {
                    BoundedResponse buffered = new BoundedResponse(response, maximum);
                    request.setAttribute(DashboardSharePrincipal.class.getName(), principal);
                    chain.doFilter(request, buffered);
                    byte[] body = buffered.complete();
                    reservation.finish(body.length);
                    bytes = body.length;
                    response.setContentLength(body.length);
                    response.getOutputStream().write(body);
                    outcome = response.getStatus() < 400 ? DashboardShareSecurityEvents.Outcome.ALLOWED
                            : response.getStatus() < 500 ? DashboardShareSecurityEvents.Outcome.DENIED
                            : DashboardShareSecurityEvents.Outcome.ERROR;
                    reason = response.getStatus() < 400 ? DashboardShareSecurityEvents.Reason.SUCCESS
                            : response.getStatus() < 500 ? DashboardShareSecurityEvents.Reason.UNAVAILABLE
                            : DashboardShareSecurityEvents.Reason.INTERNAL_ERROR;
                }
            }
        } catch (RefererDenied failure) {
            outcome = DashboardShareSecurityEvents.Outcome.DENIED;
            reason = DashboardShareSecurityEvents.Reason.REFERER_DENIED;
            bytes += writeError(response, DashboardErrorCode.SHARE_SCOPE_FORBIDDEN, principal);
        } catch (ResponseLimit failure) {
            reason = DashboardShareSecurityEvents.Reason.RESPONSE_LIMIT;
            bytes += writeError(response, DashboardErrorCode.SHARE_DEPENDENCY_UNAVAILABLE, principal);
        } catch (BusinessException failure) {
            ErrorCode code = failure.errorCode();
            outcome = code.httpStatus() < 500 ? DashboardShareSecurityEvents.Outcome.DENIED : DashboardShareSecurityEvents.Outcome.ERROR;
            reason = code.code() == 60056 ? DashboardShareSecurityEvents.Reason.RATE_LIMITED
                    : code.code() == 10001 ? DashboardShareSecurityEvents.Reason.INVALID_INPUT
                    : DashboardShareSecurityEvents.Reason.UNAVAILABLE;
            bytes += writeError(response, code, principal);
        } catch (Exception failure) {
            // 事务创建/Redis连接失败可能发生在领域方法体之前；保持统一503且绝不把异常原文写出。
            bytes += writeError(response, DashboardErrorCode.SHARE_DEPENDENCY_UNAVAILABLE, principal);
        } finally {
            if (response.getStatus() == 429) {
                outcome = DashboardShareSecurityEvents.Outcome.DENIED;
                reason = DashboardShareSecurityEvents.Reason.RATE_LIMITED;
            } else if (response.getStatus() >= 500) {
                outcome = DashboardShareSecurityEvents.Outcome.ERROR;
            }
            request.removeAttribute(DashboardSharePrincipal.class.getName());
            try {
                events.record(DashboardShareSecurityEvents.Type.REQUEST, outcome, reason, TraceContext.current(), route,
                        principal == null ? null : principal.shareId(), principal == null ? null : principal.projectId(),
                        bytes, (System.nanoTime() - started) / 1_000_000);
            } finally {
                if (source != null) source.close();
            }
        }
    }

    /** 固定路由枚举，诊断永不记录实际device/property/选择器路径。 */
    private static DashboardShareSecurityEvents.Route route(String suffix) {
        return switch (suffix) {
            case "context" -> DashboardShareSecurityEvents.Route.CONTEXT;
            case "schema" -> DashboardShareSecurityEvents.Route.SCHEMA;
            case "devices/snapshots/query" -> DashboardShareSecurityEvents.Route.SNAPSHOTS;
            case "devices/current-values/query" -> DashboardShareSecurityEvents.Route.CURRENT_VALUES;
            case "devices/catalog" -> DashboardShareSecurityEvents.Route.CATALOG;
            case "alarms/query" -> DashboardShareSecurityEvents.Route.ALARMS;
            default -> DashboardShareSecurityEvents.Route.HISTORY;
        };
    }

    /** 方法已精确匹配；POST仅JSON正文，GET仅自身闭集query，不因开放数据读取接收query凭据。 */
    private static void requireTransport(HttpServletRequest request, DashboardShareSecurityEvents.Route route) {
        if ("POST".equals(request.getMethod())) {
            // 读取parameterMap可能触发Servlet无界form解析，故POST完全不访问该入口。
            String contentType = singleHeader(request, "Content-Type");
            if (!contentType.matches("(?i)application/json(?:[ \t]*;[ \t]*charset[ \t]*=[ \t]*(?:utf-8|\"utf-8\"))?[ \t]*")
                    || request.getHeader("Content-Encoding") != null || request.getQueryString() != null
                    || request.getContentLengthLong() > MAX_REQUEST_BYTES) throw invalid();
            return;
        }
        if (request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null) throw invalid();
        Set<String> allowed = switch (route) {
            case CATALOG -> Set.of("variableKey", "cursor", "limit");
            case HISTORY -> Set.of("expectedModelVersionId", "windowPreset", "anchorAt", "granularity", "aggregation");
            default -> Set.of();
        };
        if (!allowed.containsAll(request.getParameterMap().keySet())
                || (allowed.isEmpty() && request.getQueryString() != null)) throw invalid();
    }

    /** 缺失、多个、逗号拼接或非规范编码均输入拒绝；不读取Cookie、Session或URL中的凭据。 */
    private static String credentialHash(HttpServletRequest request) {
        if (request.getHeader("Authorization") != null) throw invalid();
        String token = singleHeader(request, "X-Share-Token");
        if (!SECRET.matcher(token).matches()) throw invalid();
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(token.substring(3));
            if (decoded.length != 32 || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(token.substring(3))) {
                throw invalid();
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (IllegalArgumentException malformed) {
            throw invalid();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK缺少SHA-256", impossible);
        }
    }

    /** Referer只附加校实际发起页origin，不把它当capability、转发证明或可伪造用户身份。 */
    private void requireReferer(HttpServletRequest request, DashboardSharePrincipal principal) {
        if ("NONE".equals(principal.refererPolicy())) return;
        if (!"HOST_ORIGIN".equals(principal.refererPolicy())) throw new IllegalStateException("持久Referer策略非法");
        try {
            String raw = singleHeader(request, "Referer");
            URI actual = URI.create(raw);
            URI expected = URI.create(properties.hostOrigin());
            if (!actual.isAbsolute() || actual.getUserInfo() != null || actual.getFragment() != null
                    || actual.getHost() == null || (!"http".equalsIgnoreCase(actual.getScheme())
                    && !"https".equalsIgnoreCase(actual.getScheme()))
                    || !actual.getScheme().equalsIgnoreCase(expected.getScheme())
                    || !actual.getHost().equalsIgnoreCase(expected.getHost()) || port(actual) != port(expected)) {
                throw new RefererDenied();
            }
        } catch (IllegalArgumentException | BusinessException failure) {
            throw new RefererDenied();
        }
    }

    /** 未显式端口按HTTP标准有效端口比较，不比较字符串前缀/后缀。 */
    private static int port(URI uri) { return uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80; }
    /** Header仅一个原始字段且不能用逗号组合多个值。 */
    private static String singleHeader(HttpServletRequest request, String name) {
        List<String> values = Collections.list(request.getHeaders(name));
        if (values.size() != 1 || values.getFirst().isBlank() || values.getFirst().contains(",")) throw invalid();
        return values.getFirst();
    }
    /** 拒绝正文/query中的凭据，不回显非法值。 */
    private static BusinessException invalid() { return new BusinessException(CommonErrorCode.INVALID_PARAMETER); }
    /** contextPath仅部署前缀，不属于冻结业务路由。 */
    private static String path(HttpServletRequest request) { return request.getRequestURI().substring(request.getContextPath().length()); }

    /**
     * 已知身份的错误也按精确编码字节预留/结算；预算或Redis连错误体也无法保护时只返回429/503头。
     * 返回尝试写出的编码字节，不声称客户端已收到；未知身份仅固定≤2KiB错误，不能为未知token建键。
     */
    private long writeError(HttpServletResponse response, ErrorCode code, DashboardSharePrincipal principal) throws IOException {
        if (response.isCommitted()) return 0;
        byte[] bytes = mapper.writeValueAsBytes(new ApiError(code.code(), code.defaultMessage(), TraceContext.current(), List.of()));
        if (bytes.length > 2048) {
            emptyFailure(response, 503);
            return 0;
        }
        if (principal != null) {
            try (var reservation = protection.reserve(principal.shareId(), principal.projectId(), principal.tenantId(), bytes.length)) {
                reservation.finish(bytes.length);
            } catch (Exception unavailable) {
                int status = unavailable instanceof BusinessException business && business.errorCode().code() == 60056 ? 429 : 503;
                emptyFailure(response, status);
                return 0;
            }
        }
        response.resetBuffer();
        response.setStatus(code.httpStatus());
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Location", null);
        if (code.httpStatus() == 429) response.setHeader("Retry-After", "1");
        response.setContentLength(bytes.length);
        try {
            response.getOutputStream().write(bytes);
        } catch (IOException disconnected) {
            // 连接已断不重试；预算与诊断保守记尝试发送字节，不能退款后声称这些字节从未可能到达。
            return bytes.length;
        }
        return bytes.length;
    }

    /** 硬保护耗尽时不无限产生未计量JSON，空体仍精确提供HTTP状态/no-store与重试提示。 */
    private static void emptyFailure(HttpServletResponse response, int status) {
        response.resetBuffer();
        response.setStatus(status);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Location", null);
        response.setContentLength(0);
        if (status == 429) response.setHeader("Retry-After", "1");
    }

    /** 一次性上限缓冲：flush/close不会提前提交真实响应，越界在预留额度内失败。 */
    private static final class BoundedResponse extends HttpServletResponseWrapper {
        /** 固定容量不因下游写入而扩容，也不接受无界ContentCachingResponseWrapper。 */
        private final ByteArrayOutputStream body;
        /** 合同context/schema分别16KiB/768KiB。 */
        private final int maximum;
        /** MVC使用writer时延迟编码内容也必须在complete前刷入同一有限缓冲。 */
        private PrintWriter writer;
        /** 超限即使被MVC异常处理捕获，最终也必须丢弃整轮响应。 */
        private boolean exceeded;
        /** 仅提供同步有界写接口，不允许异步Servlet绕过结算。 */
        private final ServletOutputStream output = new ServletOutputStream() {
            /** 同步Servlet写入不会依赖异步ready状态。 */
            @Override public boolean isReady() { return true; }
            /** 未交付异步响应，不允许注册监听后脱离本轮预算。 */
            @Override public void setWriteListener(WriteListener listener) { throw new IllegalStateException("分享不支持异步响应"); }
            /** 单字节同样受编码后字节上限限制。 */
            @Override public void write(int value) { ensure(1); body.write(value); }
            /** 写入前检查完整长度，不能先写一部分再宣布越界。 */
            @Override public void write(byte[] value, int offset, int length) { ensure(length); body.write(value, offset, length); }
        };
        /** @param maximum 完整输出编码后字节硬上限 */
        BoundedResponse(HttpServletResponse response, int maximum) {
            super(response);
            this.maximum = maximum;
            this.body = new ByteArrayOutputStream(maximum);
        }
        /** 所有实际输出统一进入同一有界流。 */
        @Override public ServletOutputStream getOutputStream() { return output; }
        /** 使用UTF-8与冻结HTTP合同一致。 */
        @Override public PrintWriter getWriter() {
            if (writer == null) writer = new PrintWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
            return writer;
        }
        /** flush仅刷新到本地，不让错误页或MVC提前下发未结算内容。 */
        @Override public void flushBuffer() { if (writer != null) writer.flush(); }
        /** 不让redirect绕过body预算和同源无重定向契约。 */
        @Override public void sendRedirect(String location) { throw new ResponseLimit(); }
        /** Servlet6.1显式状态重定向也不能绕过本地缓冲。 */
        @Override public void sendRedirect(String location, int status) { throw new ResponseLimit(); }
        /** Servlet6.1清缓存标志不改变匿名禁止重定向语义。 */
        @Override public void sendRedirect(String location, boolean clearBuffer) { throw new ResponseLimit(); }
        /** Servlet6.1完整重载同样不能直接提交真实response。 */
        @Override public void sendRedirect(String location, int status, boolean clearBuffer) { throw new ResponseLimit(); }
        /** 直接设置3xx也不能偷偷把浏览器带到凭据或业务正文的另一个来源。 */
        @Override public void setStatus(int status) { if (status >= 300 && status < 400) throw new ResponseLimit(); super.setStatus(status); }
        /** 公开匿名响应绝不签发会话Cookie。 */
        @Override public void addCookie(Cookie cookie) { throw new ResponseLimit(); }
        /** 拒绝Location/Set-Cookie，不依赖调用者使用高级重定向API。 */
        @Override public void setHeader(String name, String value) {
            requireSafeHeader(name); super.setHeader(name, value);
        }
        /** 多值方式也不得绕过安全header拒绝。 */
        @Override public void addHeader(String name, String value) {
            requireSafeHeader(name); super.addHeader(name, value);
        }
        /** 任意大小写均不能从响应透出Cookie或重定向。 */
        private static void requireSafeHeader(String name) {
            if ("Location".equalsIgnoreCase(name) || "Set-Cookie".equalsIgnoreCase(name)) throw new ResponseLimit();
        }
        /** sendError留在有界缓冲，不调用容器提交真正错误页。 */
        @Override public void sendError(int status, String message) { setStatus(status); body.reset(); }
        /** 无正文sendError同样不提交。 */
        @Override public void sendError(int status) { sendError(status, ""); }
        /** 容器尚未提交，MVC错误处理只清本地缓冲。 */
        @Override public void resetBuffer() { body.reset(); }
        /** reset只清本地缓冲并恢复安全头，不提交底层响应；已超限标记不能被错误处理抹去。 */
        @Override public void reset() {
            body.reset(); super.reset(); super.setHeader("Cache-Control", "no-store");
        }
        /** 不依赖controller设置的content-length证明预算，最终以实际字节重算。 */
        byte[] complete() { flushBuffer(); if (exceeded) throw new ResponseLimit(); return body.toByteArray(); }
        /** ByteArrayOutputStream提前定容，禁止扩容路径。 */
        private void ensure(int length) { if (length < 0 || length > maximum - body.size()) { exceeded = true; throw new ResponseLimit(); } }
    }
    /** 固定内部分类不携带原始Referer。 */
    private static final class RefererDenied extends RuntimeException { }
    /** 固定内部分类不携带业务正文。 */
    private static final class ResponseLimit extends RuntimeException { }
}
