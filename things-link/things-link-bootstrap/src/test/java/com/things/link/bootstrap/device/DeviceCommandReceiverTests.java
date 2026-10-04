package com.things.link.bootstrap.device;

import com.things.link.device.application.DeviceCommandReceiverPort;
import com.things.link.device.application.DeviceCommandReceiverRoute;
import com.things.link.shared.id.Uuid7;
import com.things.link.testing.AbstractIntegrationTest;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实PG原接收者关系许可；直接SQL夹具遵守即时角色与延期投影约束，不关闭守卫。 */
class DeviceCommandReceiverTests extends AbstractIntegrationTest {
    /** 生产MANDATORY端口。 */ @Autowired private DeviceCommandReceiverPort receivers;
    /** 普通APP事务。 */ @Autowired private TransactionTemplate tx;
    /** 独占身份图清单。 */ private final List<Fixture> fixtures=new ArrayList<>();

    /** 直连以自己接收，子设备以原网关接收，离线不会伪装成关系失效。 */
    @Test void acceptsDirectAndOriginalGatewayWithoutRequiringOnline() {
        var f=seed();
        assertThat(current(f,f.direct(),f.direct())).get().extracting(DeviceCommandReceiverRoute::connectionDeviceKey).isEqualTo("direct");
        var route=current(f,f.child(),f.gateway()).orElseThrow();
        assertThat(route.targetDeviceId()).isEqualTo(f.child());
        assertThat(route.connectionDeviceId()).isEqualTo(f.gateway());
        assertThat(route.targetDeviceKey()).isEqualTo("child");
        assertThat(route.connectionDeviceKey()).isEqualTo("gateway");
        assertThatThrownBy(()->receivers.lockCurrent(f.tenant(),f.project(),f.child(),f.gateway())).isInstanceOf(IllegalTransactionStateException.class);
    }

    /** 改绑后只为新独立身份请求返回新关系，旧命令接收者绝不自动改投。 */
    @Test void rejectsOldGatewayAfterRebinding() {
        var f=seed();closeAndRebind(f,true);
        assertThat(current(f,f.child(),f.gateway())).isEmpty();
        assertThat(current(f,f.child(),f.other())).get().extracting(DeviceCommandReceiverRoute::connectionDeviceKey).isEqualTo("other");
    }

    /** 子设备解绑不能降级成直连。 */
    @Test void unboundSubDeviceNeverFallsBackToItself() {
        var f=seed();closeAndRebind(f,false);
        assertThat(current(f,f.child(),f.gateway())).isEmpty();
        assertThat(current(f,f.child(),f.child())).isEmpty();
    }

    /** 软删、清空类型、换成无绑定子设备及项目归档均拒绝原直连身份。 */
    @ParameterizedTest @ValueSource(strings={"DELETED","UNTYPED","SUB_DEVICE","ARCHIVED"})
    void rejectsUnavailableDirectIdentity(String change) {
        var f=seed();var owner=owner();
        switch(change) {
            case "DELETED" -> owner.update("UPDATE dev_device SET deleted_at=clock_timestamp() WHERE id=?",f.direct());
            case "UNTYPED" -> owner.update("UPDATE dev_device SET device_type_id=NULL WHERE id=?",f.direct());
            case "SUB_DEVICE" -> owner.update("UPDATE dev_device SET device_type_id=? WHERE id=?",f.subType(),f.direct());
            case "ARCHIVED" -> owner.update("UPDATE sys_project SET status='ARCHIVED' WHERE id=?",f.project());
            default -> throw new IllegalArgumentException(change);
        }
        assertThat(current(f,f.direct(),f.direct())).isEmpty();
    }

    /** 未知或跨身份不能使用无行默认路由。 */
    @Test void rejectsCrossTenantAndMissingReceiver() {
        var f=seed();
        assertThat(tx.<Optional<DeviceCommandReceiverRoute>>execute(s->receivers.lockCurrent(Uuid7.generate(),f.project(),f.child(),f.gateway()))).isEmpty();
        assertThat(current(f,f.child(),Uuid7.generate())).isEmpty();
        assertThat(current(f,Uuid7.generate(),f.gateway())).isEmpty();
    }

    /** 实际设备或类型竞争立即抛数据库异常，释放后原许可恢复，绝不返回永久拒绝。 */
    @ParameterizedTest @ValueSource(strings={"DEVICE","TYPE"})
    void nowaitContentionPropagatesAndRecovers(String kind) throws Exception {
        var f=seed();
        try(var holder=DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword())) {
            holder.setAutoCommit(false);
            String table=kind.equals("DEVICE")?"dev_device":"dev_type";
            UUID id=kind.equals("DEVICE")?f.child():f.subType();
            try(var statement=holder.prepareStatement("SELECT id FROM "+table+" WHERE id=? FOR UPDATE")) {
                statement.setObject(1,id);statement.executeQuery().close();
                long start=System.nanoTime();
                assertThatThrownBy(()->current(f,f.child(),f.gateway())).isInstanceOf(DataAccessException.class);
                assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(2));
            } finally {holder.rollback();}
        }
        assertThat(current(f,f.child(),f.gateway())).isPresent();
    }

    /** 已持许可对设备/类型的写入保护持续到外层事务结束。 */
    @Test void permissionHoldsDeviceAndTypeProtectionUntilCommit() {
        var f=seed();
        tx.executeWithoutResult(s->{
            assertThat(receivers.lockCurrent(f.tenant(),f.project(),f.child(),f.gateway())).isPresent();
            for(String table:List.of("dev_device","dev_type")) {
                UUID id=table.equals("dev_device")?f.child():f.subType();
                assertThatThrownBy(()->owner().queryForList("SELECT id FROM "+table+" WHERE id=? FOR UPDATE NOWAIT",id)).isInstanceOf(DataAccessException.class);
            }
        });
        owner().queryForList("SELECT id FROM dev_device WHERE id=? FOR UPDATE NOWAIT",f.child());
        owner().queryForList("SELECT id FROM dev_type WHERE id=? FOR UPDATE NOWAIT",f.subType());
    }

    /** APP事务读取原目标/接收者，身份来自独占夹具。 */
    private Optional<DeviceCommandReceiverRoute> current(Fixture f,UUID target,UUID connection) {
        return tx.execute(s->receivers.lockCurrent(f.tenant(),f.project(),target,connection));
    }

    /** 按关旧建新维护投影与关系，真实约束在提交时核验。 */
    private void closeAndRebind(Fixture f,boolean rebind) {
        var owner=owner();new TransactionTemplate(new DataSourceTransactionManager(owner.getDataSource())).executeWithoutResult(s->{
            owner.update("UPDATE dev_topo SET unbound_at=clock_timestamp() WHERE sub_device_id=? AND unbound_at IS NULL",f.child());
            owner.update("UPDATE dev_device SET gateway_id=? WHERE id=?",rebind?f.other():null,f.child());
            if(rebind) owner.update("INSERT INTO dev_topo(id,tenant_id,project_id,gateway_device_id,sub_device_id,bind_source) VALUES(?,?,?,?,?,'CONTROL_PLANE')",Uuid7.generate(),f.tenant(),f.project(),f.other(),f.child());
        });
    }

    /** 独立owner只播种合法设备域祖先，不用于被测许可。 */
    private Fixture seed() {
        var f=new Fixture(Uuid7.generate(),Uuid7.generate(),Uuid7.generate(),Uuid7.generate(),Uuid7.generate(),Uuid7.generate(),Uuid7.generate(),Uuid7.generate(),Uuid7.generate());fixtures.add(f);
        var owner=owner();new TransactionTemplate(new DataSourceTransactionManager(owner.getDataSource())).executeWithoutResult(s->{
            owner.update("INSERT INTO sys_tenant(id,name) VALUES(?,'接收者测试')",f.tenant());
            owner.update("INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES(?,?,'接收者测试','sh-1',?)",f.project(),f.tenant(),"receiver_"+f.project().toString().replace("-",""));
            Object[][] types={{f.directType(),"DIRECT"},{f.gatewayType(),"GATEWAY"},{f.subType(),"SUB_DEVICE"}};
            for(var type:types) owner.update("INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,device_kind,access_protocol,network_type,status) VALUES(?,?,?,?,?,?,?,'WIFI','PUBLISHED')",type[0],f.tenant(),f.project(),type[1],type[1],type[1],type[1].equals("GATEWAY")?"STANDARD_GATEWAY":"STANDARD");
            Object[][] devices={{f.gateway(),f.gatewayType(),"gateway",null},{f.other(),f.gatewayType(),"other",null},{f.direct(),f.directType(),"direct",null},{f.child(),f.subType(),"child",f.gateway()}};
            for(var device:devices) owner.update("INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,gateway_id) VALUES(?,?,?,?,?,'接收者测试',?)",device[0],f.tenant(),f.project(),device[1],device[2],device[3]);
            owner.update("INSERT INTO dev_topo(id,tenant_id,project_id,gateway_device_id,sub_device_id,bind_source) VALUES(?,?,?,?,?,'CONTROL_PLANE')",Uuid7.generate(),f.tenant(),f.project(),f.gateway(),f.child());
        });
        return f;
    }

    /** 只清理本例祖先，延期约束在同一事务内看到最终删除。 */
    @AfterEach void cleanup() {
        var owner=owner();for(var f:fixtures) new TransactionTemplate(new DataSourceTransactionManager(owner.getDataSource())).executeWithoutResult(s->{
            owner.update("DELETE FROM dev_topo WHERE project_id=?",f.project());owner.update("DELETE FROM dev_device WHERE project_id=?",f.project());
            owner.update("DELETE FROM dev_type WHERE project_id=?",f.project());owner.update("DELETE FROM sys_project WHERE id=?",f.project());owner.update("DELETE FROM sys_tenant WHERE id=?",f.tenant());
        });
    }

    /** 私有测试库的独立owner连接。 */
    private JdbcTemplate owner() {return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()));}
    /** 独占原接收者关系图。 */
    private record Fixture(UUID tenant,UUID project,UUID child,UUID gateway,UUID other,UUID direct,UUID subType,UUID gatewayType,UUID directType) { }
}
