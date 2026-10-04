package com.things.link.iam.infrastructure.security;

import com.things.link.iam.domain.IamErrorCode;
import com.things.link.shared.error.ApiError;
import com.things.link.support.trace.TraceContext;
import com.things.link.support.observability.DataPlaneMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * 校验 Broker → 应用回调请求的共享密钥（架构文档 8.3，架构缺口 G-01，切片 S3.5-1）。
 *
 * <h2>为什么必须有这一层</h2>
 * 架构文档 5.0.1 承诺「三个归属 ID 必须由<b>已认证的 Topic</b> 派生」。这句话成立的前提是
 * 应用能确认请求确实来自 EMQX —— 而回调请求体里的 {@code username} 与 {@code topic} 都是
 * 请求方自己填的，应用校验它们<b>彼此一致</b>，并不能证明发请求的是 Broker。
 *
 * <p>在此之前这些端点是 {@code permitAll} 的，后果具体而严重：
 * <ul>
 *   <li>知道 {@code projectKey/deviceKey}（它们出现在 Topic 里，本就不是秘密）即可
 *       <b>伪造任意设备的属性上报</b>，污染时序库与设备影子；</li>
 *   <li>打 {@code /events/disconnected} 可以把任意设备<b>强制置为离线</b>；</li>
 *   <li>{@code /auth} 更严重：它退化成一个<b>绕开 MQTT 连接层全部限速的离线凭据校验
 *       oracle</b>，可以用来暴破设备密钥，而 Broker 侧不会留下任何痕迹。</li>
 * </ul>
 *
 * <h2>为什么用共享密钥而不是 mTLS</h2>
 * mTLS 更强，但它把「证书签发与轮换」变成部署的前置条件，本地开发栈也要跟着做一套 CA。
 * 当前形态下 Broker 与应用在同一信任域内，共享密钥 + 网络层隔离（架构文档 8.3 要求
 * <b>另外</b>做端口分离或 IP 白名单）已经能挡住这一类攻击。真要上 mTLS 时，
 * 换掉的只是本类。
 *
 * <h2>为什么比对要用常量时间</h2>
 * {@link String#equals} 在第一个不同的字节处就返回，比对耗时因此与「猜对了多少个前缀
 * 字节」相关。攻击者可以据此逐字节地把密钥试出来，而不需要 {@code 2^n} 次尝试。
 * {@link MessageDigest#isEqual} 是 JDK 里现成的常量时间比较。
 *
 * <h2>为什么不放行 {@code /api/v1/emqx/register}</h2>
 * 它的路径前缀虽然也是 {@code /api/v1/emqx/}，但<b>调用方是设备而不是 Broker</b>
 * ——一型一密动态注册（ADR 0003）就是设备用产品密钥换取自己的 deviceKey。给它加共享密钥
 * 等于要求每台出厂设备都预置这个密钥，那样它就不再是秘密了。它的防线是产品密钥本身
 * 加来源限流，见 {@code DeviceRegistrationController}。
 *
 * <p>这个路径命名是有误导性的（在 {@code /emqx/} 下却不是 EMQX 回调），但它已经是
 * 设备侧契约，改路径的代价见架构文档 5.3 开头那段：固件一旦烧录就改不动。
 */
public class BrokerCallbackAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(BrokerCallbackAuthenticationFilter.class);

    /**
     * 共享密钥的最小长度。
     *
     * <p>与 JWT 密钥同样取 32 字节：它同样是一个「猜中即完全绕过」的凭据，
     * 而且它<b>不会过期、不会轮换</b>，比访问令牌更需要长度。
     */
    private static final int MIN_SECRET_BYTES = 32;

    /**
     * 携带共享密钥的请求头。
     *
     * <h2>为什么不用 {@code Authorization: Bearer <secret>}</h2>
     * 试过，会撞车，而且症状具有误导性。本服务同时是 OAuth2 资源服务器，
     * Spring Security 的 {@code BearerTokenAuthenticationFilter} 会拦下<b>任何</b>
     * 带 {@code Authorization: Bearer} 的请求并尝试按 JWT 解析 —— 共享密钥不是 JWT，
     * 解析失败，于是请求在到达本过滤器之前就被 401 掉了。
     *
     * <p>看到的现象是：<b>带对了密钥反而 401，且错误码是 20020「登录已失效」</b>。
     * 排查方向会被引到「密钥配错了」上，但密钥其实是对的。
     *
     * <p>可以靠调整过滤器顺序绕开，但那是在两个都想解析同一个头的组件之间玩顺序，
     * 以后任何一次 Spring Security 升级都可能重新打破它。用一个专属头则从根上没有
     * 这个问题：Broker 凭据与控制台会话是两套独立的东西，本来就不该共用一个头。
     */
    public static final String CREDENTIAL_HEADER = "X-Broker-Callback-Token";

    /**
     * 需要 Broker 凭据的回调路径。
     *
     * <p><b>用精确匹配而不是 {@code /api/v1/emqx/} 前缀匹配</b>，原因有二：
     * 一是前缀匹配会连带保护到 {@code /register}，而它必须对设备开放（见类注释）；
     * 二是新增回调时，这里不写一行就不会被保护 —— 让「漏保护」需要一次遗漏，
     * 而不是让「误保护设备入口」只需要一次疏忽。两种失效都要有，选那个能被测试
     * 立刻发现的：漏写会让新回调的鉴权测试直接红。
     */
    private static final Set<String> PROTECTED_PATHS = Set.of(
            "/api/v1/emqx/auth",
            "/api/v1/emqx/acl",
            "/api/v1/emqx/events/connected",
            "/api/v1/emqx/events/disconnected",
            "/api/v1/emqx/events/message-published",
            "/api/v1/emqx/events/command-reply"
    );

    /** 供独立接入进程的授权白名单复用同一精确回调路径，避免新增端点只改一处。 */
    public static Set<String> protectedPaths() {
        return PROTECTED_PATHS;
    }

    /** 共享密钥的 UTF-8 字节。 */
    private final byte[] expectedSecret;
    /** 统一错误响应的序列化器。 */
    private final ObjectMapper objectMapper;
    /** 回调耗时与失败率指标门面。 */
    private final DataPlaneMetrics metrics;

    /**
     * @param properties   回调鉴权配置
     * @param objectMapper 统一错误响应序列化器
     * @param metrics 回调耗时与失败率指标门面
     * @throws IllegalStateException 密钥缺失或过短 —— <b>启动即失败</b>，
     *                               而不是带着一个弱密钥若无其事地跑起来
     */
    public BrokerCallbackAuthenticationFilter(
            BrokerCallbackProperties properties,
            ObjectMapper objectMapper,
            DataPlaneMetrics metrics) {
        String secret = properties == null ? null : properties.secret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "缺少 things-link.security.broker-callback.secret。EMQX 回调端点没有这个密钥就是"
                            + "无鉴权入口，任何人都能伪造设备上行或把设备打成离线（架构文档 8.3）。");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "things-link.security.broker-callback.secret 至少需要 %d 字节（当前 %d）。"
                            .formatted(MIN_SECRET_BYTES, bytes.length)
                            + "它不会过期也不会轮换，一旦被猜中就等于回调端点重新变回无鉴权。");
        }
        this.expectedSecret = bytes;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    /**
     * 不在保护清单里的请求直接跳过本过滤器。
     *
     * <h2>路径必须用与 Spring MVC 相同的方式归一化</h2>
     * 这一行有两个已经踩过或能预见的坑，且<b>两个的失效方向都是「静默放行」</b>——
     * 过滤器看起来在，实际一个请求都没拦：
     *
     * <ul>
     *   <li><b>{@code getServletPath()} 不可靠。</b>第一版用的就是它，结果 MockMvc 下
     *       它返回空串，全部回调直接跳过本过滤器 —— 12 条用例里 11 条「通过」，
     *       因为带了密钥的请求本来就该放行，而放行和跳过看起来一模一样。
     *       唯一露馅的是那条断言「不带密钥必须 401」的用例。</li>
     *   <li><b>矩阵参数。</b>{@code /api/v1/emqx/auth;x=1} 在 {@code getRequestURI()}
     *       里原样保留，精确匹配落空；但 Spring MVC 的 {@code PathPattern} 会逐段剥掉
     *       {@code ;} 之后的内容，照样把它路由到 Controller —— 过滤器放行、业务照跑。
     *       <p>当前它其实先被 Spring Security 的 {@code StrictHttpFirewall} 拒成 400，
     *       走不到这里。但那一层是<b>可配置的</b>：任何一次「某客户端要用矩阵参数，
     *       把 allowSemicolon 打开」的改动都会让它消失，而那次改动的人不会想到
     *       自己同时打开了一条鉴权绕过。两道防线各自独立成立才是安全的。</li>
     * </ul>
     *
     * <p>{@link UrlPathHelper#defaultInstance} 做的正是 MVC 那一套归一化
     * （去 context path、去矩阵参数、URL 解码），因此「过滤器看到的路径」与
     * 「MVC 路由用的路径」始终是同一个。自己拼 substring 也能做，但每加一个
     * 归一化规则就要跟一次 MVC 的行为，迟早会漏。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !PROTECTED_PATHS.contains(UrlPathHelper.defaultInstance.getPathWithinApplication(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String endpoint = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        long startedAt = System.nanoTime();
        try {
            if (!hasValidCredential(request)) {
                // 记 WARN 而不是 INFO：正常运行时这条日志<b>一条都不该有</b>。
                // 出现即意味着两件事之一 —— 密钥配错（运维事故）或有人在探测（安全事件），
                // 两者都需要被看见。不打印任何请求体，回调体里带着设备密钥
                logRejection(request);
                writeUnauthorized(response);
                return;
            }
            filterChain.doFilter(request, response);
        } finally {
            metrics.recordBrokerCallback(endpoint, response.getStatus(),
                    Duration.ofNanos(System.nanoTime() - startedAt));
        }
    }

    /**
     * 校验 {@link #CREDENTIAL_HEADER} 头里的共享密钥。
     *
     * @param request 当前请求
     * @return 凭据是否正确
     */
    private boolean hasValidCredential(HttpServletRequest request) {
        String header = request.getHeader(CREDENTIAL_HEADER);
        if (header == null) {
            return false;
        }
        byte[] presented = header.getBytes(StandardCharsets.UTF_8);
        // MessageDigest.isEqual 会检查完第一个参数的全部字节才返回，不在首个不同的
        // 字节处提前退出，因此耗时不泄露「猜对了多少前缀」。不要图省事换成 equals
        return MessageDigest.isEqual(presented, expectedSecret);
    }

    /**
     * 记录一次被拒绝的回调。
     *
     * @param request 当前请求
     */
    private void logRejection(HttpServletRequest request) {
        log.warn("拒绝未通过鉴权的 Broker 回调：path={} remoteAddr={} traceId={}",
                UrlPathHelper.defaultInstance.getPathWithinApplication(request), request.getRemoteAddr(), TraceContext.current());
    }

    /**
     * 写出统一结构的 401。
     *
     * <p>本过滤器在 {@code GlobalExceptionHandler} 的作用范围之外，必须自己组装
     * {@link ApiError} —— 否则 EMQX 侧拿到的会是一个与全平台不一致的错误体。
     *
     * @param response 当前响应
     * @throws IOException 写出失败
     */
    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        IamErrorCode errorCode = IamErrorCode.INVALID_CALLBACK_CREDENTIAL;
        response.setStatus(errorCode.httpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ApiError error = new ApiError(
                errorCode.code(), errorCode.defaultMessage(), TraceContext.current(), List.of());
        response.getWriter().write(objectMapper.writeValueAsString(error));
    }

}
