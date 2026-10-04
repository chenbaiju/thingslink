package com.things.link.bootstrap.task;

import com.things.link.testing.OwnedTestContainers;

import com.things.link.device.application.DeviceSearchService;
import com.things.link.project.application.ProjectDailyQuotaDecisionService;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.application.ProjectService;
import com.things.link.project.application.QuotaStatus;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.DeviceCommandReply;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.scheduling.NotificationWorkCoordinator;
import com.things.link.support.tenant.DatabaseWorkload;
import com.things.link.support.tenant.DatabaseWorkloadContext;
import com.things.link.task.application.TaskJobCommand;
import com.things.link.task.application.TaskJobService;
import com.things.link.task.application.TaskSchedulingScanner;
import com.things.link.task.domain.TaskExecution;
import com.things.link.task.domain.TaskJob;
import com.things.link.task.domain.TaskJobRepository;
import com.things.link.telemetry.application.DeviceCommandService;
import com.things.link.telemetry.application.DeviceCommandTimeoutScanner;
import com.things.link.telemetry.application.PropertyAggregateBackfillScanner;
import com.things.link.telemetry.application.TaskDeviceCommandRequest;
import com.things.link.telemetry.application.TaskDeviceCommandResult;
import com.things.link.testing.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

/**
 * ADR0066决策1至3的原应用事务验收：只有持续许可允许展开，确定冻结后只进行有界维护。
 * 真实claim先提交，原processExecution代理自行开始事务；设备、额度、命令和回复均走真实接口。
 * 本类不模拟严格租约fencing，也不以基本收束替代5e2d的并发与失败恢复验收。
 */
@Import(TaskExecutionProjectLifecycleTests.IsolatedDatabaseConfiguration.class)
@OwnedTestContainers({"LIFECYCLE_POSTGRES"})
class TaskExecutionProjectLifecycleTests extends AbstractIntegrationTest {
    /** D-109要求物理隔离，随机项目不能阻止其他缓存上下文的全局任务领取者。 */
    private static final String DATABASE_NAME = "task_lifecycle_" + UUID.randomUUID().toString().replace("-", "");
    /** 与正式验收相同镜像和owner，所有运行池及迁移共同落到该专库。 */
    private static final PostgreSQLContainer<?> LIFECYCLE_POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse(POSTGRES.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName(DATABASE_NAME).withUsername(POSTGRES.getUsername()).withPassword(POSTGRES.getPassword());
    /** 在Registrar之前启动，保留随机端口避免依赖开发机常驻数据库。 */
    private static final String DATABASE_URL = startDatabase();
    /** 任务与命令七张物理表逐字段快照；不能把同数量覆盖更新误判为零业务增量。 */
    private static final List<String> FACT_TABLES = List.of("task_job", "task_schedule", "task_execution", "task_target",
            "ts_device_command", "ts_device_command_attempt", "sys_outbox_event");
    /** 父runner直连共享PG，专库必须真正抑制而不是仅覆盖运行池地址。 */
    @MockitoBean(enforceOverride = true, name = "relaxRestQuota")
    private ApplicationRunner unusedRestQuotaRelaxation;
    /** 原scanner是自动全局领取者；本测试手动调用真实仓储claim及原应用服务。 */
    @MockitoBean(enforceOverride = true)
    private TaskSchedulingScanner unusedTaskScanner;
    /** 本片没有通知派发目标，禁止旁路worker抢占任何后台事实。 */
    @MockitoBean(enforceOverride = true)
    private NotificationWorkCoordinator unusedNotificationCoordinator;
    /** 不允许无关回补改变后台上下文状态。 */
    @MockitoBean(enforceOverride = true)
    private PropertyAggregateBackfillScanner unusedBackfillScanner;
    /** 保留原Transactional代理，目标spy只记录原入口事务与参数。 */
    @MockitoSpyBean private TaskJobService tasks;
    /** 正常推进必须真实领取；仅身份拒绝与终态重放明确手造无权/重复Due。 */
    @Autowired private TaskJobRepository repository;
    /** 禁止超时worker抢占真实已受理命令；命令服务本身仍为原代理。 */
    @MockitoBean(enforceOverride = true) private DeviceCommandTimeoutScanner unusedCommandTimeoutScanner;
    /** 项目许可只观察真实返回，不能把false改成预设成功。 */
    @MockitoSpyBean private ProjectLifecycleAccessService lifecycle;
    /** 真实任务命令和回复归并，spy用于证明停止维护没有重新受理。 */
    @MockitoSpyBean private DeviceCommandService commands;
    /** 清理本例真实限流键，禁止全库清理。 */
    @Autowired private StringRedisTemplate redis;
    /** 原日额度的成功或拒绝都必须穿过原代理，不以NORMAL替身制造路径。 */
    @MockitoSpyBean private ProjectDailyQuotaDecisionService dailyQuota;
    /** 原设备域分页查询必须实际返回合法设备；spy只观察200页边界。 */
    @MockitoSpyBean private DeviceSearchService deviceSearch;
    /** 冻结删除只允许真实OWNER服务完成，归档使用明确状态字段夹具。 */
    @Autowired private ProjectService projects;
    /** 读取当前事务连接的真实角色、库、PID及GUC。 */
    @Autowired private JdbcTemplate jdbc;
    /** 验证迁移连接实际库，防止地址覆盖不完整。 */
    @Autowired private Environment environment;
    /** 使用生产同JSON类型构造合法空对象命令。 */
    @Autowired private ObjectMapper mapper;
    /** 每项独占身份，不依赖测试执行顺序或共享租户。 */
    private final Fixture fixture = new Fixture(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), Uuid7.generate());
    /** 精确记录quota metric、原返回或异常，不保存租户敏感事实。 */
    private final List<String> quotaObservations = new ArrayList<>();
    /** 额度资格矩阵仍调用真实决策，仅改变本例期望结果。 */
    private QuotaStatus expectedQuotaStatus = QuotaStatus.NORMAL;
    /** 参数与事务证据在真实process代理内部收集，不能只证明外层测试事务。 */
    private final List<String> processingObservations = new ArrayList<>();
    /** 页查询次数区分EXPANDING与DISPATCHING，不靠最终状态猜测路径。 */
    private final AtomicInteger pageCalls = new AtomicInteger();

    /** 原process物理连接PID，用于核对许可、分页及命令投影未另开短事务。 */
    private final AtomicInteger processPid = new AtomicInteger();

    /** 每个测试明确选择设备规模，真实UUID按插入保存。 */
    private final List<UUID> deviceIds = new ArrayList<>();
    /** 仅统计原项目许可调用，不伪造返回值。 */
    private final AtomicInteger permitCalls = new AtomicInteger();
    /** STOPPING不能借归并之名新受理命令。 */
    private final AtomicInteger submitCalls = new AtomicInteger();
    /** 既有命令只通过telemetry公开投影回读。 */
    private final AtomicInteger commandReads = new AtomicInteger();

    /** 先核验CONTROL/DATA/Flyway/owner落点和后台抑制，再播种真实关系及合法设备。 */
    @BeforeEach
    void prepare() throws Exception {
        verifyIsolation();
        seed();
        installObservers();
    }

    /** ACTIVE保留原200条分页合同，第二次真实领取只追加第201台，尚未派发。 */
    @Test
    void activeExecutionExpandsTwoRealClaimedPages() throws Exception {
        seedDevices(201);
        TaskExecution execution = createAndRun();
        process(claimExecution(execution.id()));
        assertPage(200, "EXPANDING", true);
        Map<String, List<String>> first = facts();
        process(claimExecution(execution.id()));
        assertPage(201, "DISPATCHING", false);
        assertThat(facts().get("task_target")).containsAll(first.get("task_target"));
        assertThat(pageCalls).hasValue(2);
        assertThat(permitCalls).hasValue(2);
        assertThat(submitCalls).hasValue(0);
        assertThat(processingObservations).hasSize(2);
    }

    /** 删除/归档先提交：原200目标分100/100跳过，原游标和总数不得把第201台算进去。 */
    @ParameterizedTest
    @EnumSource(Freeze.class)
    void frozenProjectStopsTwoBatchesWithoutAppendingSecondPage(Freeze freeze) throws Exception {
        seedDevices(201);
        TaskExecution execution = createAndRun();
        process(claimExecution(execution.id()));
        Map<String, List<String>> first = facts();
        freeze(freeze);
        resetObservations();
        process(claimExecution(execution.id()));
        assertStopping(first, 200, 100, 100, 0, "STOPPING");
        process(claimExecution(execution.id()));
        assertStopping(first, 200, 0, 200, 0, "FAILED");
        assertThat(permitCalls).hasValue(1);
        assertNoNewWork();
        assertTerminalReplay(execution);
    }

    /** 无任何展开事实时允许原事务直接FAILED，不为了展示中间状态空转。 */
    @ParameterizedTest
    @EnumSource(Freeze.class)
    void frozenExecutionWithZeroTargetsFailsWithoutQueryingDevices(Freeze freeze) throws Exception {
        TaskExecution execution = createAndRun();
        Map<String, List<String>> first = facts();
        freeze(freeze);
        resetObservations();
        process(claimExecution(execution.id()));
        assertStopping(first, 0, 0, 0, 0, "FAILED");
        assertThat(permitCalls).hasValue(1);
        assertNoNewWork();
        assertTerminalReplay(execution);
    }

    /** 401台真实设备先展开400台；停止每轮只处理100，不一次耗尽全部已展开目标。 */
    @Test
    void stoppingMoreThanTwoHundredTargetsUsesFourBoundedTurns() throws Exception {
        seedDevices(401);
        TaskExecution execution = createAndRun();
        process(claimExecution(execution.id()));
        process(claimExecution(execution.id()));
        assertPage(400, "EXPANDING", true);
        Map<String, List<String>> first = facts();
        freeze(Freeze.ARCHIVED);
        resetObservations();
        for (int turn = 1; turn <= 4; turn++) {
            process(claimExecution(execution.id()));
            assertStopping(first, 400, 400 - turn * 100, turn * 100, 0, turn == 4 ? "FAILED" : "STOPPING");
        }
        assertThat(permitCalls).hasValue(1);
        assertNoNewWork();
        assertTerminalReplay(execution);
    }

    /** STOPPING是已提交的停止决定，恢复ACTIVE不复活旧执行或重新查询设备。 */
    @Test
    void restoringActiveDoesNotResumeCommittedStoppingExecution() throws Exception {
        seedDevices(201);
        TaskExecution execution = createAndRun();
        process(claimExecution(execution.id()));
        Map<String, List<String>> first = facts();
        freeze(Freeze.ARCHIVED);
        resetObservations();
        process(claimExecution(execution.id()));
        assertStopping(first, 200, 100, 100, 0, "STOPPING");
        try (Connection owner = fixtureOwnerConnection()) {
            execute(owner, "UPDATE sys_project SET status='ACTIVE' WHERE id=?", fixture.projectId());
        }
        process(claimExecution(execution.id()));
        assertStopping(first, 200, 0, 200, 0, "FAILED");
        assertThat(permitCalls).hasValue(1);
        assertNoNewWork();
        assertTerminalReplay(execution);
    }

    /**
     * EXPANDING+ACCEPTED是ADR0066明确的防御种子，不称正常生产已出现该组合。
     * 命令由真实submitTask受理，STOPPING保留command/attempt/Outbox，真实回复后只归并原command。
     */
    @Test
    void stoppingPreservesAcceptedCommandAndReconcilesItsRealReply() throws Exception {
        seedDevices(201);
        TaskExecution execution = createAndRun();
        process(claimExecution(execution.id()));
        UUID deviceId = UUID.fromString(mapper.readTree(facts().get("task_target").getFirst()).path("device_id").asText());
        TaskDeviceCommandResult accepted = asOwner(() -> commands.submitTask(new TaskDeviceCommandRequest(
                execution.id(), fixture.projectId(), fixture.accountId(), deviceId, "reboot", mapper.createObjectNode())));
        assertThat(accepted.status()).isEqualTo(TaskDeviceCommandResult.Status.ACCEPTED);
        assertThat(accepted.commandId()).isNotNull();
        // 唯一防御性拼接只标任务引用；命令、attempt和Outbox全部由原应用事务创建。
        try (Connection owner = fixtureOwnerConnection()) {
            execute(owner, "UPDATE task_target SET status='ACCEPTED',command_id=?,accepted_at=now() WHERE execution_id=? AND device_id=?",
                    accepted.commandId(), execution.id(), deviceId);
        }
        Map<String, List<String>> first = facts();
        assertThat(first.get("ts_device_command")).hasSize(1);
        assertThat(first.get("ts_device_command_attempt")).hasSize(1);
        assertThat(first.get("sys_outbox_event")).hasSize(1);
        freeze(Freeze.ARCHIVED);
        resetObservations();
        process(claimExecution(execution.id()));
        assertStopping(first, 200, 99, 100, 1, "STOPPING");
        process(claimExecution(execution.id()));
        assertStopping(first, 200, 0, 199, 1, "STOPPING");
        assertThat(commandReads).hasValue(2);
        assertThat(permitCalls).hasValue(1);
        assertNoNewWork();
        // 原回复路径可以收敛先于发送确认到达的SUCCESS；不篡改命令状态制造绿色归并。
        replySuccess(accepted.commandId(), deviceId);
        Map<String, List<String>> replied = facts();
        assertThat(replied.get("sys_outbox_event")).hasSize(2);
        process(claimExecution(execution.id()));
        assertStopping(replied, 200, 0, 199, 0, "PARTIAL_FAILED");
        JsonNode finished = execution(facts());
        assertThat(finished.path("accepted_targets").asInt()).isEqualTo(1);
        assertThat(finished.path("succeeded_targets").asInt()).isEqualTo(1);
        assertThat(finished.path("failed_targets").asInt()).isZero();
        assertThat(commandReads).hasValue(3);
        assertNoNewWork();
        assertTerminalReplay(execution);
    }

    /** 统一执行行锁不能破坏原DISPATCHING真实受理及RUNNING真实回复归并。 */
    @Test
    void activeDispatchingAndRunningStillAcceptAndCompleteRealCommand() throws Exception {
        seedDevices(1);
        TaskExecution execution = createAndRun();
        process(claimExecution(execution.id()));
        assertPage(1, "DISPATCHING", false);
        process(claimExecution(execution.id()));
        Map<String, List<String>> accepted = facts();
        assertThat(execution(accepted).path("status").asText()).isEqualTo("RUNNING");
        JsonNode target = mapper.readTree(accepted.get("task_target").getFirst());
        assertThat(target.path("status").asText()).isEqualTo("ACCEPTED");
        UUID commandId = UUID.fromString(target.path("command_id").asText());
        replySuccess(commandId, deviceIds.getFirst());
        process(claimExecution(execution.id()));
        JsonNode completed = execution(facts());
        assertThat(completed.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(completed.path("total_targets").asInt()).isEqualTo(1);
        assertThat(completed.path("accepted_targets").asInt()).isEqualTo(1);
        assertThat(completed.path("succeeded_targets").asInt()).isEqualTo(1);
        assertThat(completed.path("failed_targets").asInt()).isZero();
        assertThat(completed.path("skipped_targets").asInt()).isZero();
        // ADR0067新增DISPATCHING许可；EXPANDING及DIS各一次，RUNNING仍不重申请。
        assertThat(permitCalls).hasValue(2);
        assertThat(submitCalls).hasValue(1);
        assertThat(commandReads).hasValue(1);
        assertTerminalReplay(execution);
    }

    /** 已受理命令不因日额度后来达到硬限/降级被取消；只拒绝新批量，并继续真实回复归并。 */
    @ParameterizedTest(name = "accepted command survives {0}, used={1}")
    @org.junit.jupiter.params.provider.CsvSource({"HARD_LIMIT, 10", "DEGRADED, 12"})
    void acceptedCommandCompletesWhileDailyQuotaRejectsNewBatches(QuotaStatus status, long used) throws Exception {
        seedDevices(1);
        TaskExecution acceptedExecution = createAndRun();
        process(claimExecution(acceptedExecution.id()));
        process(claimExecution(acceptedExecution.id()));
        Map<String, List<String>> accepted = facts();
        assertThat(execution(accepted).path("status").asText()).isEqualTo("RUNNING");
        JsonNode target = mapper.readTree(accepted.get("task_target").getFirst());
        assertThat(target.path("status").asText()).isEqualTo("ACCEPTED");
        UUID commandId = UUID.fromString(target.path("command_id").asText());
        assertThat(accepted.get("ts_device_command")).hasSize(1);
        assertThat(accepted.get("ts_device_command_attempt")).hasSize(1);
        UUID policyId = Uuid7.generate();
        try (Connection owner = fixtureOwnerConnection()) {
            JdbcTemplate sql = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(owner, true));
            UUID oldPolicy = sql.queryForObject("SELECT quota_policy_id FROM sys_tenant WHERE id=?", UUID.class, fixture.tenantId());
            sql.update("INSERT INTO sys_quota_policy(id,code,downlink_message_daily_limit) VALUES (?,?,10)",
                    policyId, "tq" + policyId.toString().replace("-", "").substring(0, 20));
            try {
                sql.update("UPDATE sys_tenant SET quota_policy_id=? WHERE id=?", policyId, fixture.tenantId());
                sql.update("""
                        INSERT INTO sys_usage_counter_daily(id,tenant_id,project_id,usage_date,metric,used_value)
                        VALUES (?,?,?,(clock_timestamp() AT TIME ZONE 'UTC')::date,'DOWNLINK_MESSAGE',?)
                        """, Uuid7.generate(), fixture.tenantId(), fixture.projectId(), used);
                var fact = sql.queryForMap("""
                        SELECT limit_value,tenant_used_value FROM trusted_project_daily_quota_decision(
                            ?,?,(clock_timestamp() AT TIME ZONE 'UTC')::date,'DOWNLINK_MESSAGE')
                        """, fixture.tenantId(), fixture.projectId());
                assertThat(fact.get("limit_value")).isEqualTo(10L);
                assertThat(((Number) fact.get("tenant_used_value")).longValue()).isEqualTo(used);
                var decision = dailyQuota.decisionTrustedProject(fixture.tenantId(), fixture.projectId(),
                        com.things.link.project.application.QuotaMetric.DOWNLINK_MESSAGE);
                assertThat(decision.disabled()).isFalse();
                assertThat(decision.status()).isEqualTo(status);
                expectedQuotaStatus = status;
                Throwable refusal = org.assertj.core.api.Assertions.catchThrowable(() ->
                        asOwner(() -> tasks.run(fixture.projectId(), acceptedExecution.jobId())));
                assertThat(refusal).isInstanceOf(com.things.link.shared.error.BusinessException.class);
                assertThat(((com.things.link.shared.error.BusinessException) refusal).errorCode().code()).isEqualTo(40022);
                assertThat(facts()).as("新批量拒绝不能重写已受理事实").isEqualTo(accepted);
                int priorSubmits = submitCalls.get();
                int priorQuotaReads = quotaObservations.size();
                replySuccess(commandId, deviceIds.getFirst());
                process(claimExecution(acceptedExecution.id()));
                Map<String, List<String>> completedFacts = facts();
                JsonNode completed = execution(completedFacts);
                assertThat(completed.path("status").asText()).isEqualTo("SUCCEEDED");
                assertThat(completed.path("accepted_targets").asInt()).isEqualTo(1);
                assertThat(completed.path("succeeded_targets").asInt()).isEqualTo(1);
                assertThat(completed.path("failed_targets").asInt()).isZero();
                assertThat(submitCalls).hasValue(priorSubmits);
                assertThat(quotaObservations).hasSize(priorQuotaReads);
                assertThat(completedFacts.get("ts_device_command")).hasSize(1);
                assertThat(completedFacts.get("ts_device_command_attempt")).hasSize(1);
                assertThat(UUID.fromString(mapper.readTree(completedFacts.get("task_target").getFirst())
                        .path("command_id").asText())).isEqualTo(commandId);
                assertTerminalReplay(acceptedExecution);
            } finally {
                sql.update("DELETE FROM sys_usage_counter_daily WHERE project_id=? AND metric='DOWNLINK_MESSAGE'", fixture.projectId());
                sql.update("UPDATE sys_tenant SET quota_policy_id=? WHERE id=?", oldPolicy, fixture.tenantId());
                sql.update("DELETE FROM sys_quota_policy WHERE id=?", policyId);
            }
        }
    }

    /** 原ACTIVE空设备也依次走EXPANDING/DISPATCHING/RUNNING，最后FAILED而不是永久挂起。 */
    @Test
    void activeZeroTargetsKeepOriginalTerminalBehavior() throws Exception {
        TaskExecution execution = createAndRun();
        process(claimExecution(execution.id()));
        assertPage(0, "DISPATCHING", false);
        process(claimExecution(execution.id()));
        assertThat(execution(facts()).path("status").asText()).isEqualTo("RUNNING");
        process(claimExecution(execution.id()));
        JsonNode completed = execution(facts());
        assertThat(completed.path("status").asText()).isEqualTo("FAILED");
        assertThat(completed.path("total_targets").asInt()).isZero();
        assertThat(completed.path("failure_summary").isNull()).isTrue();
        // ADR0067新增DISPATCHING许可；EXPANDING及DIS各一次，RUNNING仍不重申请。
        assertThat(permitCalls).hasValue(2);
        assertThat(submitCalls).hasValue(0);
        assertTerminalReplay(execution);
    }

    /** 错tenant/project/id不能用合法executionId把别人的执行标STOPPING或继续展开。 */
    @Test
    void mismatchedPersistentIdentityIsNoOpBeforeProjectPermission() throws Exception {
        seedDevices(201);
        TaskExecution execution = createAndRun();
        Map<String, List<String>> before = facts();
        process(new TaskJobRepository.DueExecution(UUID.randomUUID(), fixture.projectId(), execution.id()));
        process(new TaskJobRepository.DueExecution(fixture.tenantId(), UUID.randomUUID(), execution.id()));
        process(new TaskJobRepository.DueExecution(fixture.tenantId(), fixture.projectId(), UUID.randomUUID()));
        assertThat(facts()).isEqualTo(before);
        assertThat(permitCalls).hasValue(0);
        assertNoNewWork();
        process(claimExecution(execution.id()));
        assertPage(200, "EXPANDING", true);
    }

    /** 只归并已经受理的真实回复，DATA工作范围不提供外层事务。 */
    private void replySuccess(UUID commandId, UUID deviceId) {
        Instant now = Instant.now();
        assertThat(inData(() -> commands.applyReply(new DeviceCommandReply(Uuid7.generate(), fixture.tenantId(),
                fixture.projectId(), deviceId, commandId, now, now, DeviceCommandReply.Status.SUCCESS,
                "{}", null, null, "task-lifecycle-test")))).isTrue();
    }

    /** 停止允许明确维护字段，其余持久快照、总数与ID必须完整保留。 */
    private void assertStopping(Map<String, List<String>> before, int total, int pending, int skipped,
                                int accepted, String state) throws SQLException {
        Map<String, List<String>> after = facts();
        JsonNode current = execution(after);
        JsonNode original = execution(before);
        assertThat(current.path("status").asText()).isEqualTo(state);
        assertThat(current.path("total_targets").asInt()).isEqualTo(total);
        assertThat(current.path("failure_summary").asText()).isEqualTo("项目已冻结，目标展开停止");
        for (String field : List.of("id", "tenant_id", "project_id", "job_id", "trigger_type", "target_type",
                "target_group_id", "command_key", "input", "requested_by", "started_at", "created_at", "expansion_cursor", "scheduled_fire_at"))
            assertThat(current.path(field)).as(field).isEqualTo(original.path(field));
        assertThat(after.get("task_target")).hasSize(total);
        Map<String, JsonNode> oldTargets = targetRows(before);
        Map<String, JsonNode> targets = targetRows(after);
        assertThat(targets.keySet()).isEqualTo(oldTargets.keySet());
        assertThat(targets.values().stream().filter(row -> row.path("status").asText().equals("PENDING")).count()).isEqualTo(pending);
        assertThat(targets.values().stream().filter(row -> row.path("status").asText().equals("SKIPPED")).count()).isEqualTo(skipped);
        assertThat(targets.values().stream().filter(row -> row.path("status").asText().equals("ACCEPTED")).count()).isEqualTo(accepted);
        targets.forEach((id, row) -> {
            for (String field : List.of("execution_id", "device_id", "tenant_id", "project_id", "created_at", "command_id", "accepted_at"))
                assertThat(row.path(field)).as(field).isEqualTo(oldTargets.get(id).path(field));
            if (row.path("status").asText().equals("PENDING") || row.path("status").asText().equals("ACCEPTED"))
                assertThat(row).isEqualTo(oldTargets.get(id));
            if (row.path("status").asText().equals("SKIPPED")) {
                assertThat(row.path("failure_summary").asText()).isEqualTo("项目已冻结，目标未受理");
                assertThat(row.path("completed_at").isNull()).isFalse();
                assertThat(row.path("lease_until").isNull()).isTrue();
            }
        });
        for (String table : List.of("task_job", "task_schedule", "ts_device_command", "ts_device_command_attempt", "sys_outbox_event"))
            assertThat(after.get(table)).as(table).isEqualTo(before.get(table));
        assertThat(current.path("lease_until").isNull()).isTrue();
        if (state.equals("STOPPING")) {
            assertThat(current.path("finished_at").isNull()).isTrue();
            for (String field : List.of("accepted_targets", "succeeded_targets", "failed_targets", "skipped_targets"))
                assertThat(current.path(field).asInt()).isZero();
        } else {
            assertThat(current.path("finished_at").isNull()).isFalse();
            assertThat(current.path("skipped_targets").asInt()).isEqualTo(skipped);
            long succeeded = targets.values().stream().filter(row -> row.path("status").asText().equals("SUCCEEDED")).count();
            long failed = targets.values().stream().filter(row -> List.of("FAILED", "TIMED_OUT").contains(row.path("status").asText())).count();
            assertThat(current.path("accepted_targets").asLong()).isEqualTo(succeeded + failed);
            assertThat(current.path("succeeded_targets").asLong()).isEqualTo(succeeded);
            assertThat(current.path("failed_targets").asLong()).isEqualTo(failed);
        }
    }

    /** 以真实deviceId索引完整目标行，数量相同但替换设备也必须失败。 */
    private Map<String, JsonNode> targetRows(Map<String, List<String>> facts) {
        Map<String, JsonNode> rows = new LinkedHashMap<>();
        for (String row : facts.get("task_target")) {
            JsonNode value = mapper.readTree(row);
            rows.put(value.path("device_id").asText(), value);
        }
        return rows;
    }

    /** 不把项目拒绝偷偷改为再次额度、设备分页或受理命令。 */
    private void assertNoNewWork() {
        assertThat(pageCalls).hasValue(0);
        assertThat(submitCalls).hasValue(0);
        assertThat(quotaObservations).isEmpty();
    }

    /** 旧Due重放只能读取已持久终态，不能改任何事实或触发许可/受理。 */
    private void assertTerminalReplay(TaskExecution execution) throws SQLException {
        Map<String, List<String>> before = facts();
        int permits = permitCalls.get();
        int submissions = submitCalls.get();
        process(new TaskJobRepository.DueExecution(fixture.tenantId(), fixture.projectId(), execution.id()));
        assertThat(facts()).isEqualTo(before);
        assertThat(permitCalls).hasValue(permits);
        assertThat(submitCalls).hasValue(submissions);
    }

    /** 切断创建时额度与本轮展开许可的计数，观察器仍调用所有真实代码。 */
    private void resetObservations() {
        quotaObservations.clear();
        processingObservations.clear();
        pageCalls.set(0);
        permitCalls.set(0);
        submitCalls.set(0);
        commandReads.set(0);
    }

    /** 创建/运行始终从真实OWNER控制面进入；手工任务安排明天避免自动调度混入领取。 */
    private TaskExecution createAndRun() {
        TaskJob job = createJob(Instant.now().plusSeconds(86_400));
        TaskExecution execution = asOwner(() -> tasks.run(fixture.projectId(), job.id()));
        assertThat(execution.tenantId()).isEqualTo(fixture.tenantId());
        assertThat(execution.projectId()).isEqualTo(fixture.projectId());
        assertThat(execution.status()).isEqualTo(TaskExecution.Status.EXPANDING);
        assertThat(execution.totalTargets()).isZero();
        assertThat(quotaObservations).containsExactly("DOWNLINK_MESSAGE=NORMAL");
        resetObservations();
        return execution;
    }

    /** 合法DIRECT/STANDARD设备类型声明reboot空对象命令，不能用无定义UUID掩盖后续路由。 */
    private TaskJob createJob(Instant runAt) {
        return asOwner(() -> tasks.create(fixture.projectId(), new TaskJobCommand("项目展开许可验收", null,
                TaskJob.ScheduleType.ONCE, runAt, null, null, TaskJob.TargetType.ALL_DEVICES, null,
                "reboot", mapper.createObjectNode(), true, null)));
    }

    /** 调用原SECURITY DEFINER函数，事务外autocommit使lease在进入process之前真正持久化。 */
    private TaskJobRepository.DueExecution claimExecution(UUID executionId) throws SQLException {
        TaskJobRepository.DueExecution claimedDue = inData(() -> {
            List<TaskJobRepository.DueExecution> claimed = repository.claimDueExecutions(100);
            assertThat(claimed).hasSize(1);
            TaskJobRepository.DueExecution due = claimed.getFirst();
            assertThat(due.executionId()).isEqualTo(executionId);
            assertThat(due.tenantId()).isEqualTo(fixture.tenantId());
            assertThat(due.projectId()).isEqualTo(fixture.projectId());
            return due;
        });
        assertClaimPersisted("task_execution");
        return claimedDue;
    }

    /** 不加测试外层事务；原service自己绑定可信Due身份、开启DATA事务并最终提交。 */
    private void process(TaskJobRepository.DueExecution due) {
        try { inData(() -> { tasks.processExecution(due); return null; }); }
        finally { processPid.set(0); }
    }

    /** spy安装在最终目标，真实注入代理及@Transactional传播从不被替换。 */
    private void installObservers() {
        assertThat(AopUtils.isAopProxy(tasks)).isTrue();
        TaskJobService taskTarget = AopTestUtils.getUltimateTargetObject(tasks);
        assertThat(mockingDetails(taskTarget).isSpy()).isTrue();
        doAnswer(invocation -> {
            processPid.set(actualAppPid());
            processingObservations.add("EXECUTION:pid=" + processPid.get() + ":rw=true");
            return invocation.callRealMethod();
        }).when(taskTarget).processExecution(any());
        ProjectDailyQuotaDecisionService quotaTarget = AopTestUtils.getUltimateTargetObject(dailyQuota);
        assertThat(AopUtils.isAopProxy(dailyQuota)).isTrue();
        assertThat(mockingDetails(quotaTarget).isSpy()).isTrue();
        doAnswer(invocation -> {
            assertThat((UUID) invocation.getArgument(0)).isEqualTo(fixture.tenantId());
            assertThat((UUID) invocation.getArgument(1)).isEqualTo(fixture.projectId());
            actualAppPid();
            try {
                Object result = invocation.callRealMethod();
                assertThat(result).isEqualTo(expectedQuotaStatus);
                quotaObservations.add(invocation.getArgument(2) + "=" + result);
                return result;
            } catch (RuntimeException failure) {
                quotaObservations.add(invocation.getArgument(2) + "=" + failure.getClass().getSimpleName());
                throw failure;
            }
        }).when(quotaTarget).decideTrustedProject(any(), any(), any());
        DeviceSearchService searchTarget = AopTestUtils.getUltimateTargetObject(deviceSearch);
        doAnswer(invocation -> {
            actualAppPid();
            assertThat(jdbc.queryForObject("SELECT current_setting('app.project_id')", String.class))
                    .isEqualTo(fixture.projectId().toString());
            assertThat((Integer) invocation.getArgument(2)).isEqualTo(200);
            pageCalls.incrementAndGet();
            return invocation.callRealMethod();
        }).when(searchTarget).listTaskTargets(any(), nullable(String.class), anyInt());
        ProjectLifecycleAccessService permitTarget = AopTestUtils.getUltimateTargetObject(lifecycle);
        doAnswer(invocation -> {
            actualAppPid();
            assertThat((UUID) invocation.getArgument(0)).isEqualTo(fixture.tenantId());
            assertThat((UUID) invocation.getArgument(1)).isEqualTo(fixture.projectId());
            permitCalls.incrementAndGet();
            return invocation.callRealMethod();
        }).when(permitTarget).lockActiveForWrite(any(), any());
        DeviceCommandService commandTarget = AopTestUtils.getUltimateTargetObject(commands);
        doAnswer(invocation -> {
            actualAppPid();
            submitCalls.incrementAndGet();
            return invocation.callRealMethod();
        }).when(commandTarget).submitTask(any());
        doAnswer(invocation -> {
            actualAppPid();
            commandReads.incrementAndGet();
            return invocation.callRealMethod();
        }).when(commandTarget).findTaskCommand(any(), any());
    }

    /** 原应用事务必须使用非bypass APP角色，且查询和写入使用同一专库连接。 */
    private int actualAppPid() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo(DATABASE_NAME);
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        assertThat(jdbc.queryForObject("SELECT current_setting('transaction_read_only')", String.class)).isEqualTo("off");
        int pid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
        if (processPid.get() != 0) assertThat(pid).isEqualTo(processPid.get());
        return pid;
    }

    /** 每页提交之后使用独立owner读取真实完整行并核对目标只处于PENDING，不能声称已经下发。 */
    private void assertPage(int targetCount, String status, boolean hasCursor) throws SQLException {
        Map<String, List<String>> facts = facts();
        assertThat(facts.get("task_target")).hasSize(targetCount);
        JsonNode execution = execution(facts);
        assertThat(execution.path("total_targets").asInt()).isEqualTo(targetCount);
        assertThat(execution.path("status").asText()).isEqualTo(status);
        assertThat(execution.path("expansion_cursor").isNull()).isEqualTo(!hasCursor);
        assertThat(execution.path("lease_until").isNull()).isTrue();
        for (String field : List.of("accepted_targets", "succeeded_targets", "failed_targets", "skipped_targets"))
            assertThat(execution.path(field).asInt()).isZero();
        for (String row : facts.get("task_target")) {
            JsonNode target = mapper.readTree(row);
            assertThat(target.path("status").asText()).isEqualTo("PENDING");
            assertThat(target.path("command_id").isNull()).isTrue();
        }
    }

    /** 领取后由独立owner观察lease非空且仍有效，排除同连接未提交可见造成假证明。 */
    private void assertClaimPersisted(String table) throws SQLException {
        try (Connection owner = fixtureOwnerConnection(); PreparedStatement query = owner.prepareStatement(
                "SELECT lease_until IS NOT NULL AND lease_until > now() FROM " + table + " WHERE project_id=?")) {
            query.setQueryTimeout(5);
            query.setObject(1, fixture.projectId());
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getBoolean(1)).isTrue();
                assertThat(rows.next()).isFalse();
            }
        }
    }

    /** 每例只有一个真实execution，完整物理行不能被RLS空投影掩盖。 */
    private JsonNode execution(Map<String, List<String>> facts) {
        assertThat(facts.get("task_execution")).hasSize(1);
        return mapper.readTree(facts.get("task_execution").getFirst());
    }

    /** 专库独立owner读取七张物理表的完整行，避免RLS空投影把有写误判为零。 */
    private Map<String, List<String>> facts() throws SQLException {
        Map<String, List<String>> result = new LinkedHashMap<>();
        try (Connection owner = fixtureOwnerConnection()) {
            for (String table : FACT_TABLES) {
                try (PreparedStatement query = owner.prepareStatement(
                        "SELECT row_to_json(f)::text FROM " + table + " f WHERE project_id=? ORDER BY row_to_json(f)::text")) {
                    query.setQueryTimeout(5);
                    query.setObject(1, fixture.projectId());
                    List<String> values = new ArrayList<>();
                    try (ResultSet rows = query.executeQuery()) { while (rows.next()) values.add(rows.getString(1)); }
                    result.put(table, List.copyOf(values));
                }
            }
        }
        return result;
    }

    /** 真实OWNER删除保留成员和任务历史；归档没有现有业务API，显式SQL仅改已定义status字段。 */
    private void freeze(Freeze freeze) throws SQLException {
        if (freeze == Freeze.DELETE) asOwner(() -> { projects.delete(fixture.projectId()); return null; });
        else try (Connection owner = fixtureOwnerConnection()) {
            execute(owner, "UPDATE sys_project SET status='ARCHIVED' WHERE id=?", fixture.projectId());
        }
        try (Connection owner = fixtureOwnerConnection(); PreparedStatement query = owner.prepareStatement(
                "SELECT status,deleted_at IS NOT NULL FROM sys_project WHERE id=?")) {
            query.setQueryTimeout(5);
            query.setObject(1, fixture.projectId());
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo(freeze == Freeze.DELETE ? "DELETING" : "ARCHIVED");
                assertThat(rows.getBoolean(2)).isEqualTo(freeze == Freeze.DELETE);
            }
        }
    }

    /** HTTP身份仅用于真实create/run/delete；后台入口从无TenantContext的DATA工作线程合同进入。 */
    private <T> T asOwner(Supplier<T> action) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        TenantContext.set(new TenantScope(fixture.tenantId(), fixture.projectId(), fixture.accountId()));
        try { return action.get(); } finally { TenantContext.clear(); }
    }

    /** workload scope不创建事务，原claim使用autocommit，原service则自行启动业务事务。 */
    private <T> T inData(Supplier<T> action) {
        assertThat(TenantContext.current()).isEmpty();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            return action.get();
        } finally { assertThat(TenantContext.current()).isEmpty(); }
    }

    /** 真实表关系与统一合法设备类型，不以task_target无FK为借口制造不存在的设备UUID。 */
    private void seed() throws SQLException {
        try (Connection owner = fixtureOwnerConnection()) {
            owner.setAutoCommit(false);
            execute(owner, "INSERT INTO sys_tenant(id,name) VALUES (?, '任务展开许可验收租户')", fixture.tenantId());
            execute(owner, "INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?, ?, '{noop}unused', '任务验收OWNER')",
                    fixture.accountId(), fixture.accountId() + "@example.com");
            execute(owner, "INSERT INTO sys_tenant_member(id,tenant_id,account_id) VALUES (?, ?, ?)", Uuid7.generate(), fixture.tenantId(), fixture.accountId());
            execute(owner, "INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?, ?, '任务展开许可验收', 'sh-1', ?)",
                    fixture.projectId(), fixture.tenantId(), "task_probe_" + fixture.projectId().toString().replace("-", ""));
            execute(owner, "INSERT INTO sys_project_member(id,project_id,account_id,role) VALUES (?, ?, ?, 'OWNER')",
                    Uuid7.generate(), fixture.projectId(), fixture.accountId());
            execute(owner, "INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,access_protocol,device_kind,status) VALUES (?, ?, ?, 'task_probe_type', '任务验收类型', 'STANDARD', 'DIRECT', 'PUBLISHED')",
                    fixture.typeId(), fixture.tenantId(), fixture.projectId());
            execute(owner, "INSERT INTO dev_command_definition(id,tenant_id,project_id,device_type_id,command_key,name,input_schema,output_schema,timeout_seconds) VALUES (?, ?, ?, ?, 'reboot', '重启', '{}'::jsonb, '{}'::jsonb, 30)",
                    Uuid7.generate(), fixture.tenantId(), fixture.projectId(), fixture.typeId());
            owner.commit();
        }
    }

    /** 每个设备都使用真实合法类型及命令定义，不以无FK目标UUID冒充目标展开。 */
    private void seedDevices(int count) throws SQLException {
        try (Connection owner = fixtureOwnerConnection()) {
            owner.setAutoCommit(false);
            for (int index = 0; index < count; index++) {
                UUID deviceId = Uuid7.generate();
                deviceIds.add(deviceId);
                execute(owner, "INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,status) VALUES (?, ?, ?, ?, ?, '任务验收设备', 'ONLINE')",
                        deviceId, fixture.tenantId(), fixture.projectId(), fixture.typeId(), "task_accept_" + index);
            }
            owner.commit();
        }
    }

    /** 同库同角色逐连接核对；同时确证task事实对象没有变成owner也受WHERE过滤的视图。 */
    private void verifyIsolation() throws SQLException {
        assertThat(DATABASE_URL).isNotEqualTo(POSTGRES.getJdbcUrl());
        for (Object disabled : List.of(unusedRestQuotaRelaxation, unusedTaskScanner, unusedNotificationCoordinator, unusedBackfillScanner, unusedCommandTimeoutScanner))
            assertThat(mockingDetails(disabled).isMock()).isTrue();
        for (DatabaseWorkload workload : DatabaseWorkload.values()) {
            try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(workload)) {
                assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo(DATABASE_NAME);
                assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
                assertThat(jdbc.queryForObject("SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname=current_user", Boolean.class)).isTrue();
            }
        }
        try (Connection migration = DriverManager.getConnection(environment.getRequiredProperty("spring.flyway.url"),
                environment.getRequiredProperty("spring.flyway.user"), environment.getRequiredProperty("spring.flyway.password"));
             Connection owner = fixtureOwnerConnection()) {
            verifyOwnerIdentity(migration);
            verifyOwnerIdentity(owner);
            for (String table : FACT_TABLES) {
                try (PreparedStatement query = owner.prepareStatement("SELECT relkind::text FROM pg_class WHERE oid=?::regclass")) {
                    query.setQueryTimeout(5);
                    query.setString(1, table);
                    try (ResultSet rows = query.executeQuery()) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getString(1)).isIn("r", "p");
                    }
                }
            }
        }
    }

    /** 明确验证Flyway/独立观察均使用专库owner，不能只有相同URL文本。 */
    private void verifyOwnerIdentity(Connection owner) throws SQLException {
        try (PreparedStatement query = owner.prepareStatement("SELECT current_database(),current_user")) {
            query.setQueryTimeout(5);
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo(DATABASE_NAME);
                assertThat(rows.getString(2)).isEqualTo(LIFECYCLE_POSTGRES.getUsername());
            }
        }
    }

    /**
     * 快照取证之后只清理本例四张可变任务夹具，避免下例全局claim领取前例执行。
     * 不删除项目、设备、成员保全或不可变审计，不关闭任何数据库守卫；其余祖先保留至容器回收。
     */
    @AfterEach
    void cleanupOwnTaskFixtures() throws SQLException {
        TenantContext.clear();
        redis.delete(List.of("tc:task:rate:{project:" + fixture.projectId() + "}",
                "tc:task:rate:{tenant:" + fixture.tenantId() + "}"));
        try (Connection owner = fixtureOwnerConnection()) {
            owner.setAutoCommit(false);
            for (String table : List.of("task_target", "task_execution", "task_schedule", "task_job"))
                execute(owner, "DELETE FROM " + table + " WHERE project_id=?", fixture.projectId());
            owner.commit();
        }
    }

    /** 所有独立owner路径落专库，不能继承默认共享数据库连接。 */
    @Override
    protected Connection fixtureOwnerConnection() throws SQLException {
        return DriverManager.getConnection(DATABASE_URL, LIFECYCLE_POSTGRES.getUsername(), LIFECYCLE_POSTGRES.getPassword());
    }

    /** 固定SQL仅接受参数化身份；5秒界限防止未来锁变化令测试无期限卡住。 */
    private void execute(Connection owner, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = owner.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            statement.executeUpdate();
        }
    }

    /** 容器生命周期由本类OwnedTestContainers负责；不关闭其他上下文的共享容器。 */
    private static String startDatabase() { LIFECYCLE_POSTGRES.start(); return LIFECYCLE_POSTGRES.getJdbcUrl(); }

    /** 专库继承原角色、凭证及池预算，仅覆盖地址并禁止本片不验证的自动副作用。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class IsolatedDatabaseConfiguration {
        /** Registrar优先于父DynamicPropertySource；无业务Bean或额度成功替身。 */
        @Bean
        DynamicPropertyRegistrar isolatedDatabaseProperties() {
            return registry -> {
                registry.add("spring.datasource.url", () -> DATABASE_URL);
                registry.add("spring.flyway.url", () -> DATABASE_URL);
                registry.add("things-link.outbox.publisher.enabled", () -> "false");
                registry.add("things-link.notification.retry.enabled", () -> "false");
                registry.add("spring.kafka.listener.auto-startup", () -> "false");
            };
        }
    }

    /** 两种冻结各自独立提交，不能用归档替代真实OWNER删除的状态与成员保全路径。 */
    private enum Freeze {
        /** 真实OWNER删除提交，项目物理行仍保留。 */ DELETE,
        /** 既有status字段的明确归档夹具。 */ ARCHIVED
    }

    /** 独立项目OWNER和合法设备类型，设备UUID只在实际插入时生成。 */
    private record Fixture(UUID tenantId, UUID projectId, UUID accountId, UUID typeId) { }
}
