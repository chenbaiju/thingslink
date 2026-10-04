package com.things.link.bootstrap.integration;

import com.things.link.integration.application.WebhookDeliveryDispatcher;
import com.things.link.integration.application.WebhookSubscriptionService;
import com.things.link.integration.domain.WebhookDeliveryRepository;
import com.things.link.integration.infrastructure.WebhookSourceKafkaConsumer;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.PublicWebhookSource;
import com.things.link.support.notification.delivery.PinnedWebhookTransport;
import com.things.link.support.notification.delivery.WebhookTlsReceiver;
import com.things.link.support.observability.OutboxMetrics;
import com.things.link.support.outbox.KafkaTransactionalOutboxPublisher;
import com.things.link.support.outbox.TransactionalOutboxRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.support.TopicPartitionOffset;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** 真实完成来源共用交付旅程；业务前置由调用方的原生产入口创建，执行器不造来源事实。 */
public final class WebhookCompletionJourney {
    private WebhookCompletionJourney() { }
    /** 本地独占TLS接收端仍验证生产Host/SNI及公有IP约束，只在测试socket层改路由。 */
    @TestConfiguration
    public static class ReceiverConfiguration {
        @Bean(destroyMethod="close") WebhookTlsReceiver completionReceiver() throws Exception { return new WebhookTlsReceiver(); }
        @Bean @Primary PinnedWebhookTransport completionTransport(WebhookTlsReceiver receiver) { return receiver.transport(); }
    }
    /** 允许原HTTP/设备事务用例携带其有检查异常，失败不转换为测试成功。 */
    @FunctionalInterface public interface Produce { void run() throws Exception; }

    /** 受理失败、原签名交付、提交不确定重投和重复Kafka四段均使用生产实现。 */
    @SuppressWarnings("unchecked")
    public static void verify(ApplicationContext app, JdbcTemplate owner, UUID tenant, UUID project, UUID actor,
            UUID device, String eventType, Produce produce) throws Exception {
        var receiver=app.getBean(WebhookTlsReceiver.class); receiver.reset();
        stopWorkers(app);
        var subscriptions=app.getBean(WebhookSubscriptionService.class);
        var subscription=subscriptions.create(tenant,project,actor,Uuid7.generate(),
                new WebhookSubscriptionService.Spec("completion-journey",receiver.target(),List.of(eventType),List.of(device)));
        var json=app.getBean(ObjectMapper.class);
        var kafka=(KafkaTemplate<String,Object>)app.getBean(KafkaTemplate.class);
        var outbox=app.getBean(TransactionalOutboxRepository.class);
        var consumer=app.getBean(WebhookSourceKafkaConsumer.class);
        var dispatcher=app.getBean(WebhookDeliveryDispatcher.class);
        var factory=(ConcurrentKafkaListenerContainerFactory<Object,Object>)app.getBean("webhookSourceKafkaListenerContainerFactory");
        String topic=PublicWebhookSource.TOPIC,group="completion-"+UUID.randomUUID();
        var fn="test_completion_"+project.toString().replace("-","");
        try(var admin=AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                app.getEnvironment().getRequiredProperty("spring.kafka.bootstrap-servers")))) {
            for(String name:List.of(topic,"tc.dlq")) {
                if(!admin.listTopics().names().get(10,TimeUnit.SECONDS).contains(name))
                    admin.createTopics(List.of(new NewTopic(name,6,(short)1))).all().get(20,TimeUnit.SECONDS);
            }
            Map<TopicPartition,Long> start=ends(admin,topic),dlq=ends(admin,"tc.dlq");
            var offsets=start.entrySet().stream().map(e->new TopicPartitionOffset(topic,e.getKey().partition(),e.getValue())).toArray(TopicPartitionOffset[]::new);
            var container=factory.createContainer(offsets); var failures=new AtomicInteger();
            container.getContainerProperties().setGroupId(group);
            container.getContainerProperties().setMessageListener((MessageListener<Object,Object>)record->{
                try { consumer.consume((ConsumerRecord<byte[],byte[]>)(Object)record); }
                catch(RuntimeException error) { failures.incrementAndGet(); throw error; }
            });
            var publisher=new KafkaTransactionalOutboxPublisher(outbox,kafka,app.getBean(OutboxMetrics.class),json,8,30);
            container.start();
            try {
                produce.run();
                var originals=owner.queryForList("SELECT payload FROM sys_outbox_event WHERE project_id=? AND event_type='PUBLIC_WEBHOOK_SOURCE'",String.class,project)
                        .stream().map(text->json.readValue(text,PublicWebhookSource.class)).toList();
                assertThat(originals).hasSize(1); var original=originals.getFirst();
                assertThat(original.event().eventType()).isEqualTo(eventType); assertThat(original.event().deviceId()).isEqualTo(device);
                owner.execute("REVOKE INSERT ON integ_webhook_event FROM thingslink_app");
                try {
                    await().atMost(Duration.ofSeconds(30)).until(()->{
                        publisher.publishReadyEvents();
                        return owner.queryForObject("SELECT count(*) FROM sys_outbox_event WHERE project_id=? AND event_type='PUBLIC_WEBHOOK_SOURCE' AND published_at IS NOT NULL",Integer.class,project)==1;
                    });
                    await().atMost(Duration.ofSeconds(20)).until(()->failures.get()>=3);
                    assertThat(advanced(admin,group,start)).isZero();
                    assertThat(owner.queryForObject("SELECT count(*) FROM integ_webhook_event WHERE project_id=?",Integer.class,project)).isZero();
                    assertThat(receiver.received).isEmpty(); assertThat(ends(admin,"tc.dlq")).isEqualTo(dlq);
                } finally { owner.execute("GRANT INSERT ON integ_webhook_event TO thingslink_app"); }
                await().atMost(Duration.ofSeconds(30)).until(()->advanced(admin,group,start)==1);
                UUID delivery=owner.queryForObject("SELECT id FROM integ_webhook_delivery WHERE project_id=?",UUID.class,project);
                var candidate=new WebhookDeliveryRepository.Candidate(tenant,project,delivery);
                owner.execute("CREATE FUNCTION "+fn+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.id='"+delivery+"'::uuid AND NEW.status='SUCCEEDED' THEN RAISE EXCEPTION 'TEST_COMPLETION_COMMIT_FAILURE'; END IF; RETURN NEW; END $$");
                try {
                    owner.execute("CREATE CONSTRAINT TRIGGER "+fn+" AFTER UPDATE ON integ_webhook_delivery DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION "+fn+"()");
                    assertThatThrownBy(()->dispatcher.dispatch(candidate)).isInstanceOf(RuntimeException.class);
                } finally { owner.execute("DROP TRIGGER IF EXISTS "+fn+" ON integ_webhook_delivery"); owner.execute("DROP FUNCTION IF EXISTS "+fn+"()"); }
                assertThat(receiver.received).hasSize(1);
                assertThat(owner.queryForObject("SELECT status FROM integ_webhook_delivery WHERE id=?",String.class,delivery)).isEqualTo("IN_FLIGHT");
                owner.update("UPDATE integ_webhook_delivery SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?",delivery);
                dispatcher.dispatch(candidate);
                assertThat(receiver.received).hasSize(2); assertThat(receiver.acceptedEffects()).isEqualTo(1);
                assertThat(receiver.received.getFirst().body()).containsExactly(receiver.received.getLast().body());
                assertThat(owner.queryForList("SELECT result FROM integ_webhook_attempt WHERE delivery_id=? ORDER BY attempt_no",String.class,delivery)).containsExactly("UNKNOWN","SUCCEEDED");
                for(var wire:receiver.received) verifyWire(json,wire,subscription.signingSecret(),original);
                kafka.send(topic,device.toString(),original).get(10,TimeUnit.SECONDS);
                await().atMost(Duration.ofSeconds(20)).until(()->advanced(admin,group,start)==2);
                assertThat(owner.queryForObject("SELECT count(*) FROM integ_webhook_delivery WHERE project_id=?",Integer.class,project)).isEqualTo(1);
                dispatcher.dispatch(candidate); assertThat(receiver.received).hasSize(2);
            } finally { container.stop(); publisher.destroy(); }
        } finally {
            receiver.release.countDown(); owner.execute("GRANT INSERT ON integ_webhook_event TO thingslink_app");
            owner.execute("DROP TRIGGER IF EXISTS "+fn+" ON integ_webhook_delivery"); owner.execute("DROP FUNCTION IF EXISTS "+fn+"()");
            cleanup(owner,project);
        }
    }
    /** 关闭本例共享自动发送与维护入口，交付由真实dispatcher显式推进。 */
    private static void stopWorkers(ApplicationContext app) throws InterruptedException {
        var scheduled=app.getBean(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class);
        var types=Set.of(com.things.link.integration.infrastructure.WebhookDeliveryWorker.class,
                com.things.link.integration.infrastructure.WebhookRetentionMaintenance.class);
        var tasks=scheduled.getScheduledTasks().stream().filter(task->{
            Runnable value=task.getTask().getRunnable();
            if(value.getClass().getName().equals("org.springframework.scheduling.config.Task$OutcomeTrackingRunnable"))
                value=(Runnable)org.springframework.test.util.ReflectionTestUtils.getField(value,"runnable");
            return value instanceof org.springframework.scheduling.support.ScheduledMethodRunnable method
                    &&types.contains(method.getMethod().getDeclaringClass())&&method.getMethod().getName().equals("tick");
        }).toList();
        assertThat(tasks).hasSize(2); tasks.forEach(task->task.cancel(false));
        app.getBean(com.things.link.integration.infrastructure.WebhookDeliveryWorker.class).destroy();
    }
    /** 使用真实topic各分区末尾作为独立消费组起点，不误认共享Broker的历史消息。 */
    private static Map<TopicPartition,Long> ends(AdminClient admin,String topic) throws Exception {
        var query=new HashMap<TopicPartition,OffsetSpec>();
        for(var partition:admin.describeTopics(List.of(topic)).allTopicNames().get(10,TimeUnit.SECONDS).get(topic).partitions())
            query.put(new TopicPartition(topic,partition.partition()),OffsetSpec.latest());
        var result=new HashMap<TopicPartition,Long>();
        admin.listOffsets(query).all().get(10,TimeUnit.SECONDS).forEach((p,v)->result.put(p,v.offset())); return result;
    }
    /** 只计算本轮起点之后的确认位移；未确认或其他分区均不冒充成功。 */
    private static long advanced(AdminClient admin,String group,Map<TopicPartition,Long> start) throws Exception {
        var committed=admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(10,TimeUnit.SECONDS);
        return start.entrySet().stream().mapToLong(e->committed.containsKey(e.getKey())?Math.max(0,committed.get(e.getKey()).offset()-e.getValue()):0).sum();
    }
    /** 接收端正文逐字节来自原来源，签名按实际头部和接收字节独立计算。 */
    private static void verifyWire(ObjectMapper json,WebhookTlsReceiver.Received wire,String secret,PublicWebhookSource original) throws Exception {
        String body=new String(wire.body(),StandardCharsets.UTF_8),id=wire.headers().getFirst("X-ThingsLink-Delivery-Id");
        assertThat(json.readTree(body).path("event")).isEqualTo(json.readTree(original.eventText()));
        var mac=javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(Base64.getDecoder().decode(secret),"HmacSHA256"));
        String signed=wire.headers().getFirst("X-ThingsLink-Timestamp")+"\n"+wire.headers().getFirst("X-ThingsLink-Nonce")+"\n"+id+"\n"+body;
        assertThat(wire.headers().getFirst("X-ThingsLink-Signature")).isEqualTo("v1="+HexFormat.of().formatHex(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8))));
    }
    /** 仅移除本例项目的接收交付图和计量；原Outbox由业务夹具负责最终清理。 */
    private static void cleanup(JdbcTemplate owner,UUID project) {
        new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(owner.getDataSource())).executeWithoutResult(tx->{
            owner.queryForList("SELECT id FROM sys_project WHERE id=? FOR UPDATE",project);
            for(String table:List.of("integ_webhook_attempt","integ_webhook_delivery","integ_webhook_conflict","integ_webhook_event","integ_webhook_ingress_state",
                    "integ_webhook_revision","integ_webhook_subscription","integ_webhook_operation","integ_webhook_recovery_operation","sys_usage_fact","sys_usage_counter_daily"))
                owner.update("DELETE FROM "+table+" WHERE project_id=?",project);
        });
    }
}
