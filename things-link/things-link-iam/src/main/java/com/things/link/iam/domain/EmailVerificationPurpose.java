package com.things.link.iam.domain;

import java.time.Duration;

/**
 * 邮箱验证令牌的用途。
 *
 * <h2>为什么用途必须是令牌的一部分，而不是「反正都是有效令牌」</h2>
 * 不区分的话，一封「验证你的邮箱」的链接可以被直接拿去重置密码。这两类令牌的
 * 暴露面差得很远：验证邮件有效期 24 小时，用户可能转发、可能长期留在邮件历史里；
 * 而重置密码的有效期只有 30 分钟，正是因为它的权限高得多。混用等于让高权限令牌
 * 继承低权限令牌的生命周期和暴露面。
 *
 * <p>取值必须与迁移 {@code V20260803_0300} 里的 CHECK 约束一一对应，改一处要改两处。
 */
public enum EmailVerificationPurpose {

    /**
     * 注册后证实邮箱。
     *
     * <p>24 小时：用户可能当天没看邮箱，明天才想起来点。这个令牌只能把邮箱标记为
     * 已验证，拿不到任何额外权限，所以给它一个宽松的窗口是划算的。
     */
    REGISTER_VERIFY(Duration.ofHours(24)),

    /**
     * 重置密码。
     *
     * <p>30 分钟：它能直接改掉账号口令，等价于一次账号接管。链接在邮箱里躺得越久，
     * 被顺手翻到的机会越大 —— 共用电脑、没锁屏的手机、被入侵的邮箱都算。
     * 短到用户「收到就去点」，是这里唯一合理的取舍。
     *
     * <p>再次申请由 {@code PasswordResetService} 先作废既有 RESET_PASSWORD 令牌再签发；
     * 重置成功也作废其余未用令牌。注册验证重发的规则与此不同。
     */
    RESET_PASSWORD(Duration.ofMinutes(30)),

    /**
     * 修改邮箱时证实新地址。
     *
     * <p>2 小时。比注册验证短：改邮箱是用户正坐在电脑前主动发起的操作，
     * 不存在「明天才想起来」的场景；而它一旦被滥用就是把账号的找回入口
     * 挪到攻击者的邮箱上，风险接近重置密码。
     *
     * <p>待合同冻结（ARCHITECTURE_GAPS.md GAP-TODO-05）： 修改邮箱流程本身尚未实现，本项只是把取值定下来，
     * 避免将来加的时候顺手塞进 REGISTER_VERIFY。
     */
    CHANGE_EMAIL(Duration.ofHours(2));

    private final Duration ttl;

    EmailVerificationPurpose(Duration ttl) {
        this.ttl = ttl;
    }

    /**
     * 该用途的令牌有效期。
     *
     * <p>放在枚举上而不是配置里：这几个值是<b>安全取舍</b>而非部署参数。
     * 做成可配置项，就等于允许某个环境把重置密码的有效期调成 7 天，
     * 而那个改动不会经过任何评审。要改就改这里，连同上面的理由一起改。
     *
     * @return 有效期
     */
    public Duration ttl() {
        return ttl;
    }

}
