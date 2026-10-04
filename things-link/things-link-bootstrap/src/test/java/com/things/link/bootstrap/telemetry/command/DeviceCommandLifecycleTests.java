package com.things.link.bootstrap.telemetry.command;

import com.things.link.iam.application.AuthRateLimiter;
import com.things.link.project.application.QuotaPolicyAssignmentService;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.RlsScope;
import com.things.link.shared.tenant.RlsScopeContext;
import com.things.link.shared.message.DeviceCommandDispatch;
import com.things.link.shared.message.DeviceCommandDispatchFailure;
import com.things.link.shared.message.DeviceCommandReply;
import com.things.link.telemetry.application.DeviceCommandService;
import com.things.link.telemetry.domain.DeviceCommand;
import com.things.link.telemetry.domain.DeviceCommandAttempt;
import com.things.link.telemetry.domain.DeviceCommandRepository;
import com.things.link.testing.AbstractIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** S4-3 命令状态机的真实 PostgreSQL 验收，覆盖 RLS、CAS 与跨项目到期领取。 */
@AutoConfigureMockMvc
@DisplayName("设备命令状态机（S4-3）")
class DeviceCommandLifecycleTests extends AbstractIntegrationTest {

    /** HTTP 与测试准备共用的 JSON 映射器。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 测试登录密码。 */
    private static final String PASSWORD = "correct-horse-battery-staple";

    /** 真实 MVC 入口用于准备已发布物模型、设备与首次命令，不能伪造领域前置条件。 */
    @Autowired private MockMvc mockMvc;

    /** 受 RLS 约束的真实应用连接。 */
    @Autowired private JdbcTemplate jdbcTemplate;

    /** 显式事务让 set_config(..., true) 与被测 SQL 保持同一连接。 */
    @Autowired private TransactionTemplate transactionTemplate;

    /** 命令持久化端口，底层为真实 PostgreSQL CAS SQL。 */
    @Autowired private DeviceCommandRepository commandRepository;

    /** 命令应用服务，负责状态迁移与超时重试时的 Outbox 追加。 */
    @Autowired private DeviceCommandService commandService;

    /** 清空认证限流状态，避免同 JVM 的其他测试影响登录准备。 */
    @Autowired private AuthRateLimiter rateLimiter;

    /** 夹具租户的运行时策略绑定入口（与生产同一条 CAS 路径）。 */
    @Autowired private QuotaPolicyAssignmentService quotaPolicyAssignmentService;

    /** 当前测试账号的登录令牌与刷新令牌。 */
    private Login owner;

    /** 每个用例独立准备一个账号，避免共享容器中的业务事实影响跨项目领取断言。 */
    @BeforeEach
    void seed() throws Exception {
        rateLimiter.clear();
        // 命令表受项目 RLS 保护，不能在无范围连接上做“看似成功”的全表 DELETE。
        jdbcTemplate.query("SELECT id, tenant_id FROM sys_project", (resultSet, row) ->
                new ProjectScope(resultSet.getObject("id", UUID.class), resultSet.getObject("tenant_id", UUID.class)))
                .forEach(scope -> transactionTemplate.executeWithoutResult(ignored -> {
                    setProjectScope(scope.tenantId(), scope.projectId());
                    jdbcTemplate.update("DELETE FROM ts_device_command_attempt WHERE project_id = ?", scope.projectId());
                    jdbcTemplate.update("DELETE FROM ts_device_command WHERE project_id = ?", scope.projectId());
                    jdbcTemplate.update("DELETE FROM sys_outbox_event WHERE project_id = ?", scope.projectId());
                }));
        // 保留其他测试的项目及外键事实，仅隔离本用例账号与租户。
        String email = "command-lifecycle-" + UUID.randomUUID() + "@example.com";
        owner = registerAndLogin(email);
        useStandardPlan(jdbcTemplate.queryForObject("""
                SELECT member.tenant_id FROM sys_tenant_member member
                JOIN sys_account account ON account.id = member.account_id
                WHERE account.email = ?
                """, UUID.class, email));
    }

    /** 通过原CAS绑定合法STANDARD额度，足够同租户跨项目测试且不放宽冻结模板。 */
    private void useStandardPlan(UUID tenantId) {
        UUID policyId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_quota_policy WHERE code = 'PLAN_R1_STANDARD'", UUID.class);
        long assignmentVersion = jdbcTemplate.queryForObject(
                "SELECT quota_policy_assignment_version FROM sys_tenant WHERE id = ?", Long.class, tenantId);
        quotaPolicyAssignmentService.assign(tenantId, policyId, assignmentVersion);
    }

    /** 派发、ACK、SUCCESS 和重复/错连接回复均必须由数据库条件更新收敛。 */
    @Test
    void transitionsDispatchAckSuccessAndAbsorbsDuplicateOrWrongConnection() throws Exception {
        Fixture fixture = createFixture("成功状态项目", "success_device");
        UUID commandId = submit(fixture, "success-lifecycle");

        inProject(fixture, () -> {
            DeviceCommand command = command(commandId, fixture);
            DeviceCommandAttempt initial = attempts(commandId, fixture).getFirst();
            assertThat(commandService.markDispatched(dispatch(fixture, command, initial), Instant.now())).isTrue();
            assertThat(commandService.markDispatched(dispatch(fixture, command, initial), Instant.now())).isFalse();
            assertThat(commandService.applyReply(reply(fixture, commandId, Uuid7.generate(), Uuid7.generate(),
                    DeviceCommandReply.Status.SUCCESS))).isFalse();
            assertThat(command(commandId, fixture).status()).isEqualTo(DeviceCommand.Status.DISPATCHED);

            UUID acknowledgementId = Uuid7.generate();
            assertThat(commandService.applyReply(reply(fixture, commandId, fixture.deviceId(), acknowledgementId,
                    DeviceCommandReply.Status.ACK))).isTrue();
            assertThat(command(commandId, fixture).status()).isEqualTo(DeviceCommand.Status.ACKNOWLEDGED);
            assertThat(attempts(commandId, fixture).getFirst().status())
                    .isEqualTo(DeviceCommandAttempt.Status.ACKNOWLEDGED);

            UUID successId = Uuid7.generate();
            assertThat(commandService.applyReply(reply(fixture, commandId, fixture.deviceId(), successId,
                    DeviceCommandReply.Status.SUCCESS))).isTrue();
            assertThat(command(commandId, fixture).status()).isEqualTo(DeviceCommand.Status.SUCCEEDED);
            assertThat(attempts(commandId, fixture).getFirst().status())
                    .isEqualTo(DeviceCommandAttempt.Status.SUCCEEDED);
            assertThat(commandService.applyReply(reply(fixture, commandId, fixture.deviceId(), successId,
                    DeviceCommandReply.Status.SUCCESS))).isFalse();
            return null;
        });
    }

    /** S12-0d：同项目同key换目标设备必须拒绝，不能把旧设备的命令ID与状态返回给当前路径。 */
    @Test
    void rejectsIdempotencyKeyReusedForAnotherConsoleCommandCandidate() throws Exception {
        Fixture fixture = createFixture("候选身份项目", "candidate_device_a");
        UUID accepted = submit(fixture, "candidate-identity-key");
        UUID otherDevice = createAdditionalDevice(fixture, "candidate_device_b");

        MvcResult conflict = mockMvc.perform(post("/api/v1/projects/%s/devices/%s/commands".formatted(
                        fixture.projectId(), otherDevice))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.login().accessToken())
                .header("Idempotency-Key", "candidate-identity-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"commandKey\":\"restart\",\"input\":{}}"))
                .andReturn();

        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        JsonNode body = JSON.readTree(conflict.getResponse().getContentAsString());
        assertThat(body.get("code").asInt()).isEqualTo(10009);
        assertThat(conflict.getResponse().getContentAsString()).doesNotContain(accepted.toString());
        inProject(fixture, () -> {
            assertThat(commandRepository.findByIdempotencyKey(fixture.projectId(), "candidate-identity-key"))
                    .get().extracting(DeviceCommand::targetDeviceId).isEqualTo(fixture.deviceId());
            assertThat(attempts(accepted, fixture)).hasSize(1);
            assertThat(outboxCount(accepted)).isEqualTo(1);
            return null;
        });
    }

    /**
     * 设备可在 EMQX publish HTTP 返回前立即回复；回复必须直接收敛 PENDING/ACCEPTED，迟到的派发确认不得降级终态。
     */
    @Test
    void acceptsReplyBeforeDispatchConfirmationAndAbsorbsLateConfirmation() throws Exception {
        Fixture fixture = createFixture("快速回复项目", "fast_reply_device");
        UUID commandId = submit(fixture, "fast-reply-lifecycle");

        inProject(fixture, () -> {
            DeviceCommand command = command(commandId, fixture);
            DeviceCommandAttempt initial = attempts(commandId, fixture).getFirst();
            assertThat(command.status()).isEqualTo(DeviceCommand.Status.ACCEPTED);
            assertThat(initial.status()).isEqualTo(DeviceCommandAttempt.Status.PENDING);

            assertThat(commandService.applyReply(reply(fixture, commandId, fixture.deviceId(), Uuid7.generate(),
                    DeviceCommandReply.Status.SUCCESS))).isTrue();
            assertThat(command(commandId, fixture).status()).isEqualTo(DeviceCommand.Status.SUCCEEDED);
            assertThat(attempts(commandId, fixture).getFirst().status())
                    .isEqualTo(DeviceCommandAttempt.Status.SUCCEEDED);

            assertThat(commandService.markDispatched(dispatch(fixture, command, initial), Instant.now())).isFalse();
            assertThat(command(commandId, fixture).status()).isEqualTo(DeviceCommand.Status.SUCCEEDED);
            assertThat(attempts(commandId, fixture).getFirst().status())
                    .isEqualTo(DeviceCommandAttempt.Status.SUCCEEDED);
            return null;
        });
    }

    /** FAILED 是独立终态；终态后的不同 messageId 也不得覆盖原始失败诊断。 */
    @Test
    void transitionsFailedAndAbsorbsLaterReply() throws Exception {
        Fixture fixture = createFixture("失败状态项目", "failed_device");
        UUID commandId = submit(fixture, "failed-lifecycle");

        inProject(fixture, () -> {
            DeviceCommand command = command(commandId, fixture);
            assertThat(commandService.markDispatched(dispatch(fixture, command, attempts(commandId, fixture).getFirst()),
                    Instant.now())).isTrue();
            assertThat(commandService.applyReply(new DeviceCommandReply(Uuid7.generate(), fixture.tenantId(),
                    fixture.projectId(), fixture.deviceId(), commandId, Instant.now(), Instant.now(),
                    DeviceCommandReply.Status.FAILED, "{}", "DEVICE_REJECTED", "设备拒绝执行", "test-trace"))).isTrue();
            DeviceCommand failed = command(commandId, fixture);
            assertThat(failed.status()).isEqualTo(DeviceCommand.Status.FAILED);
            assertThat(failed.failureCode()).isEqualTo("DEVICE_REJECTED");
            assertThat(commandService.applyReply(reply(fixture, commandId, fixture.deviceId(), Uuid7.generate(),
                    DeviceCommandReply.Status.SUCCESS))).isFalse();
            assertThat(command(commandId, fixture).failureCode()).isEqualTo("DEVICE_REJECTED");
            return null;
        });
    }

    /** 前两次到期必须生成新 attempt 与 Outbox，第三次才进入不可逆 TIMED_OUT。 */
    @Test
    void retriesTwiceThenTimesOutOnThirdAttempt() throws Exception {
        Fixture fixture = createFixture("超时状态项目", "timeout_device");
        UUID commandId = submit(fixture, "timeout-lifecycle");

        inProject(fixture, () -> {
            dispatchAndExpireCurrentAttempt(fixture, commandId);
            processOnlyClaimedDue();
            assertAttemptAndOutboxCount(fixture, commandId, 2, DeviceCommand.Status.ACCEPTED);

            dispatchAndExpireCurrentAttempt(fixture, commandId);
            processOnlyClaimedDue();
            assertAttemptAndOutboxCount(fixture, commandId, 3, DeviceCommand.Status.ACCEPTED);

            dispatchAndExpireCurrentAttempt(fixture, commandId);
            processOnlyClaimedDue();
            DeviceCommand terminal = command(commandId, fixture);
            assertThat(terminal.status()).isEqualTo(DeviceCommand.Status.TIMED_OUT);
            assertThat(terminal.failureCode()).isEqualTo("RESPONSE_TIMEOUT");
            assertThat(attempts(commandId, fixture)).allSatisfy(attempt ->
                    assertThat(attempt.status()).isEqualTo(DeviceCommandAttempt.Status.TIMED_OUT));
            // 三次派发各一条，下列第四条是 S9-2 新增的低敏终态回写事件。
            assertThat(outboxCount(commandId)).isEqualTo(4);
            return null;
        });
    }

    /** D-037：三次派发均未交给 Broker 时命令进入 TIMED_OUT(DISPATCH_RETRY_EXHAUSTED)，三次 attempt 均 FAILED。 */
    @Test
    void exhaustsDispatchRetriesIntoTerminalState() throws Exception {
        Fixture fixture = createFixture("派发失败项目", "dispatch_fail_device");
        UUID commandId = submit(fixture, "dispatch-fail-lifecycle");

        inProject(fixture, () -> {
            failDispatchAndResume(fixture, commandId, 1);
            failDispatchAndResume(fixture, commandId, 2);
            // 第三次派发失败：耗尽，命令进入 TIMED_OUT(DISPATCH_RETRY_EXHAUSTED)
            DeviceCommand command = command(commandId, fixture);
            DeviceCommandAttempt attempt3 = attempts(commandId, fixture).getLast();
            assertThat(attempt3.attemptNo()).isEqualTo(3);
            commandService.recordDispatchFailure(dispatch(fixture, command, attempt3),
                    DeviceCommandDispatchFailure.DISPATCH_CONNECTION_FAILED, Instant.now());

            DeviceCommand terminal = command(commandId, fixture);
            assertThat(terminal.status()).isEqualTo(DeviceCommand.Status.TIMED_OUT);
            assertThat(terminal.failureCode()).isEqualTo("DISPATCH_RETRY_EXHAUSTED");
            assertThat(attempts(commandId, fixture)).allSatisfy(attempt ->
                    assertThat(attempt.status()).isEqualTo(DeviceCommandAttempt.Status.FAILED));
            return null;
        });
    }

    /** 对当前 attempt 记录一次派发失败，并回拨退避时间让扫描器续重出下一 attempt。 */
    private void failDispatchAndResume(Fixture fixture, UUID commandId, int expectedAttemptNo) {
        DeviceCommand command = command(commandId, fixture);
        DeviceCommandAttempt current = attempts(commandId, fixture).getLast();
        assertThat(current.attemptNo()).isEqualTo(expectedAttemptNo);
        commandService.recordDispatchFailure(dispatch(fixture, command, current),
                DeviceCommandDispatchFailure.DISPATCH_CONNECTION_FAILED, Instant.now());
        assertThat(attempts(commandId, fixture).getLast().status()).isEqualTo(DeviceCommandAttempt.Status.FAILED);
        // 派发失败后命令停在 ACCEPTED 且 deadline_at 为 NULL，把退避时间回拨为已到期让扫描器续重。
        jdbcTemplate.update("""
                UPDATE ts_device_command
                   SET next_attempt_at = now() - INTERVAL '1 second'
                 WHERE project_id = ? AND id = ?
                """, fixture.projectId(), commandId);
        processOnlyClaimedDue();
        assertThat(command(commandId, fixture).status()).isEqualTo(DeviceCommand.Status.ACCEPTED);
        assertThat(attempts(commandId, fixture)).hasSize(expectedAttemptNo + 1);
    }

    /** SECURITY DEFINER 的领取函数必须跨项目返回到期命令，同时普通业务读仍由 RLS 范围约束。 */
    @Test
    void claimsDueCommandsAcrossProjects() throws Exception {
        Fixture first = createFixture("领取项目甲", "claim_device_a");
        Fixture second = createFixture("领取项目乙", "claim_device_b");
        UUID firstCommand = submit(first, "claim-a");
        UUID secondCommand = submit(second, "claim-b");

        inProject(first, () -> {
            dispatchAndExpireCurrentAttempt(first, firstCommand);
            return null;
        });
        inProject(second, () -> {
            dispatchAndExpireCurrentAttempt(second, secondCommand);
            return null;
        });

        List<DeviceCommandRepository.DueCommand> due = commandRepository.claimDue(10);

        assertThat(due).extracting(DeviceCommandRepository.DueCommand::projectId)
                .containsExactlyInAnyOrder(first.projectId(), second.projectId());
        assertThat(due).extracting(DeviceCommandRepository.DueCommand::commandId)
                .containsExactlyInAnyOrder(firstCommand, secondCommand);
    }

    /** 在当前项目事务中派发当前 attempt，并把截止时间回拨为已到期。 */
    private void dispatchAndExpireCurrentAttempt(Fixture fixture, UUID commandId) {
        DeviceCommand command = command(commandId, fixture);
        DeviceCommandAttempt attempt = attempts(commandId, fixture).getLast();
        assertThat(commandService.markDispatched(dispatch(fixture, command, attempt), Instant.now())).isTrue();
        jdbcTemplate.update("""
                UPDATE ts_device_command
                   SET deadline_at = now() - INTERVAL '1 second', next_attempt_at = now() - INTERVAL '1 second'
                 WHERE project_id = ? AND id = ?
                """, fixture.projectId(), commandId);
    }

    /** 领取当前唯一到期命令并使用真实服务执行它的重试或超时迁移。 */
    private void processOnlyClaimedDue() {
        List<DeviceCommandRepository.DueCommand> due = commandRepository.claimDue(1);
        assertThat(due).hasSize(1);
        commandService.processDue(due.getFirst());
    }

    /** 验证重试既增加 attempt，又在同一命令聚合下留下对应 Outbox 事实。 */
    private void assertAttemptAndOutboxCount(Fixture fixture, UUID commandId, int expectedAttempts,
                                             DeviceCommand.Status expectedStatus) {
        assertThat(command(commandId, fixture).status()).isEqualTo(expectedStatus);
        assertThat(attempts(commandId, fixture)).hasSize(expectedAttempts);
        assertThat(outboxCount(commandId)).isEqualTo(expectedAttempts);
    }

    /** @return 当前项目可见的命令事实 */
    private DeviceCommand command(UUID commandId, Fixture fixture) {
        return commandRepository.findByCommandId(fixture.projectId(), commandId).orElseThrow();
    }

    /** @return 当前项目可见的命令尝试，按 attemptNo 升序 */
    private List<DeviceCommandAttempt> attempts(UUID commandId, Fixture fixture) {
        return commandRepository.findAttempts(fixture.projectId(), commandId);
    }

    /** @return 当前命令对应的真实 Outbox 事件数量 */
    private int outboxCount(UUID commandId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sys_outbox_event WHERE aggregate_id = ?::uuid", Integer.class, commandId);
        return count == null ? 0 : count;
    }

    /** 构造只含状态机需要字段的下行信封；派发方法只信任 tenant/project/command/attempt。 */
    private static DeviceCommandDispatch dispatch(Fixture fixture, DeviceCommand command,
                                                   DeviceCommandAttempt attempt) {
        return new DeviceCommandDispatch(attempt.outboxEventId(), fixture.tenantId(), fixture.projectId(),
                command.id(), attempt.id(), attempt.attemptNo(), fixture.deviceId(), fixture.deviceKey(),
                fixture.deviceId(), fixture.deviceKey(), fixture.projectKey(), command.commandKey(),
                command.requestJson(), attempt.deadlineAt(), command.traceId());
    }

    /** 创建状态机成功/失败分支使用的标准设备回复。 */
    private static DeviceCommandReply reply(Fixture fixture, UUID commandId, UUID connectionDeviceId,
                                            UUID messageId, DeviceCommandReply.Status status) {
        return new DeviceCommandReply(messageId, fixture.tenantId(), fixture.projectId(), connectionDeviceId,
                commandId, Instant.now(), Instant.now(), status, "{}", null, null, "test-trace");
    }

    /** ADR0070：只给查询恢复RLS范围；状态准备、独立claim与process各自真实提交，不以外层事务隐藏锁序。 */
    private <T> T inProject(Fixture fixture, Supplier<T> action) {
        RlsScope previous = RlsScopeContext.current().orElse(null);
        RlsScopeContext.set(new RlsScope(fixture.tenantId(), fixture.projectId()));
        try { return action.get(); }
        finally { if (previous == null) RlsScopeContext.clear(); else RlsScopeContext.set(previous); }
    }

    /** 在当前物理事务连接写入 RLS 所需的租户和项目变量。 */
    private void setProjectScope(UUID tenantId, UUID projectId) {
        jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class,
                tenantId.toString());
        jdbcTemplate.queryForObject("SELECT set_config('app.project_id', ?, true)", String.class,
                projectId.toString());
    }

    /** 通过真实 API 创建已发布命令定义和直连设备，保证超时重试可重新解析路由。 */
    private Fixture createFixture(String projectName, String deviceKey) throws Exception {
        JsonNode project = JSON.readTree(mockMvc.perform(post("/api/v1/projects")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\",\"region\":\"sh-1\"}".formatted(projectName)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString());
        UUID projectId = UUID.fromString(project.get("id").asString());
        owner = switchProject(owner, projectId);
        Login scoped = owner;
        UUID typeId = UUID.fromString(JSON.readTree(mockMvc.perform(post("/api/v1/projects/%s/device-types".formatted(projectId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"typeKey\":\"%s_type\",\"name\":\"命令测试设备\","
                                + "\"deviceKind\":\"DIRECT\",\"payloadProtocol\":\"STANDARD\","
                                + "\"networkType\":\"WIFI\"}").formatted(deviceKey)))
                .andReturn().getResponse().getContentAsString()).get("id").asString());
        mockMvc.perform(post("/api/v1/projects/%s/device-types/%s/commands".formatted(projectId, typeId))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"commandKey\":\"restart\",\"name\":\"重启\","
                        + "\"inputSchema\":\"{\\\"type\\\":\\\"object\\\"}\","
                        + "\"outputSchema\":\"{\\\"type\\\":\\\"object\\\"}\","
                        + "\"timeoutSeconds\":60,\"sortOrder\":0}"));
        mockMvc.perform(post("/api/v1/projects/%s/device-types/%s/publish".formatted(projectId, typeId))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken()));
        JsonNode device = JSON.readTree(mockMvc.perform(post("/api/v1/projects/%s/devices".formatted(projectId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scoped.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceTypeId\":\"%s\",\"deviceKey\":\"%s\",\"name\":\"命令测试设备\"}"
                                .formatted(typeId, deviceKey)))
                .andReturn().getResponse().getContentAsString());
        UUID tenantId = jdbcTemplate.queryForObject("SELECT tenant_id FROM sys_project WHERE id = ?", UUID.class,
                projectId);
        return new Fixture(tenantId, projectId, project.get("projectKey").asString(),
                UUID.fromString(device.get("id").asString()), deviceKey, scoped);
    }

    /** 在同一已发布类型下补一个真实ONLINE设备，用于证明项目级key不能代替目标设备身份。 */
    private UUID createAdditionalDevice(Fixture fixture, String deviceKey) throws Exception {
        UUID typeId = transactionTemplate.execute(ignored -> {
            setProjectScope(fixture.tenantId(), fixture.projectId());
            return jdbcTemplate.queryForObject(
                    "SELECT id FROM dev_type WHERE project_id = ? AND status = 'PUBLISHED'", UUID.class,
                    fixture.projectId());
        });
        JsonNode device = JSON.readTree(mockMvc.perform(post("/api/v1/projects/%s/devices".formatted(
                        fixture.projectId()))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.login().accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceTypeId\":\"%s\",\"deviceKey\":\"%s\",\"name\":\"候选身份设备\"}"
                        .formatted(typeId, deviceKey)))
                .andReturn().getResponse().getContentAsString());
        return UUID.fromString(device.get("id").asString());
    }

    /** 通过受理 API 创建首次 attempt 和 Outbox，返回稳定 commandId。 */
    private UUID submit(Fixture fixture, String idempotencyKey) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/projects/%s/devices/%s/commands".formatted(
                        fixture.projectId(), fixture.deviceId()))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + fixture.login().accessToken())
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"commandKey\":\"restart\",\"input\":{}}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(202);
        return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString()).get("id").asString());
    }

    /** 注册、验证邮箱并登录，返回后续项目切换所需的 refresh cookie。 */
    private Login registerAndLogin(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)));
        jdbcTemplate.update("UPDATE sys_account SET email_verified_at = now() WHERE email = ?", email);
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD))).andReturn();
        JsonNode body = JSON.readTree(login.getResponse().getContentAsString());
        String refresh = login.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(value -> value.startsWith("tc_refresh="))
                .map(value -> value.substring("tc_refresh=".length(), value.indexOf(';')))
                .findFirst().orElseThrow();
        return new Login(body.get("accessToken").asString(), refresh);
    }

    /** 切换项目后取得带 projectId claim 的新 access token。 */
    private Login switchProject(Login login, UUID projectId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/switch-project")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.accessToken())
                        .cookie(new Cookie("tc_refresh", login.refreshToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":\"%s\"}".formatted(projectId)))
                .andReturn();
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        String refresh = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(value -> value.startsWith("tc_refresh="))
                .map(value -> value.substring("tc_refresh=".length(), value.indexOf(';')))
                .findFirst().orElseThrow();
        return new Login(body.get("accessToken").asString(), refresh);
    }

    /** @param accessToken 项目范围 JWT @param refreshToken HttpOnly 刷新令牌 */
    private record Login(String accessToken, String refreshToken) {
    }

    /** @param tenantId 命令租户 @param projectId 项目 @param projectKey MQTT 项目段
     * @param deviceId 直连目标与连接设备 @param deviceKey MQTT 设备段 @param login 项目范围登录态 */
    private record Fixture(UUID tenantId, UUID projectId, String projectKey, UUID deviceId, String deviceKey,
                           Login login) {
    }

    /** @param projectId 已存在项目 @param tenantId 项目计费归属，用于测试清理时恢复 RLS */
    private record ProjectScope(UUID projectId, UUID tenantId) {
    }
}
