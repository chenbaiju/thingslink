package com.things.link.bootstrap.integration;
import com.things.link.ingestion.infrastructure.RealtimeKafkaConsumer;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.DeviceRealtimeUpdate;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"things-link.integration.api-key.enabled=true","things-link.integration.realtime.enabled=true","spring.kafka.listener.auto-startup=false"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class RealtimeEventKafkaRecoveryTests extends RealtimeEventFixture {
    static final KafkaContainer KAFKA=new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.0")).withStartupTimeout(Duration.ofMinutes(2));
    static{KAFKA.start();}
    @DynamicPropertySource static void kafka(DynamicPropertyRegistry properties){properties.add("spring.kafka.bootstrap-servers",KAFKA::getBootstrapServers);}
    @Autowired @Qualifier("realtimeIngressKafkaListenerContainerFactory") ConcurrentKafkaListenerContainerFactory<Object,Object> factory;
    @Autowired RealtimeKafkaConsumer consumer;
    @Autowired KafkaTemplate<String,Object> kafka;
    @Test @SuppressWarnings("unchecked") void databaseFailureKeepsRealOffsetUntilOriginalPlanCommits()throws Exception {
        connect();String topic="tc.device.realtime",group=RealtimeKafkaConsumer.REALTIME_CONSUMER_GROUP;
        try(var admin=AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,KAFKA.getBootstrapServers()))){
            admin.createTopics(List.of(new NewTopic(topic,1,(short)1),new NewTopic("tc.dlq",1,(short)1))).all().get(20,TimeUnit.SECONDS);
            var container=factory.createContainer(topic);var failures=new AtomicInteger();
            container.getContainerProperties().setGroupId(group);
            container.getContainerProperties().setMessageListener((MessageListener<Object,Object>)record->{try{consumer.consume((ConsumerRecord<String,DeviceRealtimeUpdate>)(Object)record);}catch(RuntimeException failure){failures.incrementAndGet();throw failure;}});
            container.start();
            try{
                kafka.send(topic,device.toString(),update(Uuid7.generate(),"1")).get(15,TimeUnit.SECONDS);
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(30)).until(()->offset(admin,group,topic)==1);
                owner.execute("REVOKE INSERT ON integ_realtime_event FROM thingslink_app");
                var event=update(Uuid7.generate(),"2");
                try{
                    kafka.send(topic,device.toString(),event).get(15,TimeUnit.SECONDS);
                    org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(20)).until(()->failures.get()>=3);
                    assertThat(offset(admin,group,topic)).isEqualTo(1);assertThat(count("integ_realtime_event")).isEqualTo(1);
                    assertThat(admin.listOffsets(Map.of(new TopicPartition("tc.dlq",0),OffsetSpec.latest())).all().get(10,TimeUnit.SECONDS).get(new TopicPartition("tc.dlq",0)).offset()).isZero();
                }finally{owner.execute("GRANT INSERT ON integ_realtime_event TO thingslink_app");}
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(30)).until(()->offset(admin,group,topic)==2);
                assertThat(count("integ_realtime_event")).isEqualTo(2);assertThat(count("integ_realtime_delivery")).isEqualTo(2);
                kafka.send(topic,device.toString(),event).get(15,TimeUnit.SECONDS);
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(20)).until(()->offset(admin,group,topic)==3);
                assertThat(count("integ_realtime_event")).isEqualTo(2);assertThat(count("integ_realtime_delivery")).isEqualTo(2);
            }finally{container.stop();}
        }
    }
    long offset(AdminClient admin,String group,String topic)throws Exception{
        var committed=admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(10,TimeUnit.SECONDS).get(new TopicPartition(topic,0));return committed==null?-1:committed.offset();
    }
}
