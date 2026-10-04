package com.things.link.ingestion.infrastructure.protocol.http;

import com.things.link.ingestion.application.access.DeviceAccessAuthBudget;
import com.things.link.ingestion.application.access.DeviceAccessAuthFailureReason;
import com.things.link.ingestion.application.access.DeviceAccessDeviceAuthenticator;
import com.things.link.ingestion.application.access.DeviceAccessDeviceAuthenticationException;
import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * 设备面 HTTP 认证过滤器：把认证结果放进请求属性，失败则在进入业务层之前结束请求。
 *
 * <p>顺序是冻结语义的一部分：先解析凭据头（缺失即 {@code AUTH_REQUIRED}），再扣预算（超限 429 且带
 * {@code Retry-After}），再校验凭据并确认设备已开通 HTTP 平面，最后记录活动。这样一次错误口令不会同时
 * 消耗设备令牌桶之外的东西，越权请求也不会在业务层留下任何事实。</p>
 *
 * <p>明文密钥只在本过滤器内被读取一次并立刻交给校验端口，不写日志、不进审计、不进诊断；失败响应只回稳定
 * 错误码与一句说明。</p>
 */
public class DeviceAccessHttpAuthenticationFilter extends OncePerRequestFilter {

    /** 只记录放行回环明文这一件事，凭据本身永不进日志。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(DeviceAccessHttpAuthenticationFilter.class);

    /** 认证成功后写入请求属性的设备身份，业务处理器只读它，不重新解析请求头。 */
    public static final String IDENTITY_ATTRIBUTE = "things-link.device-access.identity";

    /** 设备身份头：{@code projectKey/deviceKey}。 */
    public static final String DEVICE_KEY_HEADER = "X-TC-Device-Key";

    /** 设备密钥头：明文密钥。 */
    public static final String DEVICE_SECRET_HEADER = "X-TC-Device-Secret";

    /** 设备认证与活动端口。 */
    private final DeviceAccessDeviceAuthenticator authenticator;

    /** 认证面预算。 */
    private final DeviceAccessAuthBudget authBudget;

    /** 是否允许回环明文（仅测试；生产必须为 HTTPS）。 */
    private final boolean allowInsecureLoopback;

    /**
     * @param authenticator 设备认证与活动端口
     * @param authBudget 认证面预算
     * @param allowInsecureLoopback 是否允许非 TLS 请求（仅测试开关）
     */
    public DeviceAccessHttpAuthenticationFilter(DeviceAccessDeviceAuthenticator authenticator,
                                               DeviceAccessAuthBudget authBudget,
                                               boolean allowInsecureLoopback) {
        this.authenticator = authenticator;
        this.authBudget = authBudget;
        this.allowInsecureLoopback = allowInsecureLoopback;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String deviceKey = request.getHeader(DEVICE_KEY_HEADER);
        String secret = request.getHeader(DEVICE_SECRET_HEADER);
        if (deviceKey == null || deviceKey.isBlank() || secret == null || secret.isBlank()) {
            writeError(response, 401, "AUTH_REQUIRED", "缺少设备凭据头");
            return;
        }
        if (!request.isSecure()) {
            if (!allowInsecureLoopback || !isLoopbackAddress(request.getRemoteAddr())) {
                // 测试开关也只允许实际回环连接；不能信任客户端提供的转发头来判断来源。
                writeError(response, 401, "AUTH_REQUIRED", "设备接入必须使用 HTTPS");
                return;
            }
            // 冻结要求测试专用开关留痕：只记方法与路径，不记凭据。
            LOGGER.warn("设备面收到非 TLS 请求且回环明文放行已开启 method={} path={}",
                    request.getMethod(), request.getRequestURI());
        }

        int separator = deviceKey.indexOf('/');
        if (separator <= 0 || separator == deviceKey.length() - 1) {
            writeError(response, 401, "AUTH_FAILED", "设备身份格式不合法");
            return;
        }
        String projectKey = deviceKey.substring(0, separator);
        String shortDeviceKey = deviceKey.substring(separator + 1);

        switch (authBudget.check(clientIp(request), projectKey, shortDeviceKey)) {
            case RATE_LIMITED -> {
                writeRetryAfter(response, Duration.ofSeconds(1));
                writeError(response, 429, "RATE_LIMITED", "认证请求超出接入预算");
                return;
            }
            case BACKOFF -> {
                long retryAfterMillis = authBudget.remainingBackoffMillis(projectKey, shortDeviceKey);
                writeRetryAfter(response, Duration.ofMillis(Math.max(retryAfterMillis, 1_000L)));
                writeError(response, 429, "RATE_LIMITED", "认证失败退避中");
                return;
            }
            case ALLOW -> {
                // 继续校验凭据。
            }
        }

        AuthenticatedDeviceIdentity identity;
        try {
            identity = authenticator.authenticate(com.things.link.shared.message.TransportProtocol.HTTP,
                    projectKey, shortDeviceKey, secret);
            authBudget.recordSuccess(projectKey, shortDeviceKey);
        } catch (DeviceAccessDeviceAuthenticationException exception) {
            authBudget.recordFailure(projectKey, shortDeviceKey);
            writeError(response, exception.reason().httpStatus(), exception.reason().errorCode(),
                    exception.reason().message());
            return;
        }
        request.setAttribute(IDENTITY_ATTRIBUTE, identity);
        chain.doFilter(request, response);
    }

    /** 失败响应只有稳定错误码与一句说明，不含任何凭据片段。 */
    private static void writeError(HttpServletResponse response, int status, String errorCode, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"errorCode\":\"" + errorCode + "\",\"message\":\"" + message + "\"}");
    }

    /** HTTP 设备按 Retry-After 退避；单位用秒，符合 HTTP 语义。 */
    private static void writeRetryAfter(HttpServletResponse response, Duration retryAfter) {
        response.setHeader("Retry-After", String.valueOf(Math.max(1L, retryAfter.toSeconds())));
    }

    /** 取来源地址；反向代理场景由容器按 forwarded 头还原，这里不自行解析代理头。 */
    private static String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    /** 测试用明文只接受容器报告的回环对端；不解析可由客户端伪造的转发头。 */
    private static boolean isLoopbackAddress(String remoteAddress) {
        return "127.0.0.1".equals(remoteAddress)
                || "::1".equals(remoteAddress)
                || "0:0:0:0:0:0:0:1".equals(remoteAddress);
    }
}
