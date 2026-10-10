package com.things.link.enduser.application;

import com.things.link.enduser.domain.AppRefreshToken;
import com.things.link.enduser.domain.AppRefreshTokenRepository;
import com.things.link.enduser.domain.AppUser;
import com.things.link.enduser.domain.AppUserRepository;
import com.things.link.enduser.domain.AppUserRole;
import com.things.link.enduser.domain.AppUserRoleRepository;
import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.project.application.ProjectAccessPolicy;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.token.OpaqueToken;
import com.things.link.support.tenant.TenantTransactionLocalRlsScope;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * App 会话的签发、轮换与撤销（ADR 0036）。
 *
 * <h2>轮换与复用检测</h2>
 * 与控制台 {@code RefreshTokenService} 完全同构：每刷新一次就换一把新令牌并作废旧令牌；
 * 一个已被轮换的令牌再次出现，只可能是它被复制过 —— 此时无法分辨来的是攻击者还是真用户，
 * 唯一安全的处置是<b>整族作废</b>，强制双方重新登录。
 *
 * <h2>刷新时的回库复验</h2>
 * 刷新令牌本身豁免 RLS（先查令牌才能知道租户），因此<b>命中后必须恢复租户/项目上下文，
 * 回库复验 {@code app_user.status} 与 {@code app_user_role.status}</b>。少了这一步，停用一个
 * 终端用户就只在访问令牌过期前的 15 分钟内有效 —— 之后对方用刷新令牌又能换一把新的。
 * 这是无状态令牌方案里最重要的复核点。
 */
@Service
public class AppSessionService {

    private static final Logger log = LoggerFactory.getLogger(AppSessionService.class);

    private final AppRefreshTokenRepository refreshTokenRepository;
    private final AppUserRepository appUserRepository;
    private final AppUserRoleRepository appUserRoleRepository;
    private final AppTokenIssuer tokenIssuer;
    /** ADR0064决策4：在会话持久化原事务内持有项目写许可，不能由登录前快照代替。 */
    private final ProjectLifecycleAccessService lifecycleAccessService;
    /** 按刷新令牌或已认证主体的可信二元组建立项目级事务范围。 */
    private final TransactionLocalRlsScope transactionLocalRlsScope;
    /** 跨项目全会话撤销只需要可信租户轴，不伪造项目身份。 */
    private final TenantTransactionLocalRlsScope tenantTransactionLocalRlsScope;
    private final Duration refreshTokenTtl;
    /** 未配置平台实例时登录仍可用，但安装注册必须拒绝。 */
    @org.springframework.beans.factory.annotation.Value("${things-link.app-navigation.backend-instance-id:}")
    private String backendInstanceId = "";

    /**
     * 用于在独立事务中执行「作废整族」。
     *
     * <p>不能靠 {@code @Transactional(REQUIRES_NEW)} 加在本类私有方法上 —— 自调用不经过
     * 代理，注解会静默失效（与控制台同一条踩坑记录）。
     * ADR0097：外层只持项目SHARE及用户NO KEY UPDATE，复用分支前不得写入或锁定token行；
     * 内层只更新撤销字段，不重复申请用户/项目锁，否则独立事务会等待外层形成自锁。
     */
    private final TransactionTemplate newTransaction;
    /** 目标角色只读事务，不修改外层已建立的源项目RLS。 */
    private final TransactionTemplate navigationRead;

    /**
     * @param refreshTokenRepository 刷新令牌与轮换族持久化
     * @param appUserRepository 用户状态复验
     * @param appUserRoleRepository 当前项目角色复验
     * @param tokenIssuer 已准许会话的JWT签发器
     * @param lifecycleAccessService 原事务内的项目生命周期写许可
     * @param transactionLocalRlsScope 项目会话事务局部完整范围组件
     * @param tenantTransactionLocalRlsScope 跨项目撤销事务局部租户范围组件
     * @param sessionProperties 刷新令牌时限
     * @param transactionManager 复用检测必须独立提交整族撤销
     */
    public AppSessionService(AppRefreshTokenRepository refreshTokenRepository,
                             AppUserRepository appUserRepository,
                             AppUserRoleRepository appUserRoleRepository,
                             AppTokenIssuer tokenIssuer,
                             ProjectLifecycleAccessService lifecycleAccessService,
                             TransactionLocalRlsScope transactionLocalRlsScope,
                             TenantTransactionLocalRlsScope tenantTransactionLocalRlsScope,
                             AppSessionProperties sessionProperties,
                             PlatformTransactionManager transactionManager) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.appUserRepository = appUserRepository;
        this.appUserRoleRepository = appUserRoleRepository;
        this.tokenIssuer = tokenIssuer;
        this.lifecycleAccessService = lifecycleAccessService;
        this.transactionLocalRlsScope = transactionLocalRlsScope;
        this.tenantTransactionLocalRlsScope = tenantTransactionLocalRlsScope;
        this.refreshTokenTtl = sessionProperties.refreshTokenTtl();

        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        navigationRead=new TransactionTemplate(transactionManager);
        navigationRead.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        navigationRead.setReadOnly(true);
    }

    /**
     * 为一次新登录签发令牌对，并开启一个新的轮换族。
     *
     * <p>新登录 = 新的族。与「刷新」区分开：同一台设备重新登录不应牵连其他设备上仍在
     * 使用的会话。
     *
     * @param principal 认证成功后确定的身份
     * @return 访问令牌与刷新令牌
     */
    @Transactional
    public AppIssuedSession issueForLogin(AppAuthenticatedPrincipal principal) {
        // 身份已经过登录认证；只匹配可信二元组并持锁至本会话事务提交，拒绝不得产生令牌或持久记录。
        if (!lifecycleAccessService.lockActiveForWrite(principal.tenantId(), principal.projectId())) {
            throw new BusinessException(EndUserErrorCode.END_USER_LOGIN_FAILED);
        }
        applyScope(principal.tenantId(), principal.projectId());
        // ADR0097：内部签发入口也参与用户级互斥，不能越过改密的全会话撤销。
        appUserRepository.lockByIdAndTenant(principal.tenantId(), principal.appUserId())
                .orElseThrow(() -> new BusinessException(EndUserErrorCode.END_USER_LOGIN_FAILED));
        // 锁后读取权威代次；项目排他删除被SHARE锁阻塞，因此签发与refresh事实使用同一稳定代次。
        ProjectAccessPolicy policy = lifecycleAccessService.snapshot(principal.tenantId(), principal.projectId());
        if (!policy.readAllowed() || !policy.writeAllowed() || policy.lifecycleGeneration() < 0) {
            throw new IllegalStateException("已取得项目写许可但生命周期代次不可签发");
        }
        AppAuthenticatedPrincipal qualified = new AppAuthenticatedPrincipal(
                principal.tenantId(), principal.projectId(), principal.appUserId(),
                policy.lifecycleGeneration());
        return issue(qualified, Uuid7.generate()).session();
    }

    /**
     * 用刷新令牌换一对新令牌。
     *
     * @param rawToken 刷新令牌明文。允许为 {@code null}（请求体没带），与令牌无效同样报 60007
     * @return 新的令牌对
     * @throws BusinessException 令牌缺失、无效、过期、已撤销或被复用；项目不可写、用户或项目角色已失效
     */
    @Transactional
    public AppIssuedSession rotate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new BusinessException(EndUserErrorCode.END_USER_REFRESH_INVALID);
        }

        byte[] tokenHash = OpaqueToken.hash(rawToken);
        AppRefreshToken existing = refreshTokenRepository.findByHash(tokenHash)
                // 令牌根本不存在。可能是伪造的，也可能是数据库被清过 —— 对调用方是一样的：重新登录
                .orElseThrow(() -> new BusinessException(EndUserErrorCode.END_USER_REFRESH_INVALID));

        // ADR0073：代次许可先于复用撤销、幂等判断及所有轮换写入，旧族不能影响恢复后的新会话。
        if (!lifecycleAccessService.lockActiveForWrite(
                existing.tenantId(), existing.projectId(), existing.projectGeneration())) {
            throw new BusinessException(EndUserErrorCode.END_USER_REFRESH_INVALID);
        }

        applyScope(existing.tenantId(), existing.projectId());
        // ADR0097：共享用户锁覆盖同族及改密；等待后必须重读token，锁前快照不能决定复用/签发。
        appUserRepository.lockByIdAndTenant(existing.tenantId(), existing.appUserId())
                .orElseThrow(() -> new BusinessException(EndUserErrorCode.END_USER_REFRESH_INVALID));
        existing = refreshTokenRepository.findByHash(tokenHash)
                .orElseThrow(() -> new BusinessException(EndUserErrorCode.END_USER_REFRESH_INVALID));

        Instant now = Instant.now();

        if (existing.isRotated()) {
            // 复用检测命中。这是安全事件而非普通认证失败，用 ERROR 级别
            log.error("检测到 App 刷新令牌复用，作废整族 appUserId={} familyId={} tokenId={}",
                    existing.appUserId(), existing.familyId(), existing.id());

            // ADR0097：这里尚未写/锁token，独立事务只执行撤销；外层用户锁仍阻止并发会话写入。
            // 必须独立提交，否则紧接着60007异常会回滚整族撤销，不能改成宽泛noRollbackFor。
            revokeFamilyInNewTransaction(existing.familyId(), now);
            throw new BusinessException(EndUserErrorCode.END_USER_REFRESH_INVALID);
        }

        if (!existing.isUsable(now)) {
            log.warn("App 刷新令牌不可用 appUserId={} tokenId={}", existing.appUserId(), existing.id());
            throw new BusinessException(EndUserErrorCode.END_USER_REFRESH_INVALID);
        }

        // 回库复验用户状态与项目角色（见类注释）。这也是停用生效的真正落点。
        AppAuthenticatedPrincipal principal = reverify(
                existing.appUserId(), existing.tenantId(), existing.projectId(),
                existing.projectGeneration());

        Issued issued = issue(principal, existing.familyId(), existing.sessionGroupId());
        if (refreshTokenRepository.markRotated(existing.id(), issued.tokenId()) != 1) {
            // 非预期并发/事实变更不得返回已签发结果；异常使本次refresh插入与全部原事务写入回滚。
            throw new IllegalStateException("持用户会话锁后刷新令牌条件轮换未命中唯一记录");
        }
        return issued.session();
    }

    /**
     * 同一用户跨项目换签；两项目许可按固定顺序先取得，用户锁后重读令牌，旧族撤销与新族签发原子提交。
     * @param rawToken 当前刷新凭据 @param tenant 已验签租户 @param source 已验签源项目 @param user 已验签本人 @param target 目标项目，仅为定位提示
     * @return 目标项目会话；权限拒绝不消费源令牌，已轮换令牌重放仍撤销其族
     */
    @Transactional
    public AppIssuedSession switchProject(String rawToken, UUID tenant, UUID source, UUID user, UUID target) {
        if(rawToken==null||rawToken.isBlank()||rawToken.length()>4096||target==null)
            throw new BusinessException(EndUserErrorCode.END_USER_REFRESH_INVALID);
        byte[] hash=OpaqueToken.hash(rawToken);
        var existing=refreshTokenRepository.findByHash(hash)
                .orElseThrow(()->new BusinessException(EndUserErrorCode.END_USER_REFRESH_INVALID));
        if(!existing.tenantId().equals(tenant)||!existing.projectId().equals(source)||!existing.appUserId().equals(user))
            throw new BusinessException(EndUserErrorCode.END_USER_REFRESH_INVALID);
        for(UUID project:java.util.stream.Stream.of(source,target).distinct().sorted(java.util.Comparator.comparing(UUID::toString)).toList()) {
            boolean allowed=project.equals(source)
                    ?lifecycleAccessService.lockActiveForWrite(tenant,project,existing.projectGeneration())
                    :lifecycleAccessService.lockActiveForWrite(tenant,project);
            if(!allowed)throw new BusinessException(project.equals(source)?EndUserErrorCode.END_USER_REFRESH_INVALID:EndUserErrorCode.NAVIGATION_PROJECT_UNAVAILABLE);
        }
        applyScope(tenant,source);
        appUserRepository.lockByIdAndTenant(tenant,user)
                .orElseThrow(()->new BusinessException(EndUserErrorCode.END_USER_REFRESH_INVALID));
        existing=refreshTokenRepository.findByHash(hash)
                .orElseThrow(()->new BusinessException(EndUserErrorCode.END_USER_REFRESH_INVALID));
        if(existing.isRotated()) {
            revokeFamilyInNewTransaction(existing.familyId(),Instant.now());
            throw new BusinessException(EndUserErrorCode.END_USER_REFRESH_INVALID);
        }
        if(!existing.isUsable(Instant.now()))throw new BusinessException(EndUserErrorCode.END_USER_REFRESH_INVALID);
        reverify(user,tenant,source,existing.projectGeneration());
        // 外层只建立源项目RLS；用户锁已阻止角色写入，目标角色在独立只读范围复验，禁止同事务切换隔离域。
        boolean targetAllowed=Boolean.TRUE.equals(navigationRead.execute(tx->{
            applyScope(tenant,target);
            return appUserRoleRepository.findByProjectAndUser(target,user)
                    .filter(r->r.status()==AppUserRole.Status.ACTIVE).isPresent();
        }));
        if(!targetAllowed)
            throw new BusinessException(EndUserErrorCode.NAVIGATION_PROJECT_UNAVAILABLE);
        var policy=lifecycleAccessService.snapshot(tenant,target);
        var result=issue(new AppAuthenticatedPrincipal(tenant,target,user,policy.lifecycleGeneration()),Uuid7.generate(),existing.sessionGroupId());
        refreshTokenRepository.revokeFamily(existing.familyId(),Instant.now());
        return result.session();
    }

    /**
     * 撤销一次会话（退出登录）。
     *
     * <p>令牌无效时<b>静默返回</b>：退出登录必须幂等且总能成功，让「退出」失败没有任何
     * 安全收益，只会把用户困在一个他想离开的会话里。
     *
     * @param rawToken 刷新令牌明文，可为 {@code null}
     */
    @Transactional
    public void revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        byte[] tokenHash = OpaqueToken.hash(rawToken);
        Optional<AppRefreshToken> token = refreshTokenRepository.findByHash(tokenHash);
        if (token.isEmpty()) {
            return;
        }
        AppRefreshToken located = token.orElseThrow();
        applyScope(located.tenantId(), located.projectId());
        // ADR0097：退出不要求ACTIVE许可；冻结项目仍可撤销，但必须等待该用户在途签发完成。
        if (appUserRepository.lockByIdAndTenant(located.tenantId(), located.appUserId()).isEmpty()) {
            return;
        }
        refreshTokenRepository.findByHash(tokenHash)
                .ifPresent(current -> refreshTokenRepository.revokeFamily(current.familyId(), Instant.now()));
    }

    /**
     * 撤销某终端用户的全部会话（改密时调用）。
     *
     * <p>与 {@link #revoke} 的区别是范围：那个只作废一条轮换链（一台设备），这个作废这个
     * 人的所有设备。
     *
     * @param tenantId 可信用户归属租户，不从未认证正文取得
     * @param appUserId 终端用户 ID
     */
    @Transactional
    public void revokeAllForUser(UUID tenantId, UUID appUserId) {
        tenantTransactionLocalRlsScope.establish(tenantId);
        // ADR0097：全用户撤销不能仅更新当前快照；同一用户锁排斥其他项目的新族签发。
        if (appUserRepository.lockByIdAndTenant(tenantId, appUserId).isPresent()) {
            refreshTokenRepository.revokeAllForUser(appUserId, Instant.now());
        }
    }

    /** ADR0097：保持纯仓储撤销，不在内层重新申请外层持有的用户、项目或token前置锁。 */
    private void revokeFamilyInNewTransaction(UUID familyId, Instant revokedAt) {
        newTransaction.executeWithoutResult(
                status -> refreshTokenRepository.revokeFamily(familyId, revokedAt));
    }

    /**
     * 签发结果的内部载体。
     *
     * <p>{@link #rotate} 需要新令牌 ID 回填旧记录的 {@code replaced_by}，而
     * {@link AppIssuedSession} 是对外契约，不该为内部需要多带一个字段。本类是单例 bean，
     * 不能把 ID 存成实例字段（并发刷新会互相覆盖）。
     */
    private record Issued(AppIssuedSession session, UUID tokenId) {
    }

    private Issued issue(AppAuthenticatedPrincipal principal, UUID familyId) {
        return issue(principal, familyId, familyId);
    }

    /** 跨项目换签保留会话组；sid始终指向当前刷新族，已结束的源族不能借组复活。 */
    private Issued issue(AppAuthenticatedPrincipal principal, UUID familyId, UUID groupId) {
        principal = new AppAuthenticatedPrincipal(principal.tenantId(), principal.projectId(),
                principal.appUserId(), principal.projectGeneration(), familyId);
        String rawToken = OpaqueToken.generate();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(refreshTokenTtl);

        AppRefreshToken record = new AppRefreshToken(
                Uuid7.generate(), principal.appUserId(), principal.tenantId(),
                principal.projectId(), principal.projectGeneration(), familyId,
                now, expiresAt, null, null, groupId);

        refreshTokenRepository.save(record, OpaqueToken.hash(rawToken));

        return new Issued(
                new AppIssuedSession(tokenIssuer.issue(principal), rawToken, expiresAt,
                        new AppSessionIdentity(backendInstanceId.isBlank() ? null : UUID.fromString(backendInstanceId),
                                principal.tenantId(), principal.appUserId(), principal.projectId(), familyId, groupId)),
                record.id());
    }

    /**
     * 恢复租户/项目上下文并回库复验用户与项目角色。
     *
     * <p>任一步不通过统一 60007：不区分「用户不存在」「已锁定」「无角色」「角色停用」，
     * 与登录侧 60006 的合并理由一致 —— 刷新端点也是公开的，区分开就是枚举通道。
     */
    private AppAuthenticatedPrincipal reverify(
            UUID appUserId, UUID tenantId, UUID projectId, long projectGeneration) {
        applyScope(tenantId, projectId);

        AppUser user = appUserRepository.findByIdAndTenant(tenantId, appUserId)
                .orElseThrow(() -> new BusinessException(EndUserErrorCode.END_USER_REFRESH_INVALID));
        if (user.status() != AppUser.Status.ACTIVE) {
            throw new BusinessException(EndUserErrorCode.END_USER_REFRESH_INVALID);
        }

        AppUserRole role = appUserRoleRepository.findByProjectAndUser(projectId, appUserId)
                .orElseThrow(() -> new BusinessException(EndUserErrorCode.END_USER_REFRESH_INVALID));
        if (role.status() != AppUserRole.Status.ACTIVE) {
            throw new BusinessException(EndUserErrorCode.END_USER_REFRESH_INVALID);
        }

        return new AppAuthenticatedPrincipal(tenantId, projectId, appUserId, projectGeneration);
    }

    /** 用令牌记录或认证主体的可信归属建立完整事务局部范围。 */
    private void applyScope(UUID tenantId, UUID projectId) {
        transactionLocalRlsScope.establish(tenantId, projectId);
    }

}
