package com.things.link.dashboard.application.publication;

import java.util.Objects;
import java.util.UUID;

/**
 * 应用候选准备写入版本关系表的精确有序看板引用。
 *
 * <p>本值不含尚未分配的应用版本ID；后续受控事务将其与新应用版本身份原子绑定。</p>
 *
 * @param position 草稿dashboardRefs中的零基导航位置
 * @param dashboardId 稳定看板ID
 * @param dashboardVersionId 精确不可变看板版本ID
 */
public record ApplicationVersionDashboardReference(
        int position, UUID dashboardId, UUID dashboardVersionId) {

    /** 冻结关系表可表达的0至4位置和非空目标身份。 */
    public ApplicationVersionDashboardReference {
        Objects.requireNonNull(dashboardId, "dashboardId");
        Objects.requireNonNull(dashboardVersionId, "dashboardVersionId");
        if (position < 0 || position > 4) {
            throw new IllegalArgumentException("应用看板引用位置必须在0至4之间");
        }
    }
}
