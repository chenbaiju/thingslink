package com.things.link.ingestion.infrastructure;

import com.things.link.ingestion.application.CommandDownlinkPublisher;
import com.things.link.ingestion.application.InvalidDownlinkMessageException;
import com.things.link.ingestion.application.MqttDownlinkAdmissionService;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.ModbusRequest;
import com.things.link.shared.message.TopologyReplyMessage;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static com.things.link.ingestion.infrastructure.MqttRouteFixtures.route;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 拓扑与Modbus原先没有事务准入，明确验证新增边界在网络前执行。 */
class GatewayMqttConsumersTests {
    /** 当前许可返回后，原消息和原路由一起传给对应出口。 */
    @ParameterizedTest @ValueSource(booleans={false,true})
    void permissionPrecedesPublication(boolean modbus) {
        var admission=mock(MqttDownlinkAdmissionService.class);
        var publisher=mock(CommandDownlinkPublisher.class);
        var request=modbus();var reply=topology();
        when(admission.modbus(request)).thenReturn(route(request));
        when(admission.topology(reply)).thenReturn(route(reply));
        consume(modbus,admission,publisher,request,reply,false);
        var order=inOrder(admission,publisher);
        if(modbus) {
            order.verify(admission).modbus(request);
            order.verify(publisher).publishModbusRequest(request,route(request));
        } else {
            order.verify(admission).topology(reply);
            order.verify(publisher).publishTopologyReply(reply,route(reply));
        }
    }

    /** 永久拒绝和SQL故障分别保留原分类，均不得外发。 */
    @ParameterizedTest @ValueSource(booleans={false,true})
    void routeFailureNeverPublishes(boolean modbus) {
        var admission=mock(MqttDownlinkAdmissionService.class);
        var publisher=mock(CommandDownlinkPublisher.class);
        var request=modbus();var reply=topology();
        for(var failure:new RuntimeException[]{new InvalidDownlinkMessageException("denied"),
                new DataAccessResourceFailureException("database unavailable")}) {
            reset(admission);
            when(admission.modbus(request)).thenThrow(failure);
            when(admission.topology(reply)).thenThrow(failure);
            assertThatThrownBy(()->consume(modbus,admission,publisher,request,reply,false)).isSameAs(failure);
            verifyNoInteractions(publisher);
        }
    }

    /** 外层事务与错误分区键均在数据库之前拒绝。 */
    @ParameterizedTest @ValueSource(booleans={false,true})
    void invalidCallerCannotEnterAdmission(boolean modbus) {
        var admission=mock(MqttDownlinkAdmissionService.class);
        var publisher=mock(CommandDownlinkPublisher.class);
        var request=modbus();var reply=topology();
        assertThatThrownBy(()->consume(modbus,admission,publisher,request,reply,true)).isInstanceOf(InvalidDownlinkMessageException.class);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {assertThatThrownBy(()->consume(modbus,admission,publisher,request,reply,false)).isInstanceOf(IllegalStateException.class);}
        finally {TransactionSynchronizationManager.setActualTransactionActive(false);}
        verifyNoInteractions(admission,publisher);
    }

    /** 只通过生产消费者入口执行。 */
    private void consume(boolean modbus,MqttDownlinkAdmissionService admission,CommandDownlinkPublisher publisher,
            ModbusRequest request,TopologyReplyMessage reply,boolean wrongKey) {
        if(modbus) new ModbusRequestKafkaConsumer(publisher,admission).consume(new ConsumerRecord<>(
                ModbusRequestKafkaConsumer.MODBUS_REQUEST_TOPIC,0,0,wrongKey?"wrong":request.gatewayId().toString(),request));
        else new TopologyReplyKafkaConsumer(publisher,admission).consume(new ConsumerRecord<>(
                TopologyReplyKafkaConsumer.TOPOLOGY_REPLY_TOPIC,0,0,wrongKey?"wrong":reply.gatewayId().toString(),reply));
    }

    /** 独立Modbus请求。 */
    private ModbusRequest modbus() {
        return new ModbusRequest(Uuid7.generate(),Uuid7.generate(),Uuid7.generate(),Uuid7.generate(),"project","gateway",1,"03",0,1);
    }

    /** 独立拓扑回执。 */
    private TopologyReplyMessage topology() {
        return new TopologyReplyMessage(Uuid7.generate(),Uuid7.generate(),Uuid7.generate(),Uuid7.generate(),"project","gateway","child",
                TopologyReplyMessage.Status.SUCCESS,null,null,Instant.now(),"test");
    }
}
