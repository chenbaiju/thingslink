package com.things.link.iam.application;

import com.things.link.iam.domain.Account;
import com.things.link.iam.domain.AccountRepository;
import com.things.link.iam.domain.IamErrorCode;
import com.things.link.iam.domain.TenantMembership;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 登录用例。
 *
 * <p>本类位于 {@code application} 包，是 iam 模块的对外契约面之一
 * （架构文档 10.4 规则 2）。
 */
@Service
public class AuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);

    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final AuditLogService auditLogService;

    /**
     * 账号不存在时用于消耗等量 CPU 的假哈希。
     *
     * <p>必须是**格式合法**的哈希，否则 {@code matches()} 会立刻返回 false 而不做
     * 实际计算，防计时攻击的目的就落空了。
     *
     * <p><b>在启动时对随机值现算，不用硬编码的常量。</b> 网上流传的示例哈希都
     * 对应某个已知口令（"password"、"secret" 之类），一旦有人把它当种子数据写进
     * 某个账号，那个账号就有了一个公开的口令。现算的值不对应任何可能被输入的
     * 字符串，从根上消除这类可能。
     */
    private final String dummyHash;

    /**
     * 连续失败多少次触发临时锁定。
     *
     * <p>5 次是常见取值：正常用户忘记口令时试三四次很普遍，再多就更像是在猜。
     */
    private static final int MAX_FAILED_ATTEMPTS = 5;

    /**
     * 临时锁定时长。
     *
     * <p>刻意<b>不做永久锁定</b>：那会变成一个免费的拒绝服务通道 —— 知道某人邮箱
     * 就能让他登不进去直到管理员介入，攻击成本几乎为零而防御成本是人工。
     * 15 分钟足以让口令爆破的速率降到不可行，同时把攻击者的收益限制在
     * 「延迟对方一刻钟」。
     */
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private final AuthRateLimiter rateLimiter;

    /**
     * 用于在独立事务中记录登录失败。
     *
     * <p><b>这是必须的，不是优化。</b>{@link #login} 带 {@code @Transactional}，
     * 而凭据错误时会抛异常 —— 失败计数如果写在同一个事务里，会被这个异常
     * <b>连同回滚掉</b>，于是计数永远停在 0，账号锁定永远不触发。
     *
     * <p>症状极具欺骗性：接口正确返回 401，看起来一切正常，只是那道防线根本
     * 没接上。切片 4a 的刷新令牌复用检测踩过一模一样的坑，这里是第二次。
     */
    private final TransactionTemplate newTransaction;

    public AuthenticationService(AccountRepository accountRepository,
                                 PasswordEncoder passwordEncoder,
                                 RefreshTokenService refreshTokenService,
                                 AuthRateLimiter rateLimiter,
                                 PlatformTransactionManager transactionManager,
                                 AuditLogService auditLogService) {
        this.accountRepository = accountRepository;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenService = refreshTokenService;
        this.rateLimiter = rateLimiter;
        this.auditLogService = auditLogService;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());

        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 校验凭据并签发令牌对。
     *
     * @param command 登录入参
     * @param client  客户端信息，随会话记录，仅用于设备列表与排查
     * @return 访问令牌与刷新令牌
     * @throws BusinessException 凭据错误、账号不可用或无租户归属
     */
    @Transactional
    public IssuedSession login(LoginCommand command, ClientContext client) {
        String email = normalize(command.email());

        // 限流在最前面，早于任何数据库访问。
        //
        // 它必须**不区分账号是否存在**：只对存在的账号限流，会让「是否触发限流」
        // 本身变成账号是否已注册的信号，等于用另一种方式泄露了同样的信息。
        if (!rateLimiter.tryAcquire(email, client.clientIp())) {
            throw new BusinessException(CommonErrorCode.TOO_MANY_REQUESTS);
        }

        Account account = accountRepository.findByEmail(email)
                .orElse(null);

        try {
            return authenticate(command, client, email, account);
        } catch (BusinessException rejected) {
            UUID accountId = account == null ? null : account.id();
            // 业务异常将回滚外层；拒绝证据须独立提交，不保存自报邮箱或凭据。
            newTransaction.executeWithoutResult(status -> auditLogService.record(new AuditLogEntry(
                    null, null, accountId, "account", accountId, "iam.login.rejected",
                    Map.of("errorCode", rejected.errorCode().code()))));
            throw rejected;
        }
    }

    /** 在login事务内完成认证；成功审计与会话共同提交。 */
    private IssuedSession authenticate(LoginCommand command, ClientContext client, String email, Account account) {
        // 账号不存在时仍然执行一次哈希校验，让「账号不存在」与「口令错误」
        // 的响应时间相近。否则攻击者可以通过计时差异枚举出哪些邮箱已注册 ——
        // 错误码已经刻意不区分二者（IamErrorCode 类注释），但计时会把它泄露回去。
        String hashToCheck = account != null
                ? account.passwordHash()
                : dummyHash;
        boolean passwordMatches = passwordEncoder.matches(command.password(), hashToCheck);

        if (account == null || !passwordMatches) {
            if (account != null) {
                recordFailedAttempt(account.id());
            }
            // 日志里记邮箱用于排查暴力破解，但**不记口令**
            log.warn("登录失败：凭据不匹配 email={}", email);
            throw new BusinessException(IamErrorCode.INVALID_CREDENTIALS);
        }

        // ↓↓↓ 从这里开始口令已经验证通过，可以返回有区分度的错误码了 ↓↓↓
        //
        // 顺序很关键：锁定与停用的判断**必须在口令校验之后**。
        // 放在前面的话，攻击者用任意口令试探，凡是返回 20003 / 20002 的邮箱
        // 都是已注册账号 —— 锁定机制本身就成了账号枚举通道。
        // 能看到这两个码，说明对方已经知道口令了，此时告诉他账号状态没有额外损失。
        Instant now = Instant.now();
        if (account.isTemporarilyLocked(now)) {
            log.warn("登录被拒：账号处于临时锁定期 accountId={}", account.id());
            throw new BusinessException(IamErrorCode.ACCOUNT_LOCKED);
        }

        assertAccountUsable(account);
        assertEmailVerified(account);

        TenantMembership membership = resolveMembership(account);

        // 连续失败计数清零。放在成员关系确认之后：前面任何一步抛异常都意味着
        // 这次登录没有真正成功，不该重置计数
        accountRepository.resetFailedLogin(account.id());
        rateLimiter.onSuccess(email);

        // 最近登录时间与不可篡改审计分开保存；两者均随会话事务提交。
        accountRepository.recordLogin(account.id(), Instant.now());

        // 登录时不带项目：用户可能参与多个项目，选哪个是他的决定，不是系统能猜的。
        // 前端拿到令牌后去项目列表选一个，再调 /switch-project。
        // 在那之前，受项目 RLS 保护的表一行也读不到（fail-closed），这是期望行为
        IssuedSession session = refreshTokenService.issueForLogin(
                new AuthenticatedPrincipal(account.id(), membership.tenantId(), null), client);
        auditLogService.record(new AuditLogEntry(membership.tenantId(), null, account.id(),
                "account", account.id(), "iam.login.succeeded", Map.of()));
        return session;
    }

    /**
     * 归一化邮箱：去空白并转小写。
     *
     * <p>限流的计数键必须归一化，否则 {@code Foo@x.com} 与 {@code foo@x.com}
     * 会各自计数，攻击者只要变换大小写就能把限额翻好几倍。
     * 数据库查询本身是大小写不敏感的（{@code lower(email)} 索引），不受影响。
     *
     * @param email 原始输入
     * @return 归一化后的邮箱；输入为 null 时返回空串
     */
    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 在独立事务中记录一次登录失败。
     *
     * @param accountId 账号 ID
     */
    private void recordFailedAttempt(UUID accountId) {
        newTransaction.executeWithoutResult(status ->
                accountRepository.recordFailedLogin(
                        accountId, MAX_FAILED_ATTEMPTS, LOCK_DURATION, Instant.now()));
    }

    /**
     * 校验账号状态。
     *
     * <p>与凭据校验分开报错：凭据错误是「你输错了」，账号停用是「你输对了但不让进」，
     * 二者对用户的指引完全不同。这不构成账号枚举风险 —— 能走到这里说明口令已经正确。
     */
    private void assertAccountUsable(Account account) {
        if (account.status() == Account.Status.DISABLED) {
            throw new BusinessException(IamErrorCode.ACCOUNT_DISABLED);
        }
        if (account.status() == Account.Status.LOCKED) {
            throw new BusinessException(IamErrorCode.ACCOUNT_LOCKED);
        }
        if (!account.canAuthenticate()) {
            throw new BusinessException(IamErrorCode.ACCOUNT_DISABLED);
        }
    }

    /**
     * 校验邮箱是否已完成验证。
     *
     * <p>必须放在口令校验之后：否则攻击者拿任意口令试探时，20022 会暴露「这个邮箱
     * 注册过但尚未验证」。走到这里说明口令已经正确，给真实用户明确提示比继续伪装成
     * 口令错误更有价值。
     *
     * @param account 已通过口令校验的账号
     */
    private void assertEmailVerified(Account account) {
        if (!account.isEmailVerified()) {
            log.warn("登录被拒：邮箱尚未验证 accountId={}", account.id());
            throw new BusinessException(IamErrorCode.EMAIL_NOT_VERIFIED);
        }
    }

    /**
     * 确定登录后进入哪个租户。
     *
     * <p>按 ADR0012，租户是账号的计费归属，协作通过项目成员关系建立。
     * 当前查询保留取第一个有效身份的兼容行为；跨项目协作由 switch-project
     * 重新签发令牌并回库验证项目权限，不据旧注释新增租户选择功能。
     */
    private TenantMembership resolveMembership(Account account) {
        List<TenantMembership> memberships = accountRepository.findMembershipsByAccount(account.id());
        return memberships.stream()
                .filter(TenantMembership::isActive)
                .findFirst()
                .orElseThrow(() -> {
                    // 正常流程走不到这里：注册会在同一事务内建好租户与成员关系。
                    // 出现即说明数据不一致，用 ERROR 级别，因为这是服务端的问题
                    log.error("账号无任何有效租户成员身份 accountId={}", account.id());
                    return new BusinessException(IamErrorCode.NO_TENANT_MEMBERSHIP);
                });
    }



}
