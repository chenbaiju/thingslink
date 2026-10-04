package com.things.link.dashboard.application.publication;

import java.util.Objects;

/**
 * 发布候选从IMAGE组件派生的精确内置资源清单项。
 *
 * @param resourceId 宿主内置公开资源标识
 * @param digest 资源字节SHA-256
 */
public record DashboardRequiredResource(String resourceId, String digest) {
    /** 冻结非空清单字段；字段语法已由完整Schema校验器证明。 */
    public DashboardRequiredResource {
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(digest, "digest");
    }
}
