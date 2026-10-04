package com.things.link.rule.application.engine;

import java.util.List;

/**
 * 节点配置校验结果；错误只描述固定配置路径，不携带消息载荷或租户敏感值。
 *
 * @param valid 是否可执行
 * @param errors 有界的配置错误列表
 */
public record RuleNodeValidation(boolean valid, List<String> errors) {

    /** 固化错误集合，避免校验完成后被调用方篡改。 */
    public RuleNodeValidation {
        errors = errors == null ? List.of() : List.copyOf(errors);
        if (valid && !errors.isEmpty()) {
            throw new IllegalArgumentException("有效结果不能包含错误");
        }
        if (!valid && errors.isEmpty()) {
            throw new IllegalArgumentException("无效结果必须包含错误");
        }
    }

    /** 创建无错误的有效结果。 */
    public static RuleNodeValidation success() {
        return new RuleNodeValidation(true, List.of());
    }

    /** 创建包含一个固定错误的无效结果。 */
    public static RuleNodeValidation invalid(String error) {
        return new RuleNodeValidation(false, List.of(error));
    }
}
