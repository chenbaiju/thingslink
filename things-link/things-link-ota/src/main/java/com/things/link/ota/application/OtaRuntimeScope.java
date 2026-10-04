package com.things.link.ota.application;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 域内已建立RLS的事务凭证；不自行授予租约或管理权限。 */
final class OtaRuntimeScope {
    /** 精确租户。 */ private final UUID tenant;
    /** 精确项目。 */ private final UUID project;
    /** 当前物理事务资源。 */ private final Object resource;
    /** 验真连接入口。 */ private final JdbcTemplate jdbc;
    /** 创建线程，防止把凭证转交线程池。 */ private final Thread owner;

    /** 只能经当前数据库范围验真后构造。 */
    private OtaRuntimeScope(JdbcTemplate jdbc, UUID tenant, UUID project, Object resource) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.project = project;
        this.resource = resource;
        this.owner = Thread.currentThread();
    }
    /** 只验证既有scope，绝不从传入UUID设置或切换RLS。 */
    static OtaRuntimeScope require(JdbcTemplate jdbc, UUID tenant, UUID project) {
        if (tenant == null || project == null || !TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive() || jdbc.getDataSource() == null) {
            throw new IllegalStateException("OTA后台资格缺少受控事务");
        }
        Object resource = TransactionSynchronizationManager.getResource(jdbc.getDataSource());
        if (!(resource instanceof ConnectionHolder)) throw new IllegalStateException("OTA后台资格缺少绑定连接");
        OtaRuntimeScope scope = new OtaRuntimeScope(jdbc, tenant, project, resource);
        scope.assertActive();
        return scope;
    }
    /** 每次跨helper验证相同事务连接及数据库双轴范围。 */
    void assertActive() {
        if (owner != Thread.currentThread() || !TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.getResource(jdbc.getDataSource()) != resource) {
            throw new IllegalStateException("OTA后台资格事务已失效");
        }
        Boolean matches = jdbc.queryForObject("""
                SELECT current_setting('app.tenant_id',true)=? AND current_setting('app.project_id',true)=?
                """, Boolean.class, tenant.toString(), project.toString());
        if (!Boolean.TRUE.equals(matches)) throw new IllegalStateException("OTA后台资格范围不匹配");
    }
    /** 只返回验真租户。 */
    UUID tenant() { return tenant; }
    /** 只返回验真项目。 */
    UUID project() { return project; }
}
