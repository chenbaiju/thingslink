package com.things.link.enduser.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 识别唯一允许匿名访问的 WebApp 应用定位请求。
 *
 * <p>S12-2a2b 只允许 {@code GET} 加规范 {@code appKey} 的精确路径公开；方法、尾斜杠、
 * 子路径或大小写任一项变化都不能命中。安全授权与 App 范围过滤器必须共享本实例，避免只在
 * 其中一层放行而把匿名请求错误拒绝或扩大为整个应用路径公开。</p>
 */
final class WebAppApplicationResolveRequestMatcher implements RequestMatcher {

    /** 应用公开键由数据库约束固定为前缀加三十二位小写十六进制。 */
    private static final String CANONICAL_APP_KEY = "app_[0-9a-f]{32}";

    /** 规范匿名入口；锚点阻止尾斜杠与相邻子路径继承公开资格。 */
    private static final Pattern PUBLIC_PATH = Pattern.compile(
            "^/api/v1/app/applications/" + CANONICAL_APP_KEY + "/resolve$");

    /**
     * 仅按 HTTP 方法和去除 contextPath 的请求路径判定公开资格。
     *
     * @param request 待判定请求
     * @return 仅规范 GET resolve 请求返回 true
     */
    @Override
    public boolean matches(HttpServletRequest request) {
        Objects.requireNonNull(request, "request");
        return "GET".equals(request.getMethod()) && PUBLIC_PATH.matcher(applicationPath(request)).matches();
    }

    /**
     * 返回安全链内统一使用的应用相对路径。
     *
     * @param request 当前请求
     * @return 去除部署 contextPath 后的 URI
     */
    static String applicationPath(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath == null || contextPath.isEmpty()) {
            return requestUri;
        }
        if (!requestUri.startsWith(contextPath)) {
            throw new IllegalStateException("请求URI不属于当前contextPath");
        }
        return requestUri.substring(contextPath.length());
    }
}
