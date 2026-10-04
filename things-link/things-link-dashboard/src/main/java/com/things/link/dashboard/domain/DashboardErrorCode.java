package com.things.link.dashboard.domain;

import com.things.link.shared.error.ErrorCode;

/**
 * 看板目录、草稿管理、外部资格与发布事务的稳定业务错误。
 *
 * <p>S12-1b2b至1b2f登记无HTTP服务已经能够裁决的失败。项目不可见与项目只读继续沿用
 * project领域错误；外部事实及依赖失败按稳定安全分类折叠，避免枚举内部能力或模型事实。</p>
 */
public enum DashboardErrorCode implements ErrorCode {

    /** 看板不存在、跨项目、已软删除或对当前项目成员不可见。 */
    DASHBOARD_NOT_FOUND(60034, "看板不存在或不可见", 404),

    /** 当前项目成员不是可以管理看板的OWNER或ADMIN。 */
    DASHBOARD_MANAGEMENT_FORBIDDEN(60035, "当前角色无权管理看板", 403),

    /** 看板草稿原文、revision语法或封闭结构违反冻结合同。 */
    DASHBOARD_DRAFT_INVALID(60036, "看板草稿不合法", 400),

    /** 草稿revision不一致或已经耗尽Long递增空间。 */
    DASHBOARD_DRAFT_CONFLICT(60037, "看板草稿revision冲突", 409),

    /** 看板声明的物模型版本或其不可变摘要不能按当前项目精确确认。 */
    DASHBOARD_MODEL_REFERENCE_INVALID(60038, "看板外部模型引用不合法", 400),

    /** 看板Schema或其当前外部事实不满足发布合同。 */
    DASHBOARD_PUBLICATION_INVALID(60039, "看板当前不满足发布条件", 400),

    /** 草稿或发布revision已变化，或任一发布计数已经耗尽。 */
    DASHBOARD_PUBLICATION_CONFLICT(60040, "看板发布状态已变化", 409),

    /** 平台宿主描述符或完整数据适配能力尚不可用。 */
    DASHBOARD_PUBLICATION_DEPENDENCY_UNAVAILABLE(60041, "看板发布依赖暂不可用", 503),

    /** 回滚目标版本不存在、跨项目、跨看板或对当前项目成员不可见。 */
    DASHBOARD_ROLLBACK_TARGET_NOT_FOUND(60042, "看板回滚目标版本不存在或不可见", 404),

    /** 管理读取的看板版本不存在、归属不符或随目录软删除而不可见。 */
    DASHBOARD_VERSION_NOT_FOUND(60047, "看板版本不存在或不可见", 404),

    /** ADR0101的分享字段、有限范围或Schema绑定不合法。 */
    SHARE_INVALID(60049, "分享创建范围不合法", 400),

    /** 签发观察的发布代次、精确版本或分享状态已经变化。 */
    SHARE_CONFLICT(60050, "看板分享状态已变化", 409),

    /** 同一看板的未撤销且未到期分享已达二十个。 */
    SHARE_LIMIT_EXCEEDED(60051, "看板活动分享已达上限", 409),

    /** 同一请求已经签发，secret不存储且不得重放；详情仅允许shareId。 */
    SHARE_SECRET_NOT_REPLAYABLE(60052, "分享已创建，凭据不能再次返回", 409),

    /** 分享或其项目、看板及精确版本不可用，不区分内部原因。 */
    SHARE_NOT_FOUND(60053, "分享不存在或不可用", 404),

    /** 匿名读取超出精确版本冻结的设备、模型或Schema范围。 */
    SHARE_SCOPE_FORBIDDEN(60054, "请求超出分享范围", 403),

    /** 分享保护或运行依赖不可用，不能伪装成不存在或空数据。 */
    SHARE_DEPENDENCY_UNAVAILABLE(60055, "分享依赖暂不可用", 503),

    /** 匿名来源、分享、项目或租户的保护预算超限。 */
    SHARE_RATE_LIMITED(60056, "分享访问过于频繁", 429),

    /** S14-R4c：所属租户未软删看板总数达到有效套餐上限。 */
    DASHBOARD_QUOTA_EXCEEDED(60059, "看板数量已达套餐上限，请扩容后重试", 409);

    /** 对外稳定业务码。 */
    private final int code;

    /** 不包含内部模型事实或实现细节的默认中文消息。 */
    private final String defaultMessage;

    /** 与错误语义对应的HTTP状态码，供后续接口层统一转换。 */
    private final int httpStatus;

    /**
     * 创建看板领域错误。
     *
     * @param code 稳定业务码
     * @param defaultMessage 默认中文消息
     * @param httpStatus HTTP状态码
     */
    DashboardErrorCode(int code, String defaultMessage, int httpStatus) {
        this.code = code;
        this.defaultMessage = defaultMessage;
        this.httpStatus = httpStatus;
    }

    /** {@inheritDoc} */
    @Override
    public int code() {
        return code;
    }

    /** {@inheritDoc} */
    @Override
    public String defaultMessage() {
        return defaultMessage;
    }

    /** {@inheritDoc} */
    @Override
    public int httpStatus() {
        return httpStatus;
    }
}
