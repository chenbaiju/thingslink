package com.things.link.bootstrap.rule;

import com.things.link.bootstrap.fixture.DeviceProtocolKafkaTopicsTestConfiguration;
import com.things.link.bootstrap.fixture.KafkaRecordConsumptionBarrier;
import com.things.link.ingestion.infrastructure.NormalizedUplinkKafkaConsumer;
import com.things.link.rule.application.ActionSpec;
import com.things.link.rule.application.ConditionSpec;
import com.things.link.rule.application.CreateMessageRuleCommand;
import com.things.link.rule.application.MessageRuleService;
import com.things.link.rule.application.automation.AutomationManagementService;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.AutomationPropertyAccepted;
import com.things.link.shared.message.StandardUplinkMessage;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.notification.delivery.ExternalNotificationSender;
import com.things.link.testing.AbstractKafkaIntegrationTest;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** 真实 normalized→规则→processed→遥测 Outbox→属性自动化→通知的同源消息组合。 */
@Import({RuleActionOutboxIntegrationTests.KafkaTopicTestConfiguration.class,
        RuleAutomationSameSourceIntegrationTests.AutomationTopicTestConfiguration.class,
        DeviceProtocolKafkaTopicsTestConfiguration.class})
@TestPropertySource(properties = {
        "spring.kafka.admin.auto-create=true",
        "spring.kafka.listener.auto-startup=true",
        "things-link.outbox.publisher.enabled=true",
        "things-link.automation.property.enabled=true",
        "things-link.automation.execution.scan-delay-millis=1000"
})
class RuleAutomationSameSourceIntegrationTests extends AbstractKafkaIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofMinutes(5);
    private static final String MODEL = "{\"properties\":{\"temperature\":{\"dataType\":\"NUMBER\","
            + "\"accessType\":\"REPORT\"}},\"events\":{},\"commands\":{}}";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private KafkaTemplate<String, Object> kafka;
    @Autowired private MessageRuleService rules;
    @Autowired private AutomationManagementService automations;
    @MockitoBean(name = ExternalNotificationSender.EMAIL_BEAN)
    private ExternalNotificationSender emailSender;
    private Fixture fixture;

    private record Fixture(UUID tenant, UUID account, UUID project, UUID type, UUID device, UUID policy) {
    }

    @BeforeEach
    void sender() {
        when(emailSender.channel()).thenReturn("EMAIL");
        when(emailSender.send(any())).thenAnswer(invocation -> "same-source-test:"
                + ((com.things.link.support.notification.delivery.ExternalNotificationRequest)
                invocation.getArgument(0)).deliveryId());
    }

    @AfterEach
    void clearFacts() throws Exception {
        TenantContext.clear();
        if (fixture == null) {
            return;
        }
        String project = fixture.project().toString();
        String tenant = fixture.tenant().toString();
        try (Connection connection = POSTGRES.createConnection("");
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM sys_tenant_work_slot WHERE tenant_id='" + tenant + "'");
            statement.executeUpdate("DELETE FROM sys_outbox_event WHERE project_id='" + project + "'");
            statement.executeUpdate("DELETE FROM rule_automation_attempt WHERE project_id='" + project + "'");
            statement.executeUpdate("DELETE FROM rule_notification_delivery WHERE project_id='" + project + "'");
            statement.executeUpdate("DELETE FROM rule_automation_execution WHERE project_id='" + project + "'");
            statement.executeUpdate("DELETE FROM rule_automation_event_receipt WHERE project_id='" + project + "'");
            statement.executeUpdate("UPDATE rule_automation SET active_version_id=NULL,status='DRAFT'"
                    + " WHERE project_id='" + project + "'");
            statement.executeUpdate("DELETE FROM rule_automation_version WHERE project_id='" + project + "'");
            statement.executeUpdate("DELETE FROM rule_automation WHERE project_id='" + project + "'");
            statement.executeUpdate("DELETE FROM sys_automation_quota_reservation WHERE project_id='" + project + "'");
            statement.executeUpdate("DELETE FROM rule_debug_event WHERE project_id='" + project + "'");
            statement.executeUpdate("DELETE FROM rule_execution_log WHERE project_id='" + project + "'");
            statement.executeUpdate("DELETE FROM rule_execution_receipt WHERE project_id='" + project + "'");
            statement.executeUpdate("UPDATE rule_message SET status='DRAFT',active_version_id=NULL"
                    + " WHERE project_id='" + project + "'");
            statement.executeUpdate("DELETE FROM rule_version WHERE project_id='" + project + "'");
            statement.executeUpdate("DELETE FROM rule_message WHERE project_id='" + project + "'");
            statement.executeUpdate("DELETE FROM public.ts_property_point_internal WHERE project_id='"
                    + project + "'");
            statement.executeUpdate("DELETE FROM ts_device_message_log WHERE project_id='" + project + "'");
            statement.executeUpdate("DELETE FROM sys_inbox_message WHERE project_id='" + project + "'");
            statement.executeUpdate("DELETE FROM sys_usage_counter_daily WHERE tenant_id='" + tenant + "'");
            statement.executeUpdate("DELETE FROM dev_device WHERE project_id='" + project + "'");
            statement.executeUpdate("DELETE FROM dev_device_model_binding_history WHERE project_id='"
                    + project + "'");
            statement.executeUpdate("DELETE FROM dev_thing_model_version WHERE project_id='" + project + "'");
            statement.executeUpdate("DELETE FROM dev_type WHERE project_id='" + project + "'");
            statement.executeUpdate("DELETE FROM sys_project_member WHERE project_id='" + project + "'");
            statement.executeUpdate("DELETE FROM sys_project WHERE id='" + project + "'");
            statement.executeUpdate("DELETE FROM sys_tenant_member WHERE tenant_id='" + tenant + "'");
            statement.executeUpdate("DELETE FROM sys_account WHERE id='" + fixture.account() + "'");
            statement.executeUpdate("DELETE FROM sys_tenant WHERE id='" + tenant + "'");
            statement.executeUpdate("DELETE FROM sys_quota_policy WHERE id='" + fixture.policy() + "'");
        }
    }

    @Test
    void transformedRulePayloadDrivesAutomationOnceAcrossReplay() throws Exception {
        Fixture fixture = seed();
        activateRule(fixture);
        activateAutomation(fixture);
        UUID messageId = Uuid7.generate();
        RecordMetadata first = send(fixture, messageId);
        awaitConsumed(first);
        await("派生遥测和自动化通知未完成", () -> inScope(fixture,
                "SELECT count(*) FROM ts_property_point WHERE message_id=? AND abs(value_double-27.0)<0.0001",
                messageId) == 1 && inScope(fixture,
                "SELECT count(*) FROM rule_automation_execution WHERE source_event_id=? AND status='DISPATCHED'",
                messageId) == 1 && inScope(fixture,
                "SELECT count(*) FROM rule_notification_delivery WHERE project_id=? AND status='DELIVERED'",
                fixture.project()) == 2);

        inScope(fixture, () -> {
            UUID execution = jdbc.queryForObject("SELECT id FROM rule_automation_execution WHERE source_event_id=?",
                    UUID.class, messageId);
            String input = jdbc.queryForObject("SELECT input_snapshot::text FROM rule_automation_execution WHERE id=?",
                    String.class, execution);
            assertThat(new ObjectMapper().readTree(input).path("temperature").doubleValue()).isEqualTo(27.0);
            assertThat(count("SELECT count(*) FROM rule_execution_log WHERE message_id=? AND status='SUCCESS'",
                    messageId)).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM rule_automation_attempt WHERE execution_id=?", execution))
                    .isEqualTo(1);
            assertThat(count("SELECT count(*) FROM sys_automation_quota_reservation WHERE execution_id=?", execution))
                    .isEqualTo(1);
            assertThat(count("SELECT count(*) FROM rule_notification_delivery WHERE automation_execution_id=?",
                    execution)).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM rule_notification_delivery WHERE message_id=?", messageId))
                    .isEqualTo(1);
            assertThat(jdbc.queryForList("SELECT recipient FROM rule_notification_delivery WHERE project_id=?",
                    String.class, fixture.project())).containsExactlyInAnyOrder(
                    "rule@example.com", "automation@example.com");
        });

        RecordMetadata replay = send(fixture, messageId);
        awaitConsumed(replay);
        inScope(fixture, () -> {
            assertThat(count("SELECT count(*) FROM rule_automation_execution WHERE source_event_id=?", messageId))
                    .isEqualTo(1);
            assertThat(count("SELECT count(*) FROM rule_execution_receipt WHERE message_id=?", messageId))
                    .isEqualTo(1);
            assertThat(count("SELECT count(*) FROM ts_property_point WHERE message_id=?", messageId))
                    .isEqualTo(1);
            assertThat(count("SELECT count(*) FROM rule_notification_delivery WHERE project_id=?",
                    fixture.project())).isEqualTo(2);
        });
    }

    private Fixture seed() throws Exception {
        Fixture f = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                Uuid7.generate(), Uuid7.generate(), Uuid7.generate());
        fixture = f;
        try (Connection connection = POSTGRES.createConnection("");
             Statement sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO sys_quota_policy(id,code,automation_execution_daily_limit) VALUES ('"
                    + f.policy() + "','same_" + f.policy().toString().replace("-", "").substring(0, 12)
                    + "',10)");
            sql.executeUpdate("INSERT INTO sys_tenant(id,name,quota_policy_id) VALUES ('"
                    + f.tenant() + "','same-source','" + f.policy() + "')");
            sql.executeUpdate("INSERT INTO sys_account(id,email,password_hash,display_name) VALUES ('"
                    + f.account() + "','" + f.account() + "@example.com','{noop}unused','same-source')");
            sql.executeUpdate("INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES ('"
                    + Uuid7.generate() + "','" + f.tenant() + "','" + f.account() + "')");
            sql.executeUpdate("INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES ('"
                    + f.project() + "','" + f.tenant() + "','same-source','sh-1','"
                    + f.project().toString().replace("-", "") + "')");
            sql.executeUpdate("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES ('"
                    + Uuid7.generate() + "','" + f.project() + "','" + f.account() + "','OWNER')");
            sql.executeUpdate("INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,access_protocol,device_kind,status) VALUES ('"
                    + f.type() + "','" + f.tenant() + "','" + f.project() + "','same_"
                    + f.type().toString().replace("-", "").substring(0, 12)
                    + "','same-source','STANDARD','DIRECT','PUBLISHED')");
            sql.executeUpdate("INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,status) VALUES ('"
                    + f.device() + "','" + f.tenant() + "','" + f.project() + "','" + f.type()
                    + "','same_" + f.device().toString().replace("-", "").substring(0, 12)
                    + "','same-source','ONLINE')");
            sql.executeUpdate("INSERT INTO dev_property_definition(id,tenant_id,project_id,device_type_id,property_key,name,access_type,data_type) VALUES ('"
                    + Uuid7.generate() + "','" + f.tenant() + "','" + f.project()
                    + "','" + f.type() + "','temperature','温度','REPORT','NUMBER')");
        }
        seedThingModelVersion(f.tenant(), f.project(), f.type(), f.device(), MODEL);
        return f;
    }

    private void activateRule(Fixture f) {
        inScope(f, () -> {
            var rule = rules.create(f.project(), new CreateMessageRuleCommand("same-source-rule", null,
                    "input => ({temperature: input.temperature + 0.5})",
                    List.of(new ActionSpec("notification-action", notification("rule@example.com")))));
            UUID version = rules.versions(f.project(), rule.id()).getFirst().id();
            rules.activate(f.project(), rule.id(), version, rule.version());
        });
    }

    private void activateAutomation(Fixture f) {
        inScope(f, () -> {
            ObjectNode condition = new ObjectMapper().createObjectNode().put("pointer", "/temperature")
                    .put("operator", "GT").put("value", 26.7);
            var edit = new AutomationManagementService.Edit("same-source-automation", null,
                    "PROPERTY_REPORTED", new ObjectMapper().createObjectNode().put("deviceId", f.device().toString()),
                    List.of(new ConditionSpec("payload-property-compare", condition)),
                    List.of(new ActionSpec("notification-action", notification("automation@example.com"))));
            var automation = automations.create(f.project(), edit);
            UUID version = jdbc.queryForObject("SELECT id FROM rule_automation_version WHERE automation_id=?",
                    UUID.class, automation.id());
            automations.activate(f.project(), automation.id(), version, automation.version());
        });
    }

    private ObjectNode notification(String recipient) {
        return new ObjectMapper().createObjectNode().put("channel", "email").put("recipient", recipient)
                .put("subject", "same source").put("body", "processed temperature");
    }

    private RecordMetadata send(Fixture f, UUID id) throws Exception {
        Instant now = Instant.now();
        var message = new StandardUplinkMessage(id, f.tenant(), f.project(), f.device(), null,
                TransportProtocol.MQTT, StandardUplinkMessage.Direction.UP,
                StandardUplinkMessage.Type.PROPERTY_REPORT, "1.0.0", now.minusSeconds(1), now,
                "same-source-" + id, 32, Map.of("temperature", 26.5));
        return kafka.send(NormalizedUplinkKafkaConsumer.NORMALIZED_UPLINK_TOPIC, f.device().toString(), message)
                .get(10, TimeUnit.SECONDS).getRecordMetadata();
    }

    private void awaitConsumed(RecordMetadata record) {
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 2000,
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 2000))) {
            new KafkaRecordConsumptionBarrier(TIMEOUT, () -> {
                try {
                    return admin.listConsumerGroupOffsets("things-link-ingestion-normalized")
                            .partitionsToOffsetAndMetadata().get(2, TimeUnit.SECONDS);
                } catch (Exception error) {
                    throw new IllegalStateException("normalized 消费位点查询失败", error);
                }
            }).awaitConsumed(List.of(record));
        }
    }

    private void await(String description, BooleanSupplier ready) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (!ready.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError(description);
            }
            Thread.sleep(50);
        }
    }

    private int inScope(Fixture f, String query, Object... args) {
        TenantContext.set(new TenantScope(f.tenant(), f.project(), f.account()));
        try {
            return count(query, args);
        } finally {
            TenantContext.clear();
        }
    }

    private int count(String query, Object... args) {
        return jdbc.queryForObject(query, Integer.class, args);
    }

    private void inScope(Fixture f, Runnable body) {
        TenantContext.set(new TenantScope(f.tenant(), f.project(), f.account()));
        try {
            body.run();
        } finally {
            TenantContext.clear();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class AutomationTopicTestConfiguration {
        @Bean
        NewTopic automationPropertyAcceptedTopic() {
            return TopicBuilder.name(AutomationPropertyAccepted.TOPIC).partitions(3).replicas(1).build();
        }
    }
}
