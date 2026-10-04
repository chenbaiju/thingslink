package com.things.link.device.application;

import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import java.util.Optional;

/** 已授权事务的当前通知路由，不授予设备下载或安装权限。 */
public interface OtaDeviceNotificationRoutePort {
    /** 类型→设备共享锁后核对原认证代际与有效凭据；不可见和失效均为空。 */
    Optional<OtaDeviceNotificationRoute> lockCurrent(AuthenticatedDeviceIdentity identity);
}
