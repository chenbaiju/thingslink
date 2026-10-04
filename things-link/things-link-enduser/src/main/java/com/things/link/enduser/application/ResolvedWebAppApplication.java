package com.things.link.enduser.application;

import java.util.Objects;

/**
 * WebApp 公开定位完成后的最小展示结果。
 *
 * <p>S12-2a2a只返回登录前展示所需的公开键与名称；内部租户、项目、应用标识及发布事实
 * 只参与服务端交叉复核，不能穿透到后续HTTP响应。</p>
 *
 * @param appKey 应用公开稳定键
 * @param displayName 当前不可变发布版本中的展示名称
 * @param projectKey 项目公开稳定键
 */
public record ResolvedWebAppApplication(String appKey, String displayName, String projectKey) {

    /** 最小公开投影不接受空值，避免内部端口缺陷生成语义不完整的成功结果。 */
    public ResolvedWebAppApplication {
        Objects.requireNonNull(appKey, "appKey");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(projectKey, "projectKey");
    }
}
