package com.things.link.alarm.domain;

import com.things.link.shared.error.ErrorCode;

/** S6 告警域错误码；码位与 docs/ERROR_CODES.md 的 4xxxx 登记一一对应。 */
public enum AlarmErrorCode implements ErrorCode {
    /** 规则不存在、软删除或不属于当前项目。 */
    RULE_NOT_FOUND(40001, "告警规则不存在", 404),
    /** 实例不存在或不属于当前项目。 */
    INSTANCE_NOT_FOUND(40002, "告警实例不存在", 404),
    /** 项目内有效规则名称冲突。 */
    RULE_NAME_CONFLICT(40003, "告警规则名称已存在", 409),
    /** 固定白名单条件或持续时长不合法。 */
    RULE_INVALID(40004, "告警规则不合法", 400),
    /** 当前项目角色不能确认或人工清除。 */
    MAINTAIN_FORBIDDEN(40005, "当前角色无权维护告警", 403),
    /** CAS 或前置状态不匹配。 */
    INSTANCE_STATE_CONFLICT(40006, "告警实例状态不允许当前操作", 409),
    /** 通知组、收件人、模板或绑定不存在或不属于当前项目。 */
    NOTIFICATION_CONFIGURATION_NOT_FOUND(40007, "告警通知配置不存在", 404),
    /** 项目内通知配置名称或路由唯一键冲突。 */
    NOTIFICATION_CONFIGURATION_CONFLICT(40008, "告警通知配置冲突", 409),
    /** 渠道、目标地址或固定模板变量不符合安全白名单。 */
    NOTIFICATION_CONFIGURATION_INVALID(40009, "告警通知配置不合法", 400),
    /** ADR0093：通知中心参数或浏览游标不合法，不代表阅读水位。 */
    INBOX_INVALID(40010, "告警通知参数或游标不合法", 400),
    /** ADR0093：目标事件不可见或不是可阅读的ACTIVATED通知。 */
    INBOX_EVENT_NOT_FOUND(40011, "告警通知不存在", 404),
    /** ADR0093：展示窗口或浏览游标过期，客户端必须刷新。 */
    INBOX_WINDOW_EXPIRED(40012, "告警通知列表已过期，请刷新", 409);

    /** 稳定业务码。 */
    private final int code;

    /** 面向控制台的中文默认消息。 */
    private final String message;

    /** HTTP 状态类别。 */
    private final int httpStatus;

    AlarmErrorCode(int code, String message, int httpStatus) {
        this.code = code;
        this.message = message;
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
        return message;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public int httpStatus() {
        return httpStatus;
    }
}
