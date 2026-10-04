package com.things.link.bootstrap.integration;

import com.things.link.device.domain.DeviceMqttConnectionMaintenanceRepository;
import com.things.link.device.domain.DeviceMqttConnectionRepository;
import com.things.link.device.domain.DeviceMqttConnectionRepository.Scope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.TestPropertySource;

import java.sql.DriverManager;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR0200实际受限SQL维护，调度关闭仅为精确观察批次，自动调度另行验证。 */
@TestPropertySource(properties="things-link.device.mqtt-connection-maintenance.enabled=false")
class MqttConnectionMaintenanceTests extends WebhookFixture {
    /** 生产维护入口，在真实应用角色执行。 */
    @Autowired DeviceMqttConnectionMaintenanceRepository maintenance;
    /** 原票据受理及ACL入口。 */
    @Autowired DeviceMqttConnectionRepository ledger;
    /** 本例固定会话范围。 */
    Scope scope;
    @BeforeEach void init() { scope = new Scope(tenant, project, device, 1, 0, "tc-device-" + "a".repeat(64)); }
    @AfterEach void clean() {
        for (String table : new String[]{"dev_connection", "dev_mqtt_connection_ticket", "dev_mqtt_session_cursor"})
            owner.update("DELETE FROM " + table + " WHERE project_id=?", project);
    }
    /** 每轮最多500条，未过期的PENDING不受影响。 */
    @Test void drainsExpiredTicketsInBoundedBatches() {
        expired(1001);
        var pending = tx.execute(s -> ledger.issue(scope).orElseThrow()); // 签发入口先限量清500，尚余501旧票据。
        assertThat(maintenance.cleanExpired()).isEqualTo(500);
        assertThat(rows("dev_mqtt_connection_ticket")).isEqualTo(2);
        assertThat(maintenance.cleanExpired()).isEqualTo(1);
        assertThat(tx.<Boolean>execute(s -> ledger.permits(scope, pending.id()))).isTrue();
    }
    /** ACTIVE即使过了原签发期限也保留，控制关闭后才可清理。 */
    @Test void activeFactProtectsTicketUntilControlClosesConnection() {
        UUID id = expiredActive();
        cursor(id);
        assertThat(maintenance.cleanExpired()).isZero();
        owner.update("UPDATE dev_connection SET disconnected_at=clock_timestamp() WHERE mqtt_connection_id=?", id);
        assertThat(maintenance.cleanExpired()).isEqualTo(2);
        assertThat(rows("dev_mqtt_connection_ticket")).isZero(); assertThat(rows("dev_mqtt_session_cursor")).isZero();
        assertThat(rows("dev_connection")).isEqualTo(1);
        assertThat(tx.execute(s -> ledger.connected(scope, id, null, null)).accepted()).isFalse();
    }
    /** 保留任何旧票据时，下界不能删除；过期墓碑删除后未知UUID仍拒绝。 */
    @Test void cursorSurvivesWhileAnyOlderTicketRemains() {
        var old = tx.execute(s -> ledger.issue(scope).orElseThrow());
        UUID current = expiredActive(); cursor(current);
        tx.execute(s -> ledger.disconnected(scope, current, "normal"));
        assertThat(maintenance.cleanExpired()).isEqualTo(1);
        assertThat(rows("dev_mqtt_session_cursor")).isEqualTo(1);
        assertThat(tx.execute(s -> ledger.connected(scope, old.id(), null, null)).accepted()).isFalse();
    }
    /** 签发入口先行删除后遗留的无依赖游标也会由维护回收。 */
    @Test void orphanCursorIsCollectedWithoutReusingOldIdentity() {
        UUID current = expiredActive(); cursor(current);
        owner.update("UPDATE dev_connection SET disconnected_at=clock_timestamp() WHERE mqtt_connection_id=?", current);
        owner.update("DELETE FROM dev_mqtt_connection_ticket WHERE id=?", current);
        assertThat(maintenance.cleanExpired()).isEqualTo(1);
        assertThat(rows("dev_mqtt_session_cursor")).isZero();
        assertThat(tx.execute(s -> ledger.connected(scope, current, null, null)).accepted()).isFalse();
        var fresh=tx.execute(s -> ledger.issue(scope).orElseThrow());
        assertThat(tx.execute(s -> ledger.connected(scope, fresh.id(), null, null)).accepted()).isTrue();
    }
    /** 业务设备锁繁忙时跳过，不在维护线程等待；解锁后收敛。 */
    @Test void busyDeviceIsSkippedAndRetried() throws Exception {
        expired(1);
        try (var connection=DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
             var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            connection.setAutoCommit(false);
            try(var query=connection.prepareStatement("SELECT id FROM dev_device WHERE id=? FOR NO KEY UPDATE")) { query.setObject(1,device);query.executeQuery().close(); }
            assertThat(executor.submit(maintenance::cleanExpired).get(3,TimeUnit.SECONDS)).isZero();
            connection.rollback();
        }
        assertThat(maintenance.cleanExpired()).isEqualTo(1);
    }
    /** 行锁故障注入也不让维护阻塞，保持项目→设备→票据顺序。 */
    @Test void busyTicketIsSkippedAndRetried() throws Exception {
        expired(1);
        try (var connection=DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
             var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            connection.setAutoCommit(false);
            try(var query=connection.prepareStatement("SELECT id FROM dev_mqtt_connection_ticket WHERE device_id=? FOR UPDATE")) { query.setObject(1,device);query.executeQuery().close(); }
            assertThat(executor.submit(maintenance::cleanExpired).get(3,TimeUnit.SECONDS)).isZero();
            connection.rollback();
        }
        assertThat(maintenance.cleanExpired()).isEqualTo(1);
    }
    /** 双维护调用不会重复计数，也不会越过单次批次上限。 */
    @Test void concurrentMaintenanceIsBoundedAndConverges() throws Exception {
        expired(700);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=executor.submit(maintenance::cleanExpired);var second=executor.submit(maintenance::cleanExpired);
            int a=first.get(10,TimeUnit.SECONDS),b=second.get(10,TimeUnit.SECONDS);
            assertThat(a).isBetween(0,500);assertThat(b).isBetween(0,500);
            assertThat(rows("dev_mqtt_connection_ticket")).isEqualTo(700-a-b);
        }
        while(rows("dev_mqtt_connection_ticket")>0) assertThat(maintenance.cleanExpired()).isBetween(1,500);
    }
    /** 维护提交失败时全部保留，后续同一入口可恢复；PUBLIC不能调用。 */
    @Test void rollbackAndFunctionPrivilegeAreFailClosed() {
        expired(3);
        assertThatThrownBy(()->tx.execute(s->{maintenance.cleanExpired();throw new IllegalStateException("rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(rows("dev_mqtt_connection_ticket")).isEqualTo(3);
        assertThat(owner.queryForObject("SELECT count(*) FROM pg_proc p,LATERAL aclexplode(p.proacl) a WHERE p.oid='public.dev_mqtt_connection_maintenance()'::regprocedure AND a.grantee=0",Integer.class)).isZero();
        assertThat(maintenance.cleanExpired()).isEqualTo(3);
    }
    /** 已软删项目仍维护旧元数据，绝不产生新的公开来源。 */
    @Test void softDeletedProjectMetadataIsStillCollected() {
        expired(1); owner.update("UPDATE sys_project SET deleted_at=clock_timestamp(),status='DELETING' WHERE id=?", project);
        assertThat(maintenance.cleanExpired()).isEqualTo(1);assertThat(rows("sys_outbox_event")).isZero();
    }
    /** 全部非活跃票据达到容量上限时拒绝；过期回收后恢复签发。 */
    @Test void expiredCapacityRecoversThroughProductionMaintenance() {
        owner.update("""
                INSERT INTO dev_mqtt_connection_ticket(id,tenant_id,project_id,device_id,credential_version,config_version,session_id)
                SELECT gen_random_uuid(),?,?,?,1,0,? FROM generate_series(1,1024)
                """,tenant,project,device,scope.sessionId());
        assertThat(tx.<java.util.Optional<DeviceMqttConnectionRepository.Ticket>>execute(s->ledger.issue(scope))).isEmpty();
        // 不修改不可变expires_at；用独占夹具重建成已过期事实模拟时间推进。
        owner.update("DELETE FROM dev_mqtt_connection_ticket WHERE device_id=?",device);expired(1024);
        assertThat(maintenance.cleanExpired()).isEqualTo(500);
        assertThat(tx.<java.util.Optional<DeviceMqttConnectionRepository.Ticket>>execute(s->ledger.issue(scope))).isPresent();
    }
    /** 独占历史元数据，仅通过owner构造过期而不篡改生产不可变字段。 */
    private void expired(int count) {
        owner.update("""
                INSERT INTO dev_mqtt_connection_ticket(id,tenant_id,project_id,device_id,credential_version,config_version,session_id,created_at,expires_at)
                SELECT gen_random_uuid(),?,?,?,1,0,?,clock_timestamp()-interval '301 seconds',clock_timestamp()-interval '1 second' FROM generate_series(1,?)
                """,tenant,project,device,scope.sessionId(),count);
    }
    /** 原真实活跃事实保留连接UUID，过期字段不代表已断线。 */
    private UUID expiredActive() {
        expired(1);UUID id=owner.queryForObject("SELECT id FROM dev_mqtt_connection_ticket WHERE device_id=? ORDER BY auth_order DESC LIMIT 1",UUID.class,device);
        owner.update("UPDATE dev_mqtt_connection_ticket SET state='ACTIVE' WHERE id=?",id);
        owner.update("INSERT INTO dev_connection(id,tenant_id,project_id,device_id,session_id,protocol,config_version,mqtt_connection_id) VALUES(gen_random_uuid(),?,?,?,?,'MQTT',0,?)",tenant,project,device,scope.sessionId(),id);
        return id;
    }
    /** 模拟已经观察到原连接，数据库约束仍负责次序不可回退。 */
    private void cursor(UUID id) {
        owner.update("INSERT INTO dev_mqtt_session_cursor(tenant_id,project_id,device_id,config_version,session_id,max_observed_order) SELECT tenant_id,project_id,device_id,config_version,session_id,auth_order FROM dev_mqtt_connection_ticket WHERE id=?",id);
    }
}
