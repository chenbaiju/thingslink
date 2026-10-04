package com.things.link.device.domain;

/** 固定数据库维护能力，不接受请求自报租户或删除截止点。 */
public interface DeviceMqttConnectionMaintenanceRepository {
    /** 每轮最多清理500条非活跃过期票据及无依赖游标，返回实际删除行数。 */
    int cleanExpired();
}
