package com.things.link.iam.application;

import com.things.link.iam.domain.AccountRepository;
import com.things.link.iam.domain.EmailVerificationPurpose;
import com.things.link.iam.domain.EmailVerificationToken;
import com.things.link.iam.domain.EmailVerificationTokenRepository;
import com.things.link.iam.domain.IamErrorCode;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.token.OpaqueToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * 邮箱验证令牌的签发与消费（ADR 0013）。
 *
 * <h2>令牌明文只出现两次</h2>
 * 一次是 {@link #issue} 的返回值（调用方拿去拼进邮件链接），一次是用户点开链接时
 * 带回来。服务端从不持有它 —— 库里只有 SHA-256。因此<b>「重新发一遍上次那个链接」
 * 是做不到的</b>，重发只能是签发一个新的。
 *
 * <h2>为什么重发不作废上一封</h2>
 * 连点三次「重发」会得到三个同时有效的链接。不作废旧的，是因为作废需要一个诚实的
 * 表达方式：把 {@code consumed_at} 写上是撒谎（它没被使用过），把 {@code expires_at}
 * 改成现在是改写历史，两者都会让事后排查看到错的事实。
 *
 * <p>真正控制风险的是短有效期与限流，而不是作废：一个 24 小时的验证链接有几个
 * 副本，与它有一个副本，风险差别很小 —— 它们都只能把邮箱标记为已验证。
 *
 * <p><b>这个结论只适用于注册验证。</b>切片 5 重新评估后，密码重置走了相反的路：
 * 每一个有效的重置链接都是一次完整的账号接管，同时存在三个是实打实的风险。
 * 为它加了独立的 {@code invalidated_at} 列（迁移 V20260803_0400）——
 * 那一列的存在正是为了让「作废」有一个不撒谎的表达方式。见 {@code PasswordResetService}。
 *
 * <h2>本类不发邮件</h2>
 * 它只负责令牌。发信由调用方在事务提交后异步进行 —— 理由见
 * {@code com.things.link.support.notification.mail} 的 package-info：
 * 在事务里发信，回滚了也收不回来。
 */
@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);

    private final EmailVerificationTokenRepository tokenRepository;
    private final AccountRepository accountRepository;
    private final ApplicationEventPublisher eventPublisher;

    public EmailVerificationService(EmailVerificationTokenRepository tokenRepository,
                                    AccountRepository accountRepository,
                                    ApplicationEventPublisher eventPublisher) {
        this.tokenRepository = tokenRepository;
        this.accountRepository = accountRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 签发一个验证令牌。
     *
     * @param accountId 账号 ID
     * @param email     本次要证实的邮箱。<b>传的是目标邮箱而不是账号当前邮箱</b> ——
     *                  改邮箱场景下两者不同，见迁移 {@code V20260803_0300} 的列注释
     * @param purpose   用途，决定有效期
     * @param requestIp 请求来源 IP，可为 {@code null}。仅用于排查
     * @return 令牌明文与过期时刻。<b>明文只在这里出现一次</b>，调用方不拼进链接就丢了
     */
    @Transactional
    public IssuedVerification issue(UUID accountId,
                                    String email,
                                    EmailVerificationPurpose purpose,
                                    String requestIp) {
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        String rawToken = OpaqueToken.generate();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(purpose.ttl());

        EmailVerificationToken token = new EmailVerificationToken(
                Uuid7.generate(), accountId, purpose, normalizedEmail, now, expiresAt, null, null);
        tokenRepository.save(token, OpaqueToken.hash(rawToken), requestIp);

        // 只记 ID 与用途，不记明文，也不记邮箱 —— 前者等于把链接写进日志，
        // 后者在「忘记密码」场景下会让日志变成一份注册用户清单
        log.info("签发邮箱验证令牌 tokenId={} accountId={} purpose={} expiresAt={}",
                token.id(), accountId, purpose, expiresAt);

        // 事件在事务内发布，投递由 EmailVerificationMailer 推迟到提交之后。
        // 这样调用方不需要记得「发信要放在事务外」—— 忘记这件事的代价是
        // 事务回滚后用户仍收到一封指向不存在账号的验证信
        eventPublisher.publishEvent(
                new EmailVerificationRequested(accountId, normalizedEmail, purpose, rawToken, expiresAt));

        return new IssuedVerification(rawToken, expiresAt);
    }

    /**
     * 重新发送注册验证邮件。
     *
     * <p><b>无论邮箱是否存在、是否已验证，本方法都不报错也不返回任何差异。</b>
     * 调用方（接口层）据此一律返回 204。区分开就是一个账号枚举通道：任何人都能
     * 用它逐个试探哪些邮箱在本平台注册过 —— 而这个接口<b>不需要登录</b>，
     * 探测成本比注册接口的 20010 低得多。
     *
     * <p>已验证的账号同样静默跳过：给它再发一封只会让用户困惑，
     * 而「已验证」这个事实也不该泄露给不持有该邮箱的人。
     *
     * @param email     目标邮箱
     * @param requestIp 请求来源 IP，可为 {@code null}
     */
    @Transactional
    public void resendRegistrationVerification(String email, String requestIp) {
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);

        accountRepository.findByEmail(normalizedEmail).ifPresentOrElse(account -> {
            if (account.isEmailVerified()) {
                log.info("重发验证邮件：邮箱已验证，跳过 accountId={}", account.id());
                return;
            }
            issue(account.id(), account.email(), EmailVerificationPurpose.REGISTER_VERIFY, requestIp);
        }, () -> log.info("重发验证邮件：邮箱未注册，跳过 emailDomain={}", domainOf(normalizedEmail)));
    }

    /**
     * 取邮箱的域名部分，用于日志。
     *
     * <p>不记完整邮箱：这个接口是公开的，日志里堆满它收到的邮箱等于攒出一份
     * 「有人试过的地址」清单，而其中一部分正是真实注册用户。
     *
     * @param email 邮箱
     * @return {@code @} 之后的部分；没有 {@code @} 时返回占位符
     */
    private static String domainOf(String email) {
        int at = email.indexOf('@');
        return at >= 0 ? email.substring(at) : "(无效)";
    }

    /**
     * 消费一个「注册验证」令牌，并把账号邮箱标记为已验证。
     *
     * <p>两步在同一个事务里：令牌被消费了但邮箱没标记成功，用户就会看到
     * 「验证成功」而账号仍是未验证 —— 而那个链接已经用掉了，他没有第二次机会。
     *
     * @param rawToken 令牌明文。允许为 {@code null}（链接被截断、参数拼错），
     *                 与令牌无效同样报 20021
     * @return 被证实的邮箱地址，供调用方展示「xxx 已验证」
     * @throws BusinessException 令牌无效、用途不符、已过期或已被使用（一律 20021）
     */
    @Transactional
    public String verifyRegistration(String rawToken) {
        EmailVerificationToken token = consume(rawToken, EmailVerificationPurpose.REGISTER_VERIFY);
        Instant now = Instant.now();

        boolean updated = accountRepository.markEmailVerified(token.accountId(), token.email(), now);
        if (!updated) {
            // 令牌是有效的，但账号那边没更新。两种可能：账号早就验证过了（用户点了
            // 两封不同的验证邮件），或者账号邮箱在等待期间被改掉了。
            //
            // 都不报错：对用户来说「你的邮箱已经是验证过的」就是成功，
            // 报错只会让他去点第二个链接、然后同样失败。记一条日志供排查即可。
            log.info("令牌有效但账号未更新（已验证过或邮箱已变更）tokenId={} accountId={}",
                    token.id(), token.accountId());
        } else {
            log.info("邮箱验证成功 accountId={} tokenId={}", token.accountId(), token.id());
        }
        return token.email();
    }

    /**
     * 消费一个令牌。
     *
     * <p>公开是因为密码重置也要用它（{@code PasswordResetService}）——
     * 那条流程消费的是 {@code RESET_PASSWORD} 用途的令牌，
     * 之后做的事与邮箱验证完全不同，不该塞进本类。
     *
     * @param rawToken 明文
     * @param purpose  期望用途
     * @return 被消费的令牌记录
     * @throws BusinessException 令牌无效、用途不符、已过期或已被使用
     */
    public EmailVerificationToken consume(String rawToken, EmailVerificationPurpose purpose) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new BusinessException(IamErrorCode.INVALID_VERIFICATION_TOKEN);
        }
        return tokenRepository.consume(OpaqueToken.hash(rawToken), purpose, Instant.now())
                .orElseThrow(() -> {
                    // 不记明文，也不记它「像不像一个真令牌」—— 那正是试探者想知道的
                    log.warn("邮箱验证令牌无效或已失效 purpose={}", purpose);
                    return new BusinessException(IamErrorCode.INVALID_VERIFICATION_TOKEN);
                });
    }

}
