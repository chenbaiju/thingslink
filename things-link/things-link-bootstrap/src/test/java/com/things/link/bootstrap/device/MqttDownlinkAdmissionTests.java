package com.things.link.bootstrap.device;

import com.things.link.device.application.DeviceConfigDeliveryAdmissionService;
import com.things.link.device.application.DeviceMqttDownlinkRoute;
import com.things.link.device.application.DeviceMqttDownlinkRoutePort;
import com.things.link.ingestion.application.InvalidDownlinkMessageException;
import com.things.link.ingestion.application.MqttDownlinkAdmissionService;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.DeviceCommandDispatch;
import com.things.link.shared.message.DeviceConfigPush;
import com.things.link.shared.message.ModbusRequest;
import com.things.link.shared.message.TopologyReplyMessage;
import com.things.link.telemetry.application.DeviceCommandService;
import com.things.link.testing.AbstractIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 真实PG路由与代理事务边界；命令/配置端口替身只注入准入结果及事务失败，不冒称其完整业务验收。 */
class MqttDownlinkAdmissionTests extends AbstractIntegrationTest {
    /** 生产持锁路由。 */ @Autowired private DeviceMqttDownlinkRoutePort routes;
    /** 真实APP事务管理器。 */ @Autowired private PlatformTransactionManager manager;
    /** 同事务验证写入。 */ @Autowired private JdbcTemplate jdbc;
    /** 每例独占测试身份。 */ private final List<Fixture> fixtures = new ArrayList<>();
    /** 明确可控的业务准入替身。 */ private DeviceCommandService commands;
    /** 明确可控的配置准入替身。 */ private DeviceConfigDeliveryAdmissionService configs;
    /** 通过生产注解开启真实事务。 */ private MqttDownlinkAdmissionService admission;

    /** 与生产相同的事务注解拦截，禁止直接调用对象绕过提交边界。 */
    @BeforeEach void proxy() {
        commands=mock(DeviceCommandService.class); configs=mock(DeviceConfigDeliveryAdmissionService.class);
        var factory=new ProxyFactory(new MqttDownlinkAdmissionService(routes,commands,configs));
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        admission=(MqttDownlinkAdmissionService)factory.getProxy();
        when(commands.admitDispatch(any())).thenReturn(true);
        when(configs.admit(any())).thenReturn(true);
    }

    /** 四个入口返回服务器当前版本及同一原网关键，事务已提交才交还调用方。 */
    @ParameterizedTest @ValueSource(strings={"COMMAND","CONFIG","TOPOLOGY","MODBUS"})
    void freezesAllFourBranches(String branch) {
        Fixture f=seed();bind(f,"MQTT",true,9);
        var route=admit(f,branch);
        assertThat(route.configVersion()).isEqualTo(9);
        assertThat(route.deviceId()).isEqualTo(f.device());
        assertThat(route.internalTopic("tc/v1/"+f.key()+"/device/down/config"))
                .isEqualTo("tc/private/device/"+f.device()+"/9/tc/v1/"+f.key()+"/device/down/config");
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        owner().update("UPDATE dev_access_binding SET config_version=10 WHERE device_id=?",f.device());
        assertThat(route.configVersion()).isEqualTo(9);
    }

    /** 设备SHARE在原命令准入前持有；原准入写入提交后才返回冻结路由。 */
    @Test void locksDeviceBeforeCommandAndCommitsBeforeReturning() {
        Fixture f=seed();
        when(commands.admitDispatch(any())).thenAnswer(invocation->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            try(var connection=java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
                    var statement=connection.createStatement()) {
                statement.execute("SET lock_timeout='150ms'");
                assertThatThrownBy(()->statement.executeUpdate("UPDATE dev_device SET name='blocked' WHERE id='"+f.device()+"'"))
                        .isInstanceOf(java.sql.SQLException.class);
            }
            jdbc.update("UPDATE dev_device SET name='committed' WHERE id=?",f.device());
            return true;
        });
        assertThat(admission.command(dispatch(f,"device"))).isPresent();
        assertThat(owner().queryForObject("SELECT name FROM dev_device WHERE id=?",String.class,f.device())).isEqualTo("committed");
    }

    /** 缺失路由仍执行原业务拒绝事实，空许可不是提前跳过。 */
    @Test void unavailableRouteStillCommitsOriginalCommandRejection() {
        Fixture f=seed();bind(f,"HTTP",true,1);
        when(commands.admitDispatch(any())).thenAnswer(invocation->{
            jdbc.update("UPDATE dev_device SET name='rejected' WHERE id=?",f.device());return false;
        });
        assertThat(admission.command(dispatch(f,"device"))).isEmpty();
        verify(commands).admitDispatch(any());
        assertThat(owner().queryForObject("SELECT name FROM dev_device WHERE id=?",String.class,f.device())).isEqualTo("rejected");
    }

    /** 服务器键与信封冲突时整个准入回滚，不允许先提交原许可再发现目标不符。 */
    @Test void mismatchedRouteRollsBackOriginalAdmissionWrite() {
        Fixture f=seed();
        when(commands.admitDispatch(any())).thenAnswer(invocation->{
            jdbc.update("UPDATE dev_device SET name='must-rollback' WHERE id=?",f.device());return true;
        });
        assertThatThrownBy(()->admission.command(dispatch(f,"other"))).isInstanceOf(InvalidDownlinkMessageException.class);
        assertThat(owner().queryForObject("SELECT name FROM dev_device WHERE id=?",String.class,f.device())).isEqualTo("MQTT路由测试");
    }

    /** 提交阶段失败不得把尚未可靠受理的路由返回给网络调用方。 */
    @Test void commitFailureDoesNotReturnPermission() {
        Fixture f=seed();
        when(commands.admitDispatch(any())).thenAnswer(invocation->{
            jdbc.update("UPDATE dev_device SET name='must-rollback' WHERE id=?",f.device());
            TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) { throw new IllegalStateException("commit fault"); }
            });
            return true;
        });
        assertThatThrownBy(()->admission.command(dispatch(f,"device"))).isInstanceOf(IllegalStateException.class).hasMessage("commit fault");
        assertThat(owner().queryForObject("SELECT name FROM dev_device WHERE id=?",String.class,f.device())).isEqualTo("MQTT路由测试");
    }

    /** 数据库错误从路由端口穿透，不能被误作历史无配置的MQTT。 */
    @Test void routeFailureDoesNotCallBusinessAdmission() throws Exception {
        Fixture f=seed();
        try(var holder=java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword())) {
            holder.setAutoCommit(false);
            try(var statement=holder.createStatement()) {
                statement.execute("LOCK TABLE dev_access_binding IN ACCESS EXCLUSIVE MODE");
                var tx=new org.springframework.transaction.support.TransactionTemplate(manager);
                assertThatThrownBy(()->tx.execute(status->{jdbc.execute("SET LOCAL lock_timeout='150ms'");
                    return admission.command(dispatch(f,"device"));})).isInstanceOf(DataAccessException.class);
                verifyNoInteractions(commands,configs);
            } finally {holder.rollback();}
        }
    }

    /** 非命令分支没有可用路由必须明确拒绝，不返回null或裸Topic。 */
    @ParameterizedTest @ValueSource(strings={"CONFIG","TOPOLOGY","MODBUS"})
    void rejectsDisabledGateway(String branch) {
        Fixture f=seed();bind(f,"MQTT",false,1);
        assertThatThrownBy(()->admit(f,branch)).isInstanceOf(InvalidDownlinkMessageException.class);
        if(branch.equals("CONFIG")) verify(configs).admit(any());
    }

    /** 原配置信封拒绝不能被可用MQTT路由覆盖。 */
    @Test void originalConfigFailurePropagates() {
        Fixture f=seed();
        when(configs.admit(any())).thenThrow(new IllegalStateException("original config rejected"));
        assertThatThrownBy(()->admit(f,"CONFIG")).isInstanceOf(IllegalStateException.class).hasMessage("original config rejected");
    }

    /** 统一调用四个公开入口，不绕过事务代理。 */
    private DeviceMqttDownlinkRoute admit(Fixture f,String branch) {
        return switch(branch) {
            case "COMMAND" -> admission.command(dispatch(f,"device")).orElseThrow();
            case "CONFIG" -> admission.config(new DeviceConfigPush(f.tenant(),f.project(),f.device(),f.key(),"device",DeviceConfigPush.CONFIG_TYPE,1,List.of()));
            case "TOPOLOGY" -> admission.topology(new TopologyReplyMessage(Uuid7.generate(),f.tenant(),f.project(),f.device(),f.key(),"device","child",TopologyReplyMessage.Status.SUCCESS,null,null,Instant.now(),"test"));
            case "MODBUS" -> admission.modbus(new ModbusRequest(Uuid7.generate(),f.tenant(),f.project(),f.device(),f.key(),"device",1,"03",0,1));
            default -> throw new IllegalArgumentException(branch);
        };
    }

    /** 只测准入封装，持久命令业务合同由其原真实PG测试独立覆盖。 */
    private DeviceCommandDispatch dispatch(Fixture f,String key) {
        return new DeviceCommandDispatch(Uuid7.generate(),f.tenant(),f.project(),Uuid7.generate(),Uuid7.generate(),1,
                f.device(),"device",f.device(),key,f.key(),"ping","{}",Instant.now().plusSeconds(30),"test");
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
