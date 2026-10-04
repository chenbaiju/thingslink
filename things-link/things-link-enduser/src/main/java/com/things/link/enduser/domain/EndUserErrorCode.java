package com.things.link.enduser.domain;

import com.things.link.shared.error.ErrorCode;

/**
 * 终端用户与应用域错误码，占用 {@code 6xxxx} 段；新增前必须登记到 ERROR_CODES.md。
 *
 * <p>分段规则：1xxxx 通用、2xxxx 认证授权、3xxxx 设备、4xxxx 规则告警任务、5xxxx
 * 项目成员配额、6xxxx 终端用户与应用。终端用户是与控制台账号分离的第二类身份，
 * 其「不存在/无权」语义与控制台账号（2xxxx）不同，独立成段（ADR 0035 / 0036）。
 */
public enum EndUserErrorCode implements ErrorCode {

    /**
     * 终端用户不存在，或不属于当前项目归属租户。
     *
     * <p>跨租户的用户对项目管理员「不可见」时统一返回本码，不区分「不存在」与
     * 「在其他租户」—— 区分开会泄露别的租户里有没有这个用户名。
     */
    END_USER_NOT_FOUND(60001, "终端用户不存在", 404),

    /**
     * 当前角色无权管理终端用户。
     *
     * <p>仅项目 OWNER / ADMIN 可预置账号、分配或停用项目角色；OPERATOR / VIEWER 只读。
     * 能看到本码说明调用者已是项目成员（否则先返回 50001 项目不存在），因此不泄露信息。
     */
    END_USER_MANAGE_FORBIDDEN(60002, "当前角色无权管理终端用户", 403),

    /**
     * 用户名在该租户内已存在。
     *
     * <p>由 {@code (tenant_id, username)} 唯一约束仲裁，不先查后插（与注册邮箱冲突同一套处理）。
     */
    END_USER_USERNAME_TAKEN(60003, "用户名已存在", 409),

    /**
     * 该终端用户已在本项目拥有角色。
     *
     * <p>由 {@code (project_id, app_user_id)} 唯一约束仲裁。
     */
    END_USER_ROLE_ALREADY_ASSIGNED(60004, "该终端用户已在本项目拥有角色", 409),

    /**
     * 终端用户在本项目没有角色。
     *
     * <p>停用/恢复角色时，目标用户与项目无角色关系时统一返回本码。
     */
    END_USER_ROLE_NOT_FOUND(60005, "该终端用户在本项目没有角色", 404),

    /**
     * App 登录失败。
     *
     * <p>合并「projectKey 无效 / 用户不存在 / 口令错误 / 账号锁定 / 无有效项目角色」为同一
     * 结果，防止枚举：区分开会泄露「这个用户名是否存在」「这个项目是否存在」。
     */
    END_USER_LOGIN_FAILED(60006, "用户名或密码错误", 401),

    /**
     * App 刷新令牌无效或已失效。
     *
     * <p>覆盖缺失、伪造、过期、已撤销、被复用，以及刷新时回库复验 {@code app_user} /
     * {@code app_user_role} 状态不通过。处置一律是重新登录。
     */
    END_USER_REFRESH_INVALID(60007, "刷新令牌无效或已过期", 401),

    /**
     * 改密时原口令不正确。
     *
     * <p>独立的 400 而非 401：调用者已持有有效访问令牌，身份已确认，只是提供的旧口令
     * 对不上。
     */
    END_USER_PASSWORD_INCORRECT(60008, "原密码不正确", 400),

    /**
     * App 访问令牌无效或已过期。
     *
     * <p>用于受 App 访问令牌保护的端点：令牌签名不合法、过期，或 subject 对应的终端用户
     * 已不存在时统一返回，不区分具体原因。
     */
    END_USER_ACCESS_INVALID(60009, "访问令牌无效或已过期", 401),

    /**
     * 设备未绑定、不存在或不属于当前项目。
     *
     * <p>App 数据面统一语义：无有效 {@code app_user_device} 绑定、设备已软删、或设备属于其他项目，
     * 都返回本码（404），不区分具体原因 —— 区分开会泄露「这个设备是否存在」「它在不在我能看到的范围」。
     */
    END_USER_DEVICE_NOT_FOUND(60010, "设备不存在或未授权", 404),

    /**
     * 设备关系角色不允许控制。
     *
     * <p>只有 {@code relation_role ∈ {PRIMARY, MEMBER}} 且 {@code status=ACTIVE} 才能下发命令；
     * {@code READ_ONLY} 关系即使项目角色是 APP_ADMIN/MAINTAINER 也不能控制（ADR 0035 两层授权矩阵）。
     */
    END_USER_DEVICE_CONTROL_FORBIDDEN(60011, "设备关系角色不允许控制", 403),

    /**
     * CLAIM 令牌不可用。
     *
     * <p>不存在、格式不匹配、过期、尝试耗尽、跨项目、用途不符及被其他用户消费统一
     * 返回本码，避免把令牌状态变成探测接口（ADR 0037、G2-A1c）。
     */
    DEVICE_CLAIM_TOKEN_INVALID(60012, "设备认领令牌无效或已过期", 400),

    /**
     * 设备已有有效主控关系。
     *
     * <p>应用层预检只提供友好失败，数据库部分唯一索引负责最终并发仲裁。
     */
    DEVICE_ALREADY_CLAIMED(60013, "设备已有主控", 409),

    /**
     * 控制台签发入口看不到目标设备。
     *
     * <p>不存在、已软删和跨项目统一返回，不能泄露其他项目设备事实。
     */
    CLAIM_DEVICE_NOT_FOUND(60014, "认领设备不存在", 404),

    /** App CLAIM 消费请求命中用户或来源 IP 固定窗口上限。 */
    DEVICE_CLAIM_RATE_LIMITED(60015, "设备认领请求过于频繁", 429),

    /** 不存在、过期、跨范围、用途错误或非本人重放的 TRANSFER 令牌统一不可用。 */
    DEVICE_TRANSFER_TOKEN_INVALID(60016, "主控转移令牌无效或已过期", 400),

    /** 签发者已不是当前 PRIMARY、接收者就是签发者或关系状态发生冲突。 */
    DEVICE_TRANSFER_CONFLICT(60017, "设备主控状态已变化", 409),

    /** App TRANSFER 消费请求命中用户或来源 IP 固定窗口上限。 */
    DEVICE_TRANSFER_RATE_LIMITED(60018, "主控转移请求过于频繁", 429),

    /** 不存在、过期、跨范围、用途错误或非本人重放的 SHARE 令牌统一不可用。 */
    DEVICE_SHARE_TOKEN_INVALID(60019, "设备共享令牌无效或已过期", 400),

    /** 签发者已非当前 PRIMARY、自共享或接收者已有有效设备关系。 */
    DEVICE_SHARE_CONFLICT(60020, "设备共享状态已变化", 409),

    /** App SHARE 消费请求命中用户或来源 IP 固定窗口上限。 */
    DEVICE_SHARE_RATE_LIMITED(60021, "设备共享请求过于频繁", 429),

    /** ADR0064：归档项目可读但禁止业务写入，403避免客户端误以为刷新令牌可恢复写资格。 */
    PROJECT_READ_ONLY(60022, "项目只读", 403),

    /** 应用、当前发布版本或项目运行事实不存在、不一致或当前不可运行。 */
    APPLICATION_RUNTIME_UNAVAILABLE(60023, "应用运行入口不可用或未授权", 404),

    /** 运行访问冻结§4.2：仅OWNER/ADMIN具备dashboard_definition:manage授权管理能力。 */
    DASHBOARD_GRANT_MANAGE_FORBIDDEN(60024, "无权管理看板授权", 403),

    /** 隐藏目标用户、项目角色、看板及授权的不存在或不可见差异。 */
    DASHBOARD_GRANT_NOT_FOUND(60025, "看板授权目标不存在或不可见", 404),

    /** 严格外层信封之外，授权状态或expectedRevision业务语法错误。 */
    DASHBOARD_GRANT_INVALID(60026, "看板授权参数不合法", 400),

    /** 旧revision与Long递增空间耗尽统一为并发冲突，不自动覆盖最新授权决定。 */
    DASHBOARD_GRANT_CONFLICT(60027, "看板授权版本已变化", 409),

    /** 浏览器会话只接受部署显式配置的同源POST，不能用Referer/Host替代。 */
    APP_BROWSER_ORIGIN_FORBIDDEN(60028, "浏览器认证来源不允许", 403),

    /** 旧Cookie与当前期望代次不匹配，禁止清Cookie或触发旧族撤销。 */
    APP_BROWSER_EPOCH_MISMATCH(60029, "浏览器会话代次已变化", 409),

    /** 独立浏览器适配默认关闭；不将数据库内部故障混同未启用。 */
    APP_BROWSER_UNAVAILABLE(60057, "浏览器认证入口未启用", 503),

    /** S14-R4b：所属租户全部终端用户身份已达到有效套餐上限。 */
    END_USER_QUOTA_EXCEEDED(60058, "终端用户数量已达套餐上限，请扩容后重试", 409);

    private final int code;
    private final String defaultMessage;
    private final int httpStatus;

    EndUserErrorCode(int code, String defaultMessage, int httpStatus) {
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
