package com.things.link.ota.domain;

import java.util.Optional;
import java.time.Instant;
import java.util.UUID;

/** 报告当前头仓储，必须加入已锁当前设备资格的调用方事务。 */
public interface OtaDeviceReportRepository {
    /** 当前事务连接的数据库实时时钟，与执行来源捕获采用同一时间轴。 */
    Instant currentTime();
    /** 同一设备首次登记与替换的事务级范围锁。 */
    void lockRegistration(UUID tenantId, UUID projectId, UUID deviceId);
    /** 精确作用域读取，可选共享或排他锁，两者互斥。 */
    Optional<OtaDeviceReportState> find(UUID projectId, UUID deviceId, boolean exclusive, boolean shared);
    /** 创建首次事实，初始修订为一。 */
    void create(OtaDeviceReportState state);
    /** 期望修订匹配才更新，新事实仍由数据库严格检查单调性。 */
    boolean replace(long expectedRevision, OtaDeviceReportState state);
}
