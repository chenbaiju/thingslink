package com.things.link.bootstrap.rule;

import com.things.link.bootstrap.fixture.DeviceDataPlaneTopicsTestConfiguration;
import com.things.link.device.domain.ThingModelVersionRepository;
import com.things.link.rule.application.ActionSpec;
import com.things.link.rule.application.automation.AutomationExecutionRunner;
import com.things.link.rule.application.automation.AutomationManagementService;
import com.things.link.rule.application.automation.AutomationTimeScheduler;
import com.things.link.rule.domain.AutomationExecutionRepository;
import com.things.link.rule.domain.AutomationScheduleRepository;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.AutomationPropertyAccepted;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.mqtt.BrokerIngressReadiness;
import com.things.link.testing.AbstractKafkaIntegrationTest;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TopicExistsException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * R4-3d：自然时间发生点至真实TLS设备回复的连续软件链；不代表物理继电器或部署资格。
 *
 * <p>只创建本类Broker与租户，使用部署认证/ACL/durable规则、真实生产调度/Outbox/Kafka消费。
 * 不回写调度时间、不缩短租约，不直接调用发布器、回复服务或写入命令终态。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "spring.kafka.admin.auto-create=true",
        "spring.kafka.listener.auto-startup=false",
        "things-link.outbox.publisher.enabled=true",
        "things-link.automation.time.enabled=true"
})
@Import(DeviceDataPlaneTopicsTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AutomationTimeMqttJourneyTests extends AbstractKafkaIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Path TLS = Path.of("../things-link-ingestion/src/test/resources/tls");
    private static final String CALLBACK = UUID.randomUUID().toString();
    private static final String INGRESS_PASSWORD = UUID.randomUUID().toString();
    private static final String API_KEY = "time-journey-publisher";
    private static final String API_SECRET = UUID.randomUUID().toString();
    private static final Path EVIDENCE = evidenceDirectory();
    private static final int APP_PORT = freePort();
    private static final GenericContainer<?> BROKER = broker();

    @Autowired private AutomationManagementService management;
    @Autowired private AutomationTimeScheduler timeScheduler;
    @Autowired private AutomationExecutionRunner executionRunner;
    @Autowired private BrokerIngressReadiness ingress;
    @Autowired private KafkaListenerEndpointRegistry listeners;
    @Autowired private ThingModelVersionRepository models;
    @Autowired private TransactionTemplate transactions;
    @Autowired private TransactionLocalRlsScope rls;
    private final List<MessageListenerContainer> propertyListeners = new ArrayList<>();
    private boolean propertyJourney;
    private final List<Fixture> fixtures = new ArrayList<>();
    private final List<Device> clients = new ArrayList<>();
    private UUID automation;
    private Fixture target;

    /** 独占随机HTTP端口只供真实Broker调用既有平台回调，不增加测试生产API。 */
    private static int freePort() {
        try (var socket = new ServerSocket(0)) { return socket.getLocalPort(); }
        catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }

    /** 沿既有认证Broker夹具读取单源HOCON，只替换本次回调、API身份和测试TLS证书。 */
    private static GenericContainer<?> broker() {
        try {
            Testcontainers.exposeHostPorts(APP_PORT);
            String config = Files.readString(Path.of("../../deploy/emqx/base.hocon"))
                    .replace("http://host.docker.internal:8080", "http://host.testcontainers.internal:" + APP_PORT)
                    .replace("dev-only-broker-callback-secret-do-not-use-in-production", CALLBACK)
                    + "\napi_key.bootstrap_file = \"/opt/emqx/etc/time-journey-keys\"\n"
                    + "listeners.ssl.default.ssl_options.certfile = \"/opt/emqx/etc/time-cert.pem\"\n"
                    + "listeners.ssl.default.ssl_options.keyfile = \"/opt/emqx/etc/time-key.pem\"\n";
            var broker = new GenericContainer<>("emqx/emqx:6.2.3")
                    .withExposedPorts(1883, 8883, 18083)
                    .withCopyToContainer(Transferable.of(config.getBytes(StandardCharsets.UTF_8), 0444), "/opt/emqx/etc/base.hocon")
                    .withCopyToContainer(Transferable.of((API_KEY + ":" + API_SECRET + ":publisher\n").getBytes(StandardCharsets.UTF_8), 0444), "/opt/emqx/etc/time-journey-keys")
                    .withCopyToContainer(Transferable.of(Files.readAllBytes(TLS.resolve("device-access-test-cert.pem")), 0444), "/opt/emqx/etc/time-cert.pem")
                    .withCopyToContainer(Transferable.of(Files.readAllBytes(TLS.resolve("device-access-test-key.pem")), 0444), "/opt/emqx/etc/time-key.pem")
                    .waitingFor(Wait.forHttp("/status").forPort(18083).forStatusCode(200).withStartupTimeout(Duration.ofSeconds(120)));
            try { broker.start(); return broker; }
            catch (RuntimeException failure) { broker.stop(); throw failure; }
        } catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }

    /** 内部ingress仍走既有独占Broker网络；外部设备严格走TLS与平台签发的设备身份。 */
    @DynamicPropertySource static void environment(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> APP_PORT);
        registry.add("things-link.security.broker-callback.secret", () -> CALLBACK);
        registry.add("things-link.ingestion.emqx-api.base-url", () -> "http://" + BROKER.getHost() + ":" + BROKER.getMappedPort(18083));
        registry.add("things-link.ingestion.emqx-api.api-key", () -> API_KEY);
        registry.add("things-link.ingestion.emqx-api.api-secret", () -> API_SECRET);
        registry.add("things-link.ingress.handoff.enabled", () -> true);
        registry.add("things-link.ingress.handoff.broker-uri", () -> "tcp://" + BROKER.getHost() + ":" + BROKER.getMappedPort(1883));
        registry.add("things-link.ingress.handoff.username", () -> "thingslink-uplink-ingress");
        registry.add("things-link.ingress.handoff.password", () -> INGRESS_PASSWORD);
        registry.add("things-link.ingress.handoff.qualification.enabled", () -> true);
        registry.add("things-link.ingress.handoff.qualification.evidence-path", () -> EVIDENCE.resolve("handoff.jsonl"));
        registry.add("things-link.ingress.handoff.qualification.scenario-path", () -> EVIDENCE.resolve("scenario.txt"));
        registry.add("things-link.ingress.handoff.qualification.ack-barrier-path", () -> EVIDENCE.resolve("unused-ack-barrier"));
    }

    /** 不停止共享PG/Redis/Kafka，Broker仅属于此类。 */
    @AfterAll static void stopBroker() { BROKER.stop(); }

    /** SUBACK和Kafka分区就绪先于产生未来任务，避免把启动耗时误作业务超时。 */
    @BeforeEach void ready() {
        var listener = listeners.getListenerContainer("sharedCommandDownlink");
        assertThat(listener).isNotNull();
        if (!listener.isRunning()) listener.start();
        await().atMost(Duration.ofSeconds(45)).until(ingress::isReady);
        await().atMost(Duration.ofSeconds(30)).until(() -> listener.getAssignedPartitions() != null && !listener.getAssignedPartitions().isEmpty());
        target = seed();
    }

    /** 两个自然时间触发各生成真实COMMAND/PROPERTY_SET并经原ID回复收敛；重放与外租户不能多写事实。 */
    @ParameterizedTest
    @ValueSource(strings = {"ONE_SHOT", "CRON"})
    void naturalTimeActionsReachAuthenticatedTlsDevicesAndOriginalReplies(String trigger) throws Exception {
        Fixture foreign = seed();
        Device own = connect(target), other = connect(foreign);
        own.subscribe(target.base() + "/down/command/+", true);
        own.subscribe(target.base() + "/down/property/set", true);
        other.subscribe(foreign.base() + "/down/command/+", true);
        other.subscribe(foreign.base() + "/down/property/set", true);
        other.subscribe(target.base() + "/down/command/+", false);
        other.subscribe(target.base() + "/down/property/set", false);
        assertThat(other.client.isConnected()).isTrue();

        Instant planned = now().plusSeconds(10);
        var config = JSON.createObjectNode().put("deviceId", target.device().toString());
        if (trigger.equals("CRON")) config.put("cronExpression", planned.atZone(ZoneOffset.UTC).getSecond() + " * * * * *").put("timezone", "UTC");
        else config.put("runAt", planned.toString());
        var actions = List.of(
                new ActionSpec("device-command-action", JSON.createObjectNode().put("commandKey", "reboot").set("input", JSON.createObjectNode())),
                new ActionSpec("device-property-set-action", JSON.createObjectNode().set("properties", JSON.createObjectNode().put("power", true))));
        var definition = asOwner(() -> management.create(target.project(), new AutomationManagementService.Edit("real time MQTT", null, trigger, config, List.of(), actions)));
        automation = definition.id();
        UUID version = owner().queryForObject("SELECT id FROM rule_automation_version WHERE automation_id=?", UUID.class, automation);
        asOwner(() -> management.activate(target.project(), automation, version, definition.version()));
        Instant fireAt = owner().queryForObject("SELECT next_fire_at FROM rule_automation_schedule WHERE automation_id=?", Timestamp.class, automation).toInstant();
        assertThat(fireAt).isAfter(now());
        // No direct scanner/runner call initiates delivery: natural production @Scheduled workers own this chain.
        await().atMost(Duration.ofSeconds(85)).until(() -> own.received.size() >= 2);
        await().atMost(Duration.ofSeconds(10)).until(() -> count(target, "rule_automation_execution", " AND status='DISPATCHED'") == 1);
        UUID execution = owner().queryForObject("SELECT id FROM rule_automation_execution WHERE automation_id=?", UUID.class, automation);
        var fact = owner().queryForMap("SELECT trigger_type,automation_version_id,scheduled_fire_at,accepted_at FROM rule_automation_execution WHERE id=?", execution);
        assertThat(fact).containsEntry("trigger_type", trigger).containsEntry("automation_version_id", version).containsEntry("scheduled_fire_at", Timestamp.from(fireAt));
        assertThat(((Timestamp) fact.get("accepted_at")).toInstant()).isAfterOrEqualTo(fireAt);
        // Replaying the original candidate before the next legal minute cannot create a second occurrence/action.
        timeScheduler.run(new AutomationScheduleRepository.Candidate(target.tenant(), target.project(), automation));
        executionRunner.run(new AutomationExecutionRepository.Candidate(target.tenant(), target.project(), automation, execution));
        pause(); // Subsequent legal CRON minutes are different occurrences and outside this single-occurrence test.

        await().atMost(Duration.ofSeconds(10)).until(() -> count(target, "ts_device_command", " AND status='DISPATCHED'") == 2);
        var commands = owner().queryForList("SELECT id,operation_type FROM ts_device_command WHERE tenant_id=?", target.tenant());
        assertThat(commands).hasSize(2);
        assertThat(own.received).hasSize(2);
        for (var command : commands) {
            UUID id = (UUID) command.get("id");
            boolean property = command.get("operation_type").equals("PROPERTY_SET");
            String down = target.base() + (property ? "/down/property/set" : "/down/command/" + id);
            var messages = own.received.stream().filter(item -> item.topic().equals(down)).toList();
            assertThat(messages).hasSize(1);
            Message message = messages.getFirst();
            assertThat(message.qos()).isEqualTo(1); assertThat(message.retained()).isFalse();
            JsonNode body = JSON.readTree(message.bytes());
            assertThat(body.path("targetDeviceKey").asString()).isEqualTo("test");
            assertThat(body.path("attempt").asInt()).isEqualTo(1);
            if (property) { assertThat(body.path("requestId").asString()).isEqualTo(id.toString()); assertThat(body.path("properties")).isEqualTo(JSON.createObjectNode().put("power", true)); }
            else { assertThat(body.path("commandKey").asString()).isEqualTo("reboot"); assertThat(body.path("input")).isEqualTo(JSON.createObjectNode()); }
            UUID replyId = Uuid7.generate();
            byte[] reply = JSON.writeValueAsBytes(JSON.createObjectNode().put("messageId", replyId.toString()).put("requestId", id.toString())
                    .put("occurredAt", now().toString()).put("status", "SUCCESS").set("output", JSON.createObjectNode()));
            String suffix = property ? "/up/property/set/reply" : "/up/command/" + id + "/reply";
            UUID foreignReplyId = Uuid7.generate();
            var foreignReply = (tools.jackson.databind.node.ObjectNode) JSON.readTree(reply);
            foreignReply.put("messageId", foreignReplyId.toString());
            other.publish(foreign.base() + suffix, JSON.writeValueAsBytes(foreignReply));
            await().atMost(Duration.ofSeconds(10)).until(() -> handoffs(foreignReplyId, "duplicate") == 1);
            assertThat(owner().queryForObject("SELECT status FROM ts_device_command WHERE id=?", String.class, id)).isEqualTo("DISPATCHED");
            own.publish(target.base() + suffix, reply);
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(owner().queryForObject("SELECT status FROM ts_device_command WHERE id=?", String.class, id)).isEqualTo("SUCCEEDED"));
            await().atMost(Duration.ofSeconds(10)).until(() -> handoffs(replyId, "accepted") == 1);
            own.publish(target.base() + suffix, reply);
            await().atMost(Duration.ofSeconds(10)).until(() -> handoffs(replyId, "duplicate") == 1);
            assertThat(owner().queryForObject("SELECT reply_message_id FROM ts_device_command_attempt WHERE command_id=? AND attempt_no=1", UUID.class, id)).isEqualTo(replyId);
            assertThat(owner().queryForObject("SELECT count(*) FROM sys_outbox_event WHERE aggregate_id=? AND event_type='DEVICE_COMMAND_TERMINAL'", Integer.class, id)).isEqualTo(1);
        }
        assertThat(other.client.isConnected()).isTrue(); assertThat(other.received).isEmpty();
        assertThat(count(target, "rule_automation_execution", "")).isEqualTo(1);
        assertThat(count(target, "sys_automation_quota_reservation", "")).isEqualTo(1);
        assertThat(owner().queryForObject("SELECT used_value FROM sys_usage_counter_daily WHERE project_id=? AND metric='AUTOMATION_EXECUTION'", Long.class, target.project())).isEqualTo(1L);
        assertThat(count(foreign, "ts_device_command", "")).isZero();
        assertThat(owner().queryForList("""
                SELECT command.operation_type FROM rule_device_action_delivery delivery
                JOIN ts_device_command command ON command.id=delivery.command_id
                JOIN ts_device_command_attempt attempt ON attempt.command_id=command.id
                JOIN sys_outbox_event event ON event.id=attempt.outbox_event_id
                WHERE delivery.automation_execution_id=? AND command.target_device_id=?
                  AND command.status='SUCCEEDED' AND command.attempt_count=1
                  AND attempt.attempt_no=1 AND attempt.status='SUCCEEDED'
                  AND event.event_type='DEVICE_COMMAND_DISPATCH' AND event.status='PUBLISHED'
                """, String.class, execution, target.device())).containsExactlyInAnyOrder("COMMAND", "PROPERTY_SET");
        assertThat(own.received).hasSize(2);
    }


    /** 真实属性上报经持久接管和四个生产消费者触发动作；原报文重放不能追加执行或计量。 */
    @Test
    void propertyReportReachesAuthenticatedTlsDevicesAndOriginalReplies() throws Exception {
        propertyJourney = true;
        Fixture foreign = seed();
        for (Fixture fixture : List.of(target, foreign)) {
            inFixture(fixture, () -> transactions.execute(status -> {
                rls.establish(fixture.tenant(), fixture.project());
                return models.createInitialFromDefinitions(fixture.tenant(), fixture.project(), fixture.type());
            }));
        }
        startPropertyListeners();
        Device own = connect(target), other = connect(foreign);
        own.subscribe(target.base() + "/down/command/+", true);
        own.subscribe(target.base() + "/down/property/set", true);
        other.subscribe(foreign.base() + "/down/command/+", true);
        other.subscribe(foreign.base() + "/down/property/set", true);
        other.subscribe(target.base() + "/down/command/+", false);
        other.subscribe(target.base() + "/down/property/set", false);
        var config = JSON.createObjectNode().put("deviceId", target.device().toString());
        var actions = List.of(
                new ActionSpec("device-command-action", JSON.createObjectNode().put("commandKey", "reboot").set("input", JSON.createObjectNode())),
                new ActionSpec("device-property-set-action", JSON.createObjectNode().set("properties", JSON.createObjectNode().put("power", true))));
        var definition = asOwner(() -> management.create(target.project(), new AutomationManagementService.Edit(
                "real property MQTT", null, "PROPERTY_REPORTED", config, List.of(), actions)));
        automation = definition.id();
        UUID version = owner().queryForObject("SELECT id FROM rule_automation_version WHERE automation_id=?", UUID.class, automation);
        asOwner(() -> management.activate(target.project(), automation, version, definition.version()));

        UUID foreignSource = Uuid7.generate();
        other.publish(foreign.base() + "/up/property/report", report(foreignSource));
        await().atMost(Duration.ofSeconds(30)).until(() -> acceptedEvents(foreign, foreignSource) == 1);
        drainPropertyPipeline();
        assertPropertyFacts(foreign, foreignSource);
        assertThat(count(target, "rule_automation_execution", "")).isZero();
        assertThat(count(foreign, "rule_automation_execution", "")).isZero();
        assertThat(own.received).isEmpty();

        UUID source = Uuid7.generate();
        byte[] original = report(source);
        own.publish(target.base() + "/up/property/report", original);
        await().atMost(Duration.ofSeconds(30)).until(() -> own.received.size() >= 2);
        await().atMost(Duration.ofSeconds(10)).until(() -> count(target, "rule_automation_execution", " AND status='DISPATCHED'") == 1);
        UUID execution = owner().queryForObject("SELECT id FROM rule_automation_execution WHERE automation_id=?", UUID.class, automation);
        assertThat(owner().queryForMap("SELECT trigger_type,automation_version_id,source_event_id,device_id FROM rule_automation_execution WHERE id=?", execution))
                .containsEntry("trigger_type", "PROPERTY_REPORTED").containsEntry("automation_version_id", version)
                .containsEntry("source_event_id", source).containsEntry("device_id", target.device());
        assertPropertyReplies(own, other, foreign, execution);
        assertPropertyFacts(target, source);

        // Keep the automation active: the original byte-for-byte report, not a paused rule, must be idempotent.
        own.publish(target.base() + "/up/property/report", original);
        await().atMost(Duration.ofSeconds(10)).until(() -> rawHandoffs(source, original) == 2);
        drainPropertyPipeline();
        assertPropertyFacts(target, source);
        assertThat(count(target, "rule_automation_execution", "")).isEqualTo(1);
        assertThat(count(target, "sys_automation_quota_reservation", "")).isEqualTo(1);
        assertThat(owner().queryForObject("SELECT used_value FROM sys_usage_counter_daily WHERE project_id=? AND metric='AUTOMATION_EXECUTION'", Long.class, target.project())).isEqualTo(1L);
        assertThat(count(target, "ts_device_command", "")).isEqualTo(2);
        assertThat(count(foreign, "ts_device_command", "")).isZero();
        assertThat(count(foreign, "sys_automation_quota_reservation", "")).isZero();
        assertThat(own.received).hasSize(2);
        assertThat(other.received).isEmpty();
    }

    /** 同一动作链的下行字节/原ID回复/外租户拒绝，沿已验证时间场景保持完全相同断言。 */
    private void assertPropertyReplies(Device own, Device other, Fixture foreign, UUID execution) throws Exception {
        await().atMost(Duration.ofSeconds(10)).until(() -> count(target, "ts_device_command", " AND status='DISPATCHED'") == 2);
        var commands = owner().queryForList("SELECT id,operation_type FROM ts_device_command WHERE tenant_id=?", target.tenant());
        assertThat(commands).hasSize(2);
        assertThat(own.received).hasSize(2);
        for (var command : commands) {
            UUID id = (UUID) command.get("id");
            boolean property = command.get("operation_type").equals("PROPERTY_SET");
            String down = target.base() + (property ? "/down/property/set" : "/down/command/" + id);
            var messages = own.received.stream().filter(item -> item.topic().equals(down)).toList();
            assertThat(messages).hasSize(1);
            Message message = messages.getFirst();
            assertThat(message.qos()).isEqualTo(1); assertThat(message.retained()).isFalse();
            JsonNode body = JSON.readTree(message.bytes());
            assertThat(body.path("targetDeviceKey").asString()).isEqualTo("test");
            assertThat(body.path("attempt").asInt()).isEqualTo(1);
            if (property) { assertThat(body.path("requestId").asString()).isEqualTo(id.toString()); assertThat(body.path("properties")).isEqualTo(JSON.createObjectNode().put("power", true)); }
            else { assertThat(body.path("commandKey").asString()).isEqualTo("reboot"); assertThat(body.path("input")).isEqualTo(JSON.createObjectNode()); }
            UUID replyId = Uuid7.generate();
            byte[] reply = JSON.writeValueAsBytes(JSON.createObjectNode().put("messageId", replyId.toString()).put("requestId", id.toString())
                    .put("occurredAt", now().toString()).put("status", "SUCCESS").set("output", JSON.createObjectNode()));
            String suffix = property ? "/up/property/set/reply" : "/up/command/" + id + "/reply";
            UUID foreignReplyId = Uuid7.generate();
            var foreignReply = (tools.jackson.databind.node.ObjectNode) JSON.readTree(reply);
            foreignReply.put("messageId", foreignReplyId.toString());
            other.publish(foreign.base() + suffix, JSON.writeValueAsBytes(foreignReply));
            await().atMost(Duration.ofSeconds(10)).until(() -> handoffs(foreignReplyId, "duplicate") == 1);
            assertThat(owner().queryForObject("SELECT status FROM ts_device_command WHERE id=?", String.class, id)).isEqualTo("DISPATCHED");
            own.publish(target.base() + suffix, reply);
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(owner().queryForObject("SELECT status FROM ts_device_command WHERE id=?", String.class, id)).isEqualTo("SUCCEEDED"));
            await().atMost(Duration.ofSeconds(10)).until(() -> handoffs(replyId, "accepted") == 1);
            own.publish(target.base() + suffix, reply);
            await().atMost(Duration.ofSeconds(10)).until(() -> handoffs(replyId, "duplicate") == 1);
            assertThat(owner().queryForObject("SELECT reply_message_id FROM ts_device_command_attempt WHERE command_id=? AND attempt_no=1", UUID.class, id)).isEqualTo(replyId);
            assertThat(owner().queryForObject("SELECT count(*) FROM sys_outbox_event WHERE aggregate_id=? AND event_type='DEVICE_COMMAND_TERMINAL'", Integer.class, id)).isEqualTo(1);
        }
        assertThat(other.client.isConnected()).isTrue(); assertThat(other.received).isEmpty();
        assertThat(count(target, "rule_automation_execution", "")).isEqualTo(1);
        assertThat(count(target, "sys_automation_quota_reservation", "")).isEqualTo(1);
        assertThat(owner().queryForObject("SELECT used_value FROM sys_usage_counter_daily WHERE project_id=? AND metric='AUTOMATION_EXECUTION'", Long.class, target.project())).isEqualTo(1L);
        assertThat(count(foreign, "ts_device_command", "")).isZero();
        assertThat(owner().queryForList("""
                SELECT command.operation_type FROM rule_device_action_delivery delivery
                JOIN ts_device_command command ON command.id=delivery.command_id
                JOIN ts_device_command_attempt attempt ON attempt.command_id=command.id
                JOIN sys_outbox_event event ON event.id=attempt.outbox_event_id
                WHERE delivery.automation_execution_id=? AND command.target_device_id=?
                  AND command.status='SUCCEEDED' AND command.attempt_count=1
                  AND attempt.attempt_no=1 AND attempt.status='SUCCEEDED'
                  AND event.event_type='DEVICE_COMMAND_DISPATCH' AND event.status='PUBLISHED'
                """, String.class, execution, target.device())).containsExactlyInAnyOrder("COMMAND", "PROPERTY_SET");
        assertThat(own.received).hasSize(2);
    }

    /** 上报只从真实MQTT入口进入，初始版本由生产版本仓库产生与绑定。 */
    private byte[] report(UUID messageId) {
        return JSON.writeValueAsBytes(JSON.createObjectNode().put("messageId", messageId.toString())
                .put("occurredAt", now().toString()).put("modelVersion", "1.0.0")
                .set("payload", JSON.createObjectNode().put("power", false)));
    }

    /** 必须观察到持久事件已发布，随后用真实Kafka提交位点等待后续消费者，而非只观察PUBACK。 */
    private int acceptedEvents(Fixture f, UUID source) {
        return owner().queryForObject("SELECT count(*) FROM sys_outbox_event WHERE tenant_id=? AND event_type='AUTOMATION_PROPERTY_ACCEPTED' AND payload::jsonb->>'sourceEventId'=? AND status='PUBLISHED'",
                Integer.class, f.tenant(), source.toString());
    }

    /** CURRENT历史点、reported和事件首次计划必须共同绑定原上报ID，回复成功不伪造reported状态。 */
    private void assertPropertyFacts(Fixture f, UUID source) {
        assertThat(owner().queryForObject("SELECT count(*) FROM sys_inbox_message WHERE project_id=? AND message_id=?", Integer.class, f.project(), source)).isEqualTo(1);
        assertThat(owner().queryForList("SELECT value_bool FROM public.ts_property_point_internal WHERE project_id=? AND device_id=? AND message_id=? AND property_key='power' AND model_version='1.0.0' AND data_type='SWITCH' AND thing_model_version_id=(SELECT thing_model_version_id FROM dev_device WHERE id=?)",
                Boolean.class, f.project(), f.device(), source, f.device())).containsExactly(false);
        assertThat(owner().queryForObject("SELECT reported->>'power' FROM dev_shadow WHERE project_id=? AND device_id=?", String.class, f.project(), f.device())).isEqualTo("false");
        assertThat(acceptedEvents(f, source)).isEqualTo(1);
        assertThat(owner().queryForObject("SELECT count(*) FROM rule_automation_event_receipt WHERE project_id=? AND source_event_id=? AND device_id=? AND result='ACCEPTED'",
                Integer.class, f.project(), source, f.device())).isEqualTo(1);
    }

    /** 只开启本场景需要的四个真实消费者；不增加测试消费器或自行发布accepted事件。 */
    private void startPropertyListeners() throws Exception {
        try (Admin admin = kafkaAdmin()) {
            try { admin.createTopics(List.of(new NewTopic(AutomationPropertyAccepted.TOPIC, 3, (short) 1))).all().get(15, TimeUnit.SECONDS); }
            catch (java.util.concurrent.ExecutionException failure) {
                if (!(failure.getCause() instanceof TopicExistsException)) throw failure;
            }
        }
        for (String topic : propertyTopics()) {
            var matching = listeners.getListenerContainers().stream().filter(container -> {
                String[] topics = container.getContainerProperties().getTopics();
                return topics != null && Arrays.asList(topics).contains(topic);
            }).toList();
            assertThat(matching).as("one real listener for %s", topic).hasSize(1);
            var listener = matching.getFirst();
            assertThat(listener.isRunning()).as("scenario owns listener startup: %s", topic).isFalse();
            propertyListeners.add(listener);
            listener.start();
            await().atMost(Duration.ofSeconds(30)).until(() -> listener.getAssignedPartitions() != null && !listener.getAssignedPartitions().isEmpty());
        }
    }

    private List<String> propertyTopics() {
        return List.of("tc.device.uplink.raw", "tc.device.uplink.normalized", "tc.device.uplink.processed", AutomationPropertyAccepted.TOPIC);
    }

    private Admin kafkaAdmin() { return Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers())); }

    /** 顺序读取每层真实末尾并等待原消费组提交，防止上游尚未产生下游时的假空队列。 */
    private void drainPropertyPipeline() throws Exception {
        try (Admin admin = kafkaAdmin()) {
            for (String topic : propertyTopics()) {
                String group = topic.equals(AutomationPropertyAccepted.TOPIC) ? "things-link-rule-automation-property"
                        : "things-link-ingestion-" + topic.substring(topic.lastIndexOf('.') + 1);
                var description = admin.describeTopics(List.of(topic)).allTopicNames().get(10, TimeUnit.SECONDS).get(topic);
                Map<TopicPartition, OffsetSpec> latest = new LinkedHashMap<>();
                description.partitions().forEach(partition -> latest.put(new TopicPartition(topic, partition.partition()), OffsetSpec.latest()));
                var ends = admin.listOffsets(latest).all().get(10, TimeUnit.SECONDS);
                await().atMost(Duration.ofSeconds(30)).until(() -> {
                    var committed = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
                    return ends.entrySet().stream().allMatch(entry -> entry.getValue().offset() == 0
                            || committed.get(entry.getKey()) != null && committed.get(entry.getKey()).offset() >= entry.getValue().offset());
                });
            }
        }
    }

    /** 原始接管按业务ID和字节摘要精确匹配，不能拿其他设备的通道总数替代重放证据。 */
    private long rawHandoffs(UUID source, byte[] original) throws Exception {
        Path file = EVIDENCE.resolve("handoff.jsonl");
        if (!Files.exists(file)) return 0;
        String text = Files.readString(file);
        int complete = text.lastIndexOf('\n');
        if (complete < 0) return 0;
        var rows = text.substring(0, complete).lines().map(JSON::readTree)
                .filter(row -> row.path("businessMessageId").asString().equals(source.toString())
                        && row.path("dispatchType").asString().equals("raw") && row.path("result").asString().equals("accepted")).toList();
        String expected = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(original));
        for (var row : rows) assertThat(row.path("payloadSha256").asString()).isEqualTo(expected);
        return rows.size();
    }

    private <T> T inFixture(Fixture f, Supplier<T> action) {
        var prior = TenantContext.current();
        TenantContext.set(new TenantScope(f.tenant(), f.project(), f.account()));
        try { return action.get(); } finally { TenantContext.clear(); prior.ifPresent(TenantContext::set); }
    }

    /** 仅回收本方法启动的消费者；必须真正退出后才删除其所属租户事实。 */
    private void stopPropertyListeners() throws InterruptedException {
        for (var listener : propertyListeners.reversed()) {
            CountDownLatch stopped = new CountDownLatch(1);
            listener.stop(stopped::countDown);
            assertThat(stopped.await(15, TimeUnit.SECONDS)).as("owned property listener stopped").isTrue();
        }
    }

    /** 自建凭据/模型只用于播种；认证与所有业务事实由真实平台服务产生。 */
    private Fixture seed() {
        Fixture f = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), UUID.randomUUID().toString());
        fixtures.add(f); var db = owner();
        db.update("INSERT INTO sys_quota_policy(id,code,automation_execution_daily_limit) VALUES(?,?,10)", f.policy(), f.policy().toString().replace("-", ""));
        db.update("INSERT INTO sys_tenant(id,name,quota_policy_id) VALUES(?,'time MQTT',?)", f.tenant(), f.policy());
        db.update("INSERT INTO sys_account(id,email,password_hash,display_name) VALUES(?,?, '{noop}unused','test')", f.account(), f.account()+"@example.com");
        db.update("INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES(?,?,?)", Uuid7.generate(), f.tenant(), f.account());
        db.update("INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES(?,?,'time MQTT','sh-1',?)", f.project(), f.tenant(), f.project().toString());
        db.update("INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES(?,?,?,'OWNER')", Uuid7.generate(), f.project(), f.account());
        db.update("INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,device_kind,access_protocol,network_type,status) VALUES(?,?,?,'time-mqtt','time MQTT','DIRECT','STANDARD','WIFI','PUBLISHED')", f.type(), f.tenant(), f.project());
        db.update("INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name) VALUES(?,?,?,?,'test','time MQTT')", f.device(), f.tenant(), f.project(), f.type());
        db.update("INSERT INTO dev_access_binding(device_id,tenant_id,project_id,protocol) VALUES(?,?,?,'MQTT')", f.device(), f.tenant(), f.project());
        db.update("INSERT INTO dev_command_definition(id,tenant_id,project_id,device_type_id,command_key,name,input_schema,output_schema,timeout_seconds) VALUES(?,?,?,?,'reboot','reboot','{}','{}',30)", Uuid7.generate(), f.tenant(), f.project(), f.type());
        db.update("INSERT INTO dev_property_definition(id,tenant_id,project_id,device_type_id,property_key,name,access_type,data_type) VALUES(?,?,?,?,'power','power','SHARED','SWITCH')", Uuid7.generate(), f.tenant(), f.project(), f.type());
        db.update("INSERT INTO dev_credential(id,tenant_id,project_id,device_id,auth_type,credential_hash,display_name) VALUES(?,?,?,?,'ACCESS_TOKEN',encode(digest(?,'sha256'),'hex'),'owned test')", Uuid7.generate(), f.tenant(), f.project(), f.device(), f.token());
        return f;
    }

    /** 仅信任已入库测试CA且启用主机名校验，不使用全信任或修改JVM默认TLS。 */
    private Device connect(Fixture fixture) throws Exception {
        var trust = KeyStore.getInstance(KeyStore.getDefaultType()); trust.load(null);
        try (var input = Files.newInputStream(TLS.resolve("device-access-test-cert.pem"))) {
            trust.setCertificateEntry("owned", CertificateFactory.getInstance("X.509").generateCertificate(input));
        }
        var managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); managers.init(trust);
        var ssl = SSLContext.getInstance("TLS"); ssl.init(null, managers.getTrustManagers(), null);
        Device result = new Device(new MqttAsyncClient("ssl://localhost:" + BROKER.getMappedPort(8883), "time-journey-" + Uuid7.generate(), new MemoryPersistence()));
        clients.add(result);
        var options = new MqttConnectOptions(); options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
        options.setUserName(fixture.project()+"/test"); options.setPassword(fixture.token().toCharArray());
        options.setSocketFactory(ssl.getSocketFactory()); options.setHttpsHostnameVerificationEnabled(true);
        options.setAutomaticReconnect(false); options.setCleanSession(true); options.setConnectionTimeout(10);
        result.client.connect(options).waitForCompletion(15_000);
        assertThat(result.client.isConnected()).isTrue(); return result;
    }

    private record Fixture(UUID tenant, UUID project, UUID account, UUID device, UUID type, UUID policy, String token) {
        String base() { return "tc/v1/" + project + "/test"; }
    }
    private record Message(String topic, byte[] bytes, int qos, boolean retained) { }
    /** 有界Paho对端只观察原消息并发送明确构造的设备回复，不代写平台事实。 */
    private static final class Device {
        final MqttAsyncClient client;
        final List<Message> received = new CopyOnWriteArrayList<>();
        Device(MqttAsyncClient client) {
            this.client=client;
            client.setCallback(new MqttCallback() {
                @Override public void connectionLost(Throwable failure) { }
                @Override public void deliveryComplete(IMqttDeliveryToken token) { }
                @Override public void messageArrived(String topic, MqttMessage message) {
                    received.add(new Message(topic, message.getPayload().clone(), message.getQos(), message.isRetained()));
                }
            });
        }
        void subscribe(String topic, boolean allowed) throws MqttException {
            var token = client.subscribe(topic, 1);
            try { token.waitForCompletion(10_000); }
            catch (MqttException failure) {
                if (allowed || failure.getReasonCode()!=MqttException.REASON_CODE_SUBSCRIBE_FAILED) throw failure;
            }
            assertThat(token.getGrantedQos()).containsExactly(allowed ? 1 : 128);
        }
        void publish(String topic, byte[] body) throws MqttException { client.publish(topic, body, 1, false).waitForCompletion(10_000); }
    }

    /** 已有生产取证器只记录摘要/消息ID；normal场景不触发ACK屏障，不按全局计数猜测处理完成。 */
    private static Path evidenceDirectory() {
        try {
            Path directory=Path.of("../../logs/verification/r4-time-mqtt-journey/runtime/"+Uuid7.generate()).toAbsolutePath().normalize();
            Files.createDirectories(directory); Files.writeString(directory.resolve("scenario.txt"),"normal\n");
            return directory;
        } catch (java.io.IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private long handoffs(UUID messageId, String outcome) throws java.io.IOException {
        Path file=EVIDENCE.resolve("handoff.jsonl");
        if(!Files.exists(file))return 0;
        String text=Files.readString(file);
        // 正在追加的最后一行尚未完成时暂不观察；完整记录中的解析错误必须保留失败。
        int complete=text.lastIndexOf('\n'); if(complete<0)return 0;
        return text.substring(0,complete).lines().map(JSON::readTree)
                .filter(row->row.path("businessMessageId").asString().equals(messageId.toString())
                        && row.path("dispatchType").asString().equals("command-reply")
                        && row.path("result").asString().equals(outcome)).count();
    }
    private Instant now() { return owner().queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant(); }
    private JdbcTemplate owner() { return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())); }
    private int count(Fixture f, String table, String predicate) { return owner().queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id=?" + predicate, Integer.class, f.tenant()); }
    private <T> T asOwner(Supplier<T> action) {
        var prior=TenantContext.current(); TenantContext.set(new TenantScope(target.tenant(),target.project(),target.account()));
        try { return action.get(); } finally { TenantContext.clear(); prior.ifPresent(TenantContext::set); }
    }
    private void pause() {
        if (automation==null) return;
        var current=asOwner(()->management.get(target.project(),automation));
        if (!current.status().name().equals("PAUSED")) asOwner(()->management.pause(target.project(),automation,current.version()));
    }

    /** 关闭自己的会话并等断连回调提交后，按外键顺序只清理自己身份图。 */
    @AfterEach void cleanup() throws Exception {
        try { pause(); }
        finally {
            Exception closeFailure=null;
            for (Device client : clients) {
                try { if(client.client.isConnected()) client.client.disconnect().waitForCompletion(10_000); }
                catch (Exception failure) { if(closeFailure==null)closeFailure=failure; else closeFailure.addSuppressed(failure); }
                finally {
                    try { client.client.close(true); }
                    catch (Exception failure) { if(closeFailure==null)closeFailure=failure; else closeFailure.addSuppressed(failure); }
                }
            }
            if(closeFailure!=null)throw closeFailure;
        }
        stopPropertyListeners();
        TenantContext.clear();
        for (Fixture f : fixtures) {
            await().atMost(Duration.ofSeconds(10)).until(() -> owner().queryForObject("SELECT count(*) FROM dev_connection WHERE device_id=? AND disconnected_at IS NULL",Integer.class,f.device())==0);
            for (String table : List.of("rule_automation_schedule", "rule_automation_schedule_state", "sys_tenant_work_slot", "sys_outbox_event", "rule_device_action_delivery", "ts_device_command_claim", "ts_device_command_attempt", "ts_device_command", "rule_notification_delivery", "rule_automation_attempt", "rule_automation_execution", "rule_automation_event_receipt"))
                owner().update("DELETE FROM "+table+" WHERE tenant_id=?",f.tenant());
            if (propertyJourney) {
                for (String table : List.of("public.ts_property_point_internal", "ts_device_message_log", "sys_message_log_inbox", "sys_inbox_message", "dev_shadow", "dev_access_request"))
                    owner().update("DELETE FROM " + table + " WHERE project_id=?", f.project());
            }
            owner().update("UPDATE rule_automation SET active_version_id=NULL,status='DRAFT' WHERE tenant_id=?",f.tenant());
            for (String table : List.of("rule_automation_version", "rule_automation", "sys_automation_quota_reservation", "sys_usage_counter_daily", "dev_connection", "dev_mqtt_connection_ticket", "dev_mqtt_session_cursor", "dev_credential", "dev_access_binding", "dev_device", "dev_command_definition", "dev_property_definition", "dev_type"))
                if (propertyJourney && table.equals("dev_type")) {
                    owner().update("DELETE FROM dev_device_model_binding_history WHERE project_id=?", f.project());
                    owner().update("DELETE FROM dev_thing_model_version WHERE project_id=?", f.project());
                    owner().update("DELETE FROM dev_type WHERE tenant_id=?", f.tenant());
                } else owner().update("DELETE FROM "+table+" WHERE tenant_id=?",f.tenant());
            owner().update("DELETE FROM sys_project_member WHERE project_id=?",f.project());
            owner().update("DELETE FROM sys_project WHERE id=?",f.project());
            owner().update("DELETE FROM sys_tenant_member WHERE tenant_id=?",f.tenant());
            owner().update("DELETE FROM sys_account WHERE id=?",f.account());
            owner().update("DELETE FROM sys_tenant WHERE id=?",f.tenant());
            owner().update("DELETE FROM sys_quota_policy WHERE id=?",f.policy());
        }
    }
}
