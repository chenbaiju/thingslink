package com.things.link.iam.infrastructure.security;

import com.things.link.support.web.DashboardDataNoStoreFilter;
import com.things.link.support.web.DashboardDataRequestPaths;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;

import com.things.link.iam.application.RestQuotaPolicyResolver;
import com.things.link.iam.application.SessionProperties;
import com.things.link.iam.domain.IamErrorCode;
import com.things.link.project.application.ProjectDailyQuotaDecisionService;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectUsageFactRecorder;
import com.things.link.shared.error.ApiError;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.support.idempotency.IdempotencyFilter;
import com.things.link.support.observability.DataPlaneMetrics;
import com.things.link.support.trace.TraceContext;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * HTTP 安全配置。
 *
 * <h2>公开与受保护的边界</h2>
 * 默认<b>全部接口需要认证</b>，只显式放行少数入口。反过来做（默认放行、逐个保护）
 * 的问题是：新加的接口默认是公开的，漏配一个就是未授权访问，而且不会有任何症状。
 *
 * <h2>为什么关闭 CSRF 与会话</h2>
 * 业务接口认证靠 Authorization 头里的 Bearer 令牌而非 Cookie，Bearer 需要
 * JavaScript 主动附加，因此不受 CSRF 影响。
 *
 * <p><b>S1 切片 4 引入了 HttpOnly Cookie 存放刷新令牌，这里要修正之前的判断。</b>
 * 原注释写的是「那时刷新接口必须重新启用 CSRF」——方向对，但结论下得太粗。
 * 实际权衡见 {@code RefreshTokenCookie} 的类注释，要点：
 * <ul>
 *   <li>攻击者读不到跨源响应，因此 CSRF 打 {@code /refresh} 拿不到访问令牌，
 *       最坏后果是<b>强制登出</b>，不是账号接管</li>
 *   <li>{@code SameSite=Strict} 已经让跨站请求带不上这个 Cookie</li>
 *   <li>因此当前不加 CSRF 令牌。<b>但一旦控制台与 API 拆成跨站部署</b>，
 *       SameSite 必须放宽为 {@code None}，那时 CSRF 令牌不再是可选项（ADR 0010）</li>
 * </ul>
 */
@Configuration
@SecurityScheme(name = "consoleAccessBearer", type = SecuritySchemeType.HTTP, scheme = "bearer",
        bearerFormat = "JWT", description = "Console账号访问令牌；不能替代App用户令牌")
public class SecurityConfiguration {

    /**
     * 无需认证即可访问的路径。
     *
     * <p>每一条都要能说清为什么必须公开：
     * <ul>
     *   <li>{@code /api/v1/auth/login} —— 登录本身不可能要求先登录。
     *       <b>只放行这一个路径，不放行 /api/v1/auth/**</b>：整个前缀放行的话，
     *       同前缀下的 /me 就不受路径规则保护，只能靠「没有令牌时注入的 Jwt 为
     *       null 从而空指针」来挡 —— 那会返回 500 而不是 401，而且下一个加进来的
     *       认证接口很容易被忘记单独保护</li>
     *   <li>{@code /actuator/health} 与 {@code /actuator/prometheus} —— 负载均衡、容器编排
     *       和指标采集器不持有控制台凭据。注意只放行这两个端点，不放行整个 actuator：
     *       {@code /actuator/env} 会暴露全部配置，包括数据库地址</li>
     *   <li>{@code /v3/api-docs/**} —— 开发期需要。生产由
     *       {@code springdoc.api-docs.enabled=false} 整体关闭（G3-SEC-1 prod 默认值与启动守卫），
     *       而不是靠这里放行与否</li>
     * </ul>
     */
    private static final String[] PUBLIC_PATHS = {
            "/api/v1/auth/login",
            // 注册本身不可能要求先登录。它是未认证的**写**入口，
            // 因此按 IP 单独限流（ADR 0008 的后果一节点名了这一项）
            "/api/v1/auth/register",
            "/api/v1/auth/project-invitation/preview",
            "/api/v1/auth/project-invitation/register",
            // 刷新与退出<b>不需要 Bearer 令牌</b>：它们的凭据是 HttpOnly Cookie 里的
            // 刷新令牌，而访问令牌此时多半已经过期 —— 要求它就等于「必须先有效才能续期」，
            // 刷新接口存在的意义正好是访问令牌失效之后。
            // 两者各自在 RefreshTokenService 里校验刷新令牌，不是无认证接口
            "/api/v1/auth/refresh",
            "/api/v1/auth/logout",
            // 邮箱验证与重发。都必须免登录：用户点开邮件链接时通常并未登录，
            // 而「重发」正是给那些还进不来的人用的。
            //
            // 两者各自有自己的防线：verify 的凭据是一次性令牌（无效一律 20021，
            // 不区分四种失败原因）；resend 无论邮箱存不存在都返回 204，
            // 并按邮箱与 IP 双维度限流 —— 它每次调用都会真的发一封信
            "/api/v1/auth/email/verify",
            "/api/v1/auth/email/resend",
            // 找回与重置密码。免登录是这条流程的前提 —— 用户正是因为进不来才走它。
            //
            // forgot 无论邮箱存不存在都返回 204，并按邮箱与 IP 双维度限流；
            // reset 的凭据是一次性重置令牌，且成功后会撤销该账号的全部会话
            "/api/v1/auth/password/forgot",
            "/api/v1/auth/password/reset",
            // 浏览器原生 WebSocket 不能设置 Authorization 头；该精确路径在 WebSocket
            // 握手拦截器中从 Sec-WebSocket-Protocol 校验 JWT。不能放行 /api/v1/realtime/**，
            // 否则以后误加的 HTTP 端点会绕过 resource server（S4-4）。
            "/api/v1/realtime/ws",
            "/actuator/health",
            "/actuator/health/**",
            // Prometheus 抓取不携带控制台 JWT；端点仅输出指标，不开放 env/configprops 等配置面。
            "/actuator/prometheus",
            "/v3/api-docs/**",
            // EMQX HTTP 回调。调用方是 EMQX 集群而非浏览器，因此不走 Bearer JWT —— 它们
            // 没有控制台账号。但「不走 JWT」不等于「不鉴权」：
            // S3.5-1 起由 BrokerCallbackAuthenticationFilter 校验共享密钥，
            // 在这条链的更前面就把无凭据的请求挡掉（架构文档 8.3）。
            //
            // 在此之前它们是真正的无鉴权入口：知道 projectKey/deviceKey 就能伪造任意设备
            // 上行、把设备打成离线，/auth 还能当成绕开 MQTT 限速的凭据暴破入口。
            "/api/v1/emqx/auth",
            "/api/v1/emqx/acl",
            "/api/v1/emqx/events/connected",
            "/api/v1/emqx/events/disconnected",
            "/api/v1/emqx/events/message-published",
            "/api/v1/emqx/events/command-reply",
            // ⚠️ 与上面六条不同：/register 的调用方是**设备**，不是 Broker（ADR 0003
            // 一型一密动态注册）。因此它<b>不能</b>要求共享密钥 —— 那等于把密钥预置进
            // 每一台出厂设备，它也就不再是秘密。它的防线是产品密钥本身加来源限流。
            // 路径在 /emqx/ 下是历史命名，已经是设备侧契约，不动它
            "/api/v1/emqx/register"
    };

    /** 统一错误响应 JSON 序列化器。 */
    private final ObjectMapper objectMapper;
    /** 控制台令牌校验器。enduser 引入 App 解码器后同类型 bean 变为两个，必须显式点名。 */
    private final JwtDecoder jwtDecoder;
    /** 刷新 Cookie 与跨源白名单配置。 */
    private final SessionProperties sessionProperties;
    /** Broker 回调共享密钥配置。 */
    private final BrokerCallbackProperties brokerCallbackProperties;
    /** EMQX 回调耗时与失败率指标门面。 */
    private final DataPlaneMetrics dataPlaneMetrics;
    /** REST 短窗口限流所需的项目归属套餐解析端口。 */
    private final RestQuotaPolicyResolver restQuotaPolicyResolver;
    /** REST 短窗口 Redis 原子令牌桶入口。 */
    private final StringRedisTemplate redis;
    /** REST 配额限流的低基数指标门面。 */
    private final RestQuotaRateLimitMetrics restQuotaRateLimitMetrics;
    /** PostgreSQL 权威 UTC 日额度判定服务。 */
    private final ProjectDailyQuotaDecisionService dailyQuotaDecisionService;
    /** 已认证 REST 只追加 用量事实端口。 */
    private final ProjectUsageFactRecorder usageFactRecorder;
    /** 控制台JWT建立租户范围前的项目代次权威核验端口。 */
    private final ProjectLifecycleAccessService projectLifecycleAccessService;
    /** 认证与可信范围建立后执行的公共幂等完成墓碑。 */
    private final IdempotencyFilter idempotencyFilter;
    /** 是否记录 REST 日用量；仅 IAM 隔离测试关闭，生产默认开启。 */
    private final boolean dailyUsageRecordingEnabled;

    /**
     * @param objectMapper 统一错误响应 JSON 序列化器
     * @param sessionProperties 会话安全配置
     * @param brokerCallbackProperties Broker 回调共享密钥配置
     * @param dataPlaneMetrics EMQX 回调指标门面
     * @param restQuotaPolicyResolver 项目归属套餐解析端口
     * @param redis REST 短窗口 Redis 原子令牌桶入口
     * @param restQuotaRateLimitMetrics REST 配额限流低基数指标门面
     */
    public SecurityConfiguration(ObjectMapper objectMapper,
                                 @Qualifier("jwtDecoder") JwtDecoder jwtDecoder,
                                 SessionProperties sessionProperties,
                                 BrokerCallbackProperties brokerCallbackProperties,
                                 DataPlaneMetrics dataPlaneMetrics,
                                 RestQuotaPolicyResolver restQuotaPolicyResolver,
                                 StringRedisTemplate redis,
                                 RestQuotaRateLimitMetrics restQuotaRateLimitMetrics,
                                 ProjectDailyQuotaDecisionService dailyQuotaDecisionService,
                                 ProjectUsageFactRecorder usageFactRecorder,
                                 ProjectLifecycleAccessService projectLifecycleAccessService,
                                 IdempotencyFilter idempotencyFilter,
                                 @Value("${things-link.quota.daily-usage-recording-enabled:true}")
                                 boolean dailyUsageRecordingEnabled) {
        this.objectMapper = objectMapper;
        this.jwtDecoder = jwtDecoder;
        this.sessionProperties = sessionProperties;
        this.brokerCallbackProperties = brokerCallbackProperties;
        this.dataPlaneMetrics = dataPlaneMetrics;
        this.restQuotaPolicyResolver = restQuotaPolicyResolver;
        this.redis = redis;
        this.restQuotaRateLimitMetrics = restQuotaRateLimitMetrics;
        this.dailyQuotaDecisionService = dailyQuotaDecisionService;
        this.usageFactRecorder = usageFactRecorder;
        this.projectLifecycleAccessService = projectLifecycleAccessService;
        this.idempotencyFilter = idempotencyFilter;
        this.dailyUsageRecordingEnabled = dailyUsageRecordingEnabled;
    }

    /**
     * 主过滤链。
     *
     * @param http 配置入口
     * @return 过滤链
     * @throws Exception 配置失败
     */
    @Bean
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                // 关闭 CSRF 的依据见类注释：Bearer 不受影响，刷新 Cookie 由
                // SameSite=Strict 兜住，且 CSRF 在此处最坏只能造成强制登出。
                // 改成跨站部署时这一行必须重新评估
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // 无状态：不创建也不使用 HttpSession。有会话就有会话固定、
                // 集群共享、粘性路由一系列问题，而令牌方案本就不需要它
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // ADR0099：仅两个GET握手改由独立子协议认证，不放行其他方法或/ws/**。
                        .requestMatchers(HttpMethod.GET, "/ws/app/properties", "/ws/app/dashboard", "/ws/dashboard/properties").permitAll()
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        // 兜底：未显式放行的一律要求认证
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(jwtDecoder))
                        // 数据运行合同§3.6要求无效Bearer也返回ApiError；旧入口仍保留原处理器。
                        // 仅配置exceptionHandling不能接管Bearer过滤器自身的验签失败。
                        .authenticationEntryPoint((request, response, failure) -> {
                            String path = request.getRequestURI().substring(request.getContextPath().length());
                            if (DashboardDataRequestPaths.isConsolePostRead(request.getMethod(), path)) {
                                writeError(response, IamErrorCode.INVALID_TOKEN);
                            } else {
                                new BearerTokenAuthenticationEntryPoint().commence(request, response, failure);
                            }
                        }))
                // Broker 回调的共享密钥校验。挂在 AuthorizationFilter 之前：
                // 那五个路径在授权阶段是 permitAll，等授权阶段之后再校验就晚了 ——
                // 请求已经被判定为「允许」，再拦一次只是补丁。这里让它在授权决策
                // 之前就把无凭据的请求终止掉，permitAll 的含义才准确：
                // 「不需要控制台账号」，而不是「谁都能调」
                .addFilterBefore(new DashboardDataNoStoreFilter(), BearerTokenAuthenticationFilter.class)
                .addFilterBefore(new ProjectInvitationNoStoreFilter(), BearerTokenAuthenticationFilter.class)
                .addFilterBefore(
                        new BrokerCallbackAuthenticationFilter(
                                brokerCallbackProperties, objectMapper, dataPlaneMetrics),
                        AuthorizationFilter.class)
                // 挂在链尾：认证与授权都已完成，此时才能从安全上下文里取出租户。
                // 挂错位置的症状是「租户上下文有时有、有时没有」，极难排查
                .addFilterAfter(new TenantScopeFilter(projectLifecycleAccessService, objectMapper),
                        AuthorizationFilter.class)
                // S12-0c：公共幂等必须显式处于认证、授权与可信范围之后，不能靠Servlet
                // @Order猜Security代理的位置；已完成只返回无正文墓碑，不跳过当前安全链。
                .addFilterAfter(idempotencyFilter, TenantScopeFilter.class)
                // REST 配额位于幂等之后：新执行才扣桶；公共重复请求由墓碑直接拒绝，
                // 自带领域幂等及精确POST查询会继续进入配额与Controller完成当前授权。
                .addFilterAfter(new QuotaRestRateLimitFilter(restQuotaPolicyResolver, redis,
                        restQuotaRateLimitMetrics, objectMapper, dailyQuotaDecisionService,
                        usageFactRecorder, dailyUsageRecordingEnabled, projectLifecycleAccessService), IdempotencyFilter.class)
                // Spring Security 的默认错误响应是空 body + WWW-Authenticate 头，
                // 与架构文档 11.1 的统一错误结构不一致。客户端不该为「认证失败」
                // 单独写一套解析逻辑，所以这里改成同样的 ApiError 形状
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) ->
                                writeError(response, IamErrorCode.INVALID_TOKEN))
                        .accessDeniedHandler((request, response, deniedException) ->
                                writeError(response, CommonErrorCode.RESOURCE_NOT_FOUND)))
                .build();
    }

    /**
     * 跨源配置。
     *
     * <p><b>默认不允许任何跨源请求</b>（{@code allowedOrigins} 为空）。开发走 Vite 代理
     * 是同源的，生产按 ADR 0010 要求控制台与 API 同站，两种情况都不需要 CORS。
     *
     * <p>真要开时必须写<b>精确来源</b>（含协议与端口）。这里刻意用
     * {@code setAllowedOrigins} 而非 {@code setAllowedOriginPatterns}：后者支持通配，
     * 而通配 + {@code allowCredentials} 是这类配置最经典的漏洞——一个
     * {@code https://*.example.com} 就把所有子域（包括用户可控的那些）都变成了
     * 可携带凭据的来源。
     *
     * <p>顺带一提，浏览器本身禁止 {@code Access-Control-Allow-Origin: *} 与
     * {@code allowCredentials: true} 并存，但 patterns 形式会绕过这个保护 ——
     * Spring 会回显具体来源，看起来完全合法。
     *
     * @return CORS 配置源
     */
    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(sessionProperties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        // 浏览器要携带 Cookie 就必须开这一项，且它与上面的「精确来源」是成对的：
        // 架构文档 11.2 特意点名不能一边改一边猜
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        if (!sessionProperties.allowedOrigins().isEmpty()) {
            source.registerCorsConfiguration("/api/**", configuration);
        }
        return source;
    }

    /**
     * 以统一结构写出错误响应。
     *
     * <p>这里在 {@code GlobalExceptionHandler} 的作用范围之外（异常处理器只覆盖
     * Controller 层），所以必须自己组装 {@link ApiError}。
     *
     * <p>权限不足返回 {@code RESOURCE_NOT_FOUND}（404）而非 403 是刻意的：
     * 403 等于告诉调用方「这个资源存在，只是你没权限」，本身就是一种信息泄露。
     * 对无权访问者，资源应当表现为不存在。
     */
    private void writeError(HttpServletResponse response,
                            com.things.link.shared.error.ErrorCode errorCode) throws IOException {
        response.setStatus(errorCode.httpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ApiError error = new ApiError(
                errorCode.code(), errorCode.defaultMessage(), TraceContext.current(), List.of());
        response.getWriter().write(objectMapper.writeValueAsString(error));
    }

}
