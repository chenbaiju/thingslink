package com.things.link.bootstrap.ingestion.modbus;

import com.things.link.device.application.ModbusPollService;
import com.things.link.device.domain.ModbusPoll;
import com.things.link.device.infrastructure.persistence.JdbcModbusPollRepository;
import com.things.link.ingestion.infrastructure.ModbusResponseKafkaConsumer;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.message.ModbusResponse;
import com.things.link.shared.message.StandardUplinkMessage;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.shared.tenant.RlsScopeContext;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.outbox.JdbcTransactionalOutboxRepository;
import com.things.link.support.outbox.OutboxEvent;
import com.things.link.support.tenant.DatabaseWorkload;
import com.things.link.support.tenant.DatabaseWorkloadContext;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

/**
 * ADR0063：真实消费者接管请求完成、版本冻结及 normalized Outbox，提交前失败必须保留数据库重放资格。
 * 正常及故障入口没有外层事务，只有挂起测试显式模拟已有调用事务；Kafka不是接纳成功的前置。
 */
class ModbusResponseAcceptanceIntegrationTests extends AbstractModbusPollIntegrationTest {

    /** 冻结类型字符串允许旧生产源码先编译执行能力反例。 */
    private static final String EVENT_TYPE = "DEVICE_MODBUS_NORMALIZED";
    /** 与点位名称及类型一致的合法初始版本。 */
    private static final String MODEL_SNAPSHOT = """
            {"properties":{"temperature_0":{"dataType":"NUMBER","accessType":"REPORT"}},"events":{},"commands":{}}
            """;
    /** 通过真实公开消费入口验证生产接管接线。 */
    @Autowired private ModbusResponseKafkaConsumer consumer;
    /** 首次发送请求走实际轮询服务。 */
    @Autowired private ModbusPollService pollService;
    /** JSON从持久事实还原，完整核验冻结信封。 */
    @Autowired private ObjectMapper mapper;
    /** 只在本例真实append完成后制造SQL故障，不替换其持久化行为。 */
    @MockitoSpyBean private JdbcTransactionalOutboxRepository outboxRepository;
    /** 并发测试只在真实查询完成后暂停，不伪造匹配的轮询行。 */
    @MockitoSpyBean private JdbcModbusPollRepository pollRepository;
    /** Boot工厂类型为通配符，按已知bean名选择，避免泛型匹配装配失败。 */
    @MockitoSpyBean(name = "kafkaTemplate") private KafkaTemplate<?, ?> kafkaTemplate;

    /** 正常及重复消费只持久接纳一次，不能保留旧consumer直接Kafka发送路径。 */
    @Test
    void acceptsResponseAndDuplicateAsOneCompleteDurableEnvelopeWithoutDirectSend() throws Exception {
        Fixture fixture = inFlight(true);
        ModbusResponse response = response(fixture);
        AtomicInteger directSends = forbidDirectSend(fixture);
        consumeWithoutTransaction(response);
        consumeWithoutTransaction(response);
        assertAccepted(fixture, response);
        assertThat(directSends.get()).isZero();
    }

    /** 真实缺失版本必须回滚poll CAS，建立真实初始版本后同响应可重新接纳一次。 */
    @Test
    void missingVersionRollsBackAndRecoversWithTheSameRequest() throws Exception {
        Fixture fixture = inFlight(false);
        ModbusResponse response = response(fixture);
        Snapshot before = snapshot(fixture);
        AtomicInteger directSends = forbidDirectSend(fixture);
        Throwable failure = catchThrowable(() -> consumeWithoutTransaction(response));
        assertThat(failure).isInstanceOf(BusinessException.class);
        assertThat(snapshot(fixture)).isEqualTo(before);
        bindVersion(fixture);
        consumeWithoutTransaction(response);
        consumeWithoutTransaction(response);
        assertAccepted(fixture, response);
        assertThat(directSends.get()).isZero();
    }

    /** 真实append后SQL中止必须同时回滚完成及事件，恢复后仍由原requestId接纳一次。 */
    @Test
    void databaseFailureAfterRealAppendRollsBackBothFactsAndCanRecover() throws Exception {
        Fixture fixture = inFlight(true);
        ModbusResponse response = response(fixture);
        Snapshot before = snapshot(fixture);
        AtomicInteger directSends = forbidDirectSend(fixture);
        AtomicBoolean inject = new AtomicBoolean(true);
        AtomicBoolean reached = new AtomicBoolean();
        JdbcTransactionalOutboxRepository target = AopTestUtils.getUltimateTargetObject(outboxRepository);
        doAnswer(invocation -> {
            OutboxEvent event = invocation.getArgument(0);
            invocation.callRealMethod();
            if (event.eventType().equals(EVENT_TYPE) && event.projectId().equals(fixture.projectId()) && inject.getAndSet(false)) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM sys_outbox_event WHERE id = ?", Integer.class, event.id()))
                        .isEqualTo(1);
                assertThat(jdbcTemplate.queryForObject("SELECT request_id IS NULL FROM dev_modbus_poll WHERE project_id = ?",
                        Boolean.class, fixture.projectId())).isTrue();
                try (Connection owner = ownerConnection()) {
                    assertThat(number(owner, "SELECT count(*) FROM sys_outbox_event WHERE id = ?", event.id())).isZero();
                    assertThat(number(owner, "SELECT count(*) FROM dev_modbus_poll WHERE project_id = ? AND request_id = ?",
                            fixture.projectId(), response.requestId())).isEqualTo(1);
                }
                reached.set(true);
                jdbcTemplate.execute("SELECT 1 / 0");
            }
            return null;
        }).when(target).append(any(OutboxEvent.class));
        Throwable failure = catchThrowable(() -> consumeWithoutTransaction(response));
        assertThat(reached.get()).isTrue();
        assertThat(sqlState(failure)).isEqualTo("22012");
        assertThat(snapshot(fixture)).isEqualTo(before);
        consumeWithoutTransaction(response);
        consumeWithoutTransaction(response);
        assertAccepted(fixture, response);
        assertThat(directSends.get()).isZero();
    }

    /** 两个租户各自用新事务提交；预置不同范围的外层事务回滚也不能撤销已接纳事实。 */
    @Test
    void crossTenantAcceptanceCommitsSurviveOuterRollbackAndPreserveItsScope() throws Exception {
        Fixture first = inFlight(true);
        Fixture second = inFlight(true);
        Fixture outerScope = fixture(List.of());
        ModbusResponse firstResponse = response(first);
        ModbusResponse secondResponse = response(second);
        AtomicInteger firstDirectSends = forbidDirectSend(first);
        AtomicInteger secondDirectSends = forbidDirectSend(second);
        assertThat(first.tenantId()).isNotEqualTo(second.tenantId());
        assertThat(first.projectId()).isNotEqualTo(second.projectId());
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                setSqlScope(outerScope);
                int outerPid = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
                consumeRecord(firstResponse);
                assertThat(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class)).isEqualTo(outerPid);
                assertSqlScope(outerScope);
                assertThat(RlsScopeContext.current()).isEmpty();
                assertThat(TenantContext.current()).isEmpty();
                assertAcceptedFromIndependentConnection(first, firstResponse);
                consumeRecord(secondResponse);
                assertThat(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class)).isEqualTo(outerPid);
                assertSqlScope(outerScope);
                assertThat(RlsScopeContext.current()).isEmpty();
                assertThat(TenantContext.current()).isEmpty();
                assertAcceptedFromIndependentConnection(second, secondResponse);
                status.setRollbackOnly();
            });
        }
        assertAccepted(first, firstResponse);
        assertAccepted(second, secondResponse);
        assertThat(firstDirectSends.get()).isZero();
        assertThat(secondDirectSends.get()).isZero();
    }

    /** 缺版本只回滚响应新事务；异常传播后外层仍可查询且原范围、poll与Outbox均不变。 */
    @Test
    void versionFailureRollsBackInnerTransactionWithoutPoisoningOuterTransaction() throws Exception {
        Fixture fixture = inFlight(false);
        Fixture outerScope = fixture(List.of());
        ModbusResponse response = response(fixture);
        Snapshot before = snapshot(fixture);
        AtomicInteger directSends = forbidDirectSend(fixture);
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                setSqlScope(outerScope);
                int outerPid = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
                Throwable failure = catchThrowable(() -> consumeRecord(response));
                assertThat(failure).isInstanceOf(BusinessException.class);
                assertThat(status.isRollbackOnly()).isFalse();
                assertThat(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class)).isEqualTo(outerPid);
                assertThat(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).isOne();
                assertSqlScope(outerScope);
                assertThat(RlsScopeContext.current()).isEmpty();
                assertThat(TenantContext.current()).isEmpty();
                assertThat(snapshotFromIndependentConnection(fixture)).isEqualTo(before);
                status.setRollbackOnly();
            });
        }
        assertThat(snapshot(fixture)).isEqualTo(before);
        assertThat(directSends.get()).isZero();
    }

    /** 两个真实独立连接读到同一请求后同时推进，只有CAS胜者持久追加一份结果。 */
    @Test
    void concurrentResponsesAcceptOneDurableEvent() throws Exception {
        Fixture fixture = inFlight(true);
        ModbusResponse response = response(fixture);
        AtomicInteger directSends = forbidDirectSend(fixture);
        CountDownLatch reads = new CountDownLatch(2);
        CountDownLatch resume = new CountDownLatch(1);
        ConcurrentLinkedQueue<Integer> backends = new ConcurrentLinkedQueue<>();
        JdbcModbusPollRepository target = AopTestUtils.getUltimateTargetObject(pollRepository);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Optional<ModbusPoll> poll = (Optional<ModbusPoll>) invocation.callRealMethod();
            assertThat(poll).isPresent();
            backends.add(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
            reads.countDown();
            assertThat(resume.await(10, TimeUnit.SECONDS)).isTrue();
            return poll;
        }).when(target).findByRequestId(response.requestId());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> consumeWithoutTransaction(response));
            Future<?> second = executor.submit(() -> consumeWithoutTransaction(response));
            assertThat(reads.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(backends).hasSize(2).doesNotHaveDuplicates();
            resume.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            assertAccepted(fixture, response);
            assertThat(directSends.get()).isZero();
        } finally {
            resume.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    /**
     * ADR0063三秒预算必须覆盖CAS行锁等待；先从PG阻塞图证明真实等待，再验超时回滚及恢复。
     * 本例不设置statement_timeout，也不强求精确墙钟三秒，预算完全来自生产事务入口。
     */
    @Test
    void casLockWaitTimesOutWithoutLosingRequestAndRecoversAfterUnlock() throws Exception {
        Fixture fixture = inFlight(true);
        ModbusResponse response = response(fixture);
        Snapshot before = snapshot(fixture);
        AtomicInteger directSends = forbidDirectSend(fixture);
        CompletableFuture<Integer> responsePid = new CompletableFuture<>();
        JdbcModbusPollRepository target = AopTestUtils.getUltimateTargetObject(pollRepository);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            responsePid.complete(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
            return result;
        }).when(target).findByRequestId(response.requestId());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = ownerConnection()) {
            holder.setAutoCommit(false);
            try {
                int holderPid = (int) number(holder, "SELECT pg_backend_pid()");
                try (PreparedStatement lock = holder.prepareStatement(
                        "SELECT id FROM dev_modbus_poll WHERE project_id = ? AND request_id = ? FOR UPDATE")) {
                    parameters(lock, fixture.projectId(), response.requestId());
                    try (ResultSet rows = lock.executeQuery()) {
                        assertThat(rows.next()).isTrue();
                    }
                }
                Future<Throwable> waiting = executor.submit(() -> {
                    Throwable failure = catchThrowable(() -> consumeWithoutTransaction(response));
                    if (!responsePid.isDone()) {
                        responsePid.completeExceptionally(failure == null
                                ? new IllegalStateException("响应事务未公布PID") : failure);
                    }
                    return failure;
                });
                assertBlockedBy(responsePid.get(5, TimeUnit.SECONDS), holderPid, waiting);
                Throwable failure = waiting.get(10, TimeUnit.SECONDS);
                assertThat(isTransactionTimeout(failure)).as("真实锁等待应因生产事务预算失败，实际异常=%s", failure).isTrue();
                assertThat(snapshot(fixture)).isEqualTo(before);
                assertThat(directSends.get()).isZero();

                holder.rollback();
                consumeWithoutTransaction(response);
                assertAccepted(fixture, response);
                assertThat(directSends.get()).isZero();
            } finally {
                // 先释放数据库锁再回收线程，测试失败也不能把worker挂在持锁者之后。
                holder.rollback();
                executor.shutdownNow();
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    /** 独立observer读取真实阻塞图及未授予锁，不能用固定sleep假装CAS已经等待。 */
    private void assertBlockedBy(int waitingPid, int holderPid, Future<Throwable> waiting) throws Exception {
        assertThat(waitingPid).isNotEqualTo(holderPid);
        try (Connection observer = ownerConnection(); PreparedStatement query = observer.prepareStatement("""
                SELECT pg_backend_pid(), ? = ANY(pg_blocking_pids(?)),
                       EXISTS (SELECT 1 FROM pg_locks WHERE pid = ? AND NOT granted)
                """)) {
            parameters(query, holderPid, waitingPid, waitingPid);
            query.setQueryTimeout(3);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                try (ResultSet rows = query.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isNotEqualTo(waitingPid).isNotEqualTo(holderPid);
                    if (rows.getBoolean(2) && rows.getBoolean(3)) return;
                }
                if (waiting.isDone()) {
                    throw new AssertionError("响应未等待指定poll行锁便已结束", waiting.get());
                }
                Thread.onSpinWait();
            }
        }
        throw new AssertionError("未观察到响应事务等待指定poll行锁");
    }

    /** 接受PG取消语句57014或Spring事务预算映射，不把任意SQL故障当作超时通过。 */
    private boolean isTransactionTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TransactionTimedOutException) return true;
            if (cause instanceof SQLException sql && "57014".equals(sql.getSQLState())) return true;
        }
        return false;
    }

    /** 正常生产入口只有DATA路由，绝不提前创建可掩盖旧实现提交边界的外层事务。 */
    private void consumeWithoutTransaction(ModbusResponse response) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TenantContext.current()).isEmpty();
        assertThat(RlsScopeContext.current()).isEmpty();
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            consumeRecord(response);
        } finally {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        }
    }

    /** 所有入口使用正确网关key和真实已确权响应信封；本测试不执行MQTT鉴权。 */
    private void consumeRecord(ModbusResponse response) {
        consumer.consume(new ConsumerRecord<>(ModbusResponseKafkaConsumer.MODBUS_RESPONSE_TOPIC,
                0, 0L, response.gatewayId().toString(), response));
    }

    /** 只禁用本例旧直接发布路径，记录次数可证明成功不依赖任何Kafka Future。 */
    private AtomicInteger forbidDirectSend(Fixture fixture) {
        AtomicInteger sends = new AtomicInteger();
        KafkaTemplate<Object, Object> target = AopTestUtils.getUltimateTargetObject(kafkaTemplate);
        doAnswer(invocation -> {
            sends.incrementAndGet();
            return CompletableFuture.failedFuture(new IllegalStateException("ADR0063禁止consumer直接发布"));
        }).when(target).send(eq("tc.device.uplink.normalized"), eq(fixture.subDeviceId().toString()), any());
        return sends;
    }

    /** 在线网关经真实控制面物化及DATA扫描生成关联，不直接插入poll。 */
    private Fixture inFlight(boolean withVersion) throws Exception {
        Fixture fixture = fixture(points(1));
        try (Connection owner = ownerConnection()) {
            assertThat(execute(owner, "UPDATE dev_device SET status = 'ONLINE' WHERE id = ?", fixture.gatewayId())).isEqualTo(1);
        }
        if (withVersion) bindVersion(fixture);
        pushConfig(fixture);
        try (DatabaseWorkloadContext.Scope ignored = DatabaseWorkloadContext.enter(DatabaseWorkload.DATA)) {
            Integer sent = new TransactionTemplate(transactionManager).execute(status -> {
                assertThat(jdbcTemplate.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
                return pollService.scanDue(100);
            });
            assertThat(sent).isPositive();
        }
        return fixture;
    }

    /** 读取实际子设备类型并建立合法INITIAL版本绑定，保持版本解析端口真实。 */
    private void bindVersion(Fixture fixture) throws SQLException {
        UUID typeId;
        try (Connection owner = ownerConnection(); PreparedStatement query = owner.prepareStatement("SELECT device_type_id FROM dev_device WHERE id = ?")) {
            query.setObject(1, fixture.subDeviceId());
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                typeId = rows.getObject(1, UUID.class);
            }
        }
        seedThingModelVersion(fixture.tenantId(), fixture.projectId(), typeId, fixture.subDeviceId(), MODEL_SNAPSHOT);
    }

    /** 原始接收时间固定，避免接管错误地使用当前时间重建协议事实。 */
    private ModbusResponse response(Fixture fixture) throws SQLException {
        try (Connection owner = ownerConnection(); PreparedStatement query = owner.prepareStatement("SELECT request_id FROM dev_modbus_poll WHERE project_id = ?")) {
            query.setObject(1, fixture.projectId());
            try (ResultSet rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return new ModbusResponse(rows.getObject(1, UUID.class), fixture.tenantId(), fixture.projectId(), fixture.gatewayId(),
                        ModbusResponse.Status.SUCCESS, List.of(0, 0x3f80), null,
                        Instant.parse("2026-08-20T01:02:03.123456Z"), "0123456789abcdef0123456789abcdef");
            }
        }
    }

    /** 单一完整持久事件必须与已完成poll同时对独立owner可见。 */
    private void assertAccepted(Fixture fixture, ModbusResponse response) throws SQLException {
        try (Connection owner = ownerConnection()) {
            assertThat(number(owner, "SELECT count(*) FROM dev_modbus_poll WHERE project_id = ? AND status = 'IDLE' AND request_id IS NULL",
                    fixture.projectId())).isEqualTo(1);
            try (PreparedStatement query = owner.prepareStatement("""
                    SELECT id, tenant_id, project_id, aggregate_type, aggregate_id, partition_key,
                           destination_topic, payload, trace_id, published_at IS NULL
                      FROM sys_outbox_event WHERE project_id = ? AND event_type = ?
                    """)) {
                parameters(query, fixture.projectId(), EVENT_TYPE);
                try (ResultSet rows = query.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getObject(1, UUID.class).version()).isEqualTo(7);
                    assertThat(rows.getObject(2, UUID.class)).isEqualTo(fixture.tenantId());
                    assertThat(rows.getObject(3, UUID.class)).isEqualTo(fixture.projectId());
                    assertThat(rows.getString(4)).isEqualTo("DEVICE_MODBUS_RESULT");
                    assertThat(rows.getObject(5, UUID.class)).isEqualTo(fixture.subDeviceId());
                    assertThat(rows.getString(6)).isEqualTo(fixture.subDeviceId().toString());
                    assertThat(rows.getString(7)).isEqualTo("tc.device.uplink.normalized");
                    StandardUplinkMessage normalized = mapper.readValue(rows.getString(8), StandardUplinkMessage.class);
                    assertThat(normalized.messageId()).isEqualTo(response.requestId());
                    assertThat(normalized.tenantId()).isEqualTo(fixture.tenantId());
                    assertThat(normalized.projectId()).isEqualTo(fixture.projectId());
                    assertThat(normalized.deviceId()).isEqualTo(fixture.subDeviceId());
                    assertThat(normalized.gatewayId()).isEqualTo(fixture.gatewayId());
                    assertThat(normalized.modelVersion()).isEqualTo("1.0.0");
                    assertThat(normalized.protocol()).isEqualTo(TransportProtocol.MQTT);
                    assertThat(normalized.direction()).isEqualTo(StandardUplinkMessage.Direction.UP);
                    assertThat(normalized.type()).isEqualTo(StandardUplinkMessage.Type.PROPERTY_REPORT);
                    assertThat(normalized.occurredAt()).isEqualTo(response.receivedAt());
                    assertThat(normalized.receivedAt()).isEqualTo(response.receivedAt());
                    assertThat(normalized.traceId()).isEqualTo(response.traceId());
                    assertThat(normalized.rawBytes()).isZero();
                    assertThat(normalized.payload()).containsEntry("temperature_0", -2.25d);
                    assertThat(rows.getString(9)).isEqualTo(response.traceId());
                    assertThat(rows.getBoolean(10)).isTrue();
                    assertThat(rows.next()).isFalse();
                }
            }
        }
    }

    /** 在不加入外层事务的 owner 连接上确认内层提交已立即可见。 */
    private void assertAcceptedFromIndependentConnection(Fixture fixture, ModbusResponse response) {
        try {
            assertAccepted(fixture, response);
        } catch (SQLException exception) {
            throw new IllegalStateException("无法读取响应接纳提交事实", exception);
        }
    }

    /** 完整行快照捕捉回滚遗漏的租约、attempt、时间及旧发送事件变化。 */
    private Snapshot snapshot(Fixture fixture) throws SQLException {
        try (Connection owner = ownerConnection()) {
            return new Snapshot(rows(owner, "SELECT row_to_json(p)::text FROM dev_modbus_poll p WHERE project_id = ? ORDER BY id", fixture),
                    rows(owner, "SELECT row_to_json(o)::text FROM sys_outbox_event o WHERE project_id = ? ORDER BY id", fixture));
        }
    }

    /** 在外层事务仍存活时用 owner 连接读取当前快照，避免读取被挂起连接的视图。 */
    private Snapshot snapshotFromIndependentConnection(Fixture fixture) {
        try {
            return snapshot(fixture);
        } catch (SQLException exception) {
            throw new IllegalStateException("无法读取响应接纳回滚事实", exception);
        }
    }

    /** 只读取本例归属，不会把其他测试事件纳入回滚断言。 */
    private List<String> rows(Connection owner, String sql, Fixture fixture) throws SQLException {
        try (PreparedStatement query = owner.prepareStatement(sql)) {
            query.setObject(1, fixture.projectId());
            List<String> result = new ArrayList<>();
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) result.add(rows.getString(1));
            }
            return List.copyOf(result);
        }
    }

    /** 直接设置已借出连接的SQL范围，不能用ThreadLocal冒充事务内范围恢复。 */
    private void setSqlScope(Fixture fixture) {
        jdbcTemplate.queryForMap("SELECT set_config('app.tenant_id', ?, true), set_config('app.project_id', ?, true)",
                fixture.tenantId().toString(), fixture.projectId().toString());
    }

    /** 内层响应事务结束后两个SQL值必须仍是外层项目，不能变成响应归属或被清空。 */
    private void assertSqlScope(Fixture fixture) {
        assertThat(jdbcTemplate.queryForObject("SELECT current_setting('app.tenant_id', true)", String.class))
                .isEqualTo(fixture.tenantId().toString());
        assertThat(jdbcTemplate.queryForObject("SELECT current_setting('app.project_id', true)", String.class))
                .isEqualTo(fixture.projectId().toString());
    }

    /** 沿cause保留真实PostgreSQL首因，不让事务包装异常覆盖最初22012。 */
    private String sqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) return sql.getSQLState();
        }
        return null;
    }

    /** @param polls 全部本例poll行 @param outbox 全部本例Outbox行 */
    private record Snapshot(List<String> polls, List<String> outbox) { }
}
