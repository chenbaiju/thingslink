package com.things.link.enduser.application;

import java.util.Objects;
import java.util.UUID;

/**
 * 五条App数据请求共用的精确运行版本上下文。
 *
 * @param appKey 当前应用公开键
 * @param applicationVersionId 当前应用版本ID
 * @param publicationRevision 当前应用发布代次
 * @param dashboardVersionId 当前看板版本ID
 */
public record WebAppRuntimeContext(
        String appKey, UUID applicationVersionId, long publicationRevision, UUID dashboardVersionId) {

    /** 只接受已由HTTP层完成规范语法解析的完整正代次上下文。 */
    public WebAppRuntimeContext {
        Objects.requireNonNull(appKey, "appKey");
        Objects.requireNonNull(applicationVersionId, "applicationVersionId");
        Objects.requireNonNull(dashboardVersionId, "dashboardVersionId");
        if (publicationRevision <= 0) throw new IllegalArgumentException("应用发布代次必须为正数");
    }
}
