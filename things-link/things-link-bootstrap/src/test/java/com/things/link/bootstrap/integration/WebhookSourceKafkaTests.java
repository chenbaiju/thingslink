package com.things.link.bootstrap.integration;

import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.*;
import com.things.link.support.webhook.*;
import com.things.link.support.outbox.*;
import com.things.link.support.observability.OutboxMetrics;
import com.things.link.integration.infrastructure.WebhookSourceKafkaConsumer;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.test.context.*;
import com.things.link.testing.OwnedTestContainers;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** Real original transaction, production Outbox publisher, dedicated consumer policy and broker offsets. */
@ContextConfiguration(initializers=WebhookSourceKafkaTests.IsolatedDatabaseInitializer.class)
@OwnedTestContainers({"DATABASE","KAFKA"})
class WebhookSourceKafkaTests extends WebhookFixture {
    /** 共享测试库含其他用例的待发布 Outbox；本例需要真实但独占的领取顺序。 */
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("webhook_source_kafka")
            .withUsername(POSTGRES.getUsername())
            .withPassword(POSTGRES.getPassword());
    private static final String DATABASE_URL = startDatabase();
    static final KafkaContainer KAFKA=new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.0")).withStartupTimeout(Duration.ofMinutes(2));
    static{KAFKA.start();}
    private static String startDatabase(){DATABASE.start();return DATABASE.getJdbcUrl();}
    @Override protected String fixtureJdbcUrl(){return DATABASE_URL;}
    static final class IsolatedDatabaseInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override public void initialize(ConfigurableApplicationContext context){
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "webhookSourceKafkaDatabase",Map.of(
                            "spring.datasource.url",DATABASE_URL,
                            "spring.flyway.url",DATABASE_URL)));
        }
    }
    @DynamicPropertySource static void kafka(DynamicPropertyRegistry p){p.add("spring.kafka.bootstrap-servers",KAFKA::getBootstrapServers);}
    @Autowired @Qualifier("webhookSourceKafkaListenerContainerFactory") ConcurrentKafkaListenerContainerFactory<Object,Object> factory;
    @Autowired WebhookSourceKafkaConsumer consumer;
    @Autowired PublicWebhookSourceWriter source;
    @Autowired TransactionalOutboxRepository outbox;
    @Autowired OutboxMetrics metrics;
    @Autowired KafkaTemplate<String,Object> kafka;
    @Autowired ObjectMapper mapper;
    @AfterEach void cleanupSources(){owner.update("DELETE FROM sys_outbox_event WHERE project_id=? AND event_type='PUBLIC_WEBHOOK_SOURCE'",project);}
    PublicWebhookSource append(String payload,boolean rollback){return tx.execute(s->{var now=source.recordedAt();var event=new PublicWebhookEvent(Uuid7.generate(),"device.property.report",tenant,project,0,"device",device,device,now,now,null,payload);source.append(event);if(rollback)s.setRollbackOnly();return new PublicWebhookCodec(mapper).prepare(event);});}
    @Test @SuppressWarnings("unchecked") void originalOutboxSurvivesConsumerFailureAndRejectsWithoutOffsetLoss()throws Exception {
        create(Uuid7.generate());String topic=PublicWebhookSource.TOPIC,group="things-link-integration-webhook-source";
        try(var admin=AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,KAFKA.getBootstrapServers()))){
            admin.createTopics(List.of(new NewTopic(topic,1,(short)1),new NewTopic("tc.dlq",1,(short)1))).all().get(20,TimeUnit.SECONDS);
            var container=factory.createContainer(topic);var failures=new AtomicInteger();container.getContainerProperties().setGroupId(group);
            container.getContainerProperties().setMessageListener((MessageListener<Object,Object>)r->{try{consumer.consume((ConsumerRecord<byte[],byte[]>)(Object)r);}catch(RuntimeException failure){failures.incrementAndGet();throw failure;}});
            var publisher=new KafkaTransactionalOutboxPublisher(outbox,kafka,metrics,mapper,8,30);container.start();
            try{
                append("{\"rolledBack\":true}",true);
                assertThat(owner.queryForObject("SELECT count(*) FROM sys_outbox_event WHERE project_id=?",Integer.class,project)).isZero();
                append("{\"value\":9007199254740993}",false);publisher.publishReadyEvents();
                await().atMost(Duration.ofSeconds(30)).until(()->offset(admin,group,topic)==1);
                assertThat(rows("integ_webhook_delivery")).isEqualTo(1);
                await().atMost(Duration.ofSeconds(10)).until(()->owner.queryForObject("SELECT count(*) FROM sys_outbox_event WHERE project_id=? AND published_at IS NOT NULL",Integer.class,project)==1);
                owner.execute("REVOKE INSERT ON integ_webhook_event FROM thingslink_app");PublicWebhookSource second;
                try{
                    second=append("{\"value\":0.12345678901234567890123456789}",false);publisher.publishReadyEvents();
                    await().atMost(Duration.ofSeconds(25)).until(()->failures.get()>=3);
                    assertThat(offset(admin,group,topic)).isEqualTo(1);assertThat(rows("integ_webhook_event")).isEqualTo(1);
                    await().atMost(Duration.ofSeconds(10)).until(()->owner.queryForObject("SELECT count(*) FROM sys_outbox_event WHERE project_id=? AND published_at IS NOT NULL",Integer.class,project)==2);
                    assertThat(admin.listOffsets(Map.of(new TopicPartition("tc.dlq",0),OffsetSpec.latest())).all().get(10,TimeUnit.SECONDS).get(new TopicPartition("tc.dlq",0)).offset()).isZero();
                }finally{owner.execute("GRANT INSERT ON integ_webhook_event TO thingslink_app");}
                await().atMost(Duration.ofSeconds(30)).until(()->offset(admin,group,topic)==2);
                assertThat(rows("integ_webhook_delivery")).isEqualTo(2);
                assertThat(owner.queryForObject("SELECT event_text FROM integ_webhook_event WHERE project_id=? AND event_id=?",String.class,project,second.event().eventId())).contains("0.12345678901234567890123456789");
                kafka.send(topic,device.toString(),second).get(15,TimeUnit.SECONDS);
                await().atMost(Duration.ofSeconds(20)).until(()->offset(admin,group,topic)==3);assertThat(rows("integ_webhook_delivery")).isEqualTo(2);
                append("{\"large\":\""+"x".repeat(300000)+"\"}",false);publisher.publishReadyEvents();
                await().atMost(Duration.ofSeconds(20)).until(()->offset(admin,group,topic)==4);
                assertThat(rows("integ_webhook_delivery")).isEqualTo(2);assertThat(owner.queryForObject("SELECT count(*) FROM integ_webhook_event WHERE project_id=? AND result='OVERSIZE' AND event_text IS NULL",Integer.class,project)).isEqualTo(1);
            }finally{container.stop();publisher.destroy();}
        }
    }
    long offset(AdminClient admin,String group,String topic)throws Exception{var offset=admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(10,TimeUnit.SECONDS).get(new TopicPartition(topic,0));return offset==null?-1:offset.offset();}
}
