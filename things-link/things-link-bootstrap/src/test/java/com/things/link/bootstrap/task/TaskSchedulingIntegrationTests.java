package com.things.link.bootstrap.task;

import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.task.application.TaskDispatchRateLimiter;
import com.things.link.task.domain.TaskExecution;
import com.things.link.task.domain.TaskJob;
import com.things.link.task.domain.TaskJobRepository;
import com.things.link.task.domain.TaskTarget;
import com.things.link.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** S7-4 迁移、短租约领取、目标快照、执行终态与 Redis 限速的真实中间件验收。 */
class TaskSchedulingIntegrationTests extends AbstractIntegrationTest {

    /** 任务状态机持久化端口。 */
    @Autowired
    private TaskJobRepository repository;
    /** 真实 PostgreSQL 元数据、RLS 范围和夹具入口。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;
    /** 真实 Redis Lua 双桶。 */
    @Autowired
    private TaskDispatchRateLimiter rateLimiter;

    /** 后台任务测试不得把 ThreadLocal 范围带到其他测试。 */
    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    /** V0130/V0140 必须落下权威时区、四张任务表、目录注释、RLS 和受限领取函数。 */
    @Test
    @Transactional
    void migrationsCreateTaskSchemaWithCommentsRlsAndRestrictedClaimFunctions() {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT column_default
                  FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND table_name = 'sys_project'
                   AND column_name = 'timezone'
                """, String.class)).isEqualTo("'Asia/Shanghai'::character varying");
        assertThat(jdbcTemplate.queryForList("""
                SELECT table_name
                  FROM information_schema.tables
                 WHERE table_schema = 'public'
                   AND table_name IN ('task_job','task_schedule','task_execution','task_target')
                 ORDER BY table_name
                """, String.class)).containsExactly(
                "task_execution", "task_job", "task_schedule", "task_target");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*)
                  FROM information_schema.columns c
                 WHERE c.table_schema = 'public'
                   AND c.table_name LIKE 'task_%'
                   AND col_description((quote_ident(c.table_schema) || '.' || quote_ident(c.table_name))::regclass,
                                       c.ordinal_position) IS NULL
                """, Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForList("""
                SELECT relname
                  FROM pg_class
                 WHERE relname IN ('task_job','task_schedule','task_execution','task_target')
                   AND relrowsecurity
                 ORDER BY relname
                """, String.class)).containsExactly(
                "task_execution", "task_job", "task_schedule", "task_target");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*)
                  FROM information_schema.routine_privileges
                 WHERE routine_schema = 'public'
                   AND routine_name IN ('claim_due_task_schedules','claim_due_task_executions')
                   AND grantee = 'PUBLIC'
                   AND privilege_type = 'EXECUTE'
                """, Integer.class)).isZero();
    }

    /** 同一 fire-at 只能建一个执行；一次任务推进后不再领取，手工运行仍可重复。 */
    @Test
    @Transactional
    void scheduledFireIsIdempotentWhileManualRunsRemainRepeatable() {
        Fixture fixture = seedFixture();
        Instant fireAt = Instant.now().minusSeconds(5);
        TaskJob job = onceJob(fixture, fireAt);
        selectScope(fixture.tenantId(), fixture.projectId());
        repository.create(job);

        TaskJobRepository.DueSchedule due = repository.claimDueSchedules(10).stream()
                .filter(candidate -> candidate.jobId().equals(job.id()))
                .findFirst().orElseThrow();
        assertThat(repository.createScheduledExecution(due, Instant.now())).isPresent();
        assertThat(repository.createScheduledExecution(due, Instant.now())).isEmpty();
        assertThat(repository.advanceSchedule(fixture.projectId(), job.id(), fireAt, null)).isTrue();
        assertThat(repository.claimDueSchedules(10)).noneMatch(candidate -> candidate.jobId().equals(job.id()));

        TaskExecution first = repository.createManualExecution(manualExecution(fixture, job));
        TaskExecution second = repository.createManualExecution(manualExecution(fixture, job));
        assertThat(first.id()).isNotEqualTo(second.id());
        assertThat(repository.findExecutions(fixture.projectId(), job.id())).hasSize(3);
    }

    /** 空设备集必须稳定结束为 FAILED，不能永久停留在 DISPATCHING/RUNNING。 */
    @Test
    @Transactional
    void zeroTargetExecutionReachesFailedTerminalState() {
        Fixture fixture = seedFixture();
        TaskJob job = onceJob(fixture, Instant.now().plusSeconds(60));
        selectScope(fixture.tenantId(), fixture.projectId());
        repository.create(job);
        TaskExecution execution = repository.createManualExecution(manualExecution(fixture, job));

        assertThat(repository.updateExpansion(execution.id(), null, false, 0, Instant.now())).isTrue();
        assertThat(repository.claimPendingTargets(execution.id(), 100)).isEmpty();
        assertThat(repository.markRunningIfNoPending(execution.id(), Instant.now())).isTrue();
        assertThat(repository.completeExecutionIfReady(execution.id(), Instant.now())).isTrue();

        TaskExecution completed = repository.findExecution(fixture.projectId(), execution.id()).orElseThrow();
        assertThat(completed.status()).isEqualTo(TaskExecution.Status.FAILED);
        assertThat(completed.totalTargets()).isZero();
        assertThat(completed.finishedAt()).isNotNull();
    }

    /** 分页写入以 execution/device 去重，并按最终命令投影形成可审计的部分失败汇总。 */
    @Test
    @Transactional
    void targetSnapshotDeduplicatesPagesAndAggregatesFinalCommandStates() {
        Fixture fixture = seedFixture();
        TaskJob job = onceJob(fixture, Instant.now().plusSeconds(60));
        selectScope(fixture.tenantId(), fixture.projectId());
        repository.create(job);
        TaskExecution execution = repository.createManualExecution(manualExecution(fixture, job));
        UUID succeededDevice = UUID.randomUUID();
        UUID failedDevice = UUID.randomUUID();

        assertThat(repository.appendTargets(execution.id(), fixture.tenantId(), fixture.projectId(),
                List.of(succeededDevice, failedDevice))).isEqualTo(2);
        assertThat(repository.appendTargets(execution.id(), fixture.tenantId(), fixture.projectId(),
                List.of(failedDevice))).isZero();
        assertThat(repository.updateExpansion(execution.id(), null, false, 2, Instant.now())).isTrue();
        List<TaskTarget> targets = repository.claimPendingTargets(execution.id(), 100);
        assertThat(targets).extracting(TaskTarget::deviceId)
                .containsExactlyInAnyOrder(succeededDevice, failedDevice);
        assertThat(repository.markTargetAccepted(execution.id(), succeededDevice,
                UUID.randomUUID(), Instant.now())).isTrue();
        assertThat(repository.markTargetAccepted(execution.id(), failedDevice,
                UUID.randomUUID(), Instant.now())).isTrue();
        assertThat(repository.markRunningIfNoPending(execution.id(), Instant.now())).isTrue();
        assertThat(repository.completeTarget(execution.id(), succeededDevice,
                TaskTarget.Status.SUCCEEDED, "SUCCEEDED", Instant.now())).isTrue();
        assertThat(repository.completeTarget(execution.id(), failedDevice,
                TaskTarget.Status.FAILED, "TIMED_OUT", Instant.now())).isTrue();
        assertThat(repository.completeExecutionIfReady(execution.id(), Instant.now())).isTrue();

        TaskExecution completed = repository.findExecution(fixture.projectId(), execution.id()).orElseThrow();
        assertThat(completed.status()).isEqualTo(TaskExecution.Status.PARTIAL_FAILED);
        assertThat(completed.totalTargets()).isEqualTo(2);
        assertThat(completed.acceptedTargets()).isEqualTo(2);
        assertThat(completed.succeededTargets()).isEqualTo(1);
        assertThat(completed.failedTargets()).isEqualTo(1);
    }

    /** app.project_id 必须同时隔离任务定义、调度、执行和目标快照。 */
    @Test
    @Transactional
    void projectRlsHidesEveryTaskTableFromAnotherProject() {
        Fixture first = seedFixture();
        Fixture second = seedFixture();
        TaskJob firstJob = onceJob(first, Instant.now().plusSeconds(60));
        TaskJob secondJob = onceJob(second, Instant.now().plusSeconds(60));
        selectScope(first.tenantId(), first.projectId());
        repository.create(firstJob);
        TaskExecution firstExecution = repository.createManualExecution(manualExecution(first, firstJob));
        repository.appendTargets(firstExecution.id(), first.tenantId(), first.projectId(), List.of(UUID.randomUUID()));
        selectScope(second.tenantId(), second.projectId());
        repository.create(secondJob);
        TaskExecution secondExecution = repository.createManualExecution(manualExecution(second, secondJob));
        repository.appendTargets(secondExecution.id(), second.tenantId(), second.projectId(), List.of(UUID.randomUUID()));

        selectScope(first.tenantId(), first.projectId());
        assertThat(ids("task_job")).containsExactly(firstJob.id());
        assertThat(ids("task_schedule")).hasSize(1);
        assertThat(ids("task_execution")).containsExactly(firstExecution.id());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM task_target", Integer.class)).isEqualTo(1);
        assertThat(repository.findExecution(first.projectId(), secondExecution.id())).isEmpty();
    }

    /** 真实 Redis 对正数执行令牌扣减，对零值立即拒绝；键使用随机 ID 避免测试间共享。 */
    @Test
    void realRedisHonorsPositiveAndZeroTaskDispatchPolicies() {
        UUID tenantId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();

        assertThat(rateLimiter.tryAcquire(tenantId, projectId, 1L, 1L)).isTrue();
        assertThat(rateLimiter.tryAcquire(tenantId, projectId, 1L, 1L)).isFalse();
        assertThat(rateLimiter.tryAcquire(UUID.randomUUID(), UUID.randomUUID(), 100L, 0L)).isFalse();
    }

    /** @return 插入一个不依赖 HTTP 鉴权的最小租户/账号/项目夹具。 */
    private Fixture seedFixture() {
        UUID tenantId = Uuid7.generate();
        UUID accountId = Uuid7.generate();
        UUID projectId = Uuid7.generate();
        jdbcTemplate.update("INSERT INTO sys_tenant (id, name) VALUES (?, ?)", tenantId, "S7-4 tenant");
        jdbcTemplate.update("""
                INSERT INTO sys_account (id, email, password_hash, display_name)
                VALUES (?, ?, '{noop}unused', 'S7-4 account')
                """, accountId, "s7-task-" + accountId + "@example.com");
        jdbcTemplate.update("""
                INSERT INTO sys_project (id, tenant_id, name, region, project_key)
                VALUES (?, ?, 'S7-4 project', 'sh-1', ?)
                """, projectId, tenantId, projectKey(projectId));
        return new Fixture(tenantId, projectId, accountId);
    }

    /** 将当前真实事务连接切到指定租户和项目，验证与后台 scanner 相同的 LOCAL RLS 语义。 */
    private void selectScope(UUID tenantId, UUID projectId) {
        jdbcTemplate.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
        jdbcTemplate.queryForObject("SELECT set_config('app.project_id', ?, true)", String.class, projectId.toString());
    }

    /** @param fixture 数据归属 @param fireAt 一次任务触发点 @return 可持久化任务定义 */
    private static TaskJob onceJob(Fixture fixture, Instant fireAt) {
        Instant now = Instant.now();
        return new TaskJob(Uuid7.generate(), fixture.tenantId(), fixture.projectId(), "S7-4 once", null,
                TaskJob.Status.ACTIVE, 1L, TaskJob.ScheduleType.ONCE, fireAt, null,
                "Asia/Shanghai", fireAt, TaskJob.TargetType.ALL_DEVICES, null,
                "reboot", "{}", fixture.accountId(), now, now);
    }

    /** @param fixture 数据归属 @param job 来源任务 @return 尚未展开的手工执行 */
    private static TaskExecution manualExecution(Fixture fixture, TaskJob job) {
        Instant now = Instant.now();
        return new TaskExecution(Uuid7.generate(), fixture.tenantId(), fixture.projectId(), job.id(),
                TaskExecution.TriggerType.MANUAL, TaskExecution.Status.EXPANDING, null,
                job.targetType(), job.targetGroupId(), job.commandKey(), job.inputJson(), fixture.accountId(),
                0, 0, 0, 0, 0, now, null, null, null);
    }

    /** @param table 已通过固定白名单调用的任务表 @return 当前 RLS 范围可见 ID */
    private Set<UUID> ids(String table) {
        if (!Set.of("task_job", "task_schedule", "task_execution").contains(table)) {
            throw new IllegalArgumentException("测试表不在任务白名单");
        }
        return Set.copyOf(jdbcTemplate.queryForList("SELECT id FROM " + table, UUID.class));
    }

    /** @param projectId 项目 UUID @return 满足 MQTT 项目标识约束的唯一短键 */
    private static String projectKey(UUID projectId) {
        return "s74" + projectId.toString().replace("-", "").substring(0, 16);
    }

    /** 测试数据的计费租户、项目隔离轴与审计账号。 */
    private record Fixture(UUID tenantId, UUID projectId, UUID accountId) {
    }
}
