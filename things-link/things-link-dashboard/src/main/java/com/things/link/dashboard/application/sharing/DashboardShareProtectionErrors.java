package com.things.link.dashboard.application.sharing;

import com.things.link.dashboard.domain.DashboardErrorCode;
import com.things.link.shared.error.BusinessException;

/** 分享保护公开错误工厂；ingestion通过应用合同使用错误，不跨模块导入domain枚举。 */
public final class DashboardShareProtectionErrors {
    /** 静态工厂不保留状态或凭据。 */
    private DashboardShareProtectionErrors() { }
    /** 容量超限保持分享429/60056，不伪装成能力不存在。 */
    public static BusinessException rateLimited() { return new BusinessException(DashboardErrorCode.SHARE_RATE_LIMITED); }
    /** Redis/事务首因保留给内部诊断，公开响应仍固定503/60055。 */
    public static BusinessException unavailable(Throwable cause) {
        BusinessException failure=new BusinessException(DashboardErrorCode.SHARE_DEPENDENCY_UNAVAILABLE);
        if(cause!=null)failure.initCause(cause);
        return failure;
    }
}
