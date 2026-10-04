package com.things.link.iam.domain;

import com.things.link.shared.error.ErrorCode;

/**
 * 认证与授权错误码，占 {@code 2xxxx} 段（架构文档 11.1）。
 *
 * <p>新增前必须先登记进 {@code docs/ERROR_CODES.md}。
 *
 * <h2>为什么登录失败不区分「账号不存在」与「口令错误」</h2>
 * 两者都返回 {@link #INVALID_CREDENTIALS}。区分开会形成**账号枚举漏洞**：
 * 攻击者可以用任意邮箱试探，从不同的错误码判断该邮箱是否已注册，
 * 进而得到一份有效账号清单用于撞库或钓鱼。
 *
 * <p>代价是用户拼错邮箱时得不到精确提示。这个取舍在所有正经系统里都是这么做的。
 */
public enum IamErrorCode implements ErrorCode {

    /** 邮箱或口令错误。刻意不区分二者，见类注释。 */
    INVALID_CREDENTIALS(20001, "邮箱或口令错误", 401),

    /** 账号被管理员停用。与凭据错误区分开：这是账号状态问题，重试无用。 */
    ACCOUNT_DISABLED(20002, "账号已被停用，请联系管理员", 403),

    /** 账号因连续登录失败被锁定。 */
    ACCOUNT_LOCKED(20003, "账号已被锁定，请稍后重试或联系管理员", 403),

    /**
     * 账号不隶属于任何租户。
     *
     * <p>正常流程下不会出现——注册会在同一事务内建好租户与成员关系。
     * 出现即说明数据不一致（例如租户被清理但账号残留），因此用 500 而非 4xx：
     * 这不是调用方的问题，而是服务端的问题。
     */
    NO_TENANT_MEMBERSHIP(20004, "账号未关联任何租户", 500),

    /**
     * 邮箱已被注册。注册接口专用；登录接口不得返回它，见类注释。
     *
     * <p><b>这是一个已知且被接受的信息泄露</b>：任何人都能用它探测某个邮箱是否在
     * 本平台注册过。接受它的理由是，不告知冲突会让用户完全无法自助解决 ——
     * 他不知道自己究竟注册过没有，只能反复试。限流与审计负责把探测成本抬到不划算。
     * 详见 {@code docs/ERROR_CODES.md} 该条的说明。
     */
    EMAIL_ALREADY_REGISTERED(20010, "该邮箱已被注册", 409),

    /**
     * 口令强度不足。
     *
     * <p>只校验长度，不做组合规则 —— 理由见 {@code RegistrationService} 与
     * {@code docs/ERROR_CODES.md}。
     */
    WEAK_PASSWORD(20011, "口令需为10到128个字符，且不得使用常见默认口令", 400),

    /** 令牌无效、已过期或已被撤销。 */
    INVALID_TOKEN(20020, "登录已失效，请重新登录", 401),

    /**
     * 邮箱验证链接无效、用途不符、已过期或已被使用。
     *
     * <p><b>这四种情况刻意合并成一个码。</b>区分开会告诉一个拿着随机令牌试探的人
     * 「这个令牌真实存在过」，而对真实用户来说四种情况的处置完全一样：重新获取链接。
     *
     * <p>用 400 而非 401：请求里携带的是一次性的验证令牌，不是登录凭据。
     * 返回 401 会让前端的拦截器把它当成「会话过期」而跳去登录页 ——
     * 但用户此时本来就没登录，跳过去只会让他更困惑。
     */
    INVALID_VERIFICATION_TOKEN(20021, "验证链接无效或已过期，请重新获取", 400),

    /**
     * 邮箱尚未完成验证。
     *
     * <p>只在口令已经验证通过之后返回。放在凭据校验之前会让攻击者用任意口令试探
     * 哪些邮箱已注册、哪些还没验证；放在之后，能看到这个码的人已经知道口令，
     * 此时给出明确指引能让真实用户去邮箱完成最后一步。
     */
    EMAIL_NOT_VERIFIED(20022, "邮箱尚未验证，请先前往邮箱完成验证", 403),

    /**
     * Broker → 应用的回调端点鉴权失败（架构文档 8.3，S3.5-1）。
     *
     * <p>这一条的取舍与本枚举里其它几条<b>方向相反</b>，因为调用方不同：
     * 20001 / 50001 面对的是匿名的外部试探者，所以刻意含糊；本码面对的是
     * <b>我们自己运维的 EMQX 集群</b>，含糊只会伤到排查的人。
     *
     * <p>因此：<b>不返回 404 假装端点不存在。</b>隐藏的收益很低——回调路径本来
     * 就明写在 {@code deploy/emqx/base.hocon} 里；代价却很实：密钥配错时 404
     * 看起来像 URL 写错，会把排查引向完全错误的方向，而这是一个「配错了设备
     * 就全部离线、上行全部丢失」的故障，多花的每一分钟都很贵。
     *
     * <p>但响应体<b>不区分「没带凭据」与「凭据不对」</b>：区分开对运维没有帮助
     * （两种都是去核对同一个配置项），却会给探测者一个「这里确实存在共享密钥」
     * 的确认信号。
     */
    INVALID_CALLBACK_CREDENTIAL(20030, "回调凭据无效", 401);

    private final int code;
    private final String defaultMessage;
    private final int httpStatus;

    IamErrorCode(int code, String defaultMessage, int httpStatus) {
        this.code = code;
        this.defaultMessage = defaultMessage;
        this.httpStatus = httpStatus;
    }

    @Override
    public int code() {
        return code;
    }

    @Override
    public String defaultMessage() {
        return defaultMessage;
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }

}
