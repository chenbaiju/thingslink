package com.things.link.bootstrap.telemetry.command;

import com.things.link.telemetry.domain.DeviceCommandAttempt;
import com.things.link.telemetry.domain.DeviceCommandRepository;
import com.things.link.telemetry.domain.DeviceCommandRepository.DueCommand;
import com.things.link.telemetry.infrastructure.persistence.JdbcDeviceCommandRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;

/** ADR0070：真实旧库升级、APP受限领取、独立提交和原仓储最终租约CAS的持久验收。 */
@Testcontainers
class CommandRetryIdentityMigrationTests {
    /** 单独实例避免Flyway运行角色和全局领取受到其他验收污染。 */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb-ha:pg17.4-ts2.18.2").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("command_retry_identity").withUsername("thingslink").withPassword("thingslink");
    /** 全部Bootstrap目录保留真实跨域FK与数据库守卫。 */
    private static final String[] LOCATIONS = {"classpath:db/migration/support", "classpath:db/migration/project",
            "classpath:db/migration/device", "classpath:db/migration/telemetry", "classpath:db/migration/alarm",
            "classpath:db/migration/task", "classpath:db/migration/rule", "classpath:db/migration/iam", "classpath:db/migration/enduser", "classpath:db/migration/dashboard"};
    /** 实际运行角色，禁止owner代替仓储授权。 */
    private static final String APP_ROLE = "thingslink_app";
    /** 测试专库密码只用于Flyway角色占位符。 */
    private static final String APP_PASSWORD = "thingslink";
    /** 原仓储由真正Spring事务代理装配，验证REQUIRES_NEW而非测试手写替代事务。 */
    private DeviceCommandRepository repository;
    /** 与原仓储共享APP连接源。 */
    private JdbcTemplate jdbc;
    /** 测试只为仓储本身补足原Service负责的业务事务和RLS，不包裹被测claim独立事务。 */
    private TransactionTemplate transactions;
    /** 全行JSON比较不依赖属性打印顺序。 */
    private final ObjectMapper mapper = new ObjectMapper();

    /** 一个顺序流程限定迁移版本，覆盖真实租约/权限/失败回滚，不依赖测试方法执行顺序。 */
    @Test
    void upgradesWithoutRewritingAndRequiresCurrentClaimIdentity() throws Exception {
        flyway("20260904.0210").migrate();
        Fixture first = seedParents();
        Fixture second = seedParents();
        UUID published = seedCommand(first, "DISPATCHED", "PUBLISHED", 1, true);
        UUID failed = seedCommand(first, "ACCEPTED", "FAILED", 1, true);
        UUID foreign = seedCommand(second, "ACKNOWLEDGED", "ACKNOWLEDGED", 3, true);
        seedCommand(first, "DISPATCHED", "PUBLISHED", 1, false);
        UUID pending = seedCommand(first, "ACCEPTED", "PENDING", 1, true);
        seedCommand(first, "SUCCEEDED", "SUCCEEDED", 1, true);
        List<String> legacy = businessFacts();
        assertThat(flyway("20260904.0220").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(businessFacts()).as("新增领取字段不改旧业务调度或历史尝试").isEqualTo(legacy);
        assertCatalog();
        // 先验证历史迁移不改事实，再升级当前版本运行当前仓储；不可用新Java调用旧函数形状。
        flyway("20260919.0200").migrate();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(RepositoryConfiguration.class)) {
            repository = context.getBean(DeviceCommandRepository.class);
            jdbc = context.getBean(JdbcTemplate.class);
            transactions = new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
            assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
            assertThat(repository.oldestPendingAgeSeconds()).isGreaterThanOrEqualTo(120);
            assertThat(jdbc.queryForObject("SELECT prosecdef FROM pg_proc WHERE proname='device_command_oldest_pending_age'", Boolean.class)).isTrue();
            assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("command_retry_identity");
            assertThatThrownBy(() -> repository.claimDue(0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> repository.claimDue(101)).isInstanceOf(IllegalArgumentException.class);
            // 外层回滚不能撤销真实claim；外层只读不持候选行锁，避免违反ADR0070调用前提。
            List<DueCommand> claimed = transactions.execute(status -> {
                List<DueCommand> result = repository.claimDue(100);
                status.setRollbackOnly();
                return result;
            });
            assertThat(claimed).hasSize(4).extracting(DueCommand::commandId).containsExactlyInAnyOrder(published, failed, foreign, pending);
            assertThat(businessFacts()).as("领取只写内部token/lease，不改next_attempt_at或updated_at").isEqualTo(legacy);
            assertThat(repository.claimDue(100)).isEmpty();
            DueCommand publishedClaim = find(claimed, published);
            assertWrongIdentityRejected(publishedClaim);
            assertConsumeRollbackAndSuccess(publishedClaim);
            retire(published);
            assertExpiredAndReplacedToken(find(claimed, failed));
            retire(failed);
            assertThat(find(claimed, foreign).expectedAttempt()).isEqualTo(3);
            assertThat(consume(find(claimed, foreign))).isTrue();
            retire(foreign);
            DueCommand pendingClaim = find(claimed, pending);
            assertThat(pendingClaim.claimKind()).isEqualTo(DeviceCommandRepository.ClaimKind.PUSH_PENDING_TIMEOUT);
            assertWrongIdentityRejected(pendingClaim);
            try (Connection owner = owner()) {
                execute(owner, "UPDATE ts_device_command SET status='DISPATCHED' WHERE id=?", pending);
                execute(owner, "UPDATE ts_device_command_attempt SET status='PUBLISHED' WHERE command_id=?", pending);
            }
            assertThat(consume(pendingClaim)).as("分支变化不能消费旧PENDING领取").isFalse();
            retire(pending);
            assertSkipLocked(first);
            assertLeaseExpiryWhileWaiting(first);
            assertFreezeAndParentCasRollback(first);
        }
        List<String> completed = businessFacts();
        assertThat(flyway("20260904.0220").migrate().migrationsExecuted).isZero();
        assertThat(businessFacts()).isEqualTo(completed);
    }

    /** 完整三元组、代次及attempt缺一都不能消费，即使APP当前项目恰好相同。 */
    private void assertWrongIdentityRejected(DueCommand claim) throws SQLException {
        String before = row(claim.commandId());
        for (DueCommand wrong : List.of(
                new DueCommand(UUID.randomUUID(), claim.projectId(), claim.commandId(), claim.retryToken(), claim.expectedAttempt(), claim.claimKind()),
                new DueCommand(claim.tenantId(), UUID.randomUUID(), claim.commandId(), claim.retryToken(), claim.expectedAttempt(), claim.claimKind()),
                new DueCommand(claim.tenantId(), claim.projectId(), UUID.randomUUID(), claim.retryToken(), claim.expectedAttempt(), claim.claimKind()),
                new DueCommand(claim.tenantId(), claim.projectId(), claim.commandId(), UUID.randomUUID(), claim.expectedAttempt(), claim.claimKind()),
                new DueCommand(claim.tenantId(), claim.projectId(), claim.commandId(), claim.retryToken(), claim.expectedAttempt() + 1, claim.claimKind()),
                new DueCommand(claim.tenantId(), claim.projectId(), claim.commandId(), claim.retryToken(), claim.expectedAttempt(), null),
                new DueCommand(claim.tenantId(), claim.projectId(), claim.commandId(), claim.retryToken(), claim.expectedAttempt(), DeviceCommandRepository.ClaimKind.PULL_FINAL_TIMEOUT),
                new DueCommand(claim.tenantId(), claim.projectId(), claim.commandId(), null, claim.expectedAttempt(), claim.claimKind()))) {
            assertThat(inProject(claim.projectId(), () -> repository.consumeRetryClaim(wrong))).isFalse();
        }
        assertThat(inProject(UUID.randomUUID(), () -> repository.findByCommandId(claim.projectId(), claim.commandId()))).isEmpty();
        assertThat(inProject(claim.projectId(), () -> repository.lockByIdentity(UUID.randomUUID(), claim.projectId(), claim.commandId()))).isEmpty();
        assertThat(row(claim.commandId())).isEqualTo(before);
    }

    /** 消费及后续业务属于同事务；中途失败回滚后原有效token仍可重试，成功只能消费一次。 */
    private void assertConsumeRollbackAndSuccess(DueCommand claim) throws SQLException {
        String before = row(claim.commandId());
        assertThatThrownBy(() -> inProject(claim.projectId(), () -> {
            assertThat(repository.lockByIdentity(claim.tenantId(), claim.projectId(), claim.commandId())).isPresent();
            assertThat(repository.consumeRetryClaim(claim)).isTrue();
            throw new IllegalStateException("注入后续业务失败");
        })).isInstanceOf(IllegalStateException.class).hasMessage("注入后续业务失败");
        assertThat(row(claim.commandId())).isEqualTo(before);
        assertThat(consume(claim)).isTrue();
        assertThat(consume(claim)).isFalse();
        try (Connection owner = owner()) {
            assertThat(text(owner, "SELECT (retry_token IS NULL AND retry_leased_until IS NULL)::text FROM ts_device_command WHERE id=?", claim.commandId())).isEqualTo("true");
        }
    }

    /** 真实过期后重新领取产生新代次，旧token及未到业务退避的状态都不能绕过最终CAS。 */
    private void assertExpiredAndReplacedToken(DueCommand old) throws SQLException {
        try (Connection owner = owner()) {
            execute(owner, "UPDATE ts_device_command SET retry_leased_until=clock_timestamp()-interval '1 second' WHERE id=?", old.commandId());
        }
        assertThat(consume(old)).isFalse();
        DueCommand renewed = find(repository.claimDue(100), old.commandId());
        assertThat(renewed.retryToken()).isNotEqualTo(old.retryToken());
        assertThat(consume(old)).isFalse();
        try (Connection owner = owner()) {
            execute(owner, "UPDATE ts_device_command SET next_attempt_at=clock_timestamp()+interval '1 hour' WHERE id=?", old.commandId());
        }
        String waiting = row(old.commandId());
        assertThat(consume(renewed)).isFalse();
        assertThat(row(old.commandId())).isEqualTo(waiting);
        try (Connection owner = owner()) {
            execute(owner, "UPDATE ts_device_command SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE id=?", old.commandId());
        }
        assertThat(consume(renewed)).isTrue();
    }

    /** 真实候选行锁被跳过，其余到期命令仍可领取；锁释放后候选没有丢失。 */
    private void assertSkipLocked(Fixture fixture) throws SQLException {
        UUID locked = seedCommand(fixture, "ACCEPTED", "FAILED", 1, true);
        UUID free = seedCommand(fixture, "DISPATCHED", "PUBLISHED", 1, true);
        try (Connection holder = owner()) {
            holder.setAutoCommit(false);
            text(holder, "SELECT id::text FROM ts_device_command WHERE id=? FOR UPDATE", locked);
            List<DueCommand> claim = repository.claimDue(100);
            assertThat(claim).extracting(DueCommand::commandId).containsExactly(free);
            holder.commit();
            assertThat(repository.claimDue(100)).extracting(DueCommand::commandId).containsExactly(locked);
        }
        retire(locked); retire(free);
    }

    /** 项目锁等待期间自然过期，最终consume不能依赖锁前只读有效或旧事务now()。 */
    private void assertLeaseExpiryWhileWaiting(Fixture fixture) throws Exception {
        UUID id = seedCommand(fixture, "DISPATCHED", "PUBLISHED", 1, true);
        DueCommand claim = find(repository.claimDue(100), id);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CompletableFuture<Integer> checked = new CompletableFuture<>();
        try (Connection holder = owner(); Connection observer = owner()) {
            holder.setAutoCommit(false);
            execute(holder, "UPDATE sys_project SET status=status WHERE id=?", fixture.projectId());
            int holderPid = Integer.parseInt(text(holder, "SELECT pg_backend_pid()::text"));
            Future<Boolean> consumed = worker.submit(() -> inProject(fixture.projectId(), () -> {
                assertThat(jdbc.queryForObject("SELECT retry_leased_until>clock_timestamp() FROM ts_device_command WHERE id=?", Boolean.class, id)).isTrue();
                checked.complete(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                jdbc.queryForObject("SELECT id FROM sys_project WHERE id=? FOR SHARE", UUID.class, fixture.projectId());
                assertThat(repository.lockByIdentity(fixture.tenantId(), fixture.projectId(), id)).isPresent();
                return repository.consumeRetryClaim(claim);
            }));
            int workerPid = checked.get(5, TimeUnit.SECONDS);
            await().atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(20)).untilAsserted(() ->
                    assertThat(text(observer, "SELECT (?=ANY(pg_blocking_pids(?)))::text", holderPid, workerPid)).isEqualTo("true"));
            execute(observer, "UPDATE ts_device_command SET retry_leased_until=clock_timestamp()+interval '1 second' WHERE id=?", id);
            String before = row(id);
            await().atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(20)).untilAsserted(() ->
                    assertThat(text(observer, "SELECT (retry_leased_until<=clock_timestamp())::text FROM ts_device_command WHERE id=?", id)).isEqualTo("true"));
            holder.commit();
            assertThat(consumed.get(5, TimeUnit.SECONDS)).isFalse();
            assertThat(row(id)).isEqualTo(before);
        } finally {
            worker.shutdown();
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) { worker.shutdownNow(); assertThat(worker.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
        }
        retire(id);
    }

    /** 冻结维护保留FAILED历史；错误计数不能先插新attempt，部分写失败必须回滚父事实。 */
    private void assertFreezeAndParentCasRollback(Fixture fixture) throws SQLException {
        for (String status : List.of("PENDING", "FAILED", "PUBLISHED", "ACKNOWLEDGED")) {
            String parent = status.equals("PUBLISHED") ? "DISPATCHED" : status.equals("ACKNOWLEDGED") ? "ACKNOWLEDGED" : "ACCEPTED";
            UUID id = seedCommand(fixture, parent, status, 1, true);
            String attemptBefore;
            try (Connection owner = owner()) { attemptBefore = text(owner, "SELECT row_to_json(a)::text FROM ts_device_command_attempt a WHERE command_id=?", id); }
            DueCommand claim = status.equals("PENDING") ? null : find(repository.claimDue(100), id);
            assertThat(inProject(fixture.projectId(), () -> {
                repository.lockByIdentity(fixture.tenantId(), fixture.projectId(), id).orElseThrow();
                if (claim != null) assertThat(repository.consumeRetryClaim(claim)).isTrue();
                return repository.stopForProjectFreeze(fixture.tenantId(), fixture.projectId(), id, 1, Instant.now());
            })).isTrue();
            assertThat(inProject(fixture.projectId(), () -> repository.stopForProjectFreeze(fixture.tenantId(), fixture.projectId(), id, 1, Instant.now()))).isFalse();
            try (Connection owner = owner()) {
                assertThat(text(owner, "SELECT status||':'||failure_code FROM ts_device_command WHERE id=?", id)).isEqualTo("FAILED:PROJECT_FROZEN");
                assertThat(text(owner, "SELECT (retry_token IS NULL AND retry_leased_until IS NULL AND next_attempt_at IS NULL AND deadline_at IS NULL)::text FROM ts_device_command WHERE id=?", id)).isEqualTo("true");
                if (status.equals("FAILED")) assertThat(text(owner, "SELECT row_to_json(a)::text FROM ts_device_command_attempt a WHERE command_id=?", id)).isEqualTo(attemptBefore);
                else assertThat(text(owner, "SELECT status||':'||error_code FROM ts_device_command_attempt WHERE command_id=?", id))
                        .isEqualTo(status.equals("PENDING") ? "FAILED:PROJECT_FROZEN" : "TIMED_OUT:RESPONSE_TIMEOUT");
            }
        }
        UUID id = seedCommand(fixture, "ACCEPTED", "PENDING", 1, true);
        String before = row(id);
        DeviceCommandAttempt wrong = new DeviceCommandAttempt(UUID.randomUUID(), fixture.tenantId(), fixture.projectId(), id,
                3, UUID.randomUUID(), fixture.deviceId(), "tc/test", DeviceCommandAttempt.Status.PENDING,
                Instant.now().plusSeconds(30), Instant.now());
        assertThatThrownBy(() -> inProject(fixture.projectId(), () -> { repository.createAttempt(wrong); return null; }))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("父状态CAS失败");
        assertThat(row(id)).isEqualTo(before);
        // 合法父计数先推进后，复用主键使INSERT真实报错；父更新也必须随原事务回滚。
        UUID existingAttempt;
        try (Connection owner = owner()) { existingAttempt = UUID.fromString(text(owner, "SELECT id::text FROM ts_device_command_attempt WHERE command_id=?", id)); }
        DeviceCommandAttempt duplicate = new DeviceCommandAttempt(existingAttempt, fixture.tenantId(), fixture.projectId(), id,
                2, UUID.randomUUID(), fixture.deviceId(), "tc/test", DeviceCommandAttempt.Status.PENDING,
                Instant.now().plusSeconds(30), Instant.now());
        assertThatThrownBy(() -> inProject(fixture.projectId(), () -> { repository.createAttempt(duplicate); return null; }))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(row(id)).isEqualTo(before);
        try (Connection owner = owner()) { assertThat(text(owner, "SELECT count(*)::text FROM ts_device_command_attempt WHERE command_id=?", id)).isEqualTo("1"); }
    }

    /** 受限函数形状、固定搜索路径、PUBLIC撤权与APP角色均来自真实PG目录。 */
    private void assertCatalog() throws SQLException {
        try (Connection owner = owner()) {
            assertThat(text(owner, "SELECT proconfig::text FROM pg_proc WHERE oid='claim_due_device_commands(integer)'::regprocedure")).contains("search_path=pg_catalog, public");
            assertThat(text(owner, "SELECT prosecdef::text FROM pg_proc WHERE oid='claim_due_device_commands(integer)'::regprocedure")).isEqualTo("true");
            assertThat(text(owner, "SELECT pg_get_function_result('claim_due_device_commands(integer)'::regprocedure)")).contains("retry_token uuid", "expected_attempt integer");
            assertThat(text(owner, "SELECT has_function_privilege('thingslink_app','claim_due_device_commands(integer)','EXECUTE')::text")).isEqualTo("true");
            assertThat(text(owner, "SELECT count(*)::text FROM pg_proc p,LATERAL aclexplode(coalesce(p.proacl,acldefault('f',p.proowner))) a WHERE p.oid='claim_due_device_commands(integer)'::regprocedure AND a.grantee=0 AND a.privilege_type='EXECUTE'")).isEqualTo("0");
            for (String column : List.of("retry_token", "retry_leased_until")) assertThat(text(owner,
                    "SELECT col_description('ts_device_command'::regclass,attnum) FROM pg_attribute WHERE attrelid='ts_device_command'::regclass AND attname=?", column)).isNotBlank();
        }
        try (Connection app = DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_ROLE, APP_PASSWORD)) {
            assertThat(text(app, "SELECT (NOT rolsuper AND NOT rolbypassrls)::text FROM pg_roles WHERE rolname=current_user")).isEqualTo("true");
            for (Integer invalid : new Integer[]{null, 0, 101}) {
                Throwable failure = catchThrowable(() -> text(app, "SELECT count(*)::text FROM claim_due_device_commands(?)", invalid));
                assertThat(failure).isInstanceOf(SQLException.class);
                assertThat(((SQLException) failure).getSQLState()).isEqualTo("22023");
            }
        }
    }

    /** 原仓储方法参与真实APP事务并持命令锁，成功清租约提交，失败保留。 */
    private boolean consume(DueCommand claim) {
        return inProject(claim.projectId(), () -> {
            repository.lockByIdentity(claim.tenantId(), claim.projectId(), claim.commandId());
            return repository.consumeRetryClaim(claim);
        });
    }

    /** 恢复事务局部RLS，禁止owner绕过仓储授权。 */
    private <T> T inProject(UUID projectId, Supplier<T> work) {
        return transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.project_id',?,true)", String.class, projectId.toString());
            return work.get();
        });
    }

    /** 固定返回身份从真实claim中解析，不手造正例token。 */
    private static DueCommand find(List<DueCommand> candidates, UUID id) {
        return candidates.stream().filter(candidate -> candidate.commandId().equals(id)).findFirst().orElseThrow();
    }

    /** 正式业务未运行的SQL升级夹具，完整合法父表关系满足原约束。 */
    private Fixture seedParents() throws SQLException {
        Fixture f = new Fixture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        try (Connection c = owner()) {
            c.setAutoCommit(false);
            execute(c, "INSERT INTO sys_tenant(id,name) VALUES (?, '命令升级租户')", f.tenantId());
            execute(c, "INSERT INTO sys_account(id,email,password_hash,display_name) VALUES (?, ?, '{noop}unused','命令升级OWNER')", f.accountId(), f.accountId()+"@example.com");
            execute(c, "INSERT INTO sys_project(id,tenant_id,name,region,project_key) VALUES (?, ?, '命令升级项目','sh-1',?)", f.projectId(), f.tenantId(), "cmd_"+f.projectId().toString().replace("-", ""));
            execute(c, "INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,access_protocol,device_kind,status) VALUES (?, ?, ?, 'cmd_type','命令类型','STANDARD','DIRECT','PUBLISHED')", f.typeId(), f.tenantId(), f.projectId());
            execute(c, "INSERT INTO dev_command_definition(id,tenant_id,project_id,device_type_id,command_key,name,input_schema,output_schema,timeout_seconds) VALUES (?, ?, ?, ?, 'reboot','重启','{}','{}',30)", f.definitionId(), f.tenantId(), f.projectId(), f.typeId());
            execute(c, "INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,status) VALUES (?, ?, ?, ?, 'cmd_device','命令设备','ONLINE')", f.deviceId(), f.tenantId(), f.projectId(), f.typeId());
            c.commit();
        }
        return f;
    }

    /** 仅生成旧版本合法数据库事实，不伪称已经实际Broker发送；完整历史attempt持续保留。 */
    private UUID seedCommand(Fixture f, String status, String attemptStatus, int count, boolean due) throws SQLException {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        Instant deadline = due ? now.minusSeconds(60) : now.plusSeconds(3600);
        try (Connection c = owner()) {
            c.setAutoCommit(false);
            execute(c, "INSERT INTO ts_device_command(id,tenant_id,project_id,target_device_id,connection_device_id,command_definition_id,command_key,input_schema,output_schema,request_payload,status,idempotency_key,requested_by,timeout_seconds,attempt_count,max_attempts,next_attempt_at,deadline_at,trace_id,accepted_at) VALUES (?,?,?,?,?,?,'reboot','{}','{}','{}',?,?,?,30,?,3,?,?,'command-upgrade',?)",
                    id, f.tenantId(), f.projectId(), f.deviceId(), f.deviceId(), f.definitionId(), status, id.toString(), f.accountId(), count,
                    Timestamp.from(deadline), attemptStatus.equals("FAILED") ? null : Timestamp.from(deadline), Timestamp.from(now.minusSeconds(120)));
            for (int attempt = 1; attempt <= count; attempt++) execute(c,
                    "INSERT INTO ts_device_command_attempt(id,tenant_id,project_id,command_id,attempt_no,outbox_event_id,connection_device_id,topic,status,deadline_at,created_at,error_code,error_message,completed_at) VALUES (?,?,?,?,?,?,?,'tc/test',?,?,?,'DISPATCH_CONNECTION_FAILED','保留原诊断',?)",
                    UUID.randomUUID(), f.tenantId(), f.projectId(), id, attempt, UUID.randomUUID(), f.deviceId(),
                    attempt == count ? attemptStatus : "TIMED_OUT", Timestamp.from(deadline), Timestamp.from(now.minusSeconds(120)),
                    attemptStatus.equals("FAILED") || attempt < count ? Timestamp.from(now.minusSeconds(90)) : null);
            c.commit();
        }
        return id;
    }

    /** 完成一段SQL验收后只终结本例候选，防止后续全局claim误领；祖先及历史attempt不删除。 */
    private void retire(UUID id) throws SQLException {
        try (Connection c = owner()) { execute(c, "UPDATE ts_device_command SET status='FAILED',next_attempt_at=NULL,retry_token=NULL,retry_leased_until=NULL WHERE id=?", id); }
    }

    /** 所有旧业务字段以及全部attempt逐行精确比较；新增内部租约字段单独断言。 */
    private List<String> businessFacts() throws SQLException {
        List<String> result = new ArrayList<>();
        try (Connection c = owner()) {
            for (String table : List.of("ts_device_command", "ts_device_command_attempt")) {
                try (PreparedStatement q = c.prepareStatement("SELECT (to_jsonb(t)-'retry_token'-'retry_leased_until')::text FROM " + table + " t ORDER BY id")) {
                    q.setQueryTimeout(5);
                    try (ResultSet rows = q.executeQuery()) { while (rows.next()) result.add(mapper.readTree(rows.getString(1)).toString()); }
                }
            }
        }
        return result;
    }

    /** 包含token/lease的当前父命令全行，拒绝路径不能写任何字段。 */
    private String row(UUID id) throws SQLException { try (Connection c = owner()) { return text(c, "SELECT row_to_json(c)::text FROM ts_device_command c WHERE id=?", id); } }
    /** 指定升级候选版本，旧文件由Flyway校验，不修改任何已有迁移。 */
    private Flyway flyway(String version) { return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).locations(LOCATIONS).placeholders(Map.of("app_role_password", APP_PASSWORD)).target(version).load(); }
    /** 只有夹具准备和独立证据使用owner。 */
    private Connection owner() throws SQLException { return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()); }
    /** 有界参数化写SQL，不关闭原约束或守卫。 */
    private void execute(Connection c, String sql, Object... values) throws SQLException { try (PreparedStatement q=c.prepareStatement(sql)) { q.setQueryTimeout(5); for (int i=0;i<values.length;i++) q.setObject(i+1,values[i]); q.executeUpdate(); } }
    /** 有界参数化单行观察。 */
    private String text(Connection c, String sql, Object... values) throws SQLException { try (PreparedStatement q=c.prepareStatement(sql)) { q.setQueryTimeout(5); for (int i=0;i<values.length;i++) q.setObject(i+1,values[i]); try (ResultSet r=q.executeQuery()) { assertThat(r.next()).isTrue(); return r.getString(1); } } }

    /** 只装配真实仓储、APP数据源和标准事务拦截器，避免Bootstrap后台组件争用全局候选。 */
    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class RepositoryConfiguration {
        /** 单独APP数据源与Flyway实例同库，RLS权限真实生效。 */
        @Bean DataSource dataSource() { return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), APP_ROLE, APP_PASSWORD); }
        /** JDBC查询统一五秒预算，锁等待不得靠扩大超时取得通过。 */
        @Bean JdbcTemplate jdbcTemplate(DataSource source) { JdbcTemplate jdbc = new JdbcTemplate(source); jdbc.setQueryTimeout(5); return jdbc; }
        /** 原Spring事务注解直接控制claim独立提交及业务回滚。 */
        @Bean DataSourceTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
        /** 真实实现由Spring代理，不用自制claim事务假装REQUIRES_NEW生效。 */
        @Bean DeviceCommandRepository repository(JdbcTemplate jdbc) { return new JdbcDeviceCommandRepository(jdbc); }
    }

    /** 旧版本合法父表关系的不可变身份。 */
    private record Fixture(UUID tenantId, UUID projectId, UUID accountId, UUID typeId, UUID deviceId, UUID definitionId) { }
}
