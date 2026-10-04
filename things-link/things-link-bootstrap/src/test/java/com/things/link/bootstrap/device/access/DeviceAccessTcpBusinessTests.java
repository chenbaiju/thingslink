package com.things.link.bootstrap.device.access;

import com.things.link.bootstrap.fixture.DeviceDataPlaneTopicsTestConfiguration;
import com.things.link.bootstrap.fixture.DeviceProtocolKafkaTopicsTestConfiguration;
import com.things.link.device.domain.DeviceAccessSessionRepository;
import com.things.link.ingestion.infrastructure.DeviceCommandDownlinkKafkaConsumer;
import com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTcpFrameCodec;
import com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTcpFrameReader;
import com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTcpFrameType;
import com.things.link.ingestion.infrastructure.protocol.tcp.DeviceAccessTcpServer;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.DeviceCommandDispatch;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.telemetry.application.DeviceCommandClaimPort;
import com.things.link.telemetry.application.DeviceCommandService;
import com.things.link.testing.AbstractKafkaIntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 设备面 TCP/TLS 上下行业务闭环：属性上报落到当前值与历史、命令回复驱动终态、错误以稳定错误码回帧。
 *
 * <p>与 HTTP 平面共用同一受理与回复用例，因此本类同时是「跨协议同源」的证据：同一份业务载荷经不同传输协议
 * 进入同一条标准管道，命令终态也只有一套。全部结论都要求真实 TLS 会话与真实 PostgreSQL／Kafka，用替身只能
 * 证明「调用过」，证不了「属性真的落地」「命令真的终态」。</p>
 *
 * <p>错误帧与关闭的对应关系也在本类钉住：业务类错误（{@code PAYLOAD_INVALID}、{@code COMMAND_NOT_FOUND}…）
 * 回一帧 {@code ERROR} 后连接必须仍然可用，帧级违规（线格式、方向）回**恰好一帧** {@code ERROR} 后关闭——
 * 「一帧」是回归断言，写两帧会让设备把同一个故障当成两次失败。</p>
 */
@SpringBootTest(properties = {
        "things-link.access.tcp.enabled=true",
        "things-link.access.tcp.port=0",
        "things-link.access.tcp.instance-id=node-a",
        // 心跳取冻结下界 10 秒：本类不做超时判定，但把读超时窗口压到 30 秒，夹具失败能快速暴露。
        "things-link.access.tcp.heartbeat-seconds=10"
})
@TestPropertySource(properties = {
        "spring.kafka.admin.auto-create=true",
        "spring.kafka.listener.auto-startup=true"
})
@Import({DeviceDataPlaneTopicsTestConfiguration.class, DeviceProtocolKafkaTopicsTestConfiguration.class})
class DeviceAccessTcpBusinessTests extends AbstractKafkaIntegrationTest {

    /** 与生产一致的 Jackson 3 映射器。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 测试证书与私钥所在目录；与 ingestion 单测共用同一份夹具，避免两份会漂移的材料。 */
    private static final Path TLS_FIXTURE_DIRECTORY = Path.of("..", "things-link-ingestion", "src", "test",
            "resources", "tls").toAbsolutePath().normalize();

    /** 测试设备密钥明文。 */
    private static final String SECRET = "bbbb2222cccc3333dddd4444eeee5555bbbb2222cccc3333dddd4444eeee5555";

    /** 与数据面夹具一致的物模型快照：仅一个可上报的 NUMBER 属性。 */
    private static final String TEMPERATURE_SNAPSHOT =
            "{\"properties\":{\"temperature\":{\"dataType\":\"NUMBER\",\"accessType\":\"REPORT\","
                    + "\"minimum\":-40,\"maximum\":125}},\"events\":{},\"commands\":{}}";

    /** 信任测试自签证书的客户端上下文；仅测试使用，生产设备按部署信任链校验。 */
    private static volatile SSLContext clientContext;

    /** 被测 TCP 接入服务器。 */
    @Autowired
    private DeviceAccessTcpServer server;

    /** 接入配置仓储；测试以显式项目范围开通 TCP 平面。 */
    @Autowired
    private DeviceAccessSessionRepository sessionRepository;

    /** 协议无关的领取端口：回复必须落在一条真实租约上。 */
    @Autowired
    private DeviceCommandClaimPort claimPort;

    /** 命令受信核心：测试用它走真实受理路径产生 attempt 与下行 Outbox。 */
    @Autowired
    private DeviceCommandService commandService;

    /** 真实下行消费者：命令信封从 Outbox 送到协议出口，推送分流就在这条链路上。 */
    @Autowired
    private com.things.link.ingestion.infrastructure.protocol.tcp.TcpCommandDownlinkKafkaConsumer downlinkConsumer;

    /** 应用角色连接。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 显式事务，保证 RLS 范围在连接首次取出前已设置。 */
    @Autowired
    private PlatformTransactionManager transactionManager;

    /** 本类独占夹具。 */
    private final List<Fixture> fixtures = new ArrayList<>();

    /**
     * 把部署侧 TLS 配置指向测试夹具。
     *
     * @param registry 动态属性注册器
     */
    @DynamicPropertySource
    static void tlsFixtureProperties(DynamicPropertyRegistry registry) {
        // 必须带 file: 前缀：裸绝对路径会被 Spring 当成 classpath 资源，证书会被判为不存在。
        registry.add("things-link.access.tls.certificate",
                () -> "file:" + TLS_FIXTURE_DIRECTORY.resolve("device-access-test-cert.pem"));
        registry.add("things-link.access.tls.private-key",
                () -> "file:" + TLS_FIXTURE_DIRECTORY.resolve("device-access-test-key.pem"));
    }

    /** 不残留夹具事实。 */
    @AfterEach
    void clearFixtures() throws SQLException {
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

    /** 属性上报经 TCP 进入同一条标准管道：当前值、历史与计量输入事实都必须真的落地。 */
    @Test
    void uplinkFrameLandsCurrentValueHistoryAndMeteringFact() throws Exception {
        Fixture fixture = seed();
        UUID messageId = Uuid7.generate();
        String report = "{\"messageId\":\"" + messageId + "\",\"occurredAt\":\"" + Instant.now()
                + "\",\"payload\":{\"temperature\":24.5}}";

        try (SSLSocket socket = authenticated(fixture)) {
            writeFrame(socket, DeviceAccessTcpFrameType.UPLINK, report.getBytes(StandardCharsets.UTF_8));
            // D-187：心跳之前必须收到本次上报的显式受理帧。
            writeFrame(socket, DeviceAccessTcpFrameType.HEARTBEAT, new byte[0]);
            JsonNode accepted = readFramePayload(socket, DeviceAccessTcpFrameType.ACCEPTED);
            assertThat(accepted).isEqualTo(JSON.readTree("{\"requestType\":\"UPLINK\",\"messageId\":\""
                    + messageId + "\",\"status\":\"ACCEPTED\"}"));
            assertThat(readFrame(socket).type()).isEqualTo(DeviceAccessTcpFrameType.HEARTBEAT_ACK);
            writeFrame(socket, DeviceAccessTcpFrameType.UPLINK, report.getBytes(StandardCharsets.UTF_8));
            assertThat(readFramePayload(socket, DeviceAccessTcpFrameType.ACCEPTED)).isEqualTo(accepted);
        }

        // 重新建链后用原ID和原载荷重试，仍可明确确认且不重复入库。
        try (SSLSocket socket = authenticated(fixture)) {
            writeFrame(socket, DeviceAccessTcpFrameType.UPLINK, report.getBytes(StandardCharsets.UTF_8));
            assertThat(readFramePayload(socket, DeviceAccessTcpFrameType.ACCEPTED).get("messageId").asString())
                    .isEqualTo(messageId.toString());
        }

        await().atMost(30, SECONDS).untilAsserted(() -> {
            assertThat(historyCount(fixture)).isEqualTo(1);
            assertThat(currentValue(fixture)).contains("24.5");
            assertThat(inboxCount(fixture, messageId)).isEqualTo(1);
        });
    }

    /** 载荷非法属业务错误：回 {@code PAYLOAD_INVALID} 后连接必须仍然可用，不得把设备踢下线。 */
    @Test
    void illegalPayloadKeepsSessionAliveAndReturnsStableErrorCode() throws Exception {
        Fixture fixture = seed();

        try (SSLSocket socket = authenticated(fixture)) {
            for (String body : List.of("not-json", "", "null")) {
                writeFrame(socket, DeviceAccessTcpFrameType.UPLINK, body.getBytes(StandardCharsets.UTF_8));
                assertThat(readFramePayload(socket, DeviceAccessTcpFrameType.ERROR).get("errorCode").asString())
                        .as("非法JSON、空载荷与null均为业务错误").isEqualTo("PAYLOAD_INVALID");
                writeFrame(socket, DeviceAccessTcpFrameType.HEARTBEAT, new byte[0]);
                assertThat(readFrame(socket).type())
                        .as("业务错误后只有心跳应答，不得多发错误或受理帧")
                        .isEqualTo(DeviceAccessTcpFrameType.HEARTBEAT_ACK);
            }
            for (String body : List.of("not-json", "", "null")) {
                writeFrame(socket, DeviceAccessTcpFrameType.REPLY, body.getBytes(StandardCharsets.UTF_8));
                assertThat(readFramePayload(socket, DeviceAccessTcpFrameType.ERROR).get("errorCode").asString())
                        .isEqualTo("PAYLOAD_INVALID");
                writeFrame(socket, DeviceAccessTcpFrameType.HEARTBEAT, new byte[0]);
                assertThat(readFrame(socket).type()).isEqualTo(DeviceAccessTcpFrameType.HEARTBEAT_ACK);
            }
        }

        assertThat(historyCount(fixture)).as("非法载荷不得产生时序点").isZero();
    }

    /** 命令回复经 TCP 驱动终态；未知命令回 {@code COMMAND_NOT_FOUND} 且连接继续可用。 */
    @Test
    void replyFrameDrivesCommandToTerminalState() throws Exception {
        Fixture fixture = seed();
        UUID commandId = seedAcceptedCommand(fixture);
        // 回复必须落在一条真实租约上：领取走生产的协议无关端口，并显式给出项目范围（命令事实受 RLS 约束）。
        assertThat(inProject(fixture, () -> claimPort.claim(new DeviceCommandClaimPort.ClaimRequest(
                fixture.tenantId(), fixture.projectId(), fixture.deviceId(), null, 1))))
                .as("租约必须建立，否则回复用例退化成了「找不到命令」").hasSize(1);

        try (SSLSocket socket = authenticated(fixture)) {
            UUID messageId = Uuid7.generate();
            String body = replyBody(commandId, messageId, "SUCCESS", "{\"duration\":7}");
            writeFrame(socket, DeviceAccessTcpFrameType.REPLY, body.getBytes(StandardCharsets.UTF_8));
            JsonNode accepted = readFramePayload(socket, DeviceAccessTcpFrameType.ACCEPTED);
            assertThat(accepted).isEqualTo(JSON.readTree("{\"requestType\":\"REPLY\",\"messageId\":\""
                    + messageId + "\",\"status\":\"ACCEPTED\",\"commandId\":\"" + commandId + "\"}"));
            assertThat(commandState(commandId)).as("应答到达时应用事务已经提交").isEqualTo("SUCCEEDED/1");
            writeFrame(socket, DeviceAccessTcpFrameType.REPLY, body.getBytes(StandardCharsets.UTF_8));
            assertThat(readFramePayload(socket, DeviceAccessTcpFrameType.ACCEPTED)).isEqualTo(accepted);
            writeFrame(socket, DeviceAccessTcpFrameType.REPLY,
                    body.replace("duration\":7", "duration\":8").getBytes(StandardCharsets.UTF_8));
            assertThat(readFramePayload(socket, DeviceAccessTcpFrameType.ERROR).get("errorCode").asString())
                    .isEqualTo("IDEMPOTENCY_CONFLICT");
            writeFrame(socket, DeviceAccessTcpFrameType.HEARTBEAT, new byte[0]);
            assertThat(readFrame(socket).type()).isEqualTo(DeviceAccessTcpFrameType.HEARTBEAT_ACK);

            writeFrame(socket, DeviceAccessTcpFrameType.REPLY, replyBody(Uuid7.generate(), Uuid7.generate(),
                    "SUCCESS", "{}").getBytes(StandardCharsets.UTF_8));
            assertThat(readFramePayload(socket, DeviceAccessTcpFrameType.ERROR).get("errorCode").asString())
                    .isEqualTo("COMMAND_NOT_FOUND");
        }

        assertThat(commandState(commandId)).as("传输写入成功必须真的变成命令终态").isEqualTo("SUCCEEDED/1");
        assertThat(text("SELECT status FROM ts_device_command_claim WHERE project_id = ? AND command_id = ?"
                + " ORDER BY attempt_no DESC LIMIT 1", fixture.projectId(), commandId)).isEqualTo("REPLIED");
    }

    /** 帧级违规必须回**恰好一帧** {@code ERROR} 再关闭，且会话事实记稳定原因。 */
    @Test
    void frameViolationsWriteExactlyOneErrorFrameAndClose() throws Exception {
        Fixture broken = seed();
        try (SSLSocket socket = authenticated(broken)) {
            // 坏魔数：拆帧阶段就失败，属于帧级违规。
            socket.getOutputStream().write(new byte[] {0x00, 0x00, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00});
            socket.getOutputStream().flush();
            assertThat(readFramePayload(socket, DeviceAccessTcpFrameType.ERROR).get("errorCode").asString())
                    .isEqualTo("FRAME_INVALID");
            assertThat(new DeviceAccessTcpFrameReader(socket.getInputStream()).readFrame())
                    .as("帧级违规只能回一帧 ERROR 再关闭；第二次读到 ERROR 说明错误帧被重复发送")
                    .isNull();
        }
        assertThat(awaitDisconnectReason(broken, "frame_invalid"))
                .as("会话事实必须记录帧级违规的稳定原因").contains("frame_invalid");

        for (var type : List.of(DeviceAccessTcpFrameType.DOWNLINK, DeviceAccessTcpFrameType.ACCEPTED)) {
            Fixture reversed = seed();
            try (SSLSocket socket = authenticated(reversed)) {
                writeFrame(socket, type, "{}".getBytes(StandardCharsets.UTF_8));
                assertThat(readFramePayload(socket, DeviceAccessTcpFrameType.ERROR).get("errorCode").asString())
                        .isEqualTo("FRAME_INVALID");
                assertThat(new DeviceAccessTcpFrameReader(socket.getInputStream()).readFrame())
                        .as("平台专用帧反向发送只回一帧并关闭").isNull();
            }
        }
    }

    /** 下行推送闭环：命令经真实下行消费者推入活跃会话，设备回复后命令终态。 */
    @Test
    void downlinkPushDeliversCommandAndReplyClosesTheLoop() throws Exception {
        Fixture fixture = seed();

        try (SSLSocket socket = authenticated(fixture)) {
            UUID commandId = submitCommand(fixture, "ax3c-push");
            dispatchFromOutbox(fixture, commandId);

            JsonNode downlink = readFramePayload(socket, DeviceAccessTcpFrameType.DOWNLINK);
            assertThat(downlink.get("commandId").asString()).isEqualTo(commandId.toString());
            assertThat(downlink.get("commandKey").asString()).isEqualTo("reboot");
            assertThat(downlink.get("attempt").asInt()).as("首次投递序号为 1").isEqualTo(1);
            assertThat(downlink.get("input").isObject()).as("input 必须是对象而不是字符串").isTrue();
            assertThat(downlink.get("expiresAt").asString()).as("推送必须携带响应截止时刻").isNotBlank();
            assertThat(commandState(commandId)).as("写帧成功即记为已派发").isEqualTo("DISPATCHED/1");

            writeFrame(socket, DeviceAccessTcpFrameType.REPLY, replyBody(commandId, Uuid7.generate(), "SUCCESS",
                    "{}").getBytes(StandardCharsets.UTF_8));
            assertThat(readFramePayload(socket, DeviceAccessTcpFrameType.ACCEPTED).get("commandId").asString())
                    .isEqualTo(commandId.toString());
            await().atMost(20, SECONDS).untilAsserted(() -> assertThat(commandState(commandId))
                    .as("传输写入成功不等于设备执行成功：终态只能由业务回复产生").isEqualTo("SUCCEEDED/1"));
        }

        // 调试时间线的"投递／回复"两段在推送形态下都有真实事实来源：下行一行（delivered_at）＋设备回复一行（replied_at）。
        assertThat(logValue("SELECT count(*) FROM ts_device_message_log WHERE project_id = ? AND message_type = 'COMMAND'"
                + " AND delivered_at IS NOT NULL", fixture.projectId())).as("派发成功必须留下投递阶段")
                .isEqualTo(1);
        assertThat(logValue("SELECT count(*) FROM ts_device_message_log WHERE project_id = ?"
                + " AND message_type = 'COMMAND_REPLY' AND replied_at IS NOT NULL", fixture.projectId()))
                .as("设备业务回复必须留下回复阶段").isEqualTo(1);
    }

    /** 以 owner 连接计数；调试日志受 RLS 约束，直连读取必须绕开应用角色。 */
    private int logValue(String sql, Object argument) throws SQLException {
        try (Connection owner = ownerConnection();
             PreparedStatement statement = owner.prepareStatement(sql)) {
            statement.setObject(1, argument);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getInt(1);
            }
        }
    }

    /** 设备离线（本实例无会话）时不得记为已派发，也不得进入终态：命令保持待发并记可诊断的失败分类。 */
    @Test
    void downlinkToOfflineDeviceKeepsCommandPending() throws Exception {
        Fixture fixture = seed();
        UUID commandId = submitCommand(fixture, "ax3c-offline");

        dispatchFromOutbox(fixture, commandId);

        assertThat(commandState(commandId)).as("没有会话就不能算已投递，命令必须停在待发的 ACCEPTED")
                .isEqualTo("ACCEPTED/1");
        assertThat(attemptState(commandId)).isEqualTo("PENDING/");
        assertThat(nextAttemptAt(commandId)).as("跳过后保留原到期恢复调度").isNotNull();
    }

    /** 接管后推送必须落在新代次会话上：旧连接不应该再收到下行。 */
    @Test
    void pushFollowsTheCurrentGenerationAfterTakeover() throws Exception {
        Fixture fixture = seed();

        try (SSLSocket first = authenticated(fixture)) {
            try (SSLSocket second = authenticated(fixture)) {
                assertThat(sessionState(fixture)).as("接管后活跃会话代次为 2").contains("TCP/2/");
                UUID commandId = submitCommand(fixture, "ax3c-takeover");
                dispatchFromOutbox(fixture, commandId);

                assertThat(readFramePayload(second, DeviceAccessTcpFrameType.DOWNLINK).get("commandId").asString())
                        .as("推送必须落到接管后的新会话").isEqualTo(commandId.toString());

                // 旧连接下一次发帧确认会话已不属于它：接管错误而不是下行命令。
                writeFrame(first, DeviceAccessTcpFrameType.HEARTBEAT, new byte[0]);
                assertThat(readFramePayload(first, DeviceAccessTcpFrameType.ERROR).get("errorCode").asString())
                        .as("旧代次连接只应被要求重新认证，不应收到下行").isEqualTo("AUTH_REQUIRED");
            }
        }
    }

    /**
     * 以真实受理路径提交一条命令：路由、Schema 校验、首次 attempt 与下行 Outbox 都由生产代码产生。
     *
     * @param fixture 独占夹具
     * @param idempotencyPrefix 幂等键前缀
     * @return 命令 ID
     */
    private UUID submitCommand(Fixture fixture, String idempotencyPrefix) {
        return inProject(fixture, () -> commandService.submitTrusted(fixture.projectId(), fixture.deviceId(),
                idempotencyPrefix + "-" + Uuid7.generate(), "reboot", JSON.readTree("{}"),
                fixture.accountId(), null).id());
    }

    /**
     * 用 Outbox 里冻结的原始信封驱动真实下行消费者。
     *
     * <p>不手写信封：命令状态机会逐字段比对原派发记录，只有原信封才能证明「推进的是这次投递」。</p>
     *
     * @param fixture 独占夹具
     * @param commandId 命令 ID
     * @throws SQLException 读取 Outbox 失败
     */
    private void dispatchFromOutbox(Fixture fixture, UUID commandId) throws SQLException {
        String payload = text("SELECT payload FROM sys_outbox_event WHERE project_id = ? AND aggregate_id = ?"
                + " ORDER BY created_at LIMIT 1", fixture.projectId(), commandId);
        assertThat(payload).as("受理必须产生下行 Outbox 事实").isNotNull();
        DeviceCommandDispatch dispatch = JSON.readValue(payload, DeviceCommandDispatch.class);
        downlinkConsumer.consume(new ConsumerRecord<>(DeviceCommandDownlinkKafkaConsumer.DOWNLINK_TOPIC, 0, 0L,
                fixture.deviceId().toString(), dispatch));
    }

    /**
     * 读取首次投递尝试的状态与失败分类。
     *
     * @param commandId 命令 ID
     * @return {@code 状态/失败码}
     * @throws SQLException 查询失败
     */
    private String attemptState(UUID commandId) throws SQLException {
        return text("SELECT status || '/' || coalesce(error_code, '') FROM ts_device_command_attempt"
                + " WHERE command_id = ? ORDER BY attempt_no LIMIT 1", commandId);
    }

    /**
     * 读取下一次派发时刻，用于证明失败后仍有退避重投而不是静默停留。
     *
     * @param commandId 命令 ID
     * @return 下次派发时刻文本；为空时 null
     * @throws SQLException 查询失败
     */
    private String nextAttemptAt(UUID commandId) throws SQLException {
        return text("SELECT next_attempt_at::text FROM ts_device_command WHERE id = ?", commandId);
    }

    /**
     * 建立连接并完成认证。
     *
     * @param fixture 独占夹具
     * @return 已完成认证的 TLS 连接
     * @throws Exception 连接或认证失败
     */
    private SSLSocket authenticated(Fixture fixture) throws Exception {
        SSLSocket socket = connect();
        String payload = "{\"projectKey\":\"" + fixture.projectKey() + "\",\"deviceKey\":\"ax3c_device\","
                + "\"secret\":\"" + SECRET + "\"}";
        writeFrame(socket, DeviceAccessTcpFrameType.AUTH_REQUEST, payload.getBytes(StandardCharsets.UTF_8));
        assertThat(readFramePayload(socket, DeviceAccessTcpFrameType.AUTH_RESPONSE).get("status").asString())
                .as("认证必须成功，否则后续业务断言无从谈起").isEqualTo("OK");
        return socket;
    }

    /**
     * 建立 TLS 连接。
     *
     * @return 已握手完成的客户端套接字
     * @throws Exception 握手失败
     */
    private SSLSocket connect() throws Exception {
        SSLSocket socket = (SSLSocket) clientContext().getSocketFactory().createSocket();
        socket.connect(new InetSocketAddress("127.0.0.1", server.boundPort()), 10_000);
        socket.startHandshake();
        socket.setSoTimeout(20_000);
        return socket;
    }

    /** 写一帧。 */
    private static void writeFrame(SSLSocket socket, DeviceAccessTcpFrameType type, byte[] payload)
            throws IOException {
        OutputStream output = socket.getOutputStream();
        output.write(DeviceAccessTcpFrameCodec.encode(type, payload));
        output.flush();
    }

    /** 读取一帧。 */
    private static DeviceAccessTcpFrameCodec.DecodedFrame readFrame(SSLSocket socket) throws IOException {
        DeviceAccessTcpFrameCodec.DecodedFrame frame = new DeviceAccessTcpFrameReader(socket.getInputStream())
                .readFrame();
        assertThat(frame).as("连接在读出帧之前被关闭").isNotNull();
        return frame;
    }

    /** 读取下一帧并断言类型，返回载荷 JSON。 */
    private static JsonNode readFramePayload(SSLSocket socket, DeviceAccessTcpFrameType expected) throws IOException {
        DeviceAccessTcpFrameCodec.DecodedFrame frame = readFrame(socket);
        assertThat(frame.type()).isEqualTo(expected);
        return JSON.readTree(frame.payload());
    }

    /** 惰性构建信任自签证书的客户端上下文。 */
    private static SSLContext clientContext() throws Exception {
        if (clientContext == null) {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {trustAll()}, new SecureRandom());
            clientContext = context;
        }
        return clientContext;
    }

    /** 仅测试使用的信任管理器：自签证书没有可校验的签发链。 */
    private static X509TrustManager trustAll() {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }

    /** 构造命令回复报文。 */
    private static String replyBody(UUID commandId, UUID messageId, String status, String output) {
        return "{\"commandId\":\"" + commandId + "\",\"messageId\":\"" + messageId + "\",\"occurredAt\":\""
                + Instant.now() + "\",\"status\":\"" + status + "\",\"output\":" + output + "}";
    }

    /**
     * 等待会话以指定原因断开。
     *
     * @param fixture 独占夹具
     * @param reason 期望的断开原因
     * @return 最终会话状态；超时返回最后一次观察值
     */
    private String awaitDisconnectReason(Fixture fixture, String reason) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        String state = "NONE";
        while (System.nanoTime() < deadline) {
            try {
                state = sessionState(fixture);
                if (state.contains(reason)) {
                    return state;
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("读取会话事实失败", exception);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return state;
    }

    /** 读取最近一次会话的协议／代次／状态／原因。 */
    private String sessionState(Fixture fixture) throws SQLException {
        String state = text("SELECT protocol || '/' || generation || '/' || owner_instance || '/'"
                + " || CASE WHEN disconnected_at IS NULL THEN 'OPEN' ELSE 'CLOSED' END || '/'"
                + " || coalesce(disconnect_reason, '') FROM dev_connection WHERE project_id = ? AND device_id = ?"
                + " ORDER BY generation DESC LIMIT 1", fixture.projectId(), fixture.deviceId());
        return state == null ? "NONE" : state;
    }

    /** 读取影子里的当前值。 */
    private String currentValue(Fixture fixture) throws SQLException {
        String reported = text("SELECT reported::text FROM dev_shadow WHERE project_id = ? AND device_id = ?",
                fixture.projectId(), fixture.deviceId());
        return reported == null ? "" : reported;
    }

    /** 统计该设备的时序点数（历史）。 */
    private int historyCount(Fixture fixture) throws SQLException {
        // ts_property_point 是 security_barrier 视图（按 app_current_project 过滤），owner 旁观必须读内部表。
        return count("SELECT count(*) FROM public.ts_property_point_internal WHERE project_id = ? AND device_id = ?",
                fixture.projectId(), fixture.deviceId());
    }

    /** 统计业务侧幂等事实。 */
    private int inboxCount(Fixture fixture, UUID messageId) throws SQLException {
        return count("SELECT count(*) FROM sys_inbox_message WHERE project_id = ? AND message_id = ?",
                fixture.projectId(), messageId);
    }

    /** 查询单列文本；未命中返回 null。 */
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

    /** 参数化计数。 */
    private int count(String sql, Object... arguments) throws SQLException {
        try (Connection owner = ownerConnection();
             PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) {
                statement.setObject(index + 1, arguments[index]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getInt(1);
            }
        }
    }

    /** 读取命令状态与投递序号。 */
    private String commandState(UUID commandId) throws SQLException {
        return text("SELECT status || '/' || attempt_count FROM ts_device_command WHERE id = ?", commandId);
    }

    /**
     * 播种待领取命令。
     *
     * @param fixture 独占夹具
     * @return 命令 ID
     * @throws SQLException 写入失败
     */
    private UUID seedAcceptedCommand(Fixture fixture) throws SQLException {
        UUID commandId = Uuid7.generate();
        try (Connection owner = ownerConnection()) {
            execute(owner, """
                    INSERT INTO ts_device_command
                        (id, tenant_id, project_id, target_device_id, connection_device_id, command_definition_id,
                         command_key, input_schema, output_schema, request_payload, status, idempotency_key,
                         requested_by, timeout_seconds, attempt_count, max_attempts, next_attempt_at, deadline_at,
                         trace_id, accepted_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'reboot', '{}', '{}', '{}'::jsonb, 'ACCEPTED', ?, ?, 30, 0, 3,
                            now(), NULL, 'ax3c-command', now())
                    """, commandId, fixture.tenantId(), fixture.projectId(), fixture.deviceId(), fixture.deviceId(),
                    fixture.definitionId(), commandId.toString(), fixture.accountId());
        }
        return commandId;
    }

    /**
     * 播种独占租户、项目、直连设备、命令定义、凭据、TCP 平面与数据面物模型绑定。
     *
     * @return 本测试独占夹具
     * @throws SQLException 播种失败
     */
    private Fixture seed() throws SQLException {
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
                    VALUES (?, ?, ?, ?, 'reboot', '重启', '{}', '{}', 30)
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
        inProject(fixture, () -> sessionRepository.bind(fixture.tenantId(), fixture.projectId(), fixture.deviceId(),
                TransportProtocol.TCP, 30));
        return fixture;
    }

    /**
     * 在夹具项目范围内执行一次应用角色调用。
     *
     * @param fixture 独占夹具
     * @param work 范围内动作
     * @param <T> 返回值类型
     * @return 动作结果
     */
    private <T> T inProject(Fixture fixture, Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class,
                    fixture.tenantId().toString());
            jdbcTemplate.queryForObject("SELECT set_config('app.project_id', ?, true)", String.class,
                    fixture.projectId().toString());
            return work.get();
        });
    }

    /**
     * 计算与凭据校验一致的 SHA-256 十六进制摘要。
     *
     * @param input 明文
     * @return 摘要
     */
    private static String sha256(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("JVM 没有 SHA-256", exception);
        }
    }

    /**
     * owner 连接用于夹具播种与独立旁观。
     *
     * @return 调用方负责关闭的连接
     * @throws SQLException 连接失败
     */
    private static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /**
     * 参数化执行夹具语句。
     *
     * @param connection owner 连接
     * @param sql 语句
     * @param arguments 参数
     * @throws SQLException 执行失败
     */
    private static void execute(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) {
                statement.setObject(index + 1, arguments[index]);
            }
            statement.executeUpdate();
        }
    }

    /**
     * 独占夹具身份。
     *
     * @param tenantId 租户
     * @param projectId 项目
     * @param deviceId 设备
     * @param typeId 设备类型
     * @param definitionId 命令定义
     * @param accountId 命令请求人
     * @param projectKey 项目短标识
     */
    private record Fixture(UUID tenantId, UUID projectId, UUID deviceId, UUID typeId, UUID definitionId,
                           UUID accountId, String projectKey) {
    }
}
