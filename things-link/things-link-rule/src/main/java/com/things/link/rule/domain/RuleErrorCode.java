package com.things.link.rule.domain;

import com.things.link.shared.error.ErrorCode;

/** 消息规则域错误码，沿用规则、告警与任务共享的 4xxxx 段。 */
public enum RuleErrorCode implements ErrorCode {
    /** 规则、版本不存在或不属于当前项目。 */
    RULE_NOT_FOUND(40030, "消息规则不存在", 404),
    /** 项目内有效规则名称冲突。 */
    RULE_NAME_CONFLICT(40031, "消息规则名称已存在", 409),
    /** 名称、描述或版本参数不合法。 */
    RULE_INVALID(40032, "消息规则参数不合法", 400),
    /** 非 OWNER/ADMIN 尝试修改规则事实。 */
    RULE_MANAGE_FORBIDDEN(40033, "当前角色无权管理消息规则", 403),
    /** 乐观锁、生命周期或版本归属冲突。 */
    RULE_STATE_CONFLICT(40034, "消息规则状态或版本冲突", 409),
    /** 源码未通过真实沙箱解析。 */
    RULE_SCRIPT_INVALID(40035, "消息规则脚本不合法", 400),
    /** 调试样例不是合法 JSON；大小越界由沙箱形成可追溯执行事实。 */
    RULE_DEBUG_INPUT_INVALID(40036, "规则调试输入不合法", 400),
    /** 动作节点类型未知或配置未通过确定性引擎校验，写入版本事实前 fail-closed。 */
    RULE_ACTION_INVALID(40037, "规则动作不合法", 400),

    /** 手动场景、版本不存在或不属于当前项目。 */
    SCENE_NOT_FOUND(40038, "场景不存在", 404),
    /** 项目内有效场景名称冲突。 */
    SCENE_NAME_CONFLICT(40039, "场景名称已存在", 409),
    /** 名称、说明或版本参数不合法。 */
    SCENE_INVALID(40040, "场景参数不合法", 400),
    /** 非 OWNER/ADMIN 尝试修改或执行场景事实。 */
    SCENE_MANAGE_FORBIDDEN(40041, "当前角色无权管理场景", 403),
    /** 乐观锁、生命周期、版本归属或幂等键内容冲突。 */
    SCENE_STATE_CONFLICT(40042, "场景状态或版本冲突", 409),
    /** 执行请求的 deviceId、payload 或幂等键缺失/越界。 */
    SCENE_EXECUTION_INPUT_INVALID(40043, "场景执行输入不合法", 400),
    /** 条件节点类型未知或配置未通过确定性引擎校验，写入版本事实前 fail-closed。 */
    SCENE_CONDITION_INVALID(40044, "场景条件不合法", 400),
    /** 场景非 ACTIVE、缺少活动版本或目标设备不可用，无法一键执行。 */
    SCENE_NOT_EXECUTABLE(40045, "场景不可执行", 409),
    /** 场景执行事实不存在或不属于当前项目。 */
    SCENE_EXECUTION_NOT_FOUND(40046, "场景执行不存在", 404),
    /** ADR0152自动化管理与运行事实，HTTP入口按1c-4另行启用。 */
    AUTOMATION_NOT_FOUND(40047,"自动化不存在",404),
    AUTOMATION_NAME_CONFLICT(40048,"自动化名称已存在",409),
    AUTOMATION_INVALID(40049,"自动化参数不合法",400),
    AUTOMATION_MANAGE_FORBIDDEN(40050,"当前角色无权管理自动化",403),
    AUTOMATION_STATE_CONFLICT(40051,"自动化状态或版本冲突",409),
    AUTOMATION_TRIGGER_INVALID(40052,"自动化触发配置不合法",400),
    AUTOMATION_CAPACITY_EXCEEDED(40053,"自动化容量或额度已达上限",429),
    AUTOMATION_EXECUTION_NOT_FOUND(40054,"自动化执行不存在",404),
    AUTOMATION_NOT_ENABLED(40055,"自动化尚未开通",403),
    AUTOMATION_DEPENDENCY_UNAVAILABLE(40056,"自动化依赖暂不可用",503);

    /** 稳定业务码。 */
    private final int code;
    /** 默认简体中文消息。 */
    private final String message;
    /** HTTP 状态码。 */
    private final int status;

    /** @param code 业务码 @param message 默认消息 @param status HTTP 状态 */
    RuleErrorCode(int code, String message, int status) {
        this.code = code;
        this.message = message;
        this.status = status;
    }

    /** {@inheritDoc} */
    @Override public int code() { return code; }

    /** {@inheritDoc} */
    @Override public String defaultMessage() { return message; }

    /** {@inheritDoc} */
    @Override public int httpStatus() { return status; }
}
