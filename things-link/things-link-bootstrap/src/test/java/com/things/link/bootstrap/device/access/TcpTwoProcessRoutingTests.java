package com.things.link.bootstrap.device.access;

import com.things.link.device.domain.DeviceAccessSessionRepository;
import com.things.link.bootstrap.fixture.DeviceDataPlaneTopicsTestConfiguration;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.telemetry.application.DeviceCommandService;
import com.things.link.testing.AbstractKafkaIntegrationTest;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTcpFrameType;
import com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTcpFrameCodec;
import com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTcpFrameReader;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.Duration;
import java.io.IOException;
import java.io.File;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.ByteArrayOutputStream;
import java.net.Socket;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.HexFormat;
import java.util.Comparator;
import java.util.function.Supplier;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** 两个独立生产JVM，共享真实PG/Kafka/Redis；父进程仅提交命令并充当TLS测试设备。 */
@SpringBootTest(properties = {"spring.kafka.admin.auto-create=true", "spring.kafka.listener.auto-startup=false"})
@org.testcontainers.junit.jupiter.Testcontainers
@Import(DeviceDataPlaneTopicsTestConfiguration.class)
class TcpTwoProcessRoutingTests extends AbstractKafkaIntegrationTest {
    /** 独占真实MQTT Broker，证明共享发布只有一个订阅消息而非mock调用次数。 */
    @org.testcontainers.junit.jupiter.Container
    private static final org.testcontainers.containers.GenericContainer<?> BROKER = new org.testcontainers.containers.GenericContainer<>("emqx/emqx:6.2.3")
            .withExposedPorts(1883, 18083)
            .withCopyToContainer(org.testcontainers.images.builder.Transferable.of("tcp-test:tcp-test-secret:publisher\n".getBytes(StandardCharsets.UTF_8), 0444), "/opt/emqx/etc/tcp-test-api-keys")
            .withCopyToContainer(org.testcontainers.images.builder.Transferable.of("""
                    dashboard.listeners.http.bind = 18083
                    authentication = []
                    authorization { no_match = allow, sources = [] }
                    api_key.bootstrap_file = "/opt/emqx/etc/tcp-test-api-keys"
                    """.getBytes(StandardCharsets.UTF_8), 0444), "/opt/emqx/etc/base.hocon")
            .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forHttp("/status").forPort(18083).forStatusCode(200).withStartupTimeout(Duration.ofSeconds(120)));
    /** 领取路径复用实际应用事务。 */
    @Autowired private com.things.link.telemetry.application.DeviceCommandClaimPort claims;
    /** 生产JSON。 */ private static final ObjectMapper JSON = new ObjectMapper();
    /** 仅测试设备凭据。 */ private static final String SECRET = "bbbb2222cccc3333dddd4444eeee5555bbbb2222cccc3333dddd4444eeee5555";
    /** 合法发布物模型。 */ private static final String TEMPERATURE_SNAPSHOT = "{\"properties\":{},\"events\":{},\"commands\":{}}";
    /** 测试证书路径。 */ private static final Path TLS_FIXTURE_DIRECTORY = Path.of("..", "things-link-ingestion", "src", "test", "resources", "tls").toAbsolutePath().normalize();
    /** 生产受理服务。 */ @Autowired private DeviceCommandService commandService;
    /** 负向信封只经真实Kafka进入，不直接调用消费者。 */ @Autowired private org.springframework.kafka.core.KafkaTemplate<String, Object> kafka;
    /** 生产接入绑定仓储。 */ @Autowired private DeviceAccessSessionRepository sessionRepository;
    /** 显式APP事务。 */ @Autowired private PlatformTransactionManager transactionManager;
    /** APP RLS连接。 */ @Autowired private JdbcTemplate jdbcTemplate;
    /** 此轮独占夹具。 */ private final List<Fixture> fixtures = new ArrayList<>();
    /** 此轮子进程。 */ private final List<Node> nodes = new ArrayList<>();
    /** 日志保留，临时classpath和控制文件在退出后删除。 */ private Path run;
    /** 回滚只能在所有新进程退出后使用兼容旧构件。 */ private String rollbackRoot;
    /** 负向启动使用独占受控Broker。 */ private String startupBroker;
    /** 负向启动不允许生成就绪文件。 */ private boolean expectStartupFailure;

    /** T01/T02/T12：确定共享组归属A、设备仅连B，反向与双设备投递。 */
    @Test void routesAcrossRealProcesses() throws Exception {
        run = Path.of("..", "..", "logs", "verify", "tcp-two-process-" + Uuid7.generate()).toAbsolutePath().normalize();
        Files.createDirectories(run);
        Files.writeString(run.resolve("environment.json"), JSON.writeValueAsString(Map.of(
                "java",System.getProperty("java.runtime.version"),"postgres",POSTGRES.getContainerId(),"redis",REDIS.getContainerId(),
                "kafka",KAFKA.getContainerId(),"emqx",BROKER.getContainerId(),"timeoutSeconds",10,"maxAttempts",3,"retryBackoffSeconds",5,"scanMillis",1000,"leaseSeconds",30)));
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            Node a = startNode("A", true);
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                var members = admin.describeConsumerGroups(List.of("things-link-ingestion-downlink")).all().get().get("things-link-ingestion-downlink").members();
                assertThat(members.stream().flatMap(m -> m.assignment().topicPartitions().stream()).distinct().count()).isEqualTo(3);
            });
            Files.writeString(run.resolve("shared-assignment.txt"), admin.describeConsumerGroups(List.of("things-link-ingestion-downlink")).all().get().toString());
            Node b = startNode("B", false);
            boolean legacy = System.getProperty("tcp.baseline.root") != null;
            if (!legacy) {
                assertThat(a.status.get("runtime").asString()).isNotEqualTo(b.status.get("runtime").asString());
                assertThat(a.status.get("group").asString()).isNotEqualTo(b.status.get("group").asString());
            }
            Fixture first = seed();
            try (SSLSocket device = authenticated(b, first)) {
                for (int i = 0; i < 3; i++) {
                    UUID command = submit(first);
                    if (legacy) {
                        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(text("SELECT status || '/' || coalesce(error_code,'') FROM ts_device_command_attempt WHERE command_id=? AND attempt_no=1", command)).isEqualTo("FAILED/DISPATCH_DEVICE_OFFLINE"));
                        evidence("RED", command, "A wrongly failed command while device connected to B");
                    } else {
                        complete(device, command);
                        assertThat(text("SELECT status || '/' || attempt_count FROM ts_device_command WHERE id=?", command)).isEqualTo("SUCCEEDED/1");
                        evidence("T01", command, "B delivered; shared group A; attempt 1");
                    }
                }
            }
            if (legacy) return;
            verifyMqttAndPullRoles();
            Fixture second = seed();
            try (SSLSocket left = authenticated(a, first); SSLSocket right = authenticated(b, second)) {
                UUID l = submit(first), r = submit(second);
                complete(left, l); complete(right, r);
                evidence("T02", l, "A delivered own device"); evidence("T02", r, "B delivered own device");
                UUID ordered1=submit(first), ordered2=submit(first);
                assertThat(frame(left,DeviceAccessTcpFrameType.DOWNLINK).get("commandId").asString()).isEqualTo(ordered1.toString());
                assertThat(frame(left,DeviceAccessTcpFrameType.DOWNLINK).get("commandId").asString()).isEqualTo(ordered2.toString());
                reply(left,ordered1); reply(left,ordered2); evidence("T02",ordered2,"same-device two commands retained partition order");
                assertThat(control(b, "stop-tcp-consumer").get("ready").asBoolean()).isFalse();
                send(right, DeviceAccessTcpFrameType.HEARTBEAT, new byte[0]);
                frame(right, DeviceAccessTcpFrameType.HEARTBEAT_ACK);
                assertThatThrownBy(() -> authenticated(b, second)).isInstanceOf(IOException.class);
                control(b, "start-tcp-consumer");
                await().atMost(Duration.ofSeconds(30)).until(() -> control(b, "status").get("ready").asBoolean());
                evidence("T12", r, "consumer stopped: new TLS rejected; existing heartbeat allowed; resume ready");
            }
            if (Boolean.getBoolean("tcp.dev.startup.only")) {
                assertThat(Boolean.getBoolean("tcp.full.matrix")).as("development selector cannot claim full matrix").isFalse();
                verifyScaleAndRollback(admin,a,b); return;
            }
            if (Boolean.getBoolean("tcp.full.matrix")) verifyFaultMatrix(admin, a, b);
        }
    }

    /** 完整故障矩阵顺序运行；任一失败直接退出，不继续后续资格场景。 */
    private void verifyFaultMatrix(Admin admin, Node a, Node b) throws Exception {
        Fixture offline = seed();
        UUID skipped = submit(offline);
        await().atMost(Duration.ofSeconds(8)).until(() -> text("SELECT status FROM sys_outbox_event WHERE aggregate_id=? AND event_type='DEVICE_COMMAND_DISPATCH'", skipped).equals("PUBLISHED"));
        consumed(admin, a, b);
        assertThat(text("SELECT status || '/' || attempt_count FROM ts_device_command WHERE id=?", skipped)).isEqualTo("ACCEPTED/1");
        long began = System.nanoTime();
        try (SSLSocket device = authenticated(b, offline)) { complete(device, skipped); }
        assertThat(text("SELECT attempt_count::text FROM ts_device_command WHERE id=?", skipped)).isEqualTo("2");
        assertThat(text("SELECT error_code FROM ts_device_command_attempt WHERE command_id=? AND attempt_no=1",skipped)).isEqualTo("DISPATCH_PENDING_TIMEOUT");
        bounded(began); evidence("T03", skipped, "both groups consumed without session; natural deadline and retry; no offset rewind");
        Fixture absent = seed(); UUID exhausted = submit(absent); began = System.nanoTime();
        await().atMost(Duration.ofSeconds(120)).untilAsserted(() -> assertThat(text("SELECT status || '/' || failure_code || '/' || attempt_count FROM ts_device_command WHERE id=?", exhausted)).isEqualTo("TIMED_OUT/DISPATCH_RETRY_EXHAUSTED/3"));
        assertThat(text("SELECT count(*)::text FROM ts_device_command_attempt WHERE command_id=? AND status='FAILED' AND error_code='DISPATCH_PENDING_TIMEOUT'", exhausted)).isEqualTo("3");
        terminalOnce(exhausted); bounded(began); evidence("T04", exhausted, "two live schedulers exhausted exactly three attempts");
        evidence("T08", exhausted, "two independent JVM schedulers; no duplicate attempts or terminal event; identity/lease races also covered by PG suite");

        Fixture takeover = seed();
        try (SSLSocket old = authenticated(a, takeover)) {
            control(a, "arm-barrier"); UUID command = submit(takeover); hit(a, "barrier-hit");
            try (SSLSocket current = authenticated(b, takeover)) {
                control(a, "release-barrier"); complete(current, command);
                send(old, DeviceAccessTcpFrameType.HEARTBEAT, new byte[0]);
                assertThat(frame(old, DeviceAccessTcpFrameType.ERROR).get("errorCode").asString()).isEqualTo("AUTH_REQUIRED");
                send(current, DeviceAccessTcpFrameType.HEARTBEAT, new byte[0]); frame(current, DeviceAccessTcpFrameType.HEARTBEAT_ACK);
                evidence("T05", command, "A paused after admission, B takeover, old close preserved B; only B completed");
            }
        }
        Fixture crash = seed(); UUID interrupted;
        try (SSLSocket lost = authenticated(b, crash)) {
            control(b, "arm-barrier"); interrupted = submit(crash); hit(b, "barrier-hit");
            b.process.destroyForcibly(); assertThat(b.process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        }
        began = System.nanoTime();
        Node c = startNode("C", false); assertThat(c.status.get("runtime").asString()).isNotEqualTo(b.status.get("runtime").asString());
        try (SSLSocket device = authenticated(c, crash)) { complete(device, interrupted); }
        bounded(began); terminalOnce(interrupted); evidence("T06", interrupted, "B killed before write; fresh C group and natural retry recovered");

        Fixture duplicate = seed(); UUID written;
        Path ledger = run.resolve("device-dedup-ledger.txt");
        try (SSLSocket device = authenticated(c, duplicate)) {
            control(c, "arm-mark-failure"); written = submit(duplicate);
            assertThat(frame(device, DeviceAccessTcpFrameType.DOWNLINK).get("commandId").asString()).isEqualTo(written.toString());
            try (var channel = java.nio.channels.FileChannel.open(ledger, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                channel.write(StandardCharsets.UTF_8.encode(written + "\n")); channel.force(true);
            }
            hit(c, "mark-failed");
        }
        assertThat(text("SELECT status FROM ts_device_command_attempt WHERE command_id=? AND attempt_no=1", written)).isEqualTo("PENDING");
        began = System.nanoTime();
        try (SSLSocket device = authenticated(c, duplicate)) {
            assertThat(Files.readAllLines(ledger)).containsExactly(written.toString());
            complete(device, written); // 已在账本中的commandId只回复，不再次执行。
        }
        bounded(began); terminalOnce(written); evidence("T07", written, "actual write then transactional SQL rollback; durable ledger across reconnect executes once");

        Fixture race=seed();
        try (SSLSocket device=authenticated(c,race)) {
            control(c,"arm-mark-barrier"); UUID command=submit(race);
            assertThat(frame(device,DeviceAccessTcpFrameType.DOWNLINK).get("commandId").asString()).isEqualTo(command.toString());
            hit(c,"mark-barrier-hit"); reply(device,command); control(c,"release-barrier"); consumed(admin,a,c);
            assertThat(text("SELECT status FROM ts_device_command_attempt WHERE command_id=?",command)).isEqualTo("SUCCEEDED");
            terminalOnce(command); evidence("T08",command,"reply committed while markDispatched was paused; late confirmation did not overwrite terminal");
        }
        verifyExpiredAndFrozen(admin, a, c);
        verifyInvalidEnvelopes(admin, a, c);
        Fixture changed = seed();
        try (SSLSocket device = authenticated(c, changed)) {
            control(c, "arm-barrier"); UUID command = submit(changed); hit(c, "barrier-hit");
            inProject(changed, () -> sessionRepository.bind(changed.tenantId(), changed.projectId(), changed.deviceId(), TransportProtocol.HTTP, 30));
            control(c, "release-barrier"); noFrame(device);
            assertThat(text("SELECT status FROM ts_device_command_attempt WHERE command_id=? AND attempt_no=1", command)).isEqualTo("PENDING");
            evidence("T10", command, "config switched to HTTP after admission; old TCP connection received no DOWNLINK");
        }
        Fixture readFailure = seed();
        try (SSLSocket device = authenticated(c, readFailure)) {
            control(c, "arm-read-failure"); UUID command = submit(readFailure); hit(c, "read-failed"); complete(device, command);
            assertThat(text("SELECT attempt_count::text FROM ts_device_command WHERE id=?", command)).isEqualTo("1");
            evidence("T11", command, "database read exception retried by Kafka; not classified offline or skipped");
        }
        verifyScaleAndRollback(admin,a,c);
        Files.writeString(run.resolve("matrix-pass.txt"), Boolean.getBoolean("tcp.forward.schema.restart")
                ? "T01-T12 plus same-candidate restart passed; no old-writer rollback qualification; PG lease/migration suite required separately\n"
                : "T01-T12 plus compatible single-node rollback passed; PG lease/migration suite required separately\n");
    }
    /** 分区与启动/回滚场景可单独开发验证；只有完整路径生成矩阵通过回执。 */
    private void verifyScaleAndRollback(Admin admin, Node a, Node c) throws Exception {
        admin.createPartitions(Map.of("tc.device.downlink", org.apache.kafka.clients.admin.NewPartitions.increaseTo(4))).all().get();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            for (Node node : List.of(a, c)) assertThat(admin.describeConsumerGroups(List.of(node.status.get("group").asString())).all().get().values().iterator().next().members().stream().flatMap(m -> m.assignment().topicPartitions().stream()).distinct().count()).isEqualTo(4);
        });
        await().atMost(Duration.ofSeconds(30)).until(() -> control(a,"status").get("ready").asBoolean() && control(c,"status").get("ready").asBoolean());
        Fixture grown = seed();
        try (SSLSocket device = authenticated(c, grown)) { UUID command = submit(grown); complete(device, command); evidence("T12", command, "both groups rebalanced from three to four partitions"); }
        for(Fixture fixture:fixtures) assertThat(text("SELECT count(*)::text FROM ts_device_command_attempt WHERE project_id=? AND error_code='DISPATCH_DEVICE_OFFLINE'",fixture.projectId())).isEqualTo("0");
        verifyUnauthorizedStartup();
        for (Node node : nodes) stop(node);
        boolean forwardRestart = Boolean.getBoolean("tcp.forward.schema.restart");
        rollbackRoot = System.getProperty("tcp.rollback.root");
        if (forwardRestart) assertThat(rollbackRoot).as("ADR0191 forward restart must not load an old writer").isNull();
        else assertThat(rollbackRoot).as("full legacy matrix requires built compatible rollback root").isNotBlank();
        Node rollback = startNode("rollback", true);
        Fixture restored = seed();
        try (SSLSocket device = authenticated(rollback, restored)) { UUID command = submit(restored); complete(device, command); evidence(forwardRestart ? "FORWARD_RESTART" : "ROLLBACK", command, forwardRestart
                ? "all processes stopped; same-candidate single process delivered on current schema"
                : "all new processes stopped; configured compatible baseline delivered on forward schema"); }
        stop(rollback); rollbackRoot = null;

    }
    /** 错误身份及原始Outbox校验通过真实消费者组验证，无合法事件混入观察窗口。 */
    private void verifyInvalidEnvelopes(Admin admin, Node a, Node c) throws Exception {
        Fixture fixture=seed();
        try (SSLSocket device=authenticated(c,fixture)) {
            control(a,"pause-outbox"); UUID command=submit(fixture);
            String raw=text("SELECT payload::text FROM sys_outbox_event WHERE aggregate_id=? AND event_type='DEVICE_COMMAND_DISPATCH'",command);
            for (String field:List.of("tenantId","projectId","connectionDeviceId","eventId")) {
                var json=(tools.jackson.databind.node.ObjectNode)JSON.readTree(raw); json.put(field,Uuid7.generate().toString());
                var envelope=JSON.treeToValue(json,com.things.link.shared.message.DeviceCommandDispatch.class);
                kafka.send("tc.device.downlink",fixture.deviceId().toString(),envelope).get(10,TimeUnit.SECONDS);
            }
            kafka.send("tc.device.downlink",Uuid7.generate().toString(),JSON.readValue(raw,com.things.link.shared.message.DeviceCommandDispatch.class)).get(10,TimeUnit.SECONDS);
            consumed(admin,a,c); noFrame(device);
            assertThat(text("SELECT status || '/' || attempt_count FROM ts_device_command WHERE id=?",command)).isEqualTo("ACCEPTED/1");
            control(a,"resume-outbox"); complete(device,command);
            evidence("T11",command,"wrong tenant/project/device/key/original Outbox rejected without state or network effects; valid source recovered");
        } finally { control(a,"resume-outbox"); }
    }
    /** 独占授权Broker确证无主题和Topic READ拒绝均不能打开TCP监听。 */
    private void verifyUnauthorizedStartup() throws Exception {
        try (var broker=new org.testcontainers.kafka.KafkaContainer(org.testcontainers.utility.DockerImageName.parse("apache/kafka:4.1.0"))
                .withEnv("KAFKA_AUTHORIZER_CLASS_NAME","org.apache.kafka.metadata.authorizer.StandardAuthorizer")
                .withEnv("KAFKA_ALLOW_EVERYONE_IF_NO_ACL_FOUND","true")
                .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE","false")) {
            broker.start(); startupBroker=broker.getBootstrapServers(); expectStartupFailure=true;
            startNode("missing-topic",false);
            try (Admin admin=Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,startupBroker))) {
                admin.createTopics(List.of(new org.apache.kafka.clients.admin.NewTopic("tc.device.downlink",3,(short)1))).all().get(15,TimeUnit.SECONDS);
                var deny=new org.apache.kafka.common.acl.AclBinding(
                        new org.apache.kafka.common.resource.ResourcePattern(org.apache.kafka.common.resource.ResourceType.TOPIC,"tc.device.downlink",org.apache.kafka.common.resource.PatternType.LITERAL),
                        new org.apache.kafka.common.acl.AccessControlEntry("User:ANONYMOUS","*",org.apache.kafka.common.acl.AclOperation.READ,org.apache.kafka.common.acl.AclPermissionType.DENY));
                var describe=new org.apache.kafka.common.acl.AclBinding(deny.pattern(),
                        new org.apache.kafka.common.acl.AccessControlEntry("User:ANONYMOUS","*",org.apache.kafka.common.acl.AclOperation.DESCRIBE,org.apache.kafka.common.acl.AclPermissionType.ALLOW));
                admin.createAcls(List.of(deny,describe)).all().get(15,TimeUnit.SECONDS);
                assertThat(admin.describeTopics(List.of("tc.device.downlink")).allTopicNames().get(15,TimeUnit.SECONDS)).containsKey("tc.device.downlink");
                Node rejected=startNode("unauthorized-topic",false);
                assertThat(Files.readString(rejected.directory.resolve("node.log"))).contains("TopicAuthorizationException");
            }
            Files.writeString(run.resolve("startup-negative.txt"),"missing topic and real READ ACL denial: process failed before listener ready\n");
        } finally { startupBroker=null; expectStartupFailure=false; }
    }
    /** Kafka真实提交位点达到场景开始后的高水位，绝不通过改位点驱动恢复。 */
    private void consumed(Admin admin, Node... owners) throws Exception {
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            var partitions = admin.describeTopics(List.of("tc.device.downlink")).allTopicNames().get().get("tc.device.downlink").partitions();
            Map<org.apache.kafka.common.TopicPartition, org.apache.kafka.clients.admin.OffsetSpec> query = new java.util.HashMap<>();
            for (var partition : partitions) query.put(new org.apache.kafka.common.TopicPartition("tc.device.downlink", partition.partition()), org.apache.kafka.clients.admin.OffsetSpec.latest());
            var ends = admin.listOffsets(query).all().get();
            for (Node node : owners) {
                var positions = admin.listConsumerGroupOffsets(node.status.get("group").asString()).partitionsToOffsetAndMetadata().get();
                for (var entry : ends.entrySet()) {
                    var position=positions.get(entry.getKey());
                    if (entry.getValue().offset()==0 && position==null) continue; // 空分区尚无记录可提交，不能把无提交误认为落后。
                    assertThat(position).isNotNull().satisfies(value -> assertThat(value.offset()).isGreaterThanOrEqualTo(entry.getValue().offset()));
                }
                Files.writeString(node.directory.resolve("offsets.txt"), positions.toString());
            }
        });
    }
    /** 恢复时间纳入实测上界。 */
    private void bounded(long start) throws IOException {
        long millis = Duration.ofNanos(System.nanoTime() - start).toMillis(); assertThat(millis).isLessThan(120000);
        Files.writeString(run.resolve("recovery-millis.txt"), millis + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
    /** 终态事件唯一。 */
    private void terminalOnce(UUID command) throws SQLException {
        assertThat(text("SELECT count(*)::text FROM sys_outbox_event WHERE aggregate_id=? AND event_type='DEVICE_COMMAND_TERMINAL' AND payload::jsonb->>'status' IN ('SUCCEEDED','TIMED_OUT')", command)).isEqualTo("1");
    }
    /** 等待明确故障回执而非猜测sleep。 */
    private void hit(Node node, String file) { await().atMost(Duration.ofSeconds(15)).until(() -> Files.exists(node.directory.resolve(file))); }
    /** 短观察窗口只断言本场景不应写网络帧。 */
    private void noFrame(SSLSocket device) throws Exception {
        device.setSoTimeout(1200); assertThatThrownBy(() -> new DeviceAccessTcpFrameReader(device.getInputStream()).readFrame()).isInstanceOf(java.net.SocketTimeoutException.class); device.setSoTimeout(30000);
    }
    /** 截止与冻结均发生在真实Kafka准入之前。 */
    private void verifyExpiredAndFrozen(Admin admin, Node a, Node c) throws Exception {
        for (Node node : List.of(a,c)) { control(node, "pause-scanner"); control(node, "stop-tcp-consumer"); }
        // 已连接设备在失去消费就绪后仍可维持连接；必须先恢复准入建立测试连接。
        control(c,"start-tcp-consumer"); await().atMost(Duration.ofSeconds(30)).until(() -> control(c,"status").get("ready").asBoolean());
        Fixture fixture=seed();
        try (SSLSocket device=authenticated(c,fixture)) {
            control(c,"stop-tcp-consumer"); UUID expired=submit(fixture);
            await().atMost(Duration.ofSeconds(15)).until(() -> text("SELECT (deadline_at < clock_timestamp())::text FROM ts_device_command WHERE id=?", expired).equals("true"));
            await().atMost(Duration.ofSeconds(8)).until(() -> text("SELECT status FROM sys_outbox_event WHERE aggregate_id=? AND event_type='DEVICE_COMMAND_DISPATCH'", expired).equals("PUBLISHED"));
            control(a,"start-tcp-consumer"); control(c,"start-tcp-consumer"); consumed(admin,a,c); noFrame(device);
            evidence("T09",expired,"expired original record consumed with scanners paused; no external write");
            for(Node node:List.of(a,c)) control(node,"resume-scanner"); complete(device,expired);
            for(Node node:List.of(a,c)) control(node,"stop-tcp-consumer"); UUID frozen=submit(fixture);
            try(Connection owner=ownerConnection()) { execute(owner,"UPDATE sys_project SET status='ARCHIVED' WHERE id=?",fixture.projectId()); }
            for(Node node:List.of(a,c)) control(node,"start-tcp-consumer");
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(text("SELECT failure_code FROM ts_device_command WHERE id=?",frozen)).isEqualTo("PROJECT_FROZEN"));
            noFrame(device); assertThat(text("SELECT attempt_count::text FROM ts_device_command WHERE id=?",frozen)).isEqualTo("1"); evidence("T09",frozen,"project archived before admission; no retry or network write");
        }
    }
    /** 关闭独占子进程，崩溃场景无需写已关闭stdin。 */
    private void stop(Node node) throws Exception {
        if (!node.process.isAlive()) return;
        node.process.getOutputStream().close();
        if (!node.process.waitFor(20, TimeUnit.SECONDS)) { node.process.destroy(); if (!node.process.waitFor(5, TimeUnit.SECONDS)) node.process.destroyForcibly().waitFor(5, TimeUnit.SECONDS); }
        assertThat(node.process.isAlive()).isFalse();
    }

    /** T10：两广播进程都存在时，MQTT真实Broker仅收到一次；HTTP/CoAP仍由领取推进。 */
    private void verifyMqttAndPullRoles() throws Exception {
        Fixture mqtt = seed(null); // 明确测试存量无行配置0；不使用当前模型禁止的伪造配置。
        // 本夹具的匿名Broker仅核验共享消费者发布一次；真实设备挂载点/认证隔离由0194专项验证。
        String prefix = com.things.link.device.application.DeviceMqttSessionIdentity.mountpoint(mqtt.deviceId(), 0)
                + "tc/v1/" + mqtt.projectKey() + "/ax3c_device/down/command/";
        try (Subscriber subscriber = new Subscriber(prefix + "#")) {
            UUID command = submit(mqtt);
            assertThat(subscriber.message(prefix + command)).isNotEmpty();
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(text("SELECT status FROM ts_device_command WHERE id=?", command)).isEqualTo("DISPATCHED"));
            subscriber.socket.setSoTimeout(1000);
            assertThatThrownBy(() -> subscriber.message(prefix + command)).isInstanceOf(java.net.SocketTimeoutException.class);
            evidence("T10", command, "real EMQX subscription received one MQTT publish with both TCP groups active");
        }
        for (TransportProtocol protocol : List.of(TransportProtocol.HTTP, TransportProtocol.COAP)) {
            Fixture fixture = seed();
            inProject(fixture, () -> sessionRepository.bind(fixture.tenantId(), fixture.projectId(), fixture.deviceId(), protocol, 30));
            UUID command = submit(fixture);
            assertThat(text("SELECT count(*)::text FROM ts_device_command_attempt WHERE command_id=?", command)).isEqualTo("0");
            assertThat(claims.claim(new com.things.link.telemetry.application.DeviceCommandClaimPort.ClaimRequest(fixture.tenantId(), fixture.projectId(), fixture.deviceId(), null, 1)))
                    .extracting(com.things.link.telemetry.application.DeviceCommandClaimPort.Claimed::commandId).containsExactly(command);
            evidence("T10", command, protocol + " uses claim and has no push attempt");
        }
    }

    /** TLS仅信任指定测试证书，启用主机名核验。 */
    private SSLSocket authenticated(Node node, Fixture fixture) throws Exception {
        java.security.cert.Certificate certificate;
        try (var input = Files.newInputStream(TLS_FIXTURE_DIRECTORY.resolve("device-access-test-cert.pem"))) {
            certificate = CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType()); trust.load(null); trust.setCertificateEntry("fixture", certificate);
        var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); factory.init(trust);
        SSLContext context = SSLContext.getInstance("TLS"); context.init(null, factory.getTrustManagers(), null);
        SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket("localhost", node.status.get("port").asInt());
        var parameters = socket.getSSLParameters(); parameters.setEndpointIdentificationAlgorithm("HTTPS"); socket.setSSLParameters(parameters);
        socket.setSoTimeout(30000);
        try { socket.startHandshake(); } catch (IOException exception) { socket.close(); throw exception; }
        send(socket, DeviceAccessTcpFrameType.AUTH_REQUEST, JSON.writeValueAsBytes(Map.of("projectKey", fixture.projectKey(), "deviceKey", "ax3c_device", "secret", SECRET)));
        assertThat(frame(socket, DeviceAccessTcpFrameType.AUTH_RESPONSE).get("status").asString()).isEqualTo("OK");
        return socket;
    }
    /** 从生产应用受理，绝不直接调用consumer。 */
    private UUID submit(Fixture fixture) {
        return inProject(fixture, () -> commandService.submitTrusted(fixture.projectId(), fixture.deviceId(), Uuid7.generate().toString(), "reboot", JSON.readTree("{}"), fixture.accountId(), null).id());
    }
    /** 完整设备下行/回复/受理闭环，设备确认才形成终态。 */
    private void complete(SSLSocket socket, UUID command) throws Exception {
        assertThat(frame(socket, DeviceAccessTcpFrameType.DOWNLINK).get("commandId").asString()).isEqualTo(command.toString());
        reply(socket, command);
    }
    /** 已收下行后发送真实回复，允许控制mark/reply竞争时序。 */
    private void reply(SSLSocket socket, UUID command) throws Exception {
        send(socket, DeviceAccessTcpFrameType.REPLY, JSON.writeValueAsBytes(Map.of("commandId", command, "messageId", Uuid7.generate(), "occurredAt", Instant.now().toString(), "status", "SUCCESS", "output", Map.of())));
        assertThat(frame(socket, DeviceAccessTcpFrameType.ACCEPTED).get("commandId").asString()).isEqualTo(command.toString());
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(text("SELECT status FROM ts_device_command WHERE id=?", command)).isEqualTo("SUCCEEDED"));
    }
    /** 真实TCP序列化写。 */
    private static void send(SSLSocket socket, DeviceAccessTcpFrameType type, byte[] body) throws IOException { socket.getOutputStream().write(DeviceAccessTcpFrameCodec.encode(type, body)); socket.getOutputStream().flush(); }
    /** 真实TCP解帧。 */
    private static JsonNode frame(SSLSocket socket, DeviceAccessTcpFrameType type) throws IOException {
        var frame = new DeviceAccessTcpFrameReader(socket.getInputStream()).readFrame(); assertThat(frame).isNotNull(); assertThat(frame.type()).isEqualTo(type); return JSON.readTree(frame.payload());
    }
    /** 保存脱敏状态/attempt/Outbox事实。 */
    private void evidence(String scenario, UUID command, String observation) throws Exception {
        String facts = text("SELECT json_build_object('command',c.id,'status',c.status,'attempt',c.attempt_count,'failure',c.failure_code,'outbox',(SELECT json_agg(json_build_object('id',o.id,'type',o.event_type,'created',o.created_at)) FROM sys_outbox_event o WHERE o.aggregate_id=c.id),'attempts',(SELECT json_agg(json_build_object('id',a.id,'no',a.attempt_no,'status',a.status,'error',a.error_code)) FROM ts_device_command_attempt a WHERE a.command_id=c.id),'sessions',(SELECT json_agg(json_build_object('rowId',s.id,'generation',s.generation,'configVersion',s.config_version,'owner',s.owner_instance,'closed',s.disconnected_at)) FROM dev_connection s WHERE s.device_id=c.target_device_id))::text FROM ts_device_command c WHERE c.id=?", command);
        Files.writeString(run.resolve("evidence.jsonl"), JSON.writeValueAsString(Map.of("scenario",scenario,"observed",observation,"facts",JSON.readTree(facts))) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
    /** 隔离classpath只复制单个测试入口，不把其他测试配置扫描进生产进程。 */
    private Node startNode(String name, boolean shared) throws Exception {
        Path dir = Files.createDirectory(run.resolve(name)); Path cp = Files.createDirectories(dir.resolve("classpath/com/things/link/bootstrap/device/access/fixture"));
        try (var classes = Files.list(Path.of("target/test-classes/com/things/link/bootstrap/device/access/fixture"))) {
            for (Path file : classes.filter(f -> f.getFileName().toString().startsWith("TcpNodeProcess")).toList()) Files.copy(file, cp.resolve(file.getFileName()));
        }
        Files.copy(Path.of("src/test/resources/application-test.yml"), dir.resolve("classpath/application-test.yml"));
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String root = Path.of("..").toAbsolutePath().normalize().toString();
        String baseline = rollbackRoot == null ? System.getProperty("tcp.baseline.root") : rollbackRoot;
        List<String> entries = new ArrayList<>(); entries.add(dir.resolve("classpath").toString());
        for (String entry : classpath.split(java.io.File.pathSeparator)) {
            if (entry.contains("/target/test-classes")) continue;
            if (baseline != null && entry.startsWith(root + "/")) entry = baseline + "/things-link" + entry.substring(root.length());
            entries.add(entry);
        }
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx384m", "-Dtcp.fixture.directory=" + dir, "-Dtcp.fixture.shared=" + shared, "-cp", String.join(File.pathSeparator, entries), "com.things.link.bootstrap.device.access.fixture.TcpNodeProcess",
                "--things-link.ingestion.emqx-api.base-url=http://" + BROKER.getHost() + ":" + BROKER.getMappedPort(18083),
                "--things-link.ingestion.emqx-api.api-key=tcp-test", "--things-link.ingestion.emqx-api.api-secret=tcp-test-secret",
                "--server.port=0", "--spring.flyway.enabled=false", "--spring.datasource.url=" + POSTGRES.getJdbcUrl(), "--spring.datasource.username=" + APP_ROLE, "--spring.datasource.password=" + APP_ROLE_PASSWORD,
                "--spring.data.redis.host=" + REDIS.getHost(), "--spring.data.redis.port=" + REDIS.getMappedPort(6379), "--spring.kafka.bootstrap-servers=" + (startupBroker == null ? KAFKA.getBootstrapServers() : startupBroker),
                "--spring.kafka.admin.auto-create=false", "--spring.kafka.listener.auto-startup=false", "--things-link.access.tcp.enabled=true", "--things-link.access.tcp.port=0", "--things-link.access.tcp.instance-id=same-label",
                "--things-link.access.tls.certificate=file:" + TLS_FIXTURE_DIRECTORY.resolve("device-access-test-cert.pem"), "--things-link.access.tls.private-key=file:" + TLS_FIXTURE_DIRECTORY.resolve("device-access-test-key.pem"),
                "--things-link.outbox.publisher.enabled=" + shared, "--things-link.kafka.concurrency.ingestion-downlink=1", "--things-link.command.timeout-scan-initial-delay-millis=1000", "--spring.kafka.consumer.properties.metadata.max.age.ms=1000"));
        if (expectStartupFailure) command.add("--things-link.access.tcp.consumer-ready-timeout-seconds=30");
        Files.writeString(dir.resolve("launch.txt"), String.join("\n", command.stream().filter(v -> !v.contains("password=") && !v.contains("api-secret=")).toList()));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(dir.resolve("node.log").toFile()).start();
        Node node = new Node(process, dir); nodes.add(node);
        await().atMost(Duration.ofSeconds(60)).until(() -> Files.exists(dir.resolve("ready.json")) || !process.isAlive());
        if (expectStartupFailure) {
            assertThat(process.isAlive()).as("unauthorized listener must fail startup").isFalse();
            assertThat(Files.exists(dir.resolve("ready.json"))).isFalse(); return node;
        }
        assertThat(process.isAlive()).as("node %s failed; see %s", name, dir.resolve("node.log")).isTrue();
        node.status = JSON.readTree(Files.readString(dir.resolve("ready.json")));
        return node;
    }
    /** 独立进程控制通道，只操作真实容器生命周期。 */
    private JsonNode control(Node node, String action) throws Exception {
        String id = Uuid7.generate().toString();
        node.process.getOutputStream().write((action + " " + id + "\n").getBytes(StandardCharsets.UTF_8));
        node.process.getOutputStream().flush();
        Path result = node.directory.resolve("done-" + id);
        await().atMost(Duration.ofSeconds(15)).until(() -> Files.exists(result));
        return JSON.readTree(Files.readString(result));
    }
    /** 先停本轮所有进程，之后清理独占夹具与退役组。 */
    @AfterEach void stopNodes() throws Exception {
        for (Node node : nodes) {
            stop(node);
        }
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            for (Node node : nodes) if (node.status != null && !node.status.get("group").asString().equals("legacy")) admin.deleteConsumerGroups(List.of(node.status.get("group").asString())).all().get(15, TimeUnit.SECONDS);
        }
        List<UUID> tenantIds=fixtures.stream().map(Fixture::tenantId).toList();
        clearFixtures();
        for(UUID tenant:tenantIds) assertThat(text("SELECT count(*)::text FROM sys_tenant WHERE id=?",tenant)).isEqualTo("0");
        for (Node node : nodes) {
            Path classpath = node.directory.resolve("classpath");
            if (Files.exists(classpath)) try (var paths = Files.walk(classpath)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
            try (var paths = Files.list(node.directory)) {
                for (Path path : paths.filter(path -> path.getFileName().toString().startsWith("done-")).toList()) Files.delete(path);
            }
        }
        if(run!=null) Files.writeString(run.resolve("cleanup.json"),JSON.writeValueAsString(Map.of("processesStopped",nodes.stream().map(n->n.process.pid()).toList(),"retiredGroupsDeleted",nodes.stream().filter(n->n.status!=null).map(n->n.status.get("group").asString()).filter(g->!g.equals("legacy")).toList(),"tenantFixturesRemoved",tenantIds,"privateClasspathAndControlsRemoved",true)));
    }
    /** 子进程所有权和只读启动回执。 */
    private static final class Node {
        /** 独占进程。 */ final Process process;
        /** 控制和日志目录。 */ final Path directory;
        /** 就绪回执。 */ JsonNode status;
        /** 绑定所有权。 */ Node(Process process, Path directory) { this.process=process; this.directory=directory; }
    }

    /** 复用已验证的独占业务夹具，保持原RLS及清理边界。 */
    private void clearFixtures() throws SQLException {
        try (Connection owner = ownerConnection()) {
            for (Fixture fixture : fixtures) {
                // 原始时序点必须先清：物模型版本删除有 MODEL_RAW_REFERENCE_REMAINS 守卫。
                execute(owner, "DELETE FROM public.ts_property_point_internal WHERE project_id = ?",
                        fixture.projectId());
                execute(owner, "DELETE FROM ts_device_message_log WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM sys_message_log_inbox WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM sys_inbox_message WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM sys_usage_counter_daily WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_shadow WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM sys_outbox_event WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM ts_device_command_claim WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM ts_device_command WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_connection WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_command_definition WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_credential WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_access_binding WHERE project_id = ?", fixture.projectId());
                // 设备必须先删：绑定历史有 D114 不可变守卫，只有真实父实体消失后的级联删除才被放行。
                execute(owner, "DELETE FROM dev_device WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_device_model_binding_history WHERE project_id = ?",
                        fixture.projectId());
                execute(owner, "DELETE FROM dev_thing_model_version WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_type WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM sys_account WHERE id = ?", fixture.accountId());
                execute(owner, "DELETE FROM sys_project WHERE id = ?", fixture.projectId());
                execute(owner, "DELETE FROM sys_tenant WHERE id = ?", fixture.tenantId());
            }
        }
        fixtures.clear();
    }


    /** 复用已验证的独占业务夹具，保持原RLS及清理边界。 */
    private Fixture seed() throws SQLException { return seed(TransportProtocol.TCP); }

    /** 无配置为存量MQTT，有配置按指定接入面建立。 */
    private Fixture seed(TransportProtocol protocol) throws SQLException {
        Fixture fixture = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                Uuid7.generate(), Uuid7.generate(),
                "ax3c_" + Uuid7.generate().toString().replace("-", "").substring(24));
        fixtures.add(fixture);
        try (Connection owner = ownerConnection()) {
            execute(owner, "INSERT INTO sys_tenant (id, name) VALUES (?, 'TCP业务独占租户')", fixture.tenantId());
            execute(owner, "INSERT INTO sys_account (id, email, password_hash, display_name)"
                    + " VALUES (?, ?, '{noop}unused', 'TCP业务 OWNER')", fixture.accountId(),
                    fixture.accountId() + "@example.com");
            execute(owner, "INSERT INTO sys_project (id, tenant_id, name, project_key)"
                    + " VALUES (?, ?, 'TCP业务项目', ?)",
                    fixture.projectId(), fixture.tenantId(), fixture.projectKey());
            execute(owner, """
                    INSERT INTO dev_type (id, tenant_id, project_id, type_key, name, device_kind,
                                          access_protocol, network_type, status)
                    VALUES (?, ?, ?, 'ax3c_type', 'TCP业务类型', 'DIRECT', 'STANDARD', 'WIFI', 'PUBLISHED')
                    """, fixture.typeId(), fixture.tenantId(), fixture.projectId());
            execute(owner, """
                    INSERT INTO dev_command_definition
                        (id, tenant_id, project_id, device_type_id, command_key, name, input_schema, output_schema,
                         timeout_seconds)
                    VALUES (?, ?, ?, ?, 'reboot', '重启', '{}', '{}', 10)
                    """, fixture.definitionId(), fixture.tenantId(), fixture.projectId(), fixture.typeId());
            execute(owner, """
                    INSERT INTO dev_device (id, tenant_id, project_id, device_type_id, device_key, name, status)
                    VALUES (?, ?, ?, ?, 'ax3c_device', 'TCP业务设备', 'ONLINE')
                    """, fixture.deviceId(), fixture.tenantId(), fixture.projectId(), fixture.typeId());
            execute(owner, """
                    INSERT INTO dev_credential
                        (id, tenant_id, project_id, device_id, auth_type, credential_hash, display_name)
                    VALUES (?, ?, ?, ?, 'ACCESS_TOKEN', ?, '设备密钥')
                    """, Uuid7.generate(), fixture.tenantId(), fixture.projectId(), fixture.deviceId(),
                    sha256(SECRET));
        }
        // 数据面夹具直接建立 1.0.0 与 INITIAL 绑定，接通版本化摄入链（版本发布属控制面）。
        seedThingModelVersion(fixture.tenantId(), fixture.projectId(), fixture.typeId(), fixture.deviceId(),
                TEMPERATURE_SNAPSHOT);
        if (protocol != null) inProject(fixture, () -> sessionRepository.bind(fixture.tenantId(), fixture.projectId(), fixture.deviceId(),
                protocol, 30));
        return fixture;
    }


    /** 复用已验证的独占业务夹具，保持原RLS及清理边界。 */
    private <T> T inProject(Fixture fixture, Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class,
                    fixture.tenantId().toString());
            jdbcTemplate.queryForObject("SELECT set_config('app.project_id', ?, true)", String.class,
                    fixture.projectId().toString());
            return work.get();
        });
    }


    /** 复用已验证的独占业务夹具，保持原RLS及清理边界。 */
    private static String sha256(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("JVM 没有 SHA-256", exception);
        }
    }


    /** 复用已验证的独占业务夹具，保持原RLS及清理边界。 */
    private static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }


    /** 复用已验证的独占业务夹具，保持原RLS及清理边界。 */
    private static void execute(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) {
                statement.setObject(index + 1, arguments[index]);
            }
            statement.executeUpdate();
        }
    }


    /** 复用已验证的独占业务夹具，保持原RLS及清理边界。 */
    private String text(String sql, Object... arguments) throws SQLException {
        try (Connection owner = ownerConnection();
             PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) {
                statement.setObject(index + 1, arguments[index]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }


    /** 复用已验证的独占业务夹具，保持原RLS及清理边界。 */
    private record Fixture(UUID tenantId, UUID projectId, UUID deviceId, UUID typeId, UUID definitionId,
                           UUID accountId, String projectKey) {
    }

    /** 最小MQTT协议设备，复用真实Broker验收的有界编解码。 */
    private static final class Subscriber implements AutoCloseable {
        /** 真实Broker连接。 */ private final Socket socket;
        /** 有界网络输入。 */ private final DataInputStream input;
        /** MQTT编码输出。 */ private final DataOutputStream output;
        /** 建立匿名协议夹具连接并等待SUBACK，避免订阅竞态。 */
        private Subscriber(String topic) throws IOException {
            socket = new Socket(BROKER.getHost(), BROKER.getMappedPort(1883));
            socket.setSoTimeout(30000);
            input = new DataInputStream(socket.getInputStream());
            output = new DataOutputStream(socket.getOutputStream());
            var connect = new ByteArrayOutputStream();
            var body = new DataOutputStream(connect);
            utf(body, "MQTT");
            body.writeByte(4);
            body.writeByte(2);
            body.writeShort(30);
            utf(body, "ota-protocol-" + UUID.randomUUID());
            send(0x10, connect.toByteArray());
            Packet ack = read();
            assertEquals(0x20, ack.header());
            assertArrayEquals(new byte[] {0, 0}, ack.body());
            var subscribe = new ByteArrayOutputStream();
            body = new DataOutputStream(subscribe);
            body.writeShort(1);
            utf(body, topic);
            body.writeByte(1);
            send(0x82, subscribe.toByteArray());
            ack = read();
            assertEquals(0x90, ack.header());
            assertArrayEquals(new byte[] {0, 1, 1}, ack.body());
        }
        /** 实际PUBLISH必须QoS1且非retained，返回精确剩余载荷。 */
        private byte[] message(String expectedTopic) throws IOException {
            Packet packet = read();
            assertEquals(0x32, packet.header());
            var body = new DataInputStream(new java.io.ByteArrayInputStream(packet.body()));
            int topicLength = body.readUnsignedShort();
            assertEquals(expectedTopic, new String(body.readNBytes(topicLength), StandardCharsets.UTF_8));
            int id = body.readUnsignedShort();
            byte[] payload = body.readAllBytes();
            send(0x40, new byte[] {(byte) (id >>> 8), (byte) id});
            return payload;
        }
        /** 剩余长度最多四字节且报文最多64KiB，坏协议立即失败。 */
        private Packet read() throws IOException {
            int header = input.readUnsignedByte();
            int size = 0;
            int multiplier = 1;
            for (int index = 0; index < 4; index++) {
                int encoded = input.readUnsignedByte();
                size += (encoded & 127) * multiplier;
                if (size > 65536) throw new IOException("MQTT测试报文超限");
                if ((encoded & 128) == 0) {
                    byte[] bytes = input.readNBytes(size);
                    if (bytes.length != size) throw new IOException("MQTT测试报文截断");
                    return new Packet(header, bytes);
                }
                multiplier *= 128;
            }
            throw new IOException("MQTT测试长度无效");
        }
        /** MQTT固定头与可变剩余长度。 */
        private void send(int header, byte[] body) throws IOException {
            output.writeByte(header);
            int remaining = body.length;
            do {
                int encoded = remaining % 128;
                remaining /= 128;
                output.writeByte(encoded | (remaining == 0 ? 0 : 128));
            } while (remaining != 0);
            output.write(body);
            output.flush();
        }
        /** MQTT采用UTF8字节长度，不能使用Java修改UTF格式。 */
        private static void utf(DataOutputStream output, String text) throws IOException {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            output.writeShort(bytes.length);
            output.write(bytes);
        }
        /** 确保测试成功或失败都关闭物理连接。 */
        @Override public void close() throws IOException { socket.close(); }
    }
    /** 有界协议包。
     * @param header 固定头
     * @param body 剩余正文
     */
    private record Packet(int header, byte[] body) { }
}
