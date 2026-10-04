package com.things.link.device.domain;

import org.springframework.dao.CannotAcquireLockException;

/**
 * ADR0058：数据库拓扑角色守卫未能即时取得设备或类型锁，当前事务必须回滚。
 * 保持瞬时数据库异常继承关系供数据面重试；控制面在写调用边界转换为明确的拓扑冲突响应。
 */
public final class DeviceTopologyBusyException extends CannotAcquireLockException {

    /** @param cause 保留服务器 SQLSTATE 与约束标记，便于核验真正的锁冲突来源 */
    public DeviceTopologyBusyException(Throwable cause) {
        super("拓扑正在变更，请稍后重试", cause);
    }
}
