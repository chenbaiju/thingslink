package com.things.link.bootstrap.device;

import com.things.link.device.application.DeviceMqttDownlinkRoutePort;
import com.things.link.device.application.DeviceMqttDownlinkRoute;
import com.things.link.shared.id.Uuid7;
import com.things.link.testing.AbstractIntegrationTest;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实PG当前MQTT下行路由许可，不用ThreadLocal管理身份替代数据面授权。 */
class DeviceMqttDownlinkRouteTests extends AbstractIntegrationTest {
    /** 生产持锁端口。 */ @Autowired private DeviceMqttDownlinkRoutePort routes;
    /** APP真实事务。 */ @Autowired private TransactionTemplate tx;
    /** 当前APP连接，用于真实锁等待与故障取证。 */ @Autowired private JdbcTemplate jdbc;
    /** 每例独占身份图。 */ private final List<Fixture> fixtures = new ArrayList<>();

    /** 无行版本0与显式正代次允许，已经冻结的旧路由不会自动升级。 */
    @Test void freezesLegacyAndExplicitMqttWithoutUpgradingOldRoute() {
        Fixture f = seed();
        var original = tx.execute(status->routes.lockCurrent(f.tenant(),f.project(),f.device()).orElseThrow());
        bind(f,"MQTT",true,7);
        var current = tx.execute(status->routes.lockCurrent(f.tenant(),f.project(),f.device()).orElseThrow());
        assertThat(original.configVersion()).isZero();
        assertThat(current.configVersion()).isEqualTo(7);
        assertThat(original.internalTopic("tc/v1/"+f.key()+"/device/down/config")).contains("/0/tc/v1/");
        assertThat(current.internalTopic("tc/v1/"+f.key()+"/device/down/config")).contains("/7/tc/v1/");
        assertThatThrownBy(()->routes.lockCurrent(f.tenant(),f.project(),f.device())).isInstanceOf(IllegalTransactionStateException.class);
    }

    /** 当前协议、启用、删除及项目冻结都必须拒绝新路由。 */
    @ParameterizedTest
    @ValueSource(strings={"HTTP","COAP","TCP","DISABLED","DELETED","ARCHIVED"})
    void rejectsUnavailableCurrentPermission(String boundary) {
        Fixture f = seed();
        switch (boundary) {
            case "DISABLED" -> bind(f,"MQTT",false,1);
            case "DELETED" -> owner().update("UPDATE dev_device SET deleted_at=clock_timestamp() WHERE id=?",f.device());
            case "ARCHIVED" -> owner().update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?",f.project());
            default -> bind(f,boundary,true,1);
        }
        assertThat(tx.<Optional<DeviceMqttDownlinkRoute>>execute(status->routes.lockCurrent(f.tenant(),f.project(),f.device()))).isEmpty();
    }

    /** 错归属、错项目及缺失设备不能按历史无行MQTT签发。 */
    @Test void rejectsWrongIdentityWithoutFallback() {
        Fixture f = seed(), other = seed();
        assertThat(tx.<Optional<DeviceMqttDownlinkRoute>>execute(status->routes.lockCurrent(other.tenant(),f.project(),f.device()))).isEmpty();
        assertThat(tx.<Optional<DeviceMqttDownlinkRoute>>execute(status->routes.lockCurrent(other.tenant(),other.project(),f.device()))).isEmpty();
        assertThat(tx.<Optional<DeviceMqttDownlinkRoute>>execute(status->routes.lockCurrent(f.tenant(),f.project(),Uuid7.generate()))).isEmpty();
    }

    /** 真实阻塞完成后读取配置写者提交的新协议。 */
    @Test void rechecksAfterDeviceLockWait() throws Exception {
        Fixture f = seed();
        var waitingPid = new CompletableFuture<Integer>();
        try (var holder=DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
                var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            holder.setAutoCommit(false);
            try {
                int blocker;
                try(var query=holder.createStatement();var rows=query.executeQuery("SELECT pg_backend_pid()")){rows.next();blocker=rows.getInt(1);}
                try(var lock=holder.prepareStatement("SELECT id FROM dev_device WHERE id=? FOR NO KEY UPDATE")){
                    lock.setObject(1,f.device());lock.executeQuery().close();
                }
                var future=pool.submit(()->tx.execute(status->{
                    waitingPid.complete(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));
                    return routes.lockCurrent(f.tenant(),f.project(),f.device());
                }));
                int waiter=waitingPid.get(5,TimeUnit.SECONDS);
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).pollInSameThread().until(()->Boolean.TRUE.equals(
                        owner().queryForObject("SELECT ?=ANY(pg_blocking_pids(?))",Boolean.class,blocker,waiter)));
                try(var insert=holder.prepareStatement("INSERT INTO dev_access_binding(device_id,tenant_id,project_id,protocol) VALUES(?,?,?,'HTTP')")){
                    insert.setObject(1,f.device());insert.setObject(2,f.tenant());insert.setObject(3,f.project());insert.executeUpdate();
                }
                holder.commit();assertThat(future.get(10,TimeUnit.SECONDS)).isEmpty();
            } finally { holder.rollback(); }
        }
    }

    /** 配置读超时是真实数据库故障，不能降级为缺行MQTT。 */
    @Test void propagatesConfigurationReadFailure() throws Exception {
        Fixture f=seed();
        try(var holder=DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword())){
            holder.setAutoCommit(false);
            try(var lock=holder.createStatement()){
                lock.execute("LOCK TABLE dev_access_binding IN ACCESS EXCLUSIVE MODE");
                assertThatThrownBy(()->tx.execute(status->{
                    jdbc.execute("SET LOCAL lock_timeout='150ms'");
                    return routes.lockCurrent(f.tenant(),f.project(),f.device());
                })).isInstanceOf(DataAccessException.class);
            } finally { holder.rollback(); }
        }
        assertThat(tx.<Optional<DeviceMqttDownlinkRoute>>execute(status->routes.lockCurrent(f.tenant(),f.project(),f.device()))).isPresent();
    }

    /** 身份只在owner播种，实际被测读取始终使用普通APP事务。 */
    private Fixture seed() {
        UUID tenant=Uuid7.generate(), project=Uuid7.generate(),device=Uuid7.generate();
        Fixture f=new Fixture(tenant,project,device,"route_"+project.toString().replace("-",""));fixtures.add(f);
        owner().update("INSERT INTO sys_tenant(id,name) VALUES(?,'MQTT路由测试')",tenant);
        owner().update("INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES(?,?,'MQTT路由测试','sh-1',?)",project,tenant,f.key());
        owner().update("INSERT INTO dev_device(id,tenant_id,project_id,device_key,name) VALUES(?,?,?,'device','MQTT路由测试')",device,tenant,project);
        return f;
    }

    /** 当前配置夹具不伪装成管理HTTP或Broker认证。 */
    private void bind(Fixture f,String protocol,boolean enabled,long version) {
        owner().update("INSERT INTO dev_access_binding(device_id,tenant_id,project_id,protocol,enabled,config_version) VALUES(?,?,?,?,?,?)",
                f.device(),f.tenant(),f.project(),protocol,enabled,version);
    }

    /** 回收自己创建的身份图，开发数据不在测试容器内。 */
    @AfterEach void cleanup() {
        for(var f:fixtures){
            owner().update("DELETE FROM dev_access_binding WHERE device_id=?",f.device());
            owner().update("DELETE FROM dev_device WHERE id=?",f.device());
            owner().update("DELETE FROM sys_project WHERE id=?",f.project());
            owner().update("DELETE FROM sys_tenant WHERE id=?",f.tenant());
        }
    }

    /** 独立owner连接只用于播种、故障和清理。 */
    private JdbcTemplate owner() { return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword())); }
    /** 不可变测试身份。 */
    private record Fixture(UUID tenant,UUID project,UUID device,String key) { }
}
