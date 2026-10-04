package com.things.link.support.tenant;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/** 根据当前入口的工作负载选择 CONTROL 或 DATA 物理池。 */
final class WorkloadRoutingDataSource extends AbstractRoutingDataSource {
    /** @return 当前线程路由键；默认值由上下文固定为 CONTROL */
    @Override
    protected Object determineCurrentLookupKey() {
        return DatabaseWorkloadContext.current();
    }
}
