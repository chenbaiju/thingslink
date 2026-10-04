package com.things.link.enduser.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * 为 WebApp 运行读取入口设置禁止缓存响应头。
 *
 * <p>S12-2a2b/2a4b/2a5 要求成功、业务拒绝、安全拒绝与系统错误都不得被浏览器或中间代理缓存。
 * 本过滤器在安全认证之前先写入响应头，因此后续链路即使没有进入 MVC 也保持同一合同；匹配
 * 只覆盖 GET resolve/current/精确Schema 模板；禁止缓存不授予匿名访问资格，也不扩展已有 App 数据接口。</p>
 */
final class WebAppRuntimeNoStoreFilter extends OncePerRequestFilter {

    /** 路径模板允许任意非斜杠段，以便规范键校验失败的 MVC 响应同样禁止缓存。 */
    private static final Pattern RUNTIME_READ_PATH = Pattern.compile(
            "^/api/v1/app/applications/[^/]+/(?:resolve|current|versions/[^/]+/dashboards/[^/]+/schema)$");

    /**
     * 在进入认证及 MVC 前固定 no-store，后续响应不能因分支不同遗漏该保护。
     *
     * @param request 当前请求
     * @param response 当前响应
     * @param filterChain 后续过滤链
     * @throws ServletException 后续过滤器失败
     * @throws IOException 响应写出失败
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        filterChain.doFilter(request, response);
    }

    /**
     * 只处理 GET resolve/current/精确Schema 模板；方法或相邻路径不同均保持原有缓存行为。
     *
     * @param request 当前请求
     * @return 不属于当前运行入口时返回 true
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"GET".equals(request.getMethod())
                || !RUNTIME_READ_PATH.matcher(WebAppApplicationResolveRequestMatcher.applicationPath(request)).matches();
    }
}
