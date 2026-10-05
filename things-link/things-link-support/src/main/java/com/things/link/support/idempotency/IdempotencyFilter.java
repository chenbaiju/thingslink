package com.things.link.support.idempotency;

import com.things.link.shared.error.ApiError;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.RlsScope;
import com.things.link.shared.tenant.RlsScopeContext;
import com.things.link.support.trace.TraceContext;
import com.things.link.support.web.ArtifactUploadRoute;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 写操作的 Idempotency-Key 支持。
 *
 * <p>架构文档 11.1：<b>所有写操作支持 Idempotency-Key。</b> 公共层提供原子抢占与
 * 完成墓碑，但不保存响应正文。保存并直接重放旧响应会跳过当前资源授权，也可能把登录凭据、
 * 一次性 secret 等敏感结果持久化。需要返回原业务事实的命令、场景和分享签发由各自领域事务处理。
 *
 * <h2>处理流程</h2>
 * <pre>
 * 非写方法 / 无 Idempotency-Key / 未建立可信身份范围 / 精确排除路由 → 直接放行
 *
 * 有 key：
 *   INSERT IN_PROGRESS 抢占
 *     成功  → 执行业务 → 2xx 则写完成墓碑；否则删除记录，允许重试
 *     失败  → 已存在同键记录
 *              body 不一致      → 409（客户端复用了幂等键，是 bug）
 *              status=COMPLETED → 409/10014（已完成、公共响应不可重放）
 *              status=IN_PROGRESS → 409（首次尚未完成）
 * </pre>
 *
 * <h2>为什么用 Filter 而不是 Interceptor</h2>
 * 需要在 Controller 前原子抢占且读取请求体算哈希。请求体一旦被读走，后面的
 * {@code @RequestBody} 就解析不到了 ——
 * {@link CachedBodyHttpServletRequest} 解决的正是这个问题。
 *
 * <h2>安全链位置</h2>
 * 生产环境由 Console/App 的 {@code SecurityFilterChain} 显式放在身份范围过滤器之后；
 * {@code @Order(LOWEST_PRECEDENCE)} 只保留库模块独立测试的 Servlet 注册，并保证生产中的
 * 外层重复注册晚于安全链。相同实例的 OncePerRequest 标记会阻止执行两次。
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);

    /** 幂等键请求头。名称沿用业界惯例（Stripe、GitHub 等），便于客户端复用既有实践。 */
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    /**
     * 需要幂等保护的 HTTP 方法。
     *
     * <p>GET / HEAD / OPTIONS 按定义就是幂等的，不需要额外机制。
     * DELETE 语义上也幂等，但纳入公共完成墓碑可阻止实现不完整的删除逻辑重复产生副作用。
     */
    private static final Set<String> GUARDED_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    /**
     * 自带领域幂等的写端点。
     *
     * <p>这些端点必须在当前授权之后进入领域事务，才能安全返回原命令/执行事实。公共墓碑若先截获，
     * 会把领域的稳定重试结果降成10014；新增同类端点必须同步此清单及领域幂等测试。
     */
    private static final List<DomainIdempotencyRoute> DOMAIN_IDEMPOTENCY_ROUTES = List.of(
            // Agent凭据使用项目锁和版本CAS；每次重试确权，不持久原始秘密的请求摘要。
            domainRoute("PUT", "^/api/v1/projects/[^/]+/assistant/model-configurations/deepseek-chat$"),
            domainRoute("PATCH", "^/api/v1/projects/[^/]+/assistant/model-configurations/deepseek-chat$"),
            domainRoute("DELETE", "^/api/v1/projects/[^/]+/assistant/model-configurations/deepseek-chat$"),
            // 合成探针按冻结批次/样本持久单次认领，重放必须复核当前身份且返回409。
            domainRoute("POST", "^/api/v1/projects/[^/]+/assistant/model-probes/[1-3]$"),
            // 分析调用只依赖持久领域台账，同键重放必须重新确权，不能缓存运行响应。
            domainRoute("POST", "^/api/v1/projects/[^/]+/assistant/analysis-runs$"),
            // 邀请接受由持久单次状态仲裁，不能让公共墓碑代替当前身份/码/权限复验。
            domainRoute("POST", "^/api/v1/project-invitations/[^/]+/accept$"),
            // 自部署申请按封套/部署身份在发行方库中幂等；公共墓碑不能抢先变成10014。
            domainRoute("POST", "^/api/v1/operations/self-hosted/enrollment-requests$"),
            domainRoute("POST", "^/api/v1/projects/[^/]+/devices/[^/]+/commands$"),
            domainRoute("POST", "^/api/v1/app/devices/[^/]+/commands$"),
            domainRoute("POST", "^/api/v1/projects/[^/]+/scenes/[^/]+/executions$"),
            domainRoute("POST", "^/api/v1/app/device-claims$"),
            domainRoute("POST", "^/api/v1/app/device-shares$"),
            domainRoute("POST", "^/api/v1/app/device-transfers$"),
            domainRoute("DELETE", "^/api/v1/app/devices/[^/]+/binding$"),
            domainRoute("PUT", "^/api/v1/app/push-tokens$"),
            domainRoute("DELETE", "^/api/v1/app/push-tokens/[^/]+$"),
            // OTA创建具备持久领域恢复映射，取消等其他写仍保留公共完成墓碑。
            domainRoute("POST", "^/api/v1/projects/[^/]+/ota/firmwares$"),
            // 活动仅创建使用持久恢复，排程和取消仍由公共墓碑保护同键异正文。
            domainRoute("POST", "^/api/v1/projects/[^/]+/ota/campaigns$"),
            domainRoute("POST", "^/api/v1/projects/[^/]+/applications$"),
            domainRoute("POST", "^/api/v1/projects/[^/]+/dashboards$"),
            domainRoute("POST", "^/api/v1/projects/[^/]+/dashboards/[^/]+/shares$")
    );

    /**
     * 采用POST承载有界查询体的只读端点。
     *
     * <p>依据ADR0101与S12-0b数据合同，这些请求每次都要重新鉴权读取，不得进入公共写幂等；
     * 精确列举避免把普通POST误判为读。包含尚待S12实现的合同路由，用于阻止实现时默认落入旧行为。
     */
    private static final List<Pattern> READ_ONLY_POST_PATHS = List.of(
            // 个人集合报告每次重验来源和权限，不能重放已删除或撤权的正文。
            Pattern.compile("^/api/v1/projects/[^/]+/assistant/fact-reports/collection$"),
            // 项目知识只读检索，每次重验当前授权和版本，不写公共墓碑。
            Pattern.compile("^/api/v1/projects/[^/]+/assistant/knowledge/search$"),
            Pattern.compile("^/api/open/v1/devices/current-values/query$"),
            Pattern.compile("^/api/open/v1/alarms/query$"),
            Pattern.compile("^/api/v1/projects/[^/]+/devices/current-values/query$"),
            Pattern.compile("^/api/v1/projects/[^/]+/devices/current-value-snapshots/query$"),
            Pattern.compile("^/api/v1/projects/[^/]+/devices/snapshots/query$"),
            Pattern.compile("^/api/v1/projects/[^/]+/alarms/query$"),
            Pattern.compile("^/api/v1/app/devices/snapshots/query$"),
            Pattern.compile("^/api/v1/app/devices/current-values/query$"),
            Pattern.compile("^/api/v1/app/alarms/query$"),
            Pattern.compile("^/api/v1/shares/[^/]+/devices/snapshots/query$"),
            Pattern.compile("^/api/v1/shares/[^/]+/devices/current-values/query$"),
            Pattern.compile("^/api/v1/shares/[^/]+/alarms/query$")
    );

    /**
     * 记录保留窗口。
     *
     * <p><b>必须不短于客户端的最大重试窗口</b>，否则重试时记录已过期，幂等失效。
     * 24 小时覆盖了指数退避重试与人工重试的绝大多数场景。
     */
    private static final Duration RETENTION = Duration.ofHours(24);

    /** 原子抢占、完成墓碑与存量记录查询端口。 */
    private final IdempotencyStore store;
    /** 过滤器层标准错误响应序列化器。 */
    private final ObjectMapper objectMapper;

    /** @param store 幂等持久化端口 @param objectMapper 标准错误JSON序列化器 */
    public IdempotencyFilter(IdempotencyStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String idempotencyKey = request.getHeader(IDEMPOTENCY_KEY_HEADER);
        String path = request.getRequestURI().substring(request.getContextPath().length());

        if (!shouldGuard(request, path, idempotencyKey)) {
            filterChain.doFilter(request, response);
            return;
        }
        if (idempotencyKey.length() > 128) {
            writeError(response, CommonErrorCode.INVALID_PARAMETER, "Idempotency-Key 长度不能超过128个字符");
            return;
        }

        // 必须用自建的可重放包装器，不能用 Spring 的 ContentCachingRequestWrapper ——
        // 后者不支持重放，预读之后 @RequestBody 会拿到空请求体并返回 400，
        // 而错误信息完全指不到真正的原因。详见 CachedBodyHttpServletRequest 的注释。
        CachedBodyHttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(request);
        String bodyHash = sha256Hex(cachedRequest.body());

        RlsScope scope = RlsScopeContext.current().orElseThrow();
        UUID tenantId = scope.tenantId();
        UUID projectId = scope.projectId();
        String method = request.getMethod();
        String storageKey = storageKey(request.getUserPrincipal(), path, idempotencyKey);

        // S12-0c之前的记录没有身份范围且保存的是原始key。先识别这种存量墓碑，避免升级后
        // 因新键方案查不到旧成功而重复执行业务；迁移已物理删除旧响应列，所以这里只会拒绝。
        Optional<IdempotencyRecord> legacy = store.find(tenantId, projectId, idempotencyKey, method, path)
                .or(() -> store.find(null, null, idempotencyKey, method, path));
        if (legacy.isPresent()) {
            handleExisting(response, legacy.orElseThrow(), bodyHash, path);
            return;
        }

        IdempotencyRecord candidate = new IdempotencyRecord(
                Uuid7.generate(), tenantId, projectId, storageKey,
                method, path, bodyHash, IdempotencyRecord.Status.IN_PROGRESS,
                Instant.now().plus(RETENTION));

        if (!store.tryAcquire(candidate)) {
            handleDuplicate(response, tenantId, projectId, storageKey, method, path, bodyHash);
            return;
        }

        executeAndRecord(cachedRequest, response, filterChain, candidate.id());
    }

    /**
     * 抢占成功：执行业务，并按结果写完成墓碑或释放。
     */
    private void executeAndRecord(CachedBodyHttpServletRequest cachedRequest,
                                  HttpServletResponse response,
                                  FilterChain filterChain,
                                  UUID recordId) throws ServletException, IOException {
        try {
            filterChain.doFilter(cachedRequest, response);
            if (isSuccessful(response.getStatus())) {
                try {
                    store.complete(recordId);
                } catch (RuntimeException completionFailure) {
                    // 业务可能已经提交，删除记录会放开重复执行；保留IN_PROGRESS直到过期是安全降级。
                    // 首次响应仍按业务结果返回，避免把已提交成功伪装为可安全重试的500。
                    log.error("幂等完成墓碑写入失败，保留抢占以阻止重复执行 recordId={}",
                            recordId, completionFailure);
                }
            } else {
                // 失败响应不记录：客户端修正问题后应当能用同一个 key 重试。
                // 记下来的话它会永远收到那个失败响应，而且无从摆脱。
                store.release(recordId);
            }
        } catch (Exception e) {
            // 业务抛异常同样要释放。不释放的话记录卡在 IN_PROGRESS，
            // 客户端后续重试全部收到 409，直到 24 小时后过期 —— 这是最糟的失败模式：
            // 一次偶发故障变成了一整天不可用。
            store.release(recordId);
            throw e;
        }
    }

    /**
     * 抢占失败：已存在同键记录，判断请求冲突、在途或完成墓碑。
     */
    private void handleDuplicate(HttpServletResponse response,
                                 UUID tenantId, UUID projectId, String idempotencyKey,
                                 String method, String path, String bodyHash) throws IOException {
        Optional<IdempotencyRecord> existing = store.find(tenantId, projectId, idempotencyKey, method, path);

        if (existing.isEmpty()) {
            // 抢占失败但又查不到 —— 记录在这两步之间被清理任务删掉了。极罕见。
            // 返回 409 让客户端重试，重试时会重新抢占成功。
            log.warn("幂等记录在抢占与查询之间消失 path={}", path);
            writeError(response, CommonErrorCode.IDEMPOTENCY_IN_PROGRESS);
            return;
        }

        handleExisting(response, existing.orElseThrow(), bodyHash, path);
    }

    /**
     * 按已有记录决定冲突、在途或完成墓碑，任何分支都不输出历史响应。
     *
     * @param response 当前响应 @param record 已有记录 @param bodyHash 当前正文摘要 @param path 请求路径
     */
    private void handleExisting(HttpServletResponse response, IdempotencyRecord record,
                                String bodyHash, String path) throws IOException {

        if (!record.requestBodyHash().equals(bodyHash)) {
            // 同一个 key 配不同的 body：调用方复用了幂等键，是客户端 bug。
            // 静默返回首次的响应会掩盖问题，让调用方以为第二次请求生效了。
            log.warn("幂等键被复用于不同请求体 path={}", path);
            writeError(response, CommonErrorCode.RESOURCE_STATE_CONFLICT,
                    "相同的 Idempotency-Key 不能用于内容不同的请求");
            return;
        }

        if (record.status() == IdempotencyRecord.Status.COMPLETED) {
            writeError(response, CommonErrorCode.IDEMPOTENCY_RESULT_NOT_REPLAYABLE);
            return;
        }

        // 首次请求仍在执行中。返回 409 而不是等待：阻塞会占住 Web 线程，
        // 大量并发重试时足以耗尽线程池（架构文档 8.3 的背压考虑）。
        writeError(response, CommonErrorCode.IDEMPOTENCY_IN_PROGRESS);
    }

    /** @param response 当前响应 @param errorCode 标准错误码 */
    private void writeError(HttpServletResponse response, CommonErrorCode errorCode) throws IOException {
        writeError(response, errorCode, errorCode.defaultMessage());
    }

    /**
     * 直接写出标准错误响应。
     *
     * <p>本过滤器在 {@code GlobalExceptionHandler} 的作用范围之外（异常处理器只覆盖
     * Controller 层），所以这里必须自己组装 {@link ApiError}，保证形状与其他接口一致。
     */
    private void writeError(HttpServletResponse response, CommonErrorCode errorCode, String message)
            throws IOException {
        response.setStatus(errorCode.httpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ApiError error = new ApiError(errorCode.code(), message, TraceContext.current(), java.util.List.of());
        response.getWriter().write(objectMapper.writeValueAsString(error));
    }

    /** @param status HTTP状态码 @return 是否为2xx成功 */
    private static boolean isSuccessful(int status) {
        return status >= 200 && status < 300;
    }

    /**
     * 判定公共层是否应抢占本次请求。
     *
     * <p>可信RLS范围与Principal同时存在，表示认证和项目生命周期检查已经执行；公开认证、Broker、
     * 匿名分享定位等入口不能在此持久化凭据响应。精确查询和领域自管路径继续进入Controller。
     *
     * @param request 当前请求 @param path 去除contextPath后的路径 @param key 原始请求键
     * @return 是否应用公共完成墓碑
     */
    private static boolean shouldGuard(HttpServletRequest request, String path, String key) {
        if (ArtifactUploadRoute.isContent(request)) return false;
        if ("POST".equals(request.getMethod()) && Set.of("/api/v1/auth/project-invitation/preview",
                "/api/v1/auth/project-invitation/register").contains(path)) return false;
        // ADR0107身份操作必须实际检查Cookie代次，不允许通用墓碑先返回或缓存凭据请求。
        if ("POST".equals(request.getMethod()) && Set.of("/api/v1/app/browser-auth/login",
                "/api/v1/app/browser-auth/refresh", "/api/v1/app/browser-auth/logout").contains(path)) return false;
        if (!GUARDED_METHODS.contains(request.getMethod()) || !StringUtils.hasText(key)) {
            return false;
        }
        if (RlsScopeContext.current().isEmpty() || request.getUserPrincipal() == null) {
            return false;
        }
        return !("POST".equals(request.getMethod()) && matches(READ_ONLY_POST_PATHS, path))
                && !usesDomainIdempotency(request.getMethod(), path);
    }

    /**
     * 判断路由是否在完成事实可恢复的领域事务内自行实现幂等。
     *
     * @param method 当前HTTP方法
     * @param path 去除contextPath后的请求路径
     * @return 命中精确领域幂等路由时为true
     */
    static boolean usesDomainIdempotency(String method, String path) {
        return ArtifactUploadRoute.isCreation(method, path) || DOMAIN_IDEMPOTENCY_ROUTES.stream()
                .anyMatch(route -> route.method().equals(method) && route.pathPattern().matcher(path).matches());
    }

    /** 构造方法与路径同时受限的领域幂等路由，避免未来同路径其他写方法意外绕过公共墓碑。 */
    private static DomainIdempotencyRoute domainRoute(String method, String pathPattern) {
        return new DomainIdempotencyRoute(method, Pattern.compile(pathPattern));
    }

    /** @param patterns 精确受控路由模式 @param path 当前路径 @return 是否命中 */
    private static boolean matches(List<Pattern> patterns, String path) {
        return patterns.stream().anyMatch(pattern -> pattern.matcher(path).matches());
    }

    /**
     * 一个由领域事务自行恢复完成结果的精确HTTP路由。
     *
     * @param method 唯一允许绕开公共墓碑的HTTP方法
     * @param pathPattern 唯一允许绕开公共墓碑的路径模式
     */
    private record DomainIdempotencyRoute(String method, Pattern pathPattern) {
    }

    /**
     * 为存储键加入认证主体维度，避免同项目不同账号共享客户端键空间。
     *
     * <p>数据库只保存固定前缀与摘要，不落原始key或主体ID。Console/App路径前缀同时隔离两类JWT主体；
     * path仍单独进入唯一索引，因此摘要无需重复包含完整资源路径。
     *
     * @param principal 已认证主体 @param path 当前请求路径 @param rawKey 客户端原始键
     * @return v2格式不可逆存储键
     */
    private static String storageKey(Principal principal, String path, String rawKey) {
        String actorType = path.startsWith("/api/v1/app/") ? "APP" : "CONSOLE";
        return "v2_" + sha256Hex((actorType + '\0' + principal.getName() + '\0' + rawKey)
                .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 计算请求体的 SHA-256。
     *
     * <p>用途不是完整性校验，而是识别「同一个 key 配了不同 body」这种客户端 bug。
     *
     * @param body 请求体字节
     * @return 64 位十六进制摘要
     */
    private static String sha256Hex(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 规范要求必须提供的算法，走不到这里
            throw new IllegalStateException("JDK 未提供 SHA-256", e);
        }
    }

}
