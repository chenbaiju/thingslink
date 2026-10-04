package com.things.link.export.domain;

import java.time.Instant;

/**
 * 下载任务锁与采用对象保护在同一数据库语句中提交的结果。
 * @param job 已锁定的成功任务
 * @param downloadExpiresAt 由数据库时钟产生的五分钟URL截止时刻
 */
public record ProjectExportDownloadClaim(ProjectExportJob job, Instant downloadExpiresAt) {

    /** 任务和数据库截止时刻必须同时存在。 */
    public ProjectExportDownloadClaim {
        if (job == null || downloadExpiresAt == null) {
            throw new IllegalArgumentException("项目导出下载锁结果不完整");
        }
    }
}
