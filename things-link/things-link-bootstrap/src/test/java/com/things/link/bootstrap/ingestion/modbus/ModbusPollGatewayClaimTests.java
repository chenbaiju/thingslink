package com.things.link.bootstrap.ingestion.modbus;

import com.things.link.device.domain.ModbusPoll;
import com.things.link.device.infrastructure.persistence.JdbcModbusPollRepository;
import com.things.link.shared.tenant.RlsScopeContext;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.tenant.DatabaseWorkload;
import com.things.link.support.tenant.DatabaseWorkloadContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * S12-P0-4c / D-117：行锁不能代替网关串行契约，使用真实独立 APP 事务验证公共领取入口。
 * 点位经配置服务物化，网关设为在线；此处不借助内部锁键或模拟结果证明互斥。
 */
@DisplayName("Modbus 网关领取串行与跨网关并行")
class ModbusPollGatewayClaimTests extends AbstractModbusPollIntegrationTest {

    /** 注入 Spring 事务代理，以便同时验证调用前置及真实 PostgreSQL SQL。 */
    @Autowired private JdbcModbusPollRepository pollRepository;

    /** 同批多个到期点只领取一个，在途期间再次扫描不得领取；完成后才让下一点进入。 */
    @Test
    void oneBatchClaimsOnePointPerGatewayAndCompletionAllowsTheNextPoint() throws Exception {
        Fixture fixture = onlineSchedule(2);
        List<ModbusPoll> first = dataTransaction(status -> pollRepository.claimDue(100));
        assertSingleGateway(first, fixture);
        List<ModbusPoll> whileInFlight = dataTransaction(status -> pollRepository.claimDue(100));
        assertThat(whileInFlight).isEmpty();
        boolean completed = dataTransaction(status -> pollRepository.complete(first.getFirst().id(), Instant.now().plusSeconds(600)));
        assertThat(completed).isTrue();
        List<ModbusPoll> next = dataTransaction(status -> pollRepository.claimDue(100));
        assertSingleGateway(next, fixture);
        assertThat(next.getFirst().id()).isNotEqualTo(first.getFirst().id());
    }

    /** A 未提交时 B 的独立连接不能跳过第一行锁后领取同网关第二点。 */
    @Test
    void uncommittedClaimExcludesAnotherTransactionOnTheSameGateway() throws Exception {
        Fixture fixture = onlineSchedule(2);
        withWorker(executor -> dataTransaction(status -> {
            assertSingleGateway(pollRepository.claimDue(1), fixture);
            int firstBackend = backendId();
            Future<List<ModbusPoll>> second = executor.submit(() -> dataTransaction(other -> {
                assertThat(backendId()).isNotEqualTo(firstBackend);
                return pollRepository.claimDue(100);
            }));
            List<ModbusPoll> whileUncommitted = await(second);
            assertThat(whileUncommitted).isEmpty();
            return null;
        }));
        List<ModbusPoll> afterCommit = dataTransaction(status -> pollRepository.claimDue(100));
        assertThat(afterCommit).isEmpty();
    }

    /** A 锁住网关一时 B 必须及时领取网关二，不能通过全局串行锁伪造同网关互斥。 */
    @Test
    void anotherGatewayRemainsClaimableWhileTheFirstTransactionIsOpen() throws Exception {
        Fixture first = onlineSchedule(2);
        Fixture second = onlineSchedule(1);
        try (Connection owner = ownerConnection()) {
            assertThat(execute(owner, "UPDATE dev_modbus_poll SET next_poll_at = now() - interval '2 minutes' WHERE project_id = ?",
                    first.projectId())).isEqualTo(2);
            assertThat(execute(owner, "UPDATE dev_modbus_poll SET next_poll_at = now() - interval '1 minute' WHERE project_id = ?",
                    second.projectId())).isEqualTo(1);
        }
        withWorker(executor -> dataTransaction(status -> {
            assertSingleGateway(pollRepository.claimDue(1), first);
            int firstBackend = backendId();
            Future<List<ModbusPoll>> otherGateway = executor.submit(() -> dataTransaction(other -> {
                assertThat(backendId()).isNotEqualTo(firstBackend);
                return pollRepository.claimDue(100);
            }));
            assertSingleGateway(await(otherGateway), second);
            return null;
        }));
    }

    /** 回滚必须恢复全部 SQL 字段，并允许新事务领取同一原始点位，不能留下网关占用。 */
    @Test
    void rollbackRestoresTheOriginalRowsAndAllowsTheSamePointToBeClaimed() throws Exception {
        Fixture fixture = onlineSchedule(2);
        List<String> before = snapshot(fixture);
        assertThat(before).hasSize(2);
        UUID rolledBackPoint = dataTransaction(status -> {
            List<ModbusPoll> claimed = pollRepository.claimDue(1);
            assertSingleGateway(claimed, fixture);
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM dev_modbus_poll WHERE project_id = ? AND status = 'IN_FLIGHT' AND lease_until > now()",
                    Integer.class, fixture.projectId())).isEqualTo(1);
            status.setRollbackOnly();
            return claimed.getFirst().id();
        });
        assertThat(snapshot(fixture)).isEqualTo(before);
        List<ModbusPoll> reclaimed = dataTransaction(status -> pollRepository.claimDue(100));
        assertSingleGateway(reclaimed, fixture);
        assertThat(reclaimed.getFirst().id()).isEqualTo(rolledBackPoint);
    }

    /** 事务级互斥只有在调用方持有事务时成立，自动提交调用须在写入前失败。 */
    @Test
    void missingTransactionIsRejectedBeforeAnyPollMutation() throws Exception {
        Fixture fixture = onlineSchedule(2);
        List<String> before = snapshot(fixture);
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            assertThat(catchThrowable(() -> pollRepository.claimDue(100)))
                    .isInstanceOf(IllegalTransactionStateException.class);
        }
        assertThat(snapshot(fixture)).isEqualTo(before);
    }

    /** 可重复读会保留锁前快照，拒绝它以免锁后重新查询仍漏掉已提交在途点。 */
    @Test
    void repeatableReadIsRejectedBeforeAnyPollMutation() throws Exception {
        Fixture fixture = onlineSchedule(2);
        List<String> before = snapshot(fixture);
        Throwable failure = catchThrowable(() -> dataTransaction(TransactionDefinition.ISOLATION_REPEATABLE_READ,
                status -> pollRepository.claimDue(100)));
        // @Repository异常翻译保留拒绝根因；通过真实代理调用时外层是数据访问API误用。
        assertThat(failure).isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("要求READ COMMITTED或READ UNCOMMITTED事务");
        assertThat(snapshot(fixture)).isEqualTo(before);
    }

    /** PostgreSQL 的 READ UNCOMMITTED 等价 READ COMMITTED，不应被错误排除。 */
    @Test
    void postgresReadUncommittedKeepsTheSameGatewayLimit() throws Exception {
        Fixture fixture = onlineSchedule(2);
        List<ModbusPoll> claimed = dataTransaction(TransactionDefinition.ISOLATION_READ_UNCOMMITTED,
                status -> pollRepository.claimDue(100));
        assertSingleGateway(claimed, fixture);
    }

    /** 线上状态是未来离线守卫的合法前置，所有轮询行仍通过真实控制面配置下发生成。 */
    private Fixture onlineSchedule(int pointCount) throws SQLException {
        Fixture fixture = fixture(points(pointCount));
        try (Connection owner = ownerConnection()) {
            assertThat(execute(owner, "UPDATE dev_device SET status = 'ONLINE' WHERE id = ?", fixture.gatewayId())).isEqualTo(1);
        }
        pushConfig(fixture);
        return fixture;
    }

    /** 同时比较租户、项目、网关及子设备，跨网关返回数量相同也不能蒙混通过。 */
    private void assertSingleGateway(List<ModbusPoll> claimed, Fixture fixture) {
        assertThat(claimed).hasSize(1);
        ModbusPoll poll = claimed.getFirst();
        assertThat(poll.tenantId()).isEqualTo(fixture.tenantId());
        assertThat(poll.projectId()).isEqualTo(fixture.projectId());
        assertThat(poll.deviceId()).isEqualTo(fixture.gatewayId());
        assertThat(poll.subDeviceId()).isEqualTo(fixture.subDeviceId());
        assertThat(poll.status()).isEqualTo(ModbusPoll.Status.IN_FLIGHT);
        assertThat(poll.leaseUntil()).isNotNull();
    }

    /** 默认隔离级别固定为生产使用的逐语句新快照，避免环境默认值改变测试含义。 */
    private <T> T dataTransaction(Function<TransactionStatus, T> action) {
        return dataTransaction(TransactionDefinition.ISOLATION_READ_COMMITTED, action);
    }

    /** 每个线程在借出连接前独立设置 DATA 路由，保持租户和项目身份为空。 */
    private <T> T dataTransaction(int isolation, Function<TransactionStatus, T> action) {
        assertThat(TenantContext.current()).isEmpty();
        assertThat(RlsScopeContext.current()).isEmpty();
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            transaction.setIsolationLevel(isolation);
            return transaction.execute(status -> {
                jdbcTemplate.execute("SET LOCAL statement_timeout = '10s'");
                assertThat(jdbcTemplate.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
                assertThat(jdbcTemplate.queryForObject("SELECT COALESCE(current_setting('app.tenant_id', true), '')", String.class)).isEmpty();
                assertThat(jdbcTemplate.queryForObject("SELECT COALESCE(current_setting('app.project_id', true), '')", String.class)).isEmpty();
                return action.apply(status);
            });
        }
    }

    /** PostgreSQL 后端 PID 直接证明两个事务占用不同物理连接。 */
    private int backendId() {
        return jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
    }

    /** 父线程持有未提交事务并等待子线程完成，自然形成确定屏障，无固定等待时间。 */
    private <T> T await(Future<T> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception failure) {
            future.cancel(true);
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new AssertionError("独立领取事务未在10秒内成功完成", failure);
        }
    }

    /** 即使断言失败也取消工作线程；外层事务先退出，避免清理等待仍被本例行锁阻塞。 */
    private <T> T withWorker(Function<ExecutorService, T> action) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            return action.apply(executor);
        } finally {
            executor.shutdownNow();
            try {
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError("领取测试工作线程未完成清理", failure);
            }
        }
    }

    /** 独立 owner 只读取本例所有字段，防止回滚测试仅看行数而漏掉租约或状态残留。 */
    private List<String> snapshot(Fixture fixture) throws SQLException {
        try (Connection owner = ownerConnection(); PreparedStatement statement = owner.prepareStatement(
                "SELECT row_to_json(p)::text FROM dev_modbus_poll p WHERE project_id = ? ORDER BY id")) {
            parameters(statement, fixture.projectId());
            try (ResultSet rows = statement.executeQuery()) {
                List<String> snapshot = new ArrayList<>();
                while (rows.next()) snapshot.add(rows.getString(1));
                return snapshot;
            }
        }
    }
}
