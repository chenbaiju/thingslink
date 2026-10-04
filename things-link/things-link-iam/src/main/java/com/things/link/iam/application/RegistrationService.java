package com.things.link.iam.application;

import com.things.link.iam.domain.Account;
import com.things.link.iam.domain.AccountRepository;
import com.things.link.iam.domain.EmailVerificationPurpose;
import com.things.link.iam.domain.IamErrorCode;
import com.things.link.project.application.TenantProvisioning;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.id.Uuid7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

/**
 * 自助注册用例（ADR 0008：注册的语义是「创建租户 + 初始账号」）。
 *
 * <h2>三者必须原子成立</h2>
 * 租户、账号、成员关系在同一个事务里创建。任何一步失败都要整体回滚 —— 否则会留下
 * <b>没有归属账号的孤儿租户</b>：没有任何人能进入它，也没有入口能删掉它。
 *
 * <p>{@link TenantProvisioning#createTenant} 用 {@code Propagation.MANDATORY} 把这条
 * 约束变成强制的：调用方忘了开事务会直接抛异常，而不是静默地各自提交。
 *
 * <h2>邮箱唯一性由数据库仲裁</h2>
 * <b>不做「先查是否存在、再插入」。</b>并发注册同一邮箱时，两个请求都会查到「不存在」，
 * 然后一个成功、另一个抛出未经处理的异常变成 500。正确做法是直接插入，让唯一索引
 * 判定，再把 {@link DuplicateKeyException} 翻译成业务错误码 —— 与幂等键那套是同一个
 * 道理：<b>数据库是唯一的事实来源，应用层的检查只是提前告知，不是保证。</b>
 *
 * <h2>邮箱验证（ADR 0013）</h2>
 * 注册后会签发一个验证令牌并发信，但<b>不签发登录会话</b> ——
 * {@code email_verified_at} 保持为空直到用户点开链接。
 *
 * <p>这里必须阻断会话签发：否则用户不验证邮箱也能直接进入控制台，邮箱验证只剩
 * 一个可跳过的提示。真正持有邮箱的人点开验证链接后，再用口令登录。
 *
 * <p><b>发信失败不影响注册结果。</b>投递发生在事务提交之后的异步线程里
 * （见 {@code EmailVerificationMailer}），用户永远有「重发」这条自助出路。
 */
@Service
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

    private final AccountRepository accountRepository;
    private final TenantProvisioning tenantProvisioning;
    private final PasswordEncoder passwordEncoder;
    private final AuthRateLimiter rateLimiter;
    private final EmailVerificationService emailVerificationService;

    public RegistrationService(AccountRepository accountRepository,
                               TenantProvisioning tenantProvisioning,
                               PasswordEncoder passwordEncoder,
                               AuthRateLimiter rateLimiter,
                               EmailVerificationService emailVerificationService) {
        this.accountRepository = accountRepository;
        this.tenantProvisioning = tenantProvisioning;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = rateLimiter;
        this.emailVerificationService = emailVerificationService;
    }

    /**
     * 注册：创建租户 + 初始账号 + 租户归属关系，并发送验证邮件。
     *
     * <p>注册成功不签发会话。邮箱未验证时账号仍然存在，但登录用例会拦住它；
     * 这样用户不能绕过邮件验证直接进入控制台。
     *
     * @param command 注册入参
     * @param client  客户端信息
     * @throws BusinessException 参数不合法、邮箱已注册或触发限流
     */
    @Transactional
    public void register(RegisterCommand command, ClientContext client) {
        if (!rateLimiter.tryAcquireRegistration(client.clientIp())) {
            throw new BusinessException(CommonErrorCode.TOO_MANY_REQUESTS);
        }

        String email = command.email().trim().toLowerCase(Locale.ROOT);
        PasswordPolicy.assertAcceptable(command.password());

        UUID accountId = Uuid7.generate();
        UUID tenantId = tenantProvisioning.createTenant(defaultTenantName(command, email));

        try {
            accountRepository.create(new Account(
                    accountId,
                    email,
                    passwordEncoder.encode(command.password()),
                    defaultDisplayName(command, email),
                    Account.Status.ACTIVE,
                    // lastLoginAt / failedLoginAttempts / lockedUntil / emailVerifiedAt。
                    // 最后一个为 null 是刻意的：注册这一刻邮箱还没被证实，
                    // 证实发生在用户点开验证链接之后（ADR 0013）
                    null, 0, null, null));
        } catch (DuplicateKeyException e) {
            // 唯一索引仲裁的结果。注意这里**整个事务会回滚**，包括上面刚建的租户 ——
            // 这正是要的行为，否则每一次重复注册都会留下一个空租户
            log.warn("注册失败：邮箱已被占用 email={}", email);
            throw new BusinessException(IamErrorCode.EMAIL_ALREADY_REGISTERED);
        }

        // 注册者必须归属于刚创建的租户。ADR 0012 后租户层不再承载角色，
        // 这里只建立登录时还原租户上下文所需的归属关系
        accountRepository.addMembership(Uuid7.generate(), tenantId, accountId);

        // 签发验证令牌并发布事件。真正的发信由 EmailVerificationMailer 在
        // 事务提交后异步进行 —— 上面任何一步失败导致回滚时，信不会发出去
        emailVerificationService.issue(
                accountId, email, EmailVerificationPurpose.REGISTER_VERIFY, client.clientIp());

        log.info("注册成功 accountId={} tenantId={}", accountId, tenantId);

    }

    /**
     * 推导默认显示名。
     *
     * @param command 注册入参
     * @param email   已归一化的邮箱
     * @return 显示名
     */
    private static String defaultDisplayName(RegisterCommand command, String email) {
        if (command.displayName() != null && !command.displayName().isBlank()) {
            return command.displayName().trim();
        }
        return localPartOf(email);
    }

    /**
     * 推导默认租户名。
     *
     * <p>注册表单暂时不收「组织名」，先从显示名或邮箱推导。
     * 待范围裁决（ARCHITECTURE_GAPS.md GAP-TODO-07）： 项目功能落地后，租户名应当可以在设置里修改 —— 现在还没有那个页面，
     * 提前收一个用户改不了的字段只会让人填错。
     *
     * @param command 注册入参
     * @param email   已归一化的邮箱
     * @return 租户名
     */
    private static String defaultTenantName(RegisterCommand command, String email) {
        return defaultDisplayName(command, email) + "的空间";
    }

    /**
     * 取邮箱 {@code @} 之前的部分。
     *
     * @param email 邮箱
     * @return 本地部分；没有 {@code @} 时返回原串
     */
    private static String localPartOf(String email) {
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }

}
