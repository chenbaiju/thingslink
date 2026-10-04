package com.things.link.enduser.application;

import com.things.link.enduser.domain.AppUser;
import com.things.link.enduser.domain.AppBrowserSessionReplacementRepository;
import com.things.link.enduser.domain.AppRefreshTokenRepository;
import java.time.Instant;
import com.things.link.enduser.domain.AppUserRepository;
import com.things.link.enduser.domain.AppUserRole;
import com.things.link.enduser.domain.AppUserRoleRepository;
import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.project.application.ProjectService;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService.DeviceAccessScope;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * 终端用户（App）登录用例（ADR 0036）。
 *
 * <h2>信任边界</h2>
 * <ul>
 *   <li>以全局唯一 {@code projectKey} + 租户内用户名 + 密码定位用户，<b>绝不接受客户端提交
 *       tenantId</b> —— 租户由 projectKey 从 RLS 豁免的 {@code sys_project} 解析。</li>
 *   <li>在<b>同一个认证事务</b>内以 transaction-local 上下文查受 RLS 保护的 {@code app_user}
 *       与 {@code app_user_role}，确保「解析出的租户」就是「后续读写的租户」。</li>
 *   <li>复验目标项目存在<b>有效</b> {@code app_user_role}（status=ACTIVE），否则拒签 ——
 *       不产生「认证成功但每个请求必 403」的废令牌。</li>
 * </ul>
 *
 * <h2>反枚举</h2>
 * 所有失败统一 60006，且用户不存在时仍执行一次哑口令比对，抹平「用户不存在」与「口令
 * 错误」的时序差。配合 {@link AppAuthRateLimiter} 的低基数限流（projectKey:username + IP）。
 */
@Service
public class AppAuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(AppAuthenticationService.class);

    /**
     * 用户不存在时用于抹平时序的哑哈希。
     *
     * <p>必须是<b>合法</b>的 DelegatingPasswordEncoder 格式，否则 {@code matches} 会在
     * 「用户不存在」分支直接抛异常，反枚举就漏了。它哈希什么无所谓 —— 只用于让不存在的
     * 用户名也走一次与真实比对等价的 bcrypt 计算。
     */
    private static final String DUMMY_PASSWORD_HASH =
            "{bcrypt}$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG";

    private final AppAuthRateLimiter rateLimiter;
    private final ProjectService projectService;
    private final AppUserRepository appUserRepository;
    private final AppUserRoleRepository appUserRoleRepository;
    private final AppSessionService sessionService;
    private final PasswordEncoder passwordEncoder;
    /** S12-2a1e 以 projectKey 权威解析结果建立完整事务局部 RLS 范围。 */
    private final TransactionLocalRlsScope transactionLocalRlsScope;
    /** ADR0097：项目许可先于用户锁和验密，避免旧密码快照跨越并发改密后签发。 */
    private final ProjectLifecycleAccessService lifecycleAccessService;

    /** 浏览器替换专用排序锁桥，不影响旧JSON登录的单用户路径。 */
    private final AppBrowserSessionReplacementRepository browserReplacement;
    /** 仅在桥锁后读取旧族并在原事务撤销，避免并发后继漏撤。 */
    private final AppRefreshTokenRepository refreshTokens;

    /**
     * 组装原登录事务；ADR0097要求生命周期许可和用户会话锁贯穿验密至签发。
     * @param rateLimiter 登录前低基数限流
     * @param projectService projectKey可信归属解析
     * @param appUserRepository 锁后用户及口令事实
     * @param appUserRoleRepository 当前项目角色复验
     * @param sessionService 同事务会话签发
     * @param passwordEncoder 原口令验证策略
     * @param transactionLocalRlsScope 事务局部RLS完整范围组件
     * @param lifecycleAccessService 先于用户会话锁的项目许可
     * @param browserReplacement 浏览器跨用户原事务排序锁桥
     * @param refreshTokens 桥锁后的旧族读取及原子撤销
     */
    public AppAuthenticationService(AppAuthRateLimiter rateLimiter,
                                    ProjectService projectService,
                                    AppUserRepository appUserRepository,
                                    AppUserRoleRepository appUserRoleRepository,
                                    AppSessionService sessionService,
                                    PasswordEncoder passwordEncoder,
                                    TransactionLocalRlsScope transactionLocalRlsScope,
                                    ProjectLifecycleAccessService lifecycleAccessService,
                                    AppBrowserSessionReplacementRepository browserReplacement,
                                    AppRefreshTokenRepository refreshTokens) {
        this.rateLimiter = rateLimiter;
        this.projectService = projectService;
        this.appUserRepository = appUserRepository;
        this.appUserRoleRepository = appUserRoleRepository;
        this.sessionService = sessionService;
        this.passwordEncoder = passwordEncoder;
        this.transactionLocalRlsScope = transactionLocalRlsScope;
        this.lifecycleAccessService = lifecycleAccessService;
        this.browserReplacement = browserReplacement;
        this.refreshTokens = refreshTokens;
    }

    /**
     * 用 projectKey + 用户名 + 密码登录，签发 App 令牌对。
     *
     * @param projectKey 项目 MQTT 标识。全局唯一，是租户解析的唯一输入
     * @param username   租户内用户名
     * @param password   明文口令
     * @param clientIp   来源 IP，可为 {@code null}，仅用于限流维度
     * @return 令牌对
     * @throws BusinessException 限流（429）或登录失败（60006）
     */
    @Transactional
    public AppIssuedSession login(String projectKey, String username, String password, String clientIp) {
        String normalizedUsername = normalizeUsername(username);
        DeviceAccessScope scope = prepareLogin(projectKey, normalizedUsername, clientIp);
        AppUser user = appUserRepository.lockByTenantAndUsername(scope.tenantId(), normalizedUsername).orElse(null);
        return authenticateLocked(scope, user, password);
    }

    /** 浏览器完整替换保持目标许可、两用户锁、锁后认证、新族签发与旧族撤销在一个原事务。 */
    @Transactional
    public AppIssuedSession loginReplacingBrowserSession(String projectKey, String username, String password,
            String clientIp, byte[] oldRefreshHash) {
        if (oldRefreshHash != null && oldRefreshHash.length != 32) throw new IllegalArgumentException("旧刷新摘要格式无效");
        byte[] frozenHash = oldRefreshHash == null ? null : oldRefreshHash.clone();
        String normalizedUsername = normalizeUsername(username);
        DeviceAccessScope scope = prepareLogin(projectKey, normalizedUsername, clientIp);
        // 锁前只消费ID；口令、状态与角色不得从这里的快照得出结论。
        AppUser located = appUserRepository.findByTenantAndUsername(scope.tenantId(), normalizedUsername).orElse(null);
        if (located == null) return authenticateLocked(scope, null, password);
        browserReplacement.lockUsers(located.id(), frozenHash);
        AppUser locked = appUserRepository.findByIdAndTenant(scope.tenantId(), located.id()).orElse(null);
        if (locked != null && !normalizedUsername.equals(locked.username())) locked = null;
        // 持两用户锁后重读旧族；不能调用会重新建立旧租户RLS的通用logout。
        var previous = frozenHash == null ? java.util.Optional.<com.things.link.enduser.domain.AppRefreshToken>empty()
                : refreshTokens.findByHash(frozenHash);
        AppIssuedSession issued = authenticateLocked(scope, locked, password);
        previous.ifPresent(token -> refreshTokens.revokeFamily(token.familyId(), Instant.now()));
        return issued;
    }

    /** 两类登录共享限流、可信归属及目标项目SHARE许可，不复制弱化身份分支。 */
    private DeviceAccessScope prepareLogin(String projectKey, String normalizedUsername, String clientIp) {
        if (!rateLimiter.tryAcquire(projectKey, normalizedUsername, clientIp)) {
            throw new BusinessException(CommonErrorCode.TOO_MANY_REQUESTS);
        }
        DeviceAccessScope scope = projectService.findDeviceAccessScope(projectKey).orElseThrow(() -> loginFailed("projectKey"));
        transactionLocalRlsScope.establish(scope.tenantId(), scope.projectId());
        if (!lifecycleAccessService.lockActiveForWrite(scope.tenantId(), scope.projectId())) throw loginFailed("projectPermission");
        return scope;
    }

    /** 验密与角色只消费锁后事实；用户不存在仍走哑口令，两个登录入口共享同一签发路径。 */
    private AppIssuedSession authenticateLocked(DeviceAccessScope scope, AppUser user, String password) {
        // 用户不存在时仍与哑哈希比对一次，避免时序差泄露「用户名是否存在」
        boolean passwordMatches = password != null
                && passwordEncoder.matches(password, user == null ? DUMMY_PASSWORD_HASH : user.passwordHash());

        if (user == null || !passwordMatches) {
            throw loginFailed("credentials");
        }
        if (user.status() != AppUser.Status.ACTIVE) {
            throw loginFailed("status");
        }

        AppUserRole role = appUserRoleRepository.findByProjectAndUser(scope.projectId(), user.id())
                .orElseThrow(() -> loginFailed("role"));
        if (role.status() != AppUserRole.Status.ACTIVE) {
            throw loginFailed("role");
        }

        return sessionService.issueForLogin(
                new AppAuthenticatedPrincipal(scope.tenantId(), scope.projectId(), user.id()));
    }

    /** 统一 60006，reason 只进 debug 日志（服务端可见，客户端不可见）。 */
    private BusinessException loginFailed(String reason) {
        log.debug("App 登录失败 reason={}", reason);
        return new BusinessException(EndUserErrorCode.END_USER_LOGIN_FAILED);
    }

    /** 宽松规范化：null / 空白归为 ""，匹配不到任何用户，仍走 60006 而非参数错误。 */
    private static String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

}
