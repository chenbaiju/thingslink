package com.things.link.bootstrap.ingestion.property;

import com.things.link.device.application.DeviceTopologyIngestionService;
import com.things.link.device.application.schema.ThingModelSchemaValidator;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.DeviceTopologyMessage;
import com.things.link.shared.message.RawUplinkMessage;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.testing.AbstractIntegrationTest;
import com.things.link.testing.OwnedTestContainers;
import com.things.link.support.scheduling.NotificationWorkCoordinator;
import com.things.link.task.application.TaskSchedulingScanner;
import com.things.link.telemetry.application.PropertyAggregateBackfillScanner;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.DeserializationFeature;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** D-146：真实四段Kafka生产链保留批量子设备数值、拒绝分摊及PG最终事实。 */
@Import(GatewayBatchPrecisionKafkaIntegrationTests.IsolatedConfiguration.class)
@OwnedTestContainers({"DATABASE", "BROKER"})
class GatewayBatchPrecisionKafkaIntegrationTests extends AbstractIntegrationTest {
    /** 本类独占数据库，不借随机项目掩盖全局监听器干扰。 */
    private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("gateway_batch_precision").withUsername(POSTGRES.getUsername()).withPassword(POSTGRES.getPassword());
    /** 专库先于Spring初始化，类末由所有权监听器关闭。 */
    private static final String DATABASE_URL = startDatabase();
    /** 本类独占生产消费组所在Broker。 */
    private static final KafkaContainer BROKER = new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.0"))
            .withStartupTimeout(Duration.ofMinutes(2));
    /** 模板与原生产监听器共同使用的真实地址。 */
    private static final String BOOTSTRAP = startBroker();
    /** 无Double中间值的设备原文。 */
    private static final String PAYLOAD = "{\"decimal\":0.12345678901234567890123456789,\"integer\":9007199254740993,"
            + "\"object\":{\"nested\":[0.12345678901234567890123456789,9007199254740993,9223372036854775808123456789]},"
            + "\"list\":[{\"decimal\":0.12345678901234567890123456789,\"integer\":9007199254740993,\"huge\":9223372036854775808123456789}]}";
    /** OBJECT定义与实际嵌套数值数组相符，所有复合容器均显式有界且封闭。 */
    private static final String OBJECT_SCHEMA = """
            {"type":"object","properties":{"nested":{"type":"array","items":{"type":"number"},
            "minItems":3,"maxItems":3}},"required":["nested"],"additionalProperties":false,"maxProperties":1}
            """;
    /** LIST采用同构对象成员，不能通过缺失schema跳过复合属性admission。 */
    private static final String LIST_SCHEMA = """
            {"type":"array","minItems":1,"maxItems":1,"items":{"type":"object",
            "properties":{"decimal":{"type":"number"},"integer":{"type":"number"},"huge":{"type":"number"}},
            "required":["decimal","integer","huge"],"additionalProperties":false,"maxProperties":3}}
            """;
    /** 版本快照携带与定义表一致的真实复合Schema；schema_profile由共用版本夹具固定为TC_PROPERTY_COMPOSITE_V1。 */
    private static final String MODEL = "{\"properties\":{\"decimal\":{\"dataType\":\"NUMBER\",\"accessType\":\"REPORT\"},"
            + "\"integer\":{\"dataType\":\"NUMBER\",\"accessType\":\"REPORT\"},"
            + "\"object\":{\"dataType\":\"OBJECT\",\"accessType\":\"REPORT\",\"schema\":" + OBJECT_SCHEMA + "},"
            + "\"list\":{\"dataType\":\"LIST\",\"accessType\":\"REPORT\",\"schema\":" + LIST_SCHEMA + "}},\"events\":{},\"commands\":{}}";
    /** 独立观察器保留数值树，不以浮点解析掩盖传输损失。 */
    private static final ObjectMapper JSON = new ObjectMapper().rebuild()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    /** 四个必须经过原工厂的阶段及其主题。 */
    private static final Map<String, String> STAGES = Map.of(
            "things-link-ingestion-raw", "tc.device.uplink.raw",
            "things-link-ingestion-batch", "tc.device.batch",
            "things-link-ingestion-normalized", "tc.device.uplink.normalized",
            "things-link-ingestion-processed", "tc.device.uplink.processed");
    /** 不允许共享库配额runner操作专库外的数据库。 */
    @MockitoBean(enforceOverride = true, name = "relaxRestQuota")
    private ApplicationRunner unusedQuotaRunner;
    /** 关闭不相关通知领取，生产摄入不mock。 */
    @MockitoBean(enforceOverride = true)
    private NotificationWorkCoordinator unusedNotifications;
    /** 回补扫描不得抢走测试正在观察的历史事实。 */
    @MockitoBean(enforceOverride = true)
    private PropertyAggregateBackfillScanner unusedBackfill;
    /** 不相关任务扫描不参与本片。 */
    @MockitoBean(enforceOverride = true)
    private TaskSchedulingScanner unusedTasks;
    /** 核实应用连接确实使用专库及普通RLS角色，避免owner断言掩盖配置错误。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;
    /** 原生产模板直接发送raw，绝不调用消费者方法。 */
    @Autowired
    private KafkaTemplate<String, Object> template;
    /** 原生产监听器注册表。 */
    @Autowired
    private KafkaListenerEndpointRegistry listeners;
    /** 真实拓扑写服务建立子设备归属。 */
    @Autowired
    private DeviceTopologyIngestionService topology;
    /** 种子Schema先通过真实定义校验器，owner插入不替代生产定义规则。 */
    @Autowired
    private ThingModelSchemaValidator schemaValidator;
    /** 原低基数拒绝与字节计数。 */
    @Autowired
    private MeterRegistry meters;
    /** 本类启动的原监听器，AfterEach先确认停止再退出。 */
    private final List<MessageListenerContainer> active = new ArrayList<>();

    /** 一帧混合合法及拒绝项经真实Kafka分流，子设备身份和十进制精度保持到PG。 */
    @Test
    void preservesPrecisionAndAccountingAcrossActualGatewayBatchKafkaChain() throws Exception {
        assertThat(jdbcTemplate.queryForObject("SELECT current_database()", String.class))
                .isEqualTo(DATABASE.getDatabaseName());
        assertThat(jdbcTemplate.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname=current_user", Boolean.class)).isTrue();
        String objectSchema = schemaValidator.validateCompositeDefinition(OBJECT_SCHEMA, "object");
        String listSchema = schemaValidator.validateCompositeDefinition(LIST_SCHEMA, "array");
        UUID tenant = Uuid7.generate(), project = Uuid7.generate(), gatewayType = Uuid7.generate(),
                subType = Uuid7.generate(), gateway = Uuid7.generate(), child = Uuid7.generate(), unbound = Uuid7.generate();
        try (Connection owner = fixtureOwnerConnection()) {
            execute(owner, "INSERT INTO sys_tenant(id,name) VALUES (?, '批量精度租户')", tenant);
            execute(owner, "INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?,?,'批量精度','sh-1','batch_precision')", project, tenant);
            execute(owner, "INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,access_protocol,device_kind,status) VALUES (?,?,?,'gateway','网关','STANDARD_GATEWAY','GATEWAY','PUBLISHED')", gatewayType, tenant, project);
            execute(owner, "INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,access_protocol,device_kind,status) VALUES (?,?,?,'sub','子设备','STANDARD','SUB_DEVICE','PUBLISHED')", subType, tenant, project);
            seedDevice(owner, tenant, project, gatewayType, gateway, "gateway");
            seedDevice(owner, tenant, project, subType, child, "child");
            seedDevice(owner, tenant, project, subType, unbound, "unbound");
            for (String key : List.of("decimal", "integer", "object", "list")) {
                execute(owner, "INSERT INTO dev_property_definition(id,tenant_id,project_id,device_type_id,property_key,name,access_type,data_type,schema) VALUES (?,?,?,?,?,?,'REPORT',?,?::jsonb)",
                        Uuid7.generate(), tenant, project, subType, key, key,
                        key.equals("object") ? "OBJECT" : key.equals("list") ? "LIST" : "NUMBER",
                        key.equals("object") ? objectSchema : key.equals("list") ? listSchema : null);
            }
        }
        seedThingModelVersion(tenant, project, subType, child, MODEL);
        topology.ingest(new DeviceTopologyMessage(Uuid7.generate(), tenant, project, gateway,
                DeviceTopologyMessage.Type.TOPO_ADD, "child", null, null, Instant.now(), "batch-precision-topology"));
        UUID messageId = Uuid7.generate();
        String valid = entry(messageId.toString(), "child"),
                frame = "{\"devices\":[" + valid + "," + entry("invalid", "child") + "," + valid + "," + entry(Uuid7.generate().toString(), "unbound") + "]}";
        byte[] bytes = frame.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", BOOTSTRAP));
             KafkaConsumer<String, String> probe = probe()) {
            List<String> topics = new ArrayList<>(STAGES.values());
            topics.add("tc.dlq");
            admin.createTopics(topics.stream().map(topic -> new NewTopic(topic, 1, (short) 1)).toList()).all().get(20, TimeUnit.SECONDS);
            List<TopicPartition> partitions = topics.stream().map(topic -> new TopicPartition(topic, 0)).toList();
            probe.assign(partitions);
            probe.seekToBeginning(partitions);
            assertThat(listeners.getListenerContainers()).noneMatch(MessageListenerContainer::isRunning);
            for (var container : listeners.getListenerContainers()) {
                if (STAGES.containsKey(container.getGroupId())) {
                    active.add(container);
                    container.start();
                }
            }
            assertThat(active).hasSize(4);
            template.send("tc.device.uplink.raw", gateway.toString(), new RawUplinkMessage(tenant, project, gateway,
                    "tc/v1/batch_precision/gateway/up/batch/report", bytes, 1, false, "gateway", Instant.now(), "batch-precision"))
                    .get(20, TimeUnit.SECONDS);
            Map<String, JsonNode> seen = new HashMap<>();
            List<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>> records = new ArrayList<>();
            await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> {
                for (var record : probe.poll(Duration.ofMillis(100))) {
                    records.add(record);
                }
                for (var record : records) {
                    assertThat(record.topic()).isNotEqualTo("tc.dlq");
                    JsonNode body = JSON.readTree(record.value());
                    if (record.topic().equals("tc.device.batch")) {
                        assertThat(record.key()).isEqualTo(gateway.toString());
                        assertThat(body.get("gatewayId").asString()).isEqualTo(gateway.toString());
                        int sum = 0;
                        for (JsonNode entry : body.get("entries")) {
                            sum += entry.get("rawBytes").asInt();
                            if (entry.has("report")) {
                                assertExact(entry.get("report").get("payload"));
                            }
                        }
                        assertThat(sum).isEqualTo(bytes.length);
                    } else if (!record.topic().equals("tc.device.uplink.raw")) {
                        assertThat(record.key()).isEqualTo(child.toString());
                        assertThat(body.get("deviceId").asString()).isEqualTo(child.toString());
                        assertThat(body.get("projectId").asString()).isEqualTo(project.toString());
                        assertThat(body.get("tenantId").asString()).isEqualTo(tenant.toString());
                        assertThat(body.get("gatewayId").asString()).isEqualTo(gateway.toString());
                        assertThat(body.get("messageId").asString()).isEqualTo(messageId.toString());
                        assertExact(body.get("payload"));
                    }
                    seen.put(record.topic(), body);
                }
                assertThat(seen.keySet()).containsAll(STAGES.values());
                try (Connection owner = fixtureOwnerConnection(); var query = owner.prepareStatement("SELECT reported::text FROM dev_shadow WHERE device_id=?")) {
                    query.setObject(1, child);
                    try (var rows = query.executeQuery()) {
                        assertThat(rows.next()).isTrue();
                        assertExact(JSON.readTree(rows.getString(1)));
                    }
                }
            });
            // 按生产依赖顺序等待提交：上游offset提交前必须已确认下游发送，避免提前冻结空的下游终点。
            for (String group : List.of("things-link-ingestion-raw", "things-link-ingestion-batch",
                    "things-link-ingestion-normalized", "things-link-ingestion-processed")) {
                TopicPartition partition = new TopicPartition(STAGES.get(group), 0);
                await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                    long end = probe.endOffsets(List.of(partition), Duration.ofSeconds(5)).get(partition);
                    assertThat(end).as(group + "输入已到达").isPositive();
                    var committed = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()
                            .get(5, TimeUnit.SECONDS).get(partition);
                    assertThat(committed).as(group + "输入已提交").isNotNull();
                    assertThat(committed.offset()).isGreaterThanOrEqualTo(end);
                });
            }
            stopListeners();
            // 所有原生产发送源停止后才冻结真实broker终点；把最后一批迟到记录/DLQ全部纳入验收。
            Map<TopicPartition, Long> ends = probe.endOffsets(partitions, Duration.ofSeconds(5));
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                for (var record : probe.poll(Duration.ofMillis(100))) records.add(record);
                for (var partition : partitions)
                    assertThat(probe.position(partition, Duration.ofSeconds(5))).isGreaterThanOrEqualTo(ends.get(partition));
            });
            assertThat(records).noneMatch(record -> record.topic().equals("tc.dlq"));
            // 首次观察已逐条验证值，最终每主题恰好一条进一步排除任何迟到子设备/重复发送。

            for (String topic : STAGES.values()) {
                assertThat(records.stream().filter(record -> record.topic().equals(topic)).count())
                        .as(topic).isEqualTo(1);
            }
            int acceptedBytes = bytes.length / 4 + (bytes.length % 4 > 0 ? 1 : 0);
            assertThat(seen.get("tc.device.uplink.processed").get("rawBytes").asInt()).isEqualTo(acceptedBytes);
            assertThat(meters.get("device_batch_item_accepted").counter().count()).isEqualTo(1);
            long rejectedBytes = 0;
            for (String reason : List.of("invalid_message_id", "duplicate_message_id", "sub_device_not_bound")) {
                assertThat(meters.get("device_batch_item_rejected").tag("reason", reason).counter().count()).isEqualTo(1);
                rejectedBytes += (long) meters.get("device_batch_rejected_bytes").tag("reason", reason).counter().count();
            }
            assertThat(rejectedBytes + acceptedBytes).isEqualTo(bytes.length);
            // NUMBER历史的value_double保持既有二进制合同；本片只承诺Kafka与JSONB原值精度。
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                try (Connection owner = fixtureOwnerConnection(); var query = owner.prepareStatement("SELECT device_id,property_key,value_json::text,value_double FROM ts_property_point_internal WHERE project_id=?")) {
                    query.setObject(1, project);
                    try (var rows = query.executeQuery()) {
                        int count = 0;
                        Set<String> keys = new HashSet<>();
                        while (rows.next()) {
                            count++;
                            assertThat(keys.add(rows.getString(2))).isTrue();
                            assertThat(rows.getObject(1, UUID.class)).isEqualTo(child);
                            if (Set.of("decimal", "integer").contains(rows.getString(2))) {
                                assertThat(rows.getString(3)).isNull();
                                double expected = new BigDecimal(rows.getString(2).equals("decimal")
                                        ? "0.12345678901234567890123456789" : "9007199254740993").doubleValue();
                                assertThat(rows.getObject(4)).isNotNull();
                                assertThat(rows.getDouble(4)).isEqualTo(expected);
                            }
                            if (rows.getString(2).equals("object")) {
                                JsonNode nested = JSON.readTree(rows.getString(3)).get("nested");
                                assertDecimal(nested.get(0));
                                assertInteger(nested.get(2), "9223372036854775808123456789");
                            }
                            if (rows.getString(2).equals("list")) {
                                JsonNode nested = JSON.readTree(rows.getString(3)).get(0);
                                assertDecimal(nested.get("decimal"));
                                assertInteger(nested.get("huge"), "9223372036854775808123456789");
                            }
                        }
                        assertThat(count).isEqualTo(4);
                        assertThat(keys).containsExactlyInAnyOrder("decimal", "integer", "object", "list");
                    }
                }
            });
        }
    }

    /** 断言原文全部精确数值，不能只比较格式化后的有限小数。 */
    private static void assertExact(JsonNode payload) {
        assertDecimal(payload.get("decimal"));
        assertInteger(payload.get("integer"), "9007199254740993");
        assertDecimal(payload.get("object").get("nested").get(0));
        assertInteger(payload.get("object").get("nested").get(1), "9007199254740993");
        assertInteger(payload.get("object").get("nested").get(2), "9223372036854775808123456789");
        assertDecimal(payload.get("list").get(0).get("decimal"));
        assertInteger(payload.get("list").get(0).get("huge"), "9223372036854775808123456789");
        assertInteger(payload.get("list").get(0).get("integer"), "9007199254740993");
    }

    /** 十进制比较避免JSON合法格式变化造成误报。 */
    private static void assertDecimal(JsonNode value) {
        assertThat(value.isNumber()).isTrue();
        assertThat(new BigDecimal(value.asText())).isEqualByComparingTo("0.12345678901234567890123456789");
    }

    /** 整数仍须是JSON number，不能把文本数字误当成保真。 */
    private static void assertInteger(JsonNode value, String expected) {
        assertThat(value.isIntegralNumber()).isTrue();
        assertThat(value.asText()).isEqualTo(expected);
    }

    /** 每个条目从字符串原文构造，重复ID仅供真实条目拒绝。 */
    private static String entry(String messageId, String deviceKey) {
        return "{\"messageId\":\"" + messageId + "\",\"deviceKey\":\"" + deviceKey + "\",\"occurredAt\":\"" + Instant.now()
                + "\",\"modelVersion\":\"1.0.0\",\"payload\":" + PAYLOAD + "}";
    }

    /** 普通设备种子不绕过拓扑服务的归属核验。 */
    private static void seedDevice(Connection owner, UUID tenant, UUID project, UUID type, UUID device, String key) throws SQLException {
        execute(owner, "INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,status) VALUES (?,?,?,?,?,?,'ONLINE')", device, tenant, project, type, key, key);
    }

    /** 所有owner连接固定本类专库。 */
    @Override
    protected Connection fixtureOwnerConnection() throws SQLException {
        return DriverManager.getConnection(DATABASE_URL, DATABASE.getUsername(), DATABASE.getPassword());
    }

    /** 参数化种子不动态拼接表名。 */
    private static void execute(Connection owner, String sql, Object... values) throws SQLException {
        try (var statement = owner.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                statement.setObject(i + 1, values[i]);
            }
            statement.executeUpdate();
        }
    }

    /** 独立只读Kafka观察器不借生产反序列化掩盖精度损失。 */
    private static KafkaConsumer<String, String> probe() {
        return new KafkaConsumer<>(Map.of("bootstrap.servers", BOOTSTRAP, "group.id", "batch-precision-probe", "enable.auto.commit", false), new StringDeserializer(), new StringDeserializer());
    }

    /** 先停止原监听器及异步任务，再允许类末关闭上下文与专属容器。 */
    @AfterEach
    void stopListeners() throws InterruptedException {
        try {
            for (var listener : active) {
                CountDownLatch stopped = new CountDownLatch(1);
                listener.stop(stopped::countDown);
                assertThat(stopped.await(30, TimeUnit.SECONDS)).isTrue();
            }
            active.clear();
        } finally {
            TenantContext.clear();
        }
    }

    /** 在属性注册前启动独占数据库。 */
    private static String startDatabase() {
        DATABASE.start();
        return DATABASE.getJdbcUrl();
    }

    /** 在属性注册前启动独占Kafka。 */
    private static String startBroker() {
        BROKER.start();
        return BROKER.getBootstrapServers();
    }

    /** 仅关闭无关后台任务，四个目标监听器仍采用原生产工厂。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class IsolatedConfiguration {
        /** 专库、Broker和开关在Spring初始化时共同生效。 */
        @Bean
        DynamicPropertyRegistrar properties() {
            return registry -> {
                registry.add("spring.datasource.url", () -> DATABASE_URL);
                registry.add("spring.flyway.url", () -> DATABASE_URL);
                registry.add("spring.kafka.bootstrap-servers", () -> BOOTSTRAP);
                registry.add("spring.kafka.listener.auto-startup", () -> "false");
                registry.add("things-link.outbox.publisher.enabled", () -> "false");
                registry.add("things-link.notification.retry.enabled", () -> "false");
            };
        }
    }
}
