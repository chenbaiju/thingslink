package com.things.link.bootstrap.integration;

import com.things.link.device.application.DeviceAccessControlService;
import com.things.link.integration.application.WebhookDeliveryDispatcher;
import com.things.link.integration.application.WebhookSubscriptionService;
import com.things.link.integration.domain.WebhookDeliveryRepository;
import com.things.link.integration.infrastructure.WebhookSourceKafkaConsumer;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.PublicWebhookSource;
import com.things.link.shared.message.StandardUplinkMessage;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.notification.delivery.PinnedWebhookTransport;
import com.things.link.support.notification.delivery.WebhookTlsReceiver;
import com.things.link.support.observability.OutboxMetrics;
import com.things.link.support.outbox.KafkaTransactionalOutboxPublisher;
import com.things.link.support.outbox.TransactionalOutboxRepository;
import com.things.link.telemetry.application.PropertyIngestionService;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** 五类实际来源贯穿PG/Outbox/Kafka/受理/签名TLS及接收端持久去重。 */
@Import(WebhookSourceJourneyTests.ReceiverConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WebhookSourceJourneyTests extends WebhookFixture {
    private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.0").withStartupTimeout(Duration.ofSeconds(120));
    static { KAFKA.start(); }
    private static final String PASSWORD = "f5".repeat(32);
    private static final String PRECISE = "40.12345678901234567890123456789";
    @DynamicPropertySource static void kafka(DynamicPropertyRegistry registry) { registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers); }
    @AfterAll static void stopKafka() { KAFKA.stop(); }
    @TestConfiguration static class ReceiverConfiguration {
        @Bean(destroyMethod = "close") WebhookTlsReceiver webhookReceiver() throws Exception { return new WebhookTlsReceiver(); }
        @Bean @Primary PinnedWebhookTransport testWebhookTransport(WebhookTlsReceiver receiver) { return receiver.transport(); }
    }
    @Autowired WebhookTlsReceiver receiver;
    @Autowired PropertyIngestionService ingestion;
    @Autowired DeviceAccessControlService control;
    @Autowired TransactionalOutboxRepository outbox;
    @Autowired OutboxMetrics metrics;
    @Autowired KafkaTemplate<String, Object> kafka;
    @Autowired WebhookSourceKafkaConsumer consumer;
    @Autowired WebhookDeliveryDispatcher dispatcher;
    @Autowired @Qualifier("webhookSourceKafkaListenerContainerFactory") ConcurrentKafkaListenerContainerFactory<Object, Object> factory;
    @Override String modelSnapshot() { return "{\"properties\":{\"value\":{\"dataType\":\"NUMBER\",\"accessType\":\"REPORT\"}},\"events\":{},\"commands\":{}}"; }
    @Override WebhookSubscriptionService.Spec spec() {
        return new WebhookSubscriptionService.Spec("source-journey", receiver.target(), List.of("device.online", "device.offline", "device.property.report", "alarm.triggered", "alarm.recovered"), List.of(device));
    }
    @AfterEach void cleanupJourney() {
        receiver.release.countDown();
        owner.execute("GRANT INSERT ON integ_webhook_event TO thingslink_app");
        owner.execute("DROP TRIGGER IF EXISTS test_source_journey_commit ON integ_webhook_delivery");
        owner.execute("DROP FUNCTION IF EXISTS test_source_journey_commit()");
        for (String table : List.of("alarm_event", "alarm_instance", "alarm_rule", "dev_access_binding", "dev_credential", "sys_outbox_event", "ts_property_point_internal", "ts_device_message_log", "ts_property_aggregate_backfill", "sys_message_log_inbox", "sys_inbox_message"))
            owner.update("DELETE FROM " + table + " WHERE project_id=?", project);
    }

    @Test @SuppressWarnings("unchecked")
    void actualSourcesSurviveAdmissionFailureAndUncertainDeliveryWithOriginalIdentity() throws Exception {
        receiver.reset(); var subscription = create(Uuid7.generate());
        String topic = PublicWebhookSource.TOPIC, group = "source-journey-" + UUID.randomUUID();
        try (var admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1), new NewTopic("tc.dlq", 1, (short) 1))).all().get(20, TimeUnit.SECONDS);
            var container = factory.createContainer(topic); var failures = new AtomicInteger();
            container.getContainerProperties().setGroupId(group);
            container.getContainerProperties().setMessageListener((MessageListener<Object, Object>) record -> {
                try { consumer.consume((ConsumerRecord<byte[], byte[]>) (Object) record); }
                catch (RuntimeException failure) { failures.incrementAndGet(); throw failure; }
            });
            var publisher = new KafkaTransactionalOutboxPublisher(outbox, kafka, metrics, json, 8, 30);
            container.start();
            try {
                produceRealFacts();
                var originals = new HashMap<UUID, PublicWebhookSource>();
                for (String text : owner.queryForList("SELECT payload FROM sys_outbox_event WHERE project_id=? AND event_type='PUBLIC_WEBHOOK_SOURCE'", String.class, project)) {
                    var source = json.readValue(text, PublicWebhookSource.class); originals.put(source.event().eventId(), source);
                }
                assertThat(originals).hasSize(6);
                assertThat(originals.values().stream().map(s -> s.event().eventType()).distinct()).containsExactlyInAnyOrder("device.online", "device.offline", "device.property.report", "alarm.triggered", "alarm.recovered");
                owner.execute("REVOKE INSERT ON integ_webhook_event FROM thingslink_app");
                try {
                    await().atMost(Duration.ofSeconds(30)).until(() -> {
                        publisher.publishReadyEvents();
                        return owner.queryForObject("SELECT count(*) FROM sys_outbox_event WHERE project_id=? AND event_type='PUBLIC_WEBHOOK_SOURCE' AND published_at IS NOT NULL", Integer.class, project) == 6;
                    });
                    await().atMost(Duration.ofSeconds(20)).until(() -> failures.get() >= 3);
                    assertThat(offset(admin, group, topic)).isLessThanOrEqualTo(0); assertThat(rows("integ_webhook_event")).isZero();
                    assertThat(receiver.received).isEmpty();
                    assertThat(admin.listOffsets(Map.of(new TopicPartition("tc.dlq", 0), OffsetSpec.latest())).all().get(10, TimeUnit.SECONDS).get(new TopicPartition("tc.dlq", 0)).offset()).isZero();
                } finally { owner.execute("GRANT INSERT ON integ_webhook_event TO thingslink_app"); }
                await().atMost(Duration.ofSeconds(30)).until(() -> offset(admin, group, topic) == 6);
                assertThat(rows("integ_webhook_event")).isEqualTo(6); assertThat(rows("integ_webhook_delivery")).isEqualTo(6);
                var ids = owner.queryForList("SELECT id FROM integ_webhook_delivery WHERE project_id=? ORDER BY id", UUID.class, project);
                UUID uncertain = ids.getFirst();
                owner.execute("CREATE FUNCTION test_source_journey_commit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.id='" + uncertain + "'::uuid AND NEW.status='SUCCEEDED' THEN RAISE EXCEPTION 'TEST_SOURCE_COMMIT_FAILURE'; END IF; RETURN NEW; END $$");
                try {
                    owner.execute("CREATE CONSTRAINT TRIGGER test_source_journey_commit AFTER UPDATE ON integ_webhook_delivery DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION test_source_journey_commit()");
                    assertThatThrownBy(() -> dispatcher.dispatch(candidate(uncertain))).isInstanceOf(RuntimeException.class);
                } finally { owner.execute("DROP TRIGGER IF EXISTS test_source_journey_commit ON integ_webhook_delivery"); owner.execute("DROP FUNCTION test_source_journey_commit()"); }
                assertThat(receiver.received).hasSize(1); assertThat(status(uncertain)).isEqualTo("IN_FLIGHT");
                owner.update("UPDATE integ_webhook_delivery SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?", uncertain);
                for (UUID id : ids) dispatcher.dispatch(candidate(id));
                assertThat(receiver.received).hasSize(7); assertThat(receiver.acceptedEffects()).isEqualTo(6);
                assertThat(owner.queryForObject("SELECT count(*) FROM integ_webhook_delivery WHERE project_id=? AND status='SUCCEEDED'", Integer.class, project)).isEqualTo(6);
                assertThat(owner.queryForList("SELECT result FROM integ_webhook_attempt WHERE delivery_id=? ORDER BY attempt_no", String.class, uncertain)).containsExactly("UNKNOWN", "SUCCEEDED");
                assertThat(receiver.received.get(1).body()).containsExactly(receiver.received.getFirst().body());
                for (var wire : receiver.received) verifyWire(wire, subscription.signingSecret(), originals);
                assertThat(receiver.received.stream().map(w -> new String(w.body(), StandardCharsets.UTF_8)).anyMatch(s -> s.contains(PRECISE))).isTrue();
                var duplicate = originals.values().iterator().next(); kafka.send(topic, device.toString(), duplicate).get(10, TimeUnit.SECONDS);
                await().atMost(Duration.ofSeconds(20)).until(() -> offset(admin, group, topic) == 7);
                assertThat(rows("integ_webhook_delivery")).isEqualTo(6);
                for (UUID id : ids) dispatcher.dispatch(candidate(id)); assertThat(receiver.received).hasSize(7);
            } finally { container.stop(); publisher.destroy(); }
        }
    }

    /** 实际HTTP活动、标准摄入和实际配置控制形成六条来源，包含两次属性报告。 */
    private void produceRealFacts() throws Exception {
        owner.update("INSERT INTO dev_access_binding(device_id,tenant_id,project_id,protocol) VALUES(?,?,?,'HTTP')", device, tenant, project);
        owner.update("INSERT INTO dev_credential(id,tenant_id,project_id,device_id,auth_type,credential_hash,display_name) VALUES(gen_random_uuid(),?,?,?,'ACCESS_TOKEN',encode(digest(?,'sha256'),'hex'),'source-journey')", tenant, project, device, PASSWORD);
        owner.update("INSERT INTO alarm_rule(id,tenant_id,project_id,name,alarm_type,originator_id,property_key,trigger_operator,trigger_threshold,clear_operator,clear_threshold,severity) VALUES(gen_random_uuid(),?,?,'journey','TYPE',?,'value','GT',30,'LT',25,'WARNING')", tenant, project, device);
        String username = owner.queryForObject("SELECT project_key FROM sys_project WHERE id=?", String.class, project) + "/" + owner.queryForObject("SELECT device_key FROM dev_device WHERE id=?", String.class, device);
        assertThat(request("POST", "/device-access/v1/command/claim", "{}", null, Map.of("X-TC-Device-Key", username, "X-TC-Device-Secret", PASSWORD)).statusCode()).isEqualTo(204);
        var now = Instant.now(); var high = report(now, new BigDecimal(PRECISE));
        assertThat(ingestion.ingest(high)).isTrue(); assertThat(ingestion.ingest(high)).isFalse();
        assertThat(ingestion.ingest(report(now.plusMillis(1), new BigDecimal("20.01234567890123456789")))).isTrue();
        TenantContext.set(new TenantScope(tenant, project, account));
        try { control.change(project, device, 1, TransportProtocol.HTTP, false); } finally { TenantContext.clear(); }
        assertThat(owner.queryForObject("SELECT condition_state FROM alarm_instance WHERE project_id=?", String.class, project)).isEqualTo("CLEARED");
        assertThat(owner.queryForObject("SELECT activity_online FROM dev_access_binding WHERE device_id=?", Boolean.class, device)).isFalse();
    }
    private StandardUplinkMessage report(Instant at, BigDecimal value) {
        return new StandardUplinkMessage(Uuid7.generate(), tenant, project, device, null, TransportProtocol.HTTP,
                StandardUplinkMessage.Direction.UP, StandardUplinkMessage.Type.PROPERTY_REPORT, "1.0.0", at, at, "source-journey", 100, Map.of("value", value));
    }
    private WebhookDeliveryRepository.Candidate candidate(UUID id) { return new WebhookDeliveryRepository.Candidate(tenant, project, id); }
    private String status(UUID id) { return owner.queryForObject("SELECT status FROM integ_webhook_delivery WHERE id=?", String.class, id); }
    private long offset(AdminClient admin, String group, String topic) throws Exception {
        var value = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS).get(new TopicPartition(topic, 0));
        return value == null ? -1 : value.offset();
    }
    /** 接收端逐字节核验签名，同时比对原PG冻结正文，不能仅验证投递次数。 */
    private void verifyWire(WebhookTlsReceiver.Received wire, String secret, Map<UUID, PublicWebhookSource> originals) throws Exception {
        String body = new String(wire.body(), StandardCharsets.UTF_8), id = wire.headers().getFirst("X-ThingsLink-Delivery-Id");
        var event = json.readTree(body).path("event"); var original = originals.get(UUID.fromString(event.path("eventId").asString()));
        assertThat(original).isNotNull(); assertThat(event).isEqualTo(json.readTree(original.eventText()));
        var mac = javax.crypto.Mac.getInstance("HmacSHA256"); mac.init(new javax.crypto.spec.SecretKeySpec(Base64.getDecoder().decode(secret), "HmacSHA256"));
        String canonical = wire.headers().getFirst("X-ThingsLink-Timestamp") + "\n" + wire.headers().getFirst("X-ThingsLink-Nonce") + "\n" + id + "\n" + body;
        assertThat(wire.headers().getFirst("X-ThingsLink-Signature")).isEqualTo("v1=" + HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8))));
    }
}
