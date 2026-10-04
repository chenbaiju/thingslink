package com.things.link.project.domain;

import java.time.Instant;

/**
 * 回收站中的项目事实。
 *
 * <p>恢复必须读取软删时刻与数据库计算的窗口截止，不能把 {@link Project} 的普通授权查询
 * 放宽到可见 {@code DELETING}。独立投影让删除事实只出现在恢复用例边界。</p>
 *
 * @param project 保留的项目基础事实，状态固定为 {@link Project.Status#DELETING}
 * @param deletedAt 项目进入删除状态的数据库时刻
 * @param restoreDeadline 固定三十天恢复窗口的截止时刻
 * @param restorable 查询时数据库墙钟是否仍严格早于截止
 */
public record DeletedProject(
        Project project,
        Instant deletedAt,
        Instant restoreDeadline,
        boolean restorable) {

    /** 删除投影必须携带完整且自洽的恢复事实，异常行不能进入应用层。 */
    public DeletedProject {
        if (project == null || project.status() != Project.Status.DELETING
                || deletedAt == null || restoreDeadline == null) {
            throw new IllegalArgumentException("回收站项目事实不完整");
        }
    }
}
