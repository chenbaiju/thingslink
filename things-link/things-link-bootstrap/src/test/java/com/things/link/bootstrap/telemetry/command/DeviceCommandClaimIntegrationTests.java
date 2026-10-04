package com.things.link.bootstrap.telemetry.command;

import com.things.link.shared.id.Uuid7;
import com.things.link.telemetry.application.DeviceCommandAccessReplyPort;
import com.things.link.telemetry.application.DeviceCommandClaimPort;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 PostgreSQL 下的命令领取租约：一次投递推进命令事实、租约内互斥、到期回到可领取集合且 attempt+1、
 * 跨设备不可见、已推送命令不被领取端点抢走，以及超时扫描只接管已耗尽尝试的领取形态。
 */
class DeviceCommandClaimIntegrationTests extends AbstractIntegrationTest {

    /** 真实领取用例。 */
    @Autowired
    private DeviceCommandClaimPort claimPort;

    /** 真实统一回复入口。 */
    @Autowired
    private DeviceCommandAccessReplyPort replyPort;

    /** 最终超时扫描必须真正推进状态，不能只验证SQL选中了它。 */
    @Autowired private com.things.link.telemetry.application.DeviceCommandService commandService;
    /** 真实领取身份端口。 */
    @Autowired private com.things.link.telemetry.domain.DeviceCommandRepository commandRepository;

    /** 应用角色连接，用于核对 RLS 可见性与命令事实。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 本类独占夹具，测试结束后按依赖顺序回收。 */
    private final List<Fixture> fixtures = new ArrayList<>();

    /** 不残留夹具事实；清理失败不掩盖断言结果，因此只做尽力回收。 */
    @AfterEach
    void clearFixtures() throws SQLException {
        try (Connection owner = ownerConnection()) {
            for (Fixture fixture : fixtures) {
                execute(owner, "DELETE FROM sys_outbox_event WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM ts_device_command_claim WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM ts_device_command WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_command_definition WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_device WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM dev_type WHERE project_id = ?", fixture.projectId());
                execute(owner, "DELETE FROM sys_project WHERE id = ?", fixture.projectId());
                execute(owner, "DELETE FROM sys_account WHERE id = ?", fixture.accountId());
                execute(owner, "DELETE FROM sys_tenant WHERE id = ?", fixture.tenantId());
            }
        }
        fixtures.clear();
    }

    /** 防御历史或异常的零attempt属性事实，不将未定义的属性动作交给HTTP/CoAP设备猜测。 */
    @Test
    void neverClaimsPropertySetAsNativeCommand() throws SQLException {
        Fixture fixture = seed();
        UUID property = seedAcceptedCommand(fixture, "{}");
        UUID command = seedAcceptedCommand(fixture, "{}");
        try (Connection owner = ownerConnection()) {
            execute(owner, "UPDATE ts_device_command SET operation_type='PROPERTY_SET',command_key=NULL,command_definition_id=NULL WHERE id=?", property);
        }
        assertThat(claim(fixture, null, 10)).extracting(DeviceCommandClaimPort.Claimed::commandId).containsExactly(command);
        assertThat(claim(fixture, null, 10)).isEmpty();
    }

    /** 首次领取把命令推进为 DISPATCHED 并写租约；租约内再次领取为空。 */
    @Test
    void claimsOnceThenHidesCommandInsideLease() throws SQLException {
        Fixture fixture = seed();
        UUID commandId = seedAcceptedCommand(fixture, "{\"speed\":3}");

        List<DeviceCommandClaimPort.Claimed> claimed = claim(fixture, null, 1);

        assertThat(claimed).hasSize(1);
        assertThat(claimed.getFirst().commandId()).isEqualTo(commandId);
        assertThat(claimed.getFirst().commandKey()).isEqualTo("reboot");
        assertThat(claimed.getFirst().input()).containsEntry("speed", 3);
        assertThat(claimed.getFirst().attempt()).isEqualTo(1);
        assertThat(claimed.getFirst().leaseExpiresAt())
                .isAfter(Instant.now().plusSeconds(20))
                .isBefore(Instant.now().plusSeconds(40));
        assertThat(commandState(commandId)).isEqualTo("DISPATCHED/1");
        assertThat(claimRowCount(fixture, commandId)).isEqualTo(1);

        assertThat(claim(fixture, null, 1)).as("租约内对其他领取者不可见").isEmpty();
    }

    /** 租约到期后命令回到可领取集合，重投序号 +1，且租约再次生效。 */
    @Test
    void expiredLeaseReturnsCommandWithNextAttempt() throws SQLException {
        Fixture fixture = seed();
        UUID commandId = seedAcceptedCommand(fixture, "{\"speed\":3}");
        assertThat(claim(fixture, null, 1)).hasSize(1);

        expireLease(commandId);

        List<DeviceCommandClaimPort.Claimed> reClaimed = claim(fixture, null, 1);
        assertThat(reClaimed).hasSize(1);
        assertThat(reClaimed.getFirst().commandId()).isEqualTo(commandId);
        assertThat(reClaimed.getFirst().attempt()).as("重投只增加投递序号").isEqualTo(2);
        assertThat(commandState(commandId)).isEqualTo("DISPATCHED/2");
        assertThat(claimRowCount(fixture, commandId)).isEqualTo(2);
        assertThat(claim(fixture, null, 1)).isEmpty();
    }

    /** 设备只能领取自己的命令：同项目的另一台设备看不到。 */
    @Test
    void anotherDeviceCannotClaimForeignCommand() throws SQLException {
        Fixture fixture = seed();
        seedAcceptedCommand(fixture, "{}");
        UUID otherDevice = seedDevice(fixture, "claim_other");

        assertThat(claim(fixture, otherDevice, 1)).isEmpty();
        assertThat(claim(fixture, null, 1)).as("目标设备仍可正常领取").hasSize(1);
    }

    /** 已经推送过的命令不能被领取端点抢走：当前投递序号是推送尝试而非领取行。 */
    @Test
    void pushedCommandIsNotClaimable() throws SQLException {
        Fixture fixture = seed();
        UUID commandId = seedAcceptedCommand(fixture, "{}");
        try (Connection owner = ownerConnection()) {
            execute(owner, """
                    INSERT INTO ts_device_command_attempt
                        (id, tenant_id, project_id, command_id, attempt_no, outbox_event_id, connection_device_id,
                         topic, status, deadline_at, created_at)
                    VALUES (?, ?, ?, ?, 1, ?, ?, 'tc/v1/p/d/down/command/reboot', 'PUBLISHED', now() + interval '30 seconds', now())
                    """, Uuid7.generate(), fixture.tenantId(), fixture.projectId(), commandId, Uuid7.generate(),
                    fixture.deviceId());
            execute(owner, "UPDATE ts_device_command SET status = 'DISPATCHED', attempt_count = 1 WHERE id = ?",
                    commandId);
        }

        assertThat(claim(fixture, null, 1)).as("推送形态的命令不得被领取端点领取").isEmpty();
    }

    /** 尝试次数耗尽后不再可领取，且既有超时扫描函数能接管该领取形态（进入既有 TIMED_OUT 语义）。 */
    @Test
    void exhaustedClaimsEnterTimeoutScanAndStopBeingClaimable() throws SQLException {
        Fixture fixture = seed();
        UUID commandId = seedAcceptedCommand(fixture, "{}");
        for (int attempt = 1; attempt <= 3; attempt++) {
            expireLease(commandId);
            assertThat(claim(fixture, null, 1)).as("第 " + attempt + " 次领取").hasSize(1);
        }
        // 第三次领取后租约同样是新的；必须等它到期，超时扫描才应该接管。
        expireLease(commandId);

        assertThat(claim(fixture, null, 1)).as("尝试耗尽后不再可领取").isEmpty();
        var due = commandRepository.claimDue(100).stream().filter(item -> item.commandId().equals(commandId)).findFirst().orElseThrow();
        assertThat(due.claimKind()).isEqualTo(com.things.link.telemetry.domain.DeviceCommandRepository.ClaimKind.PULL_FINAL_TIMEOUT);
        commandService.processDue(due);
        assertThat(commandState(commandId)).isEqualTo("TIMED_OUT/3");
        commandService.processDue(due);
        assertThat(commandState(commandId)).isEqualTo("TIMED_OUT/3");

        // 未耗尽尝试时扫描函数不得接管，否则会把 HTTP/CoAP 设备的命令交给推送形态处理。
        Fixture partial = seed();
        UUID partialCommand = seedAcceptedCommand(partial, "{}");
        expireLease(partialCommand);
        assertThat(claim(partial, null, 1)).hasSize(1);
        expireLease(partialCommand);
        assertThat(scanClaimable(partialCommand))
                .as("未耗尽尝试的领取形态保持可再领取，不交给推送扫描").isFalse();
    }

    /** 单次领取条数上限生效，且默认只领一条。 */
    @Test
    void returnsAtMostRequestedLimit() throws SQLException {
        Fixture fixture = seed();
        seedAcceptedCommand(fixture, "{}");
        seedAcceptedCommand(fixture, "{}");
        seedAcceptedCommand(fixture, "{}");

        assertThat(claim(fixture, null, 1)).hasSize(1);
        assertThat(claim(fixture, null, 2)).hasSize(2);
        assertThat(claim(fixture, null, 20)).as("超过上限收敛为 10 且受候选数量约束").isEmpty();
    }

    /** 无项目范围时应用角色看不到领取事实，RLS 是第二道防线。 */
    @Test
    void claimFactsAreInvisibleWithoutProjectScope() throws SQLException {
        Fixture fixture = seed();
        UUID commandId = seedAcceptedCommand(fixture, "{}");
        assertThat(claim(fixture, null, 1)).hasSize(1);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ts_device_command_claim WHERE command_id = ?", Integer.class, commandId))
                .as("未设置项目范围时 RLS 必须挡住应用角色")
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ts_device_command WHERE id = ?", Integer.class, commandId))
                .isZero();
    }

    /** 领取形态收到 SUCCESS 回复：命令进入终态、领取事实标记已回复，且不再可领取。 */
    @Test
    void claimedCommandRepliesToTerminalStateThroughUnifiedEntry() throws SQLException {
        Fixture fixture = seed();
        UUID commandId = seedAcceptedCommand(fixture, "{}");
        assertThat(claim(fixture, null, 1)).hasSize(1);

        UUID replyMessage = Uuid7.generate();
        assertThat(reply(fixture, commandId, replyMessage, DeviceCommandAccessReplyPort.Status.SUCCESS,
                java.util.Map.of("duration", 12), null)).isEqualTo(DeviceCommandAccessReplyPort.Outcome.APPLIED);

        assertThat(commandState(commandId)).isEqualTo("SUCCEEDED/1");
        assertThat(claimStatus(fixture, commandId)).isEqualTo("REPLIED");
        assertThat(claim(fixture, null, 1)).as("终态命令不再可领取").isEmpty();
    }

    /** ACK 只是中间态：命令不终态，租约到期后仍可再次领取（attempt+1）。 */
    @Test
    void acknowledgementKeepsCommandNonTerminal() throws SQLException {
        Fixture fixture = seed();
        UUID commandId = seedAcceptedCommand(fixture, "{}");
        assertThat(claim(fixture, null, 1)).hasSize(1);

        assertThat(reply(fixture, commandId, Uuid7.generate(), DeviceCommandAccessReplyPort.Status.ACK,
                java.util.Map.of(), null)).isEqualTo(DeviceCommandAccessReplyPort.Outcome.APPLIED);

        assertThat(commandState(commandId)).isEqualTo("ACKNOWLEDGED/1");
        expireLease(commandId);
        List<DeviceCommandClaimPort.Claimed> reClaimed = claim(fixture, null, 1);
        assertThat(reClaimed).hasSize(1);
        assertThat(reClaimed.getFirst().attempt()).isEqualTo(2);
    }

    /** 同键同结果是重放，同键异结果是冲突；两者都不改变首次结果。 */
    @Test
    void distinguishesReplayFromConflictOnSameReplyMessage() throws SQLException {
        Fixture fixture = seed();
        UUID commandId = seedAcceptedCommand(fixture, "{}");
        assertThat(claim(fixture, null, 1)).hasSize(1);
        UUID replyMessage = Uuid7.generate();
        assertThat(reply(fixture, commandId, replyMessage, DeviceCommandAccessReplyPort.Status.SUCCESS,
                java.util.Map.of("duration", 12), null)).isEqualTo(DeviceCommandAccessReplyPort.Outcome.APPLIED);

        assertThat(reply(fixture, commandId, replyMessage, DeviceCommandAccessReplyPort.Status.SUCCESS,
                java.util.Map.of("duration", 12), null))
                .as("同键同摘要的迟到回复幂等接受").isEqualTo(DeviceCommandAccessReplyPort.Outcome.DUPLICATE);
        assertThat(reply(fixture, commandId, replyMessage, DeviceCommandAccessReplyPort.Status.FAILED,
                java.util.Map.of("duration", 99), "DEVICE_REJECTED"))
                .as("同键换结果必须拒绝").isEqualTo(DeviceCommandAccessReplyPort.Outcome.CONFLICT);

        assertThat(commandState(commandId)).as("冲突不得改写首次结果").isEqualTo("SUCCEEDED/1");
        assertThat(commandFailureCode(commandId)).isNull();
    }

    /** 引用其他设备的命令或不存在命令：返回找不到且不写入任何命令事实。 */
    @Test
    void foreignOrUnknownCommandIsNotFoundWithoutFacts() throws SQLException {
        Fixture fixture = seed();
        UUID commandId = seedAcceptedCommand(fixture, "{}");
        UUID otherDevice = seedDevice(fixture, "reply_other");

        assertThat(replyPort.apply(new DeviceCommandAccessReplyPort.Reply(fixture.tenantId(), fixture.projectId(),
                otherDevice, commandId, Uuid7.generate(), DeviceCommandAccessReplyPort.Status.SUCCESS,
                java.util.Map.of(), null, null, Instant.now(), Instant.now(), "ax1d-reply")))
                .as("另一台设备不能回复本设备的命令").isEqualTo(DeviceCommandAccessReplyPort.Outcome.NOT_FOUND);
        assertThat(replyPort.apply(new DeviceCommandAccessReplyPort.Reply(fixture.tenantId(), fixture.projectId(),
                fixture.deviceId(), Uuid7.generate(), Uuid7.generate(),
                DeviceCommandAccessReplyPort.Status.SUCCESS, java.util.Map.of(), null, null, Instant.now(),
                Instant.now(), "ax1d-reply")))
                .as("不存在的命令按找不到处理").isEqualTo(DeviceCommandAccessReplyPort.Outcome.NOT_FOUND);

        assertThat(commandState(commandId)).as("越权与未知回复不得推进命令状态").isEqualTo("ACCEPTED/0");
    }

    /**
     * 执行一次业务回复。
     *
     * @param fixture 独占夹具
     * @param commandId 命令 ID
     * @param replyMessageId 回复消息 ID
     * @param status 回复状态
     * @param output 输出对象
     * @param errorCode 失败码
     * @return 处理结果
     */
    private DeviceCommandAccessReplyPort.Outcome reply(Fixture fixture, UUID commandId, UUID replyMessageId,
                                                      DeviceCommandAccessReplyPort.Status status,
                                                      java.util.Map<String, Object> output, String errorCode) {
        return replyPort.apply(new DeviceCommandAccessReplyPort.Reply(fixture.tenantId(), fixture.projectId(),
                fixture.deviceId(), commandId, replyMessageId, status, output, errorCode, null,
                Instant.now(), Instant.now(), "ax1d-reply"));
    }

    /**
     * 读取命令的领取事实状态。
     *
     * @param fixture 独占夹具
     * @param commandId 命令 ID
     * @return 领取状态，无领取事实时返回 {@code NONE}
     * @throws SQLException 查询失败
     */
    private String claimStatus(Fixture fixture, UUID commandId) throws SQLException {
        try (Connection owner = ownerConnection();
             PreparedStatement statement = owner.prepareStatement(
                     "SELECT status FROM ts_device_command_claim WHERE project_id = ? AND command_id = ?"
                             + " ORDER BY attempt_no DESC LIMIT 1")) {
            statement.setObject(1, fixture.projectId());
            statement.setObject(2, commandId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : "NONE";
            }
        }
    }

    /**
     * 读取命令的失败诊断码。
     *
     * @param commandId 命令 ID
     * @return 失败码；为空时返回 null
     * @throws SQLException 查询失败
     */
    private String commandFailureCode(UUID commandId) throws SQLException {
        try (Connection owner = ownerConnection();
             PreparedStatement statement = owner.prepareStatement(
                     "SELECT failure_code FROM ts_device_command WHERE id = ?")) {
            statement.setObject(1, commandId);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        }
    }

    /**
     * 执行一次领取。
     *
     * @param fixture 独占夹具
     * @param deviceId 指定设备；为空时使用夹具主设备
     * @param limit 请求条数
     * @return 领取结果
     */
    private List<DeviceCommandClaimPort.Claimed> claim(Fixture fixture, UUID deviceId, int limit) {
        return claimPort.claim(new DeviceCommandClaimPort.ClaimRequest(fixture.tenantId(), fixture.projectId(),
                deviceId == null ? fixture.deviceId() : deviceId, Duration.ofSeconds(30), limit));
    }

    /**
     * 把租约与响应窗口同时拨到过去，模拟租约到期。
     *
     * @param commandId 命令 ID
     * @throws SQLException 更新失败
     */
    private void expireLease(UUID commandId) throws SQLException {
        try (Connection owner = ownerConnection()) {
            execute(owner, "UPDATE ts_device_command SET next_attempt_at = now() - interval '1 second',"
                    + " deadline_at = now() - interval '1 second' WHERE id = ?", commandId);
        }
    }

    /**
     * 调用真实超时扫描函数，判断该命令是否被接管。
     *
     * @param commandId 命令 ID
     * @return 扫描结果是否包含该命令
     * @throws SQLException 查询失败
     */
    private boolean scanClaimable(UUID commandId) throws SQLException {
        try (Connection owner = ownerConnection();
             PreparedStatement statement = owner.prepareStatement(
                     "SELECT count(*) FROM claim_due_device_commands(100) WHERE command_id = ?")) {
            statement.setObject(1, commandId);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1) > 0;
            }
        }
    }

    /**
     * 读取命令状态与投递序号。
     *
     * @param commandId 命令 ID
     * @return {@code 状态/投递序号}
     * @throws SQLException 查询失败
     */
    private String commandState(UUID commandId) throws SQLException {
        try (Connection owner = ownerConnection();
             PreparedStatement statement = owner.prepareStatement(
                     "SELECT status || '/' || attempt_count FROM ts_device_command WHERE id = ?")) {
            statement.setObject(1, commandId);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        }
    }

    /**
     * 统计命令的领取事实行数。
     *
     * @param fixture 独占夹具
     * @param commandId 命令 ID
     * @return 领取行数
     * @throws SQLException 查询失败
     */
    private int claimRowCount(Fixture fixture, UUID commandId) throws SQLException {
        try (Connection owner = ownerConnection();
             PreparedStatement statement = owner.prepareStatement(
                     "SELECT count(*) FROM ts_device_command_claim WHERE project_id = ? AND command_id = ?")) {
            statement.setObject(1, fixture.projectId());
            statement.setObject(2, commandId);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getInt(1);
            }
        }
    }

    /**
     * 播种待领取命令。
     *
     * @param fixture 独占夹具
     * @param requestPayload 命令参数 JSON
     * @return 命令 ID
     * @throws SQLException 写入失败
     */
    private UUID seedAcceptedCommand(Fixture fixture, String requestPayload) throws SQLException {
        UUID commandId = Uuid7.generate();
        try (Connection owner = ownerConnection()) {
            execute(owner, """
                    INSERT INTO ts_device_command
                        (id, tenant_id, project_id, target_device_id, connection_device_id, command_definition_id,
                         command_key, input_schema, output_schema, request_payload, status, idempotency_key,
                         requested_by, timeout_seconds, attempt_count, max_attempts, next_attempt_at, deadline_at,
                         trace_id, accepted_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'reboot', '{}', '{}', ?::jsonb, 'ACCEPTED', ?, ?, 30, 0, 3,
                            now(), NULL, 'ax1d-claim', now())
                    """, commandId, fixture.tenantId(), fixture.projectId(), fixture.deviceId(), fixture.deviceId(),
                    fixture.definitionId(), requestPayload, commandId.toString(), fixture.accountId());
        }
        return commandId;
    }

    /**
     * 在夹具项目内追加一台直连设备。
     *
     * @param fixture 独占夹具
     * @param deviceKey 设备短标识
     * @return 新设备 ID
     * @throws SQLException 写入失败
     */
    private UUID seedDevice(Fixture fixture, String deviceKey) throws SQLException {
        UUID deviceId = Uuid7.generate();
        try (Connection owner = ownerConnection()) {
            execute(owner, """
                    INSERT INTO dev_device (id, tenant_id, project_id, device_type_id, device_key, name, status)
                    VALUES (?, ?, ?, ?, ?, '领取第二设备', 'ONLINE')
                    """, deviceId, fixture.tenantId(), fixture.projectId(), fixture.typeId(), deviceKey);
        }
        return deviceId;
    }

    /**
     * 播种独占租户、项目、直连设备与命令定义。
     *
     * @return 本测试独占夹具
     * @throws SQLException 播种失败直接终止用例
     */
    private Fixture seed() throws SQLException {
        Fixture fixture = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                Uuid7.generate(), Uuid7.generate());
        fixtures.add(fixture);
        try (Connection owner = ownerConnection()) {
            execute(owner, "INSERT INTO sys_tenant (id, name) VALUES (?, '命令领取独占租户')", fixture.tenantId());
            execute(owner, "INSERT INTO sys_account (id, email, password_hash, display_name)"
                    + " VALUES (?, ?, '{noop}unused', '命令领取 OWNER')", fixture.accountId(),
                    fixture.accountId() + "@example.com");
            execute(owner, "INSERT INTO sys_project (id, tenant_id, name, project_key) VALUES (?, ?, '命令领取项目', ?)",
                    fixture.projectId(), fixture.tenantId(), "ax1d_" + fixture.projectId().toString()
                            .replace("-", "").substring(24));
            execute(owner, """
                    INSERT INTO dev_type (id, tenant_id, project_id, type_key, name, device_kind,
                                          access_protocol, network_type, status)
                    VALUES (?, ?, ?, 'ax1d_type', '命令领取类型', 'DIRECT', 'STANDARD', 'WIFI', 'PUBLISHED')
                    """, fixture.typeId(), fixture.tenantId(), fixture.projectId());
            execute(owner, """
                    INSERT INTO dev_command_definition
                        (id, tenant_id, project_id, device_type_id, command_key, name, input_schema, output_schema,
                         timeout_seconds)
                    VALUES (?, ?, ?, ?, 'reboot', '重启', '{}', '{}', 30)
                    """, fixture.definitionId(), fixture.tenantId(), fixture.projectId(), fixture.typeId());
            execute(owner, """
                    INSERT INTO dev_device (id, tenant_id, project_id, device_type_id, device_key, name, status)
                    VALUES (?, ?, ?, ?, 'claim_device', '领取设备', 'ONLINE')
                    """, fixture.deviceId(), fixture.tenantId(), fixture.projectId(), fixture.typeId());
        }
        return fixture;
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
     * @param deviceId 直连设备
     * @param typeId 设备类型
     * @param definitionId 命令定义
     * @param accountId 命令请求人
     */
    private record Fixture(UUID tenantId, UUID projectId, UUID deviceId, UUID typeId, UUID definitionId,
                           UUID accountId) {
    }
}
