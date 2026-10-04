package com.things.link.dashboard.application.publication;

import java.util.Objects;

/**
 * 发布候选从组件描述符派生的精确宿主组件清单项。
 *
 * @param kind 组件kind机器值
 * @param componentVersion 精确组件版本
 */
public record DashboardRequiredComponent(String kind, String componentVersion) {
    /** 冻结非空清单字段；字段语法已由完整Schema校验器证明。 */
    public DashboardRequiredComponent {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(componentVersion, "componentVersion");
    }
}
