package com.things.link.dashboard.domain;

import com.things.link.shared.error.ErrorCode;

/**
 * 应用目录、草稿与版本生命周期管理的稳定业务错误。
 *
 * <p>S12-1a2b登记草稿管理失败，S12-1c2补充无HTTP发布失败；项目不可见与项目只读继续沿用
 * project领域错误，避免同一项目状态在不同业务模块产生不一致分类。</p>
 */
public enum ApplicationErrorCode implements ErrorCode {

    /** 应用不存在、跨项目、已软删除或对当前项目成员不可见。 */
    APPLICATION_NOT_FOUND(60030, "应用不存在或不可见", 404),

    /** 当前项目成员不是可以管理应用的OWNER或ADMIN。 */
    APPLICATION_MANAGEMENT_FORBIDDEN(60031, "当前角色无权管理应用", 403),

    /** 应用草稿原文、revision语法或封闭结构违反冻结合同。 */
    APPLICATION_DRAFT_INVALID(60032, "应用草稿不合法", 400),

    /** 草稿revision不一致或已经耗尽Long递增空间。 */
    APPLICATION_DRAFT_CONFLICT(60033, "应用草稿revision冲突", 409),

    /** 应用草稿、精确看板引用或聚合快照当前不满足发布条件。 */
    APPLICATION_PUBLICATION_INVALID(60043, "应用当前不满足发布条件", 400),

    /** 应用草稿或发布revision已变化，或发布计数已经耗尽。 */
    APPLICATION_PUBLICATION_CONFLICT(60044, "应用发布状态已变化", 409),

    /** 受管宿主描述符缺失或当前不能满足完整应用需求。 */
    APPLICATION_PUBLICATION_DEPENDENCY_UNAVAILABLE(60045, "应用发布依赖暂不可用", 503),

    /** 回滚目标不存在、跨项目、跨应用或不属于可见历史。 */
    APPLICATION_ROLLBACK_TARGET_NOT_FOUND(60046, "应用回滚目标不存在", 404),

    /** 可见应用下的普通历史版本不存在、跨项目或归属错误。 */
    APPLICATION_VERSION_NOT_FOUND(60048, "应用版本不存在或不可见", 404);

    /** 对外稳定业务码。 */
    private final int code;

    /** 不包含内部实现细节的默认中文消息。 */
    private final String defaultMessage;

    /** 与错误语义对应的HTTP状态码，供后续接口层统一转换。 */
    private final int httpStatus;

    /**
     * 创建应用领域错误。
     *
     * @param code 稳定业务码
     * @param defaultMessage 默认中文消息
     * @param httpStatus HTTP状态码
     */
    ApplicationErrorCode(int code, String defaultMessage, int httpStatus) {
        this.code = code;
        this.defaultMessage = defaultMessage;
        this.httpStatus = httpStatus;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public int code() {
        return code;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public String defaultMessage() {
        return defaultMessage;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public int httpStatus() {
        return httpStatus;
    }
}
