package com.things.link.project.application;

import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.shared.error.BusinessException;

/**
 * 向项目导出编排器公开 project 域拥有的稳定错误合同。
 *
 * <p>跨模块只能依赖 application 公开类型（总体架构第 10.4 节）；本工厂阻止导出模块
 * 为复用错误码而越过边界引用 {@code project.domain}。</p>
 */
public final class ProjectExportErrors {

    /** 工具类不允许实例化。 */
    private ProjectExportErrors() {
    }

    /** @return 对外仍为 50004 的项目或导出任务不可见错误 */
    public static BusinessException notFound() {
        return new BusinessException(ProjectErrorCode.PROJECT_NOT_FOUND);
    }

    /** @return 对外仍为 50019 的对象存储额度事实不可用错误 */
    public static BusinessException storageQuotaUnavailable() {
        return new BusinessException(ProjectErrorCode.PROJECT_EXPORT_STORAGE_QUOTA_UNAVAILABLE);
    }
}
