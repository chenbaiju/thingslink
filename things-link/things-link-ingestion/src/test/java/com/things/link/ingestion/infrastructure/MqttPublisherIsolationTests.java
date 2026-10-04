package com.things.link.ingestion.infrastructure;

import com.things.link.device.application.DeviceMqttDownlinkRoute;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.DeviceCommandDispatch;
import com.things.link.shared.message.DeviceConfigPush;
import com.things.link.shared.message.ModbusRequest;
import com.things.link.shared.message.TopologyReplyMessage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 四出口及命令/属性分支均无裸Topic、跨身份或事务内网络旁路。 */
class MqttPublisherIsolationTests {
    /** 冻结配置代次与实际网关只进入内部Topic，设备payload不泄露命名空间。 */
    @ParameterizedTest @ValueSource(strings={"COMMAND","PROPERTY","CONFIG","TOPOLOGY","MODBUS"})
    void publishesOnlyFrozenInternalNamespace(String branch) {
        Case c=message(branch);
        var builder=RestClient.builder();
        var server=MockRestServiceServer.bindTo(builder).build();
        var mapper=new ObjectMapper();
        server.expect(requestTo("http://emqx:18083/api/v5/publish")).andExpect(request->{
            var body=mapper.readTree(((MockClientHttpRequest)request).getBodyAsString());
            assertThat(body.path("topic").asString()).isEqualTo("tc/private/device/"+c.route().deviceId()+"/17/"+c.original());
            assertThat(body.path("qos").asInt()).isEqualTo(1);
            assertThat(body.path("retain").asBoolean()).isFalse();
            assertThat(body.path("payload").asString()).doesNotContain("tc/private/device/","configVersion","tc_auth_");
        }).andRespond(withSuccess("{}",MediaType.APPLICATION_JSON));
        var publisher=new EmqxHttpCommandPublisher(builder,mapper,"http://emqx:18083","key","secret");
        c.publish().accept(publisher,c.route());
        server.verify();
    }

    /** 缺失或错租户/项目/设备/键均不得执行HTTP，也不能自动改投当前路由。 */
    @ParameterizedTest @ValueSource(strings={"COMMAND","PROPERTY","CONFIG","TOPOLOGY","MODBUS"})
    void rejectsMissingOrMismatchedRouteWithoutHttp(String branch) {
        Case c=message(branch);var r=c.route();
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        var publisher=new EmqxHttpCommandPublisher(builder,new ObjectMapper(),"http://emqx:18083","key","secret");
        assertThatThrownBy(()->c.publish().accept(publisher,null)).isInstanceOf(RuntimeException.class);
        for(var wrong:List.of(
                new DeviceMqttDownlinkRoute(Uuid7.generate(),r.projectId(),r.deviceId(),17,"project","gateway"),
                new DeviceMqttDownlinkRoute(r.tenantId(),Uuid7.generate(),r.deviceId(),17,"project","gateway"),
                new DeviceMqttDownlinkRoute(r.tenantId(),r.projectId(),Uuid7.generate(),17,"project","gateway"),
                new DeviceMqttDownlinkRoute(r.tenantId(),r.projectId(),r.deviceId(),17,"project","other"))) {
            assertThatThrownBy(()->c.publish().accept(publisher,wrong)).isInstanceOf(RuntimeException.class);
        }
        server.verify();
    }

    /** 即使调用方绕过消费者，发布器也不能持数据库事务发送网络。 */
    @ParameterizedTest @ValueSource(strings={"COMMAND","PROPERTY","CONFIG","TOPOLOGY","MODBUS"})
    void rejectsAmbientTransaction(String branch) {
        Case c=message(branch);var builder=RestClient.builder();
        var server=MockRestServiceServer.bindTo(builder).build();
        var publisher=new EmqxHttpCommandPublisher(builder,new ObjectMapper(),"http://emqx:18083","key","secret");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {assertThatThrownBy(()->c.publish().accept(publisher,c.route())).isInstanceOf(IllegalStateException.class);}
        finally {TransactionSynchronizationManager.setActualTransactionActive(false);}
        server.verify();
    }

    /** 统一服务器身份且子设备与实际网关不同，防止偶然用目标设备通过测试。 */
    private Case message(String branch) {
        UUID tenant=Uuid7.generate(),project=Uuid7.generate(),gateway=Uuid7.generate(),request=Uuid7.generate();
        var route=new DeviceMqttDownlinkRoute(tenant,project,gateway,17,"project","gateway");
        if(branch.equals("COMMAND")||branch.equals("PROPERTY")) {
            boolean property=branch.equals("PROPERTY");
            var value=new DeviceCommandDispatch(Uuid7.generate(),tenant,project,request,Uuid7.generate(),1,
                    Uuid7.generate(),"child",gateway,"gateway","project",property?DeviceCommandDispatch.OperationType.PROPERTY_SET:DeviceCommandDispatch.OperationType.COMMAND,
                    property?null:"ping","{}",Instant.now().plusSeconds(30),"test");
            return new Case(route,"tc/v1/project/gateway/down/"+(property?"property/set":"command/"+request),(p,r)->p.publish(value,r));
        }
        return switch(branch) {
            case "CONFIG" -> new Case(route,"tc/v1/project/gateway/down/config",(p,r)->p.publishConfig(
                    new DeviceConfigPush(tenant,project,gateway,"project","gateway",DeviceConfigPush.CONFIG_TYPE,1,List.of()),r));
            case "TOPOLOGY" -> new Case(route,"tc/v1/project/gateway/down/topo/reply",(p,r)->p.publishTopologyReply(
                    new TopologyReplyMessage(request,tenant,project,gateway,"project","gateway","child",TopologyReplyMessage.Status.SUCCESS,null,null,Instant.now(),"test"),r));
            case "MODBUS" -> new Case(route,"tc/v1/project/gateway/down/modbus/request",(p,r)->p.publishModbusRequest(
                    new ModbusRequest(request,tenant,project,gateway,"project","gateway",1,"03",0,1),r));
            default -> throw new IllegalArgumentException(branch);
        };
    }

    /** 真实发布API路径与测试许可。 */
    private record Case(DeviceMqttDownlinkRoute route,String original,
            BiConsumer<EmqxHttpCommandPublisher,DeviceMqttDownlinkRoute> publish) { }
}
