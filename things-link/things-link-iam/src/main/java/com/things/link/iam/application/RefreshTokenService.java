package com.things.link.iam.application;

import com.things.link.iam.domain.IamErrorCode;
import com.things.link.iam.domain.RefreshToken;
import com.things.link.iam.domain.RefreshTokenRepository;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.token.OpaqueToken;
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
import java.util.OptionalLong;
import java.util.UUID;

/**
 * 刷新令牌的签发、轮换与撤销（架构文档 7.3）。
 *
 * <h2>轮换：每用一次就换一把</h2>
 * 每次刷新都签发新的刷新令牌并作废旧的。这样一个被窃取的令牌只有到真用户下次刷新
 * 之前的窗口期可用，而不是整整几天。
 *
 * <h2>复用检测：轮换真正的价值所在</h2>
 * 单纯的轮换只缩短窗口，<b>发现不了盗用</b>。关键在于：合法客户端换到新令牌后
 * 不会再用旧的，所以一个已被轮换的令牌再次出现，只可能是它被复制过。
 *
 * <p>此时服务端无法分辨来的是攻击者还是真用户（两边持有的凭据一样合法），
 * 唯一安全的处置是<b>把整族一起作废</b>，强制双方重新登录 —— 真用户重登一次，
 * 攻击者则彻底失去入口。只作废被复用的那一条是不够的：攻击者手里的新令牌仍然有效。
 *
 * <h2>令牌明文的生成与摘要</h2>
 * 见 {@link OpaqueToken} —— 与邮箱验证令牌共用同一套实现，避免两处各自演化。
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    private final RefreshTokenRepository refreshTokenRepository;
    private final CurrentUserService currentUserService;
    private final TokenIssuer tokenIssuer;
    /** 项目当前代次的权威端口；刷新轮换必须在任何复用处置或新令牌写入前先比较。 */
    private final ProjectLifecycleAccessService projectLifecycleAccessService;
    private final Duration refreshTokenTtl;

    /**
     * 用于在独立事务中执行「作废整族」。
     *
     * <p>不能靠 {@code @Transactional(REQUIRES_NEW)} 加在本类的私有方法上 ——
     * Spring 的事务是代理实现的，类内部自调用不经过代理，注解会<b>静默失效</b>：
     * 编译通过、测试可能也通过，只是事务语义完全不是写的那样。
     */
    private final TransactionTemplate newTransaction;

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository,
                               CurrentUserService currentUserService,
                               TokenIssuer tokenIssuer,
                               ProjectLifecycleAccessService projectLifecycleAccessService,
                               SessionProperties sessionProperties,
                               PlatformTransactionManager transactionManager) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.currentUserService = currentUserService;
        this.tokenIssuer = tokenIssuer;
        this.projectLifecycleAccessService = projectLifecycleAccessService;
        this.refreshTokenTtl = sessionProperties.refreshTokenTtl();

        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 为一次新登录签发令牌对，并开启一个新的轮换族。
     *
     * @param principal 认证成功后确定的身份
     * @param client    客户端信息，仅作记录
     * @return 访问令牌与刷新令牌
     */
    @Transactional
    public IssuedSession issueForLogin(AuthenticatedPrincipal principal, ClientContext client) {
        // 新登录 = 新的族。与「刷新」区分开：同一台设备重新登录不应牵连
        // 其他设备上仍在使用的会话
        AuthenticatedPrincipal currentPrincipal = currentPrincipalForSelection(
                principal.accountId(), principal.tenantId(), principal.projectId());
        return issue(currentPrincipal, Uuid7.generate(), client).session();
    }

    /**
     * 用刷新令牌换一对新令牌。
     *
     * @param rawToken 刷新令牌明文。允许为 {@code null}（请求里根本没带 Cookie），
     *                 与令牌无效同样报 20020 —— 对客户端而言处置完全一样：重新登录
     * @param client   客户端信息
     * @return 新的令牌对
     * @throws BusinessException 令牌缺失、无效、过期、已撤销或被复用；账号或成员关系已失效
     */
    @Transactional
    public IssuedSession rotate(String rawToken, ClientContext client) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new BusinessException(IamErrorCode.INVALID_TOKEN);
        }

        RefreshToken existing = refreshTokenRepository.findByHash(OpaqueToken.hash(rawToken))
                // 令牌根本不存在。可能是伪造的，也可能是数据库被清过 ——
                // 两种情况对调用方是一样的：重新登录
                .orElseThrow(() -> new BusinessException(IamErrorCode.INVALID_TOKEN));

        // ADR0073：必须早于rotated复用处置；旧代次凭据不能触发整族撤销等任何副作用。
        requireMatchingProjectGeneration(existing);

        Instant now = Instant.now();

        if (existing.isRotated()) {
            // 复用检测命中。这是**安全事件**而非普通的认证失败，所以用 ERROR 级别：
            // 它意味着某个刷新令牌被复制过，值得人去看一眼
            log.error("检测到刷新令牌复用，作废整族 accountId={} familyId={} tokenId={}",
                    existing.accountId(), existing.familyId(), existing.id());

            // 必须在**独立事务**里作废，否则紧接着抛出的异常会把它一起回滚 ——
            // 表现是「检测到了入侵、返回了 401、但一条也没真的作废」，
            // 攻击者手里那把刚换到的新令牌照常可用。
            //
            // 这是本切片实测踩到的真实缺陷：只测成功路径的话完全看不出来，
            // 因为回滚掉的恰好是「失败时才执行」的那一步
            revokeFamilyInNewTransaction(existing.familyId(), now);
            throw new BusinessException(IamErrorCode.INVALID_TOKEN);
        }

        if (!existing.isUsable(now)) {
            // 已撤销（退出登录 / 之前的复用检测）或已过期。不是安全事件，正常记 warn
            log.warn("刷新令牌不可用 accountId={} tokenId={}", existing.accountId(), existing.id());
            throw new BusinessException(IamErrorCode.INVALID_TOKEN);
        }

        // 回库复核账号状态与成员关系。
        //
        // 少了这一步，停用一个账号就只在访问令牌过期后的 15 分钟内有效 —— 之后对方
        // 用刷新令牌又能换一把新的，「停用」实际上从未生效。刷新是无状态令牌方案里
        // 最重要的一个复核点，比 /me 更关键：/me 漏了只是显示不对，这里漏了是拦不住人
        CurrentUser user = currentUserService.resolve(existing.accountId(), existing.tenantId());

        Issued issued = issue(
                // 沿用原来的项目选择 —— 它是会话状态，不该因为刷新而丢失
                new AuthenticatedPrincipal(
                        user.accountId(), user.tenantId(), existing.projectId(),
                        existing.projectLifecycleGeneration()),
                // 继承原族：这样这条会话链上任何一次复用都能牵连到全部后代
                existing.familyId(),
                client);

        refreshTokenRepository.markRotated(existing.id(), issued.tokenId());
        return issued.session();
    }

    /**
     * 撤销一次会话（退出登录）。
     *
     * <p>作废<b>整族</b>而不只是当前这一条：族代表「一次登录派生出的整条链」，
     * 只作废当前这条的话，链上任何一个中间令牌若曾被复制，仍然可用。
     *
     * <p>令牌无效时<b>静默返回</b>，不报错：退出登录必须是幂等且总能成功的。
     * 让「退出」失败没有任何安全收益，只会把用户困在一个他想离开的会话里。
     *
     * @param rawToken 刷新令牌明文，可为 {@code null}
     */
    @Transactional
    public void revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        Optional<RefreshToken> token = refreshTokenRepository.findByHash(OpaqueToken.hash(rawToken));
        token.ifPresent(t -> refreshTokenRepository.revokeFamily(t.familyId(), Instant.now()));
    }

    /**
     * 切换当前项目：在同一个会话里换一个项目，重新签发令牌对。
     *
     * <p><b>实现上就是一次轮换</b>，只是把新令牌的 {@code project_id} 换掉。
     * 复用轮换而不是另起一套：旧访问令牌里的 {@code pid} 会随之失效，
     * 否则用户切走之后，旧令牌在剩余有效期内仍然能操作原项目的数据。
     *
     * <p>调用方<b>必须先校验成员关系</b> —— 本方法不做这件事，因为成员关系归
     * project 模块管，而 iam 只能通过它的 application 入口去问。
     *
     * @param rawToken  当前刷新令牌明文
     * @param projectId 目标项目；{@code null} 表示退出项目上下文
     * @param client    客户端信息
     * @return 新的令牌对，访问令牌里带上新的 pid
     * @throws BusinessException 刷新令牌无效
     */
    @Transactional
    public IssuedSession switchProject(String rawToken, UUID projectId, ClientContext client) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new BusinessException(IamErrorCode.INVALID_TOKEN);
        }

        RefreshToken existing = refreshTokenRepository.findByHash(OpaqueToken.hash(rawToken))
                .orElseThrow(() -> new BusinessException(IamErrorCode.INVALID_TOKEN));

        // 主动切换也不能用删除前会话换取恢复后的新代次凭据。
        requireMatchingProjectGeneration(existing);

        if (existing.isRotated() || !existing.isUsable(Instant.now())) {
            // 与 rotate 保持一致：不可用的令牌一律 20020。
            // 这里不触发复用检测的整族作废 —— 切换项目是用户的主动操作，
            // 而复用检测针对的是「同一个令牌被用了两次」，语义不同
            throw new BusinessException(IamErrorCode.INVALID_TOKEN);
        }

        // 与刷新一样回库复核账号状态与成员关系
        CurrentUser user = currentUserService.resolve(existing.accountId(), existing.tenantId());

        Issued issued = issue(
                currentPrincipalForSelection(user.accountId(), user.tenantId(), projectId),
                existing.familyId(),
                client);

        refreshTokenRepository.markRotated(existing.id(), issued.tokenId());
        return issued.session();
    }

    /**
     * 在独立事务中作废整族，使其不受外层事务回滚影响。
     *
     * @param familyId  轮换族
     * @param revokedAt 撤销时刻
     */
    private void revokeFamilyInNewTransaction(UUID familyId, Instant revokedAt) {
        newTransaction.executeWithoutResult(
                status -> refreshTokenRepository.revokeFamily(familyId, revokedAt));
    }

    /**
     * 签发结果的内部载体。
     *
     * <p>{@link #rotate} 需要新令牌的 ID 去回填旧记录的 {@code replaced_by}，而
     * {@link IssuedSession} 是给外部的契约，不该为了内部需要多带一个字段。
     *
     * <p><b>不要改用「把 ID 存成字段」的写法</b>：本类是单例 bean，实例字段会被并发的
     * 刷新请求互相覆盖，症状是某次刷新的 {@code replaced_by} 指向了别人的令牌 ——
     * 轮换链断掉，复用检测随之失效，而这在低并发的开发环境里几乎不可能复现。
     *
     * @param session 对外的令牌对
     * @param tokenId 新刷新令牌的 ID
     */
    private record Issued(IssuedSession session, UUID tokenId) {
    }

    /**
     * 签发一对令牌并登记刷新令牌。
     *
     * @param principal 身份
     * @param familyId  轮换族
     * @param client    客户端信息
     * @return 令牌对与新令牌 ID
     */
    private Issued issue(AuthenticatedPrincipal principal, UUID familyId, ClientContext client) {
        // 三个公开事务入口均已取得项目SHARE锁；本方法只在锁持有期间生成并保存凭据。
        String rawToken = OpaqueToken.generate();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(refreshTokenTtl);

        RefreshToken record = new RefreshToken(
                Uuid7.generate(), principal.accountId(), principal.tenantId(),
                principal.projectId(), principal.projectLifecycleGeneration(),
                familyId, now, expiresAt, null, null);

        refreshTokenRepository.save(record, OpaqueToken.hash(rawToken), client.userAgent(), client.clientIp());

        return new Issued(
                new IssuedSession(tokenIssuer.issue(principal), rawToken, expiresAt),
                record.id());
    }

    /**
     * 项目切换是恢复后取得新代次的显式入口，先读取目标项目当前代次再交给签发前复核。
     * @param accountId 已复核账号
     * @param tenantId 当前租户
     * @param projectId 新选择的项目，可为空
     * @return 带目标项目当前代次的身份
     */
    private AuthenticatedPrincipal currentPrincipalForSelection(
            UUID accountId, UUID tenantId, UUID projectId) {
        if (projectId == null) {
            return new AuthenticatedPrincipal(accountId, tenantId, null, 0L);
        }
        OptionalLong generation = projectLifecycleAccessService
                .lockActiveGenerationForProjectToken(accountId, projectId);
        if (generation.isEmpty()) {
            throw new BusinessException(IamErrorCode.INVALID_TOKEN);
        }
        return new AuthenticatedPrincipal(accountId, tenantId, projectId, generation.getAsLong());
    }

    /**
     * 在任何轮换状态判断和副作用前核验持久会话代次，数据库异常原样传播为系统错误。
     * @param token 已按不可猜哈希读取的刷新事实
     */
    private void requireMatchingProjectGeneration(RefreshToken token) {
        if (token.projectId() == null) {
            if (token.projectLifecycleGeneration() != 0) {
                throw new BusinessException(IamErrorCode.INVALID_TOKEN);
            }
            return;
        }
        OptionalLong generation = projectLifecycleAccessService
                .lockActiveGenerationForProjectToken(token.accountId(), token.projectId());
        if (generation.isEmpty() || generation.getAsLong() != token.projectLifecycleGeneration()) {
            throw new BusinessException(IamErrorCode.INVALID_TOKEN);
        }
    }

}
