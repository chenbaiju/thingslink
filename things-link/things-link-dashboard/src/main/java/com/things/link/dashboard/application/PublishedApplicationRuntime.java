package com.things.link.dashboard.application;

import java.util.Objects;

/**
 * 可信项目RLS范围内重新确认的当前应用公开事实。
 *
 * <p>终端运行编排只能把{@code appKey}和{@code displayName}映射到公开resolve响应；内部身份用于与
 * project端口交叉复核，不得直接暴露给匿名客户端。</p>
 *
 * @param identity 已重新确认的应用归属身份
 * @param appKey 创建后不可变的公开定位符
 * @param displayName 当前不可变应用版本中的公开展示名称
 */
public record PublishedApplicationRuntime(
        ApplicationRuntimeIdentity identity, String appKey, String displayName) {

    /** 拒绝跨模块构造残缺运行投影。 */
    public PublishedApplicationRuntime {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(appKey, "appKey");
        Objects.requireNonNull(displayName, "displayName");
    }
}
