package com.things.link.iam.application;

import com.things.link.iam.domain.Account;
import com.things.link.iam.domain.AccountRepository;
import com.things.link.iam.domain.EmailVerificationPurpose;
import com.things.link.iam.domain.EmailVerificationToken;
import com.things.link.iam.domain.EmailVerificationTokenRepository;
import com.things.link.iam.domain.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;

/**
 * 忘记密码与重置密码（ADR 0013 的最后一环）。
 *
 * <h2>为什么必须先有邮箱验证</h2>
 * {@code RegistrationService} 的类注释从一开始就写着这条：邮箱未经证实时，
 * 任何人都可以用别人的邮箱注册、再走一遍重置流程，把账号「找回」到自己手里。
 * 所以本流程<b>只对 {@code email_verified_at} 非空的账号签发重置令牌</b> ——
 * 顺序不能反，这也是邮箱验证要排在忘记密码之前的全部原因。
 *
 * <h2>三件事必须一起做，缺一不可</h2>
 * <ol>
 *   <li><b>换口令</b></li>
 *   <li><b>撤销该账号的全部会话</b>。重置密码的典型场景就是「账号可能已经被别人
 *       拿到了」——只换口令而不踢掉现有会话，攻击者手里的刷新令牌照样能一直续期，
 *       这次重置等于没做</li>
 *   <li><b>作废其余未用的重置令牌</b>。攻击者可能已经触发过一次重置、手里攥着一条
 *       30 分钟内有效的链接；真用户改完口令之后，那条链接必须同时失效</li>
 * </ol>
 * 三件事在同一个事务里。少做任何一件，「重置密码」就从一个补救措施退化成一个
 * <b>看起来</b>像补救措施的动作。
 *
 * <h2>与注册验证相反：签发新链接时作废旧的</h2>
 * {@link EmailVerificationService} 对注册验证的决定是「重发不作废上一封」。
 * 那个判断在这里不成立：验证链接的副本只能把邮箱标记为已验证，而重置链接的每一个
 * 副本都是一次完整的账号接管。用户点了三次「忘记密码」再想起口令，就会留下三条
 * 可用的接管通道，而他以为这件事已经过去了。
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private final AccountRepository accountRepository;
    private final EmailVerificationService emailVerificationService;
    private final EmailVerificationTokenRepository tokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;

    public PasswordResetService(AccountRepository accountRepository,
                                EmailVerificationService emailVerificationService,
                                EmailVerificationTokenRepository tokenRepository,
                                RefreshTokenRepository refreshTokenRepository,
                                PasswordEncoder passwordEncoder) {
        this.accountRepository = accountRepository;
        this.emailVerificationService = emailVerificationService;
        this.tokenRepository = tokenRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 发起找回密码。
     *
     * <p><b>无论邮箱是否注册、是否已验证、账号是否被停用，本方法都不报错、
     * 也不返回任何差异。</b>调用方据此一律返回 204。
     *
     * <p>这里的枚举风险比重发验证邮件那条更值得防：那条至少还只能确认「这个邮箱
     * 注册过」，而这条一旦区分开，还会连带泄露账号状态。
     *
     * <p><b>但「对外无差异」不等于「什么都不做」。</b>邮箱尚未验证时会改发一封验证邮件
     * （见方法体注释）：响应仍是同一个 204，而真正持有该邮箱的人拿到了可执行的下一步。
     * 这是唯一能同时满足「不泄露」与「不把用户堵死」的做法。
     *
     * @param email     目标邮箱
     * @param requestIp 请求来源 IP，可为 {@code null}
     */
    @Transactional
    public void requestReset(String email, String requestIp) {
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);

        Account account = accountRepository.findByEmail(normalizedEmail).orElse(null);
        if (account == null) {
            log.info("找回密码：邮箱未注册，跳过 emailDomain={}", domainOf(normalizedEmail));
            return;
        }
        if (!account.isEmailVerified()) {
            // 未验证的邮箱不能拿到重置令牌：那正是「用别人的邮箱注册再把账号找回来」的入口。
            //
            // 但**也不能什么都不做**。什么都不做的话，「注册了却没验证邮箱」的人就走进了
            // 死路：他忘了口令进不去，申请找回又收不到任何东西，而界面上那句
            // 「如果已注册且已验证，链接已发出」既不能确认也不能否认 —— 他无从知道
            // 卡在哪一步，也没有任何可做的下一步。
            //
            // 所以改发一封**验证邮件**。这解开了死路而不泄露任何东西：
            //   - 对外的响应仍然是同一个 204，试探者什么也看不出来
            //   - 只有真正持有这个邮箱的人才收得到，而他本来就有权知道自己没验证
            // 验证完之后再申请一次找回，就走上正常路径了。
            log.info("找回密码：邮箱未验证，改发验证邮件 accountId={}", account.id());
            emailVerificationService.issue(
                    account.id(), account.email(), EmailVerificationPurpose.REGISTER_VERIFY, requestIp);
            return;
        }
        if (!account.canAuthenticate()) {
            // 被管理员停用的账号不该能自助恢复访问。临时锁定（locked_until）不在此列 ——
            // 那恰恰是「忘了口令一直试」的典型结果，重置正是它的正常出口
            log.info("找回密码：账号不可登录，跳过 accountId={} status={}", account.id(), account.status());
            return;
        }

        // 先作废旧的，再签发新的。顺序反过来的话，新令牌会被自己这一步作废掉，
        // 用户收到一封点开就报「链接无效」的信 —— 而日志里一切正常
        int invalidated = tokenRepository.invalidateOutstanding(
                account.id(), EmailVerificationPurpose.RESET_PASSWORD, Instant.now());
        if (invalidated > 0) {
            log.info("找回密码：作废了 {} 条尚未使用的重置令牌 accountId={}", invalidated, account.id());
        }

        emailVerificationService.issue(
                account.id(), account.email(), EmailVerificationPurpose.RESET_PASSWORD, requestIp);
    }

    /**
     * 用重置令牌设置新口令。
     *
     * @param rawToken    令牌明文。允许为 {@code null}，与令牌无效同样报 20021
     * @param newPassword 新口令明文
     * @throws com.things.link.shared.error.BusinessException 令牌无效/过期/已用过（20021）、
     *                                                        或口令强度不足（20011）
     */
    @Transactional
    public void reset(String rawToken, String newPassword) {
        // 先校验口令强度，再消费令牌。
        //
        // 顺序反了的话，用户提交一个太短的口令就会把唯一的重置链接消耗掉 ——
        // 他得到一个「口令太短」的提示，然后发现链接也失效了，只能重新走一遍找回流程。
        // 这是实打实的体验缺陷，不是理论问题：口令输错是最常见的一种失败
        PasswordPolicy.assertAcceptable(newPassword);

        EmailVerificationToken token = emailVerificationService.consume(
                rawToken, EmailVerificationPurpose.RESET_PASSWORD);
        Instant now = Instant.now();

        accountRepository.updatePassword(token.accountId(), passwordEncoder.encode(newPassword));

        // 踢掉全部会话，包括用户自己的。他刚设了新口令，重新登一次是预期之内的事；
        // 而如果账号确实被人拿到了，这一步才是重置真正起作用的地方
        int revokedSessions = refreshTokenRepository.revokeAllForAccount(token.accountId(), now);

        // 作废其余未用的重置令牌。攻击者手里可能还攥着一条
        int invalidatedTokens = tokenRepository.invalidateOutstanding(
                token.accountId(), EmailVerificationPurpose.RESET_PASSWORD, now);

        log.info("密码重置成功 accountId={} tokenId={} 撤销会话={} 作废令牌={}",
                token.accountId(), token.id(), revokedSessions, invalidatedTokens);
    }

    /**
     * 取邮箱的域名部分，用于日志。
     *
     * <p>不记完整邮箱：这是个公开接口，日志里堆满它收到的地址等于攒出一份
     * 「有人试图找回」的清单，而其中一部分正是真实注册用户。
     *
     * @param email 邮箱
     * @return {@code @} 之后的部分；没有 {@code @} 时返回占位符
     */
    private static String domainOf(String email) {
        int at = email.indexOf('@');
        return at >= 0 ? email.substring(at) : "(无效)";
    }

}
