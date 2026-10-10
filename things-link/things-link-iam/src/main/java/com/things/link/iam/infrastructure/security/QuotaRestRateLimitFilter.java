package com.things.link.iam.infrastructure.security;

import com.things.link.support.web.DashboardDataRequestPaths;

import com.things.link.iam.application.RestQuotaPolicy;
import com.things.link.iam.application.RestQuotaPolicyResolver;
import com.things.link.project.application.ProjectDailyQuotaDecisionService;
import com.things.link.project.application.ProjectUsageFactRecorder;
import com.things.link.project.application.QuotaMetric;
import com.things.link.project.application.QuotaStatus;
import com.things.link.shared.error.ApiError;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.trace.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 已认证控制台 REST 请求的租户共享短窗口限流器。
 *
 * <p>本过滤器必须位于 {@link TenantScopeFilter} 之后：只有 JWT 已认证、授权完成且租户上下文已建立，
 * 才能按账号和已选项目作出正确计数。它不负责认证、EMQX 回调或 WebSocket Upgrade；这些入口各有
 * 不同的安全与过载语义，混用同一个业务桶会放大故障或制造绕过。</p>
 */
public class QuotaRestRateLimitFilter extends OncePerRequestFilter {

    private final RestQuotaAdmissionEngine engine;
    private final ObjectMapper objectMapper;
    private final com.things.link.project.application.ProjectLifecycleAccessService managementLifecycle;

    /**
     * @param policyResolver 已选项目有效套餐解析端口
     * @param redis Redis 原子计数入口
     * @param metrics 无高基数标签的限流指标
     * @param objectMapper 统一错误响应序列化器
     */
    public QuotaRestRateLimitFilter(RestQuotaPolicyResolver policyResolver, StringRedisTemplate redis,
                                    RestQuotaRateLimitMetrics metrics, ObjectMapper objectMapper,
                                    ProjectDailyQuotaDecisionService dailyQuotaDecisionService,
                                    ProjectUsageFactRecorder usageFactRecorder) {
        this(policyResolver, redis, metrics, objectMapper, dailyQuotaDecisionService,
                usageFactRecorder, true);
    }

    /**
     * 生产装配允许测试 profile 关闭跨用例事实；业务测试直接使用六参数构造器时仍保持生产默认开启。
     */
    public QuotaRestRateLimitFilter(RestQuotaPolicyResolver policyResolver, StringRedisTemplate redis,
                                    RestQuotaRateLimitMetrics metrics, ObjectMapper objectMapper,
                                    ProjectDailyQuotaDecisionService dailyQuotaDecisionService,
                                    ProjectUsageFactRecorder usageFactRecorder,
                                    boolean dailyUsageRecordingEnabled) {
        this(policyResolver, redis, metrics, objectMapper, dailyQuotaDecisionService,
                usageFactRecorder, dailyUsageRecordingEnabled, null);
    }

    /** 生产构造提供项目公开端口，历史限流单测可独立验证纯配额职责。 */
    public QuotaRestRateLimitFilter(RestQuotaPolicyResolver policyResolver, StringRedisTemplate redis,
                                    RestQuotaRateLimitMetrics metrics, ObjectMapper objectMapper,
                                    ProjectDailyQuotaDecisionService dailyQuotaDecisionService,
                                    ProjectUsageFactRecorder usageFactRecorder, boolean dailyUsageRecordingEnabled,
                                    com.things.link.project.application.ProjectLifecycleAccessService managementLifecycle) {
        this.managementLifecycle=managementLifecycle;
        this.objectMapper=objectMapper;
        this.engine=new RestQuotaAdmissionEngine(policyResolver,redis,metrics,dailyQuotaDecisionService,usageFactRecorder,dailyUsageRecordingEnabled);
    }

    /**
     * 认证、Broker、探针与 WebSocket 各自有独立安全或限流语义，不能扣入控制台业务 REST 桶。
     *
     * @param request 当前 HTTP 请求
     * @return 是否绕过本过滤器
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = applicationPath(request);
        return path.startsWith("/api/v1/auth/")
                || path.startsWith("/api/v1/emqx/")
                || "/api/v1/realtime/ws".equals(path)
                || path.startsWith("/actuator/")
                || path.equals("/v3/api-docs")
                || path.startsWith("/v3/api-docs/");
    }

    /**
     * 在已认证的租户范围内串行扣减账号秒、项目分钟、租户分钟三个桶。
     *
     * <p>Redis 不支持跨 Cluster slot 的三键事务，因此顺序扣减允许后续桶拒绝时前序桶已被保守消耗；
     * 这是保护性限流的可接受偏紧行为，不能为了精确回滚引入跨槽非原子读改写。</p>
     *
     * @param request 当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @param filterChain 剩余安全与 MVC 过滤链
     * @throws ServletException 下游 Servlet 异常
     * @throws IOException 下游 I/O 异常
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Optional<TenantScope> scope = TenantContext.current();
        if (scope.isEmpty() || scope.orElseThrow().projectId() == null) {
            filterChain.doFilter(request, response);
            return;
        }

        TenantScope tenantScope = scope.orElseThrow();
        RestQuotaRateLimitMetrics.RequestKind kind = DashboardDataRequestPaths.isConsolePostRead(request.getMethod(),
                request.getRequestURI().substring(request.getContextPath().length()))
                ? RestQuotaRateLimitMetrics.RequestKind.READ : requestKind(request.getMethod());
        // 仅冻结的规则/场景、产品凭据、信任包导入、类型基线和接收号码写入口：归档不能先被日计量的ACTIVE查询伪装为429。
        if (managementLifecycle != null && (isRuleManagementWrite(request, tenantScope.projectId())
                || isProductCredentialWrite(request, tenantScope.projectId())
                || isOtaTrustImportWrite(request, tenantScope.projectId())
                || isOtaTypeBaselineRegistrationWrite(request, tenantScope.projectId())
                || isNotificationContactWrite(request, tenantScope.projectId()))) {
            try { managementLifecycle.requireWritableAccountSnapshot(tenantScope.accountId(), tenantScope.projectId()); }
            catch (com.things.link.shared.error.BusinessException failure) {
                response.setStatus(failure.errorCode().httpStatus());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                response.getWriter().write(objectMapper.writeValueAsString(new ApiError(failure.errorCode().code(),
                        failure.errorCode().defaultMessage(), TraceContext.current(), List.of())));
                return;
            }
        }
        var decision=engine.admit(tenantScope.accountId(),tenantScope.projectId(),null,null,kind);
        if(decision.allowed()){filterChain.doFilter(request,response);return;}
        writeTooManyRequests(response,decision.retryAfterSeconds());
    }

    /** 接收号码修改仅匹配当前项目的精确PUT路径，生命周期预检不豁免正常计量。 */
    static boolean isNotificationContactWrite(HttpServletRequest request, UUID projectId) {
        if (!"PUT".equals(request.getMethod())) return false;
        String prefix = "/api/v1/projects/" + projectId + "/end-users/";
        String path = applicationPath(request);
        return path.startsWith(prefix) && path.substring(prefix.length()).matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/notification-contact");
    }

    /** 精确方法与选中项目绑定，不拦截恢复项目、GET或未知路由。 */
    static boolean isRuleManagementWrite(HttpServletRequest request, UUID projectId) {
        String prefix = "/api/v1/projects/" + projectId + "/";
        String path = applicationPath(request);
        if (!path.startsWith(prefix)) return false;
        String tail = path.substring(prefix.length());
        String id = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
        String base = "(?:message-rules|scenes)";
        if ("PUT".equals(request.getMethod()) || "DELETE".equals(request.getMethod())) return tail.matches(base + "/" + id);
        if (!"POST".equals(request.getMethod())) return false;
        return tail.matches(base) || tail.matches(base + "/" + id + "/pause")
                || tail.matches(base + "/" + id + "/versions/" + id + "/activate")
                || tail.matches("message-rules/" + id + "/versions/" + id + "/debug")
                || tail.matches("scenes/" + id + "/executions");
    }

    /** 产品凭据只认当前项目的精确POST路径，不能把类型读取、恢复或其他写操作纳入。 */
    static boolean isProductCredentialWrite(HttpServletRequest request, UUID projectId) {
        if (!"POST".equals(request.getMethod())) return false;
        String prefix = "/api/v1/projects/" + projectId + "/device-types/";
        String path = applicationPath(request);
        if (!path.startsWith(prefix)) return false;
        return path.substring(prefix.length()).matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/product-credential");
    }

    /** 信任包导入只认选中项目、合法域及精确POST路径，其他OTA入口仍沿原配额链处理。 */
    static boolean isOtaTrustImportWrite(HttpServletRequest request, UUID projectId) {
        if (!"POST".equals(request.getMethod())) return false;
        String prefix = "/api/v1/projects/" + projectId + "/ota/trust-domains/";
        String path = applicationPath(request);
        if (!path.startsWith(prefix)) return false;
        return path.substring(prefix.length()).matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}/bundles");
    }

    /** 类型基线登记只认选中项目及精确类型UUID的POST路径，管理读取与版本历史保持原配额语义。 */
    static boolean isOtaTypeBaselineRegistrationWrite(HttpServletRequest request, UUID projectId) {
        if (!"POST".equals(request.getMethod())) return false;
        String prefix = "/api/v1/projects/" + projectId + "/ota/device-types/";
        String path = applicationPath(request);
        if (!path.startsWith(prefix)) return false;
        return path.substring(prefix.length()).matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/baseline/registrations");
    }

    /**
     * @param method HTTP 方法
     * @return GET/HEAD 归为读，其余均视为可能产生副作用的写
     */
    private static RestQuotaRateLimitMetrics.RequestKind requestKind(String method) {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
                ? RestQuotaRateLimitMetrics.RequestKind.READ : RestQuotaRateLimitMetrics.RequestKind.WRITE;
    }

    /**
     * @param request 当前请求
     * @return 已去除应用 context path 的路径，防止部署在非根路径时豁免匹配漂移
     */
    private static String applicationPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        return contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)
                ? uri.substring(contextPath.length()) : uri;
    }

    /**
     * @param response 当前 HTTP 响应
     * @param retryAfterSeconds 建议客户端等待的整数秒数
     * @throws IOException 写出响应失败
     */
    private void writeTooManyRequests(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        response.setStatus(CommonErrorCode.TOO_MANY_REQUESTS.httpStatus());
        response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(new ApiError(
                CommonErrorCode.TOO_MANY_REQUESTS.code(), CommonErrorCode.TOO_MANY_REQUESTS.defaultMessage(),
                TraceContext.current(), List.of())));
    }

}
