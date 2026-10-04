package com.things.link.bootstrap.integration;

import com.things.link.device.application.DeviceProjectCleanupContributor;
import com.things.link.project.application.ProjectCleanupClaim;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 最新迁移下受限DEVICE贡献，不能借设备级联删除超过固定批次。 */
class MqttConnectionProjectCleanupTests extends AbstractIntegrationTest {
    @Autowired DeviceProjectCleanupContributor cleanup;
    @Autowired TransactionTemplate tx;
    JdbcTemplate owner;
    final List<Fixture> fixtures = new ArrayList<>();
    private record Fixture(UUID tenant, UUID project, UUID device, UUID token) { }
    @BeforeEach void setup() { owner=new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword())); }
    @AfterEach void clean() { for(var f:fixtures){owner.update("DELETE FROM sys_project WHERE id=?",f.project());owner.update("DELETE FROM sys_tenant WHERE id=?",f.tenant());} }
    /** 票据501+游标1分批先清，其他项目及父设备不被早删。 */
    @Test void ticketsAndCursorAreBoundedBeforeParentCleanup() {
        var first=seed(501);var neighbor=seed(2);
        assertThat(tx.execute(s->cleanup.clean(claim(first))).deletedRows()).isEqualTo(500);
        assertThat(count(first,"dev_mqtt_connection_ticket")).isEqualTo(1);assertThat(count(neighbor,"dev_mqtt_connection_ticket")).isEqualTo(2);
        assertThat(count(first,"dev_device")).isEqualTo(1);
        assertThat(tx.execute(s->cleanup.clean(claim(first))).deletedRows()).isEqualTo(1);
        assertThat(tx.execute(s->cleanup.clean(claim(first))).deletedRows()).isEqualTo(1);
        assertThat(count(first,"dev_mqtt_session_cursor")).isZero();
        boolean complete=false;
        for(int i=0;i<8;i++){var result=tx.execute(s->cleanup.clean(claim(first)));assertThat(result.deletedRows()).isBetween(0,500);if(result.complete()){complete=true;break;}}
        assertThat(complete).isTrue();assertThat(count(first,"dev_device")).isZero();assertThat(count(neighbor,"dev_device")).isEqualTo(1);
    }
    /** 错租户/令牌/代次/阶段及过期租约不能删除任何票据。 */
    @Test void leaseAndScopeAreCheckedBeforeAnyDeletion() {
        var f=seed(2);var normal=claim(f);
        for(var bad:List.of(new ProjectCleanupClaim(UUID.randomUUID(),f.project(),1,"DEVICE",f.token(),normal.leaseUntil(),false),
                new ProjectCleanupClaim(f.tenant(),f.project(),2,"DEVICE",f.token(),normal.leaseUntil(),false),
                new ProjectCleanupClaim(f.tenant(),f.project(),1,"DEVICE",UUID.randomUUID(),normal.leaseUntil(),false)))
            assertThatThrownBy(()->tx.execute(s->cleanup.clean(bad))).isInstanceOf(DataAccessException.class);
        owner.update("UPDATE sys_project SET cleanup_stage='OTA' WHERE id=?",f.project());
        assertThatThrownBy(()->tx.execute(s->cleanup.clean(normal))).isInstanceOf(DataAccessException.class);
        owner.update("UPDATE sys_project SET cleanup_stage='DEVICE',cleanup_lease_until=clock_timestamp()-interval '1 second' WHERE id=?",f.project());
        assertThatThrownBy(()->tx.execute(s->cleanup.clean(normal))).isInstanceOf(DataAccessException.class);
        assertThat(count(f,"dev_mqtt_connection_ticket")).isEqualTo(2);
    }
    /** 外层失败回滚票据删除，旧绕过入口及PUBLIC均不可直接执行。 */
    @Test void outerRollbackAndRestrictedOldEntry() {
        var f=seed(3);
        assertThatThrownBy(()->tx.execute(s->{cleanup.clean(claim(f));throw new IllegalStateException("rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(count(f,"dev_mqtt_connection_ticket")).isEqualTo(3);
        assertThat(owner.queryForObject("SELECT has_function_privilege('thingslink_app','public.dev_project_cleanup_before_mqtt_connection(uuid,uuid,bigint,uuid)','EXECUTE')",Boolean.class)).isFalse();
        assertThat(owner.queryForObject("SELECT count(*) FROM pg_proc p,LATERAL aclexplode(p.proacl) a WHERE p.oid='public.dev_project_cleanup_batch(uuid,uuid,bigint,uuid)'::regprocedure AND a.grantee=0",Integer.class)).isZero();
    }
    /** 等待票据行锁期间租约过期，删除后重验必须让整批回滚。 */
    @Test void leaseExpiryDuringDeleteRollsBackBatch() throws Exception {
        var f=seed(2);
        try(var connection=java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
            var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            connection.setAutoCommit(false);
            try(var statement=connection.prepareStatement("SELECT id FROM dev_mqtt_connection_ticket WHERE project_id=? FOR UPDATE")) {
                statement.setObject(1,f.project());statement.executeQuery().close();
            }
            owner.update("UPDATE sys_project SET cleanup_lease_until=clock_timestamp()+interval '3 seconds' WHERE id=?",f.project());
            var result=executor.submit(()->{try{tx.execute(s->cleanup.clean(claim(f)));return (RuntimeException)null;}catch(RuntimeException failure){return failure;}});
            try {
                org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).until(()->owner.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE query LIKE 'SELECT * FROM public.dev_project_cleanup_batch%' AND wait_event_type='Lock')",Boolean.class));
                org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(()->owner.queryForObject(
                    "SELECT cleanup_lease_until<clock_timestamp() FROM sys_project WHERE id=?",Boolean.class,f.project()));
            } finally { connection.rollback(); }
            assertThat(result.get(10,java.util.concurrent.TimeUnit.SECONDS)).isInstanceOf(DataAccessException.class);
        }
        assertThat(count(f,"dev_mqtt_connection_ticket")).isEqualTo(2);
    }
    /** 构造真实PURGING项目与DEVICE租约，所有表仍受生产约束。 */
    private Fixture seed(int count) {
        var f=new Fixture(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());fixtures.add(f);
        owner.update("INSERT INTO sys_tenant(id,name) VALUES(?,'mqtt-cleanup')",f.tenant());
        owner.update("""
                INSERT INTO sys_project(id,tenant_id,name,project_key,status,lifecycle_generation,deleted_at,cleanup_stage,
                  cleanup_lease_token,cleanup_lease_until,cleanup_started_at,cleanup_next_attempt_at)
                VALUES(?,?,'mqtt-cleanup',?,'PURGING',1,now()-interval '31 days','DEVICE',?,clock_timestamp()+interval '5 minutes',now(),now())
                """,f.project(),f.tenant(),"m"+f.project().toString().replace("-",""),f.token());
        owner.update("INSERT INTO dev_device(id,tenant_id,project_id,device_key,name) VALUES(?,?,?,?,'cleanup')",f.device(),f.tenant(),f.project(),"d"+f.device().toString().replace("-",""));
        owner.update("""
                INSERT INTO dev_mqtt_connection_ticket(id,tenant_id,project_id,device_id,credential_version,config_version,session_id)
                SELECT gen_random_uuid(),?,?,?,1,0,? FROM generate_series(1,?)
                """,f.tenant(),f.project(),f.device(),"tc-device-"+"a".repeat(64),count);
        owner.update("INSERT INTO dev_mqtt_session_cursor(tenant_id,project_id,device_id,config_version,session_id,max_observed_order) SELECT tenant_id,project_id,device_id,config_version,session_id,max(auth_order) FROM dev_mqtt_connection_ticket WHERE device_id=? GROUP BY tenant_id,project_id,device_id,config_version,session_id",f.device());
        return f;
    }
    private ProjectCleanupClaim claim(Fixture f) { return new ProjectCleanupClaim(f.tenant(),f.project(),1,"DEVICE",f.token(),Instant.now().plusSeconds(300),false); }
    private int count(Fixture f,String table) { return owner.queryForObject("SELECT count(*) FROM "+table+" WHERE project_id=?",Integer.class,f.project()); }
}
