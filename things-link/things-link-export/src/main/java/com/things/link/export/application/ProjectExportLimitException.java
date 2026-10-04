package com.things.link.export.application;

/** 导出超过ADR0075固定时间或大小上限，属于不可重试的永久失败。 */
public class ProjectExportLimitException extends RuntimeException {

    /** @param message 不含业务数据的稳定上限说明 */
    public ProjectExportLimitException(String message) {
        super(message);
    }
}
