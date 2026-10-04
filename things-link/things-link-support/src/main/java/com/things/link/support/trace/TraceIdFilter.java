package com.things.link.support.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 为每个请求建立 traceId，并写入响应头。
 *
 * <p>顺序设为最高优先级：traceId 必须在其他所有过滤器之前建立，否则它们打的日志
 * 就没有链路 ID —— 而认证失败、租户解析失败这类问题恰恰最需要能追踪。
 *
 * <h2>接受客户端传入的 traceId</h2>
 * 上游服务（网关、控制台 BFF）已经生成过 traceId 时，沿用它才能把整条调用链串起来。
 * 但传入值属于<b>不可信输入</b>：必须校验格式，否则可以被注入换行符伪造日志行，
 * 或塞入超长字符串撑爆日志。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = resolveTraceId(request);
        TraceContext.set(traceId);
        // 在 finally 之前写响应头：异常路径下响应可能已被提交，那时再写就无效了
        response.setHeader(TraceContext.TRACE_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 必须清理。Web 容器复用线程，残留的 traceId 会串到下一个请求的日志上
            TraceContext.clear();
        }
    }

    /**
     * 取传入的 traceId，不可用时生成新的。
     *
     * @param request 当前请求
     * @return 可信的 traceId
     */
    private String resolveTraceId(HttpServletRequest request) {
        return TraceContext.resolve(request.getHeader(TraceContext.TRACE_ID_HEADER));
    }

}
