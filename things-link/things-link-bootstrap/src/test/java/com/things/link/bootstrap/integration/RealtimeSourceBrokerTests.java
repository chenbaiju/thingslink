package com.things.link.bootstrap.integration;
import com.things.link.device.application.DeviceIngestionService;
import com.things.link.ingestion.infrastructure.RealtimeKafkaConsumer;
import com.things.link.integration.application.RealtimeMqttPublisher;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.DeviceRealtimeUpdate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.kafka.KafkaContainer;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"things-link.integration.api-key.enabled=true","things-link.integration.realtime.enabled=true","things-link.security.broker-callback.secret=realtime-broker-test-only-secret","spring.kafka.listener.auto-startup=false"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class RealtimeSourceBrokerTests extends RealtimeBrokerFixture {
    static final KafkaContainer KAFKA=new KafkaContainer("apache/kafka:4.1.0").withStartupTimeout(Duration.ofSeconds(90));
    static{KAFKA.start();}
    @DynamicPropertySource static void kafkaProperties(DynamicPropertyRegistry properties){properties.add("spring.kafka.bootstrap-servers",KAFKA::getBootstrapServers);}
    @Autowired @Qualifier("realtimeIngressKafkaListenerContainerFactory") ConcurrentKafkaListenerContainerFactory<Object,Object> factory;
    @Autowired RealtimeKafkaConsumer consumer;
    @Autowired KafkaTemplate<String,Object> kafka;
    @Autowired DeviceIngestionService devices;
    @Override String modelSnapshot(){return "{\"properties\":{\"value\":{\"dataType\":\"NUMBER\",\"accessType\":\"REPORT\"},\"empty\":{\"dataType\":\"NUMBER\",\"accessType\":\"REPORT\"}},\"events\":{},\"commands\":{}}";}
    @Test @SuppressWarnings("unchecked") void postgresCommitAndRollbackReachOriginalKafkaGroupAndRealMqttWithRestRecovery()throws Exception{
        var ticket=issue(120);String topic="tc.device.realtime",group=RealtimeKafkaConsumer.REALTIME_CONSUMER_GROUP,mqttTopic=RealtimeMqttPublisher.topic(ticket.ticketId());
        UUID rolled=Uuid7.generate(),committed=Uuid7.generate();String precise="1.12345678901234567890123456789";var seen=new AtomicReference<DeviceRealtimeUpdate>();
        try(var admin=AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,KAFKA.getBootstrapServers()));var socket=wire(ticket,5,true)){
            connected(socket);subscribed(socket,mqttTopic);
            admin.createTopics(List.of(new NewTopic(topic,1,(short)1),new NewTopic("tc.dlq",1,(short)1))).all().get(20,TimeUnit.SECONDS);
            var container=factory.createContainer(topic);container.getContainerProperties().setGroupId(group);
            container.getContainerProperties().setMessageListener((MessageListener<Object,Object>)record->{var typed=(ConsumerRecord<String,DeviceRealtimeUpdate>)(Object)record;seen.set(typed.value());consumer.consume(typed);});container.start();
            try{
                source(rolled,"2.12345678901234567890123456789",true);
                assertThat(owner.queryForObject("SELECT reported->>'value' FROM dev_shadow WHERE device_id=?",String.class,device)).isEqualTo("9007199254740993");
                source(committed,precise,false);
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(30)).until(()->offset(admin,group,topic)==1);
                assertThat(count("integ_realtime_event")).isEqualTo(1);assertThat(count("integ_realtime_delivery")).isEqualTo(1);assertThat(seen.get().messageId()).isEqualTo(committed);
                assertThat(owner.queryForObject("SELECT reported->>'value' FROM dev_shadow WHERE device_id=?",String.class,device)).isEqualTo(precise);
                dispatchAll();var event=json.readTree(socket.message(mqttTopic));assertThat(event.path("eventId").asString()).isEqualTo(committed.toString());assertThat(event.path("properties").path("value").path("valueJson").asString()).isEqualTo(precise);
                assertThat(event.path("properties").path("value").path("reportedRevision").asString()).isEqualTo(owner.queryForObject("SELECT reported_revisions->>'value' FROM dev_shadow WHERE device_id=?",String.class,device));
                kafka.send(topic,device.toString(),seen.get()).get(10,TimeUnit.SECONDS);org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(20)).until(()->offset(admin,group,topic)==2);
                dispatchAll();assertThat(count("integ_realtime_delivery")).isEqualTo(1);socket.socket.setSoTimeout(300);assertThatThrownBy(()->socket.message(mqttTopic)).isInstanceOf(java.net.SocketTimeoutException.class);
                socket.disconnectWithLongExpiry();
                try(var reconnect=wire(ticket,5,false)){connected(reconnect);assertThat(reconnect.connack.body()[0]).isZero();subscribed(reconnect,mqttTopic);reconnect.socket.setSoTimeout(300);assertThatThrownBy(()->reconnect.message(mqttTopic)).isInstanceOf(java.net.SocketTimeoutException.class);}
                var body=json.writeValueAsString(Map.of("devices",List.of(Map.of("deviceId",device,"expectedModelVersionId",model,"propertyKeys",List.of("value")))));
                var restored=request("POST","/api/open/v1/devices/current-values/query",body,null,Map.of("X-Api-Key",secret));assertThat(restored.statusCode()).isEqualTo(200);assertThat(restored.body()).contains(precise);
                assertThat(owner.queryForObject("SELECT count(*) FROM integ_realtime_event WHERE project_id=? AND event_id=?",Integer.class,project,rolled)).isZero();
            }finally{container.stop();}
        }
    }
    void source(UUID id,String value,boolean rollback){tx.execute(s->{rls.establish(tenant,project);var values=Map.<String,Object>of("value",new BigDecimal(value));Instant now=Instant.now();var context=devices.validateReportedProperties(tenant,project,device,"1.0.0",now,values);devices.mergeReportedProperties(context,project,device,values,now,id,"realtime-source-transaction");if(rollback)s.setRollbackOnly();return null;});}
    long offset(AdminClient admin,String group,String topic)throws Exception{var value=admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(10,TimeUnit.SECONDS).get(new TopicPartition(topic,0));return value==null?-1:value.offset();}
}
