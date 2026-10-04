package com.things.link.device.infrastructure.persistence;

import com.things.link.device.domain.ModbusPointMapping;
import com.things.link.device.domain.ModbusPoll;
import com.things.link.device.domain.ModbusPollRepository;
import com.things.link.shared.id.Uuid7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 显式SQL持久化轮询；D-117以网关事务锁保证领取串行，点位行锁排除正在更新的行。 */
@Repository
public class JdbcModbusPollRepository implements ModbusPollRepository {
    /** 单次请求租约/超时窗口，进程崩溃后由下一轮扫描接管。 */
    private static final String LEASE_INTERVAL = "10 seconds";

    /** JDBC 执行入口。 */ private final JdbcTemplate jdbcTemplate;
    /** @param jdbcTemplate 数据访问模板 */
    public JdbcModbusPollRepository(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

    /** {@inheritDoc} */
    @Override public void syncSchedule(UUID projectId, UUID deviceId, String projectKey, String gatewayKey,
                                       List<ModbusPointMapping> points) {
        jdbcTemplate.update("DELETE FROM dev_modbus_poll WHERE project_id = ? AND device_id = ?",
                projectId, deviceId);
        for (ModbusPointMapping point : points) {
            // D-119：前16列逐项绑定点位事实，轮询周期不能漏位，否则配置及Outbox事务整体失败。
            jdbcTemplate.update("""
                    INSERT INTO dev_modbus_poll
                        (id, tenant_id, project_id, device_id, sub_device_id, project_key, gateway_key,
                         property_key, slave_address, function_code, register_address, data_type, byte_order,
                         scale, "offset", polling_interval_ms, next_poll_at, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), 'IDLE', now(), now())
                    """, Uuid7.generate(), point.tenantId(), projectId, deviceId, point.subDeviceId(),
                    projectKey, gatewayKey, point.propertyKey(), point.slaveAddress(),
                    point.functionCode().name(), point.registerAddress(), point.dataType().name(),
                    point.byteOrder().name(), point.scale(), point.offset(), point.pollingIntervalMs());
        }
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<ModbusPoll> claimDue(int limit) {
        requireClaimTransactionIsolation();
        List<UUID> gateways = findDueGatewayCandidates(limit);
        List<ModbusPoll> claimed = new ArrayList<>();
        for (UUID gatewayId : gateways) {
            if (tryLockGateway(gatewayId)) {
                // 必须与try锁分成两条SQL，RC才能看到前一持锁事务已提交的在途状态。
                claimed.addAll(claimOneGateway(gatewayId));
            }
        }
        return List.copyOf(claimed);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ModbusPoll> claimOneDue(int candidateLimit) {
        requirePositiveCandidateLimit(candidateLimit);
        requireClaimTransactionIsolation();
        for (UUID gatewayId : findDueGatewayCandidates(candidateLimit)) {
            if (!tryLockGateway(gatewayId)) {
                continue;
            }
            // 锁后新快照可能发现别的实例刚提交了在途行；此时继续检查剩余有界候选。
            Optional<ModbusPoll> claimed = claimOneGateway(gatewayId).stream().findFirst();
            if (claimed.isPresent()) {
                return claimed;
            }
        }
        return Optional.empty();
    }

    /** D-117：锁后必须读新快照；加入已有事务的isolation注解不能覆盖原隔离级别。 */
    private void requireClaimTransactionIsolation() {
        String isolation = jdbcTemplate.queryForObject("SHOW transaction_isolation", String.class);
        if (!"read committed".equals(isolation) && !"read uncommitted".equals(isolation)) {
            throw new IllegalStateException("Modbus轮询领取要求READ COMMITTED或READ UNCOMMITTED事务");
        }
    }

    /** @param limit 候选上限 @return 按最早到期时刻稳定排序的候选网关 */
    private List<UUID> findDueGatewayCandidates(int limit) {
        return jdbcTemplate.query("""
                SELECT p.device_id FROM dev_modbus_poll p
                 WHERE p.status = 'IDLE' AND p.next_poll_at <= now()
                   AND NOT EXISTS (SELECT 1 FROM dev_modbus_poll q
                                    WHERE q.device_id = p.device_id AND q.status = 'IN_FLIGHT')
                 GROUP BY p.device_id ORDER BY min(p.next_poll_at), p.device_id LIMIT ?
                """, (rs, rowNum) -> rs.getObject(1, UUID.class), limit);
    }

    /** @param gatewayId 候选网关 @return 当前事务是否取得D-117网关锁 */
    private boolean tryLockGateway(UUID gatewayId) {
        // 命名空间+完整UUID固定网关身份；哈希碰撞最多额外跳过无关网关，不会放开同网关互斥。
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0::bigint))
                """, Boolean.class, "dev_modbus_poll:" + gatewayId));
    }

    /** 持有网关事务锁后重新核对全局在途，最多领取一点；锁一直持有到状态及Outbox提交或回滚。 */
    private List<ModbusPoll> claimOneGateway(UUID gatewayId) {
        return jdbcTemplate.query("""
                WITH due AS (
                    SELECT id FROM dev_modbus_poll p
                     WHERE p.device_id = ? AND p.status = 'IDLE' AND p.next_poll_at <= now()
                       AND NOT EXISTS (SELECT 1 FROM dev_modbus_poll q
                                        WHERE q.device_id = p.device_id AND q.status = 'IN_FLIGHT')
                     ORDER BY p.next_poll_at, p.id FOR UPDATE SKIP LOCKED LIMIT 1
                ) UPDATE dev_modbus_poll t SET status = 'IN_FLIGHT',
                    lease_until = now() + interval '10 seconds', updated_at = now()
                  FROM due WHERE t.id = due.id
                RETURNING t.id, t.tenant_id, t.project_id, t.device_id, t.sub_device_id, t.project_key, t.gateway_key, t.property_key,
                          t.slave_address, t.function_code, t.register_address, t.data_type, t.byte_order,
                          t.scale, t."offset", t.polling_interval_ms, t.next_poll_at, t.lease_until,
                          t.attempt, t.status, t.request_id, t.created_at, t.updated_at
                """, this::map, gatewayId);
    }

    /** {@inheritDoc} */
    @Override public Optional<ModbusPoll> findByRequestId(UUID requestId) {
        return jdbcTemplate.query("""
                SELECT id, tenant_id, project_id, device_id, sub_device_id, project_key, gateway_key, property_key, slave_address,
                       function_code, register_address, data_type, byte_order, scale, "offset",
                       polling_interval_ms, next_poll_at, lease_until, attempt, status, request_id,
                       created_at, updated_at
                  FROM dev_modbus_poll WHERE request_id = ?
                """, this::map, requestId).stream().findFirst();
    }

    /** {@inheritDoc} */
    @Override public boolean completeRequest(UUID id, UUID expectedRequestId, Instant nextPollAt) {
        // D-120：UPDATE等待并发提交后复判关联；RR等强隔离的序列化错误须传播，不能伪装未命中。
        return jdbcTemplate.update("""
                UPDATE dev_modbus_poll
                   SET status = 'IDLE', attempt = 0, request_id = NULL, lease_until = NULL,
                       next_poll_at = ?, updated_at = now()
                 WHERE id = ? AND request_id = ? AND status = 'IN_FLIGHT'
                """, java.sql.Timestamp.from(nextPollAt), id, expectedRequestId) == 1;
    }

    /** {@inheritDoc} */
    @Override public boolean complete(UUID id, Instant nextPollAt) {
        return jdbcTemplate.update("""
                UPDATE dev_modbus_poll
                   SET status = 'IDLE', attempt = 0, request_id = NULL, lease_until = NULL,
                       next_poll_at = ?, updated_at = now()
                 WHERE id = ?
                """, java.sql.Timestamp.from(nextPollAt), id) == 1;
    }

    /** {@inheritDoc} */
    @Override public boolean pauseUntilNextInterval(UUID id) {
        // ADR0062：next_poll_at与updated_at使用同一数据库时钟，离线不补历史周期也不立即重试。
        return jdbcTemplate.update("""
                UPDATE dev_modbus_poll
                   SET status = 'IDLE', attempt = 0, request_id = NULL, lease_until = NULL,
                       next_poll_at = now() + polling_interval_ms * interval '1 millisecond', updated_at = now()
                 WHERE id = ?
                """, id) == 1;
    }

    /** {@inheritDoc} */
    @Override public boolean release(UUID id, Instant nextPollAt) {
        return complete(id, nextPollAt);
    }

    /** 设置在途请求的关联标识与尝试序号；在 claimDue 之后、Outbox 之前由服务调用。 */
    public boolean markRequest(UUID id, UUID requestId, int attempt) {
        return jdbcTemplate.update("""
                UPDATE dev_modbus_poll SET request_id = ?, attempt = ?, updated_at = now() WHERE id = ?
                """, requestId, attempt, id) == 1;
    }

    /** 领取租约过期的在途请求（FOR UPDATE SKIP LOCKED），供超时重试/释放。 */
    public List<ModbusPoll> claimExpiredInFlight(int limit) {
        return jdbcTemplate.query("""
                WITH due AS (
                    SELECT id FROM dev_modbus_poll p
                     WHERE p.status = 'IN_FLIGHT' AND p.lease_until < now()
                     ORDER BY p.lease_until, p.id FOR UPDATE SKIP LOCKED LIMIT ?
                ) UPDATE dev_modbus_poll t SET lease_until = now() + interval '10 seconds', updated_at = now()
                  FROM due WHERE t.id = due.id
                RETURNING t.id, t.tenant_id, t.project_id, t.device_id, t.sub_device_id, t.project_key, t.gateway_key, t.property_key,
                          t.slave_address, t.function_code, t.register_address, t.data_type, t.byte_order,
                          t.scale, t."offset", t.polling_interval_ms, t.next_poll_at, t.lease_until,
                          t.attempt, t.status, t.request_id, t.created_at, t.updated_at
                """, this::map, limit);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ModbusPoll> claimOneExpiredInFlight(int candidateLimit) {
        requirePositiveCandidateLimit(candidateLimit);
        return jdbcTemplate.query("""
                WITH candidates AS (
                    SELECT id, lease_until FROM dev_modbus_poll
                     WHERE status = 'IN_FLIGHT' AND lease_until < now()
                     ORDER BY lease_until, id LIMIT ?
                ), due AS (
                    SELECT p.id FROM dev_modbus_poll p
                      JOIN candidates c ON c.id = p.id
                     WHERE p.status = 'IN_FLIGHT' AND p.lease_until < now()
                     ORDER BY c.lease_until, p.id FOR UPDATE OF p SKIP LOCKED LIMIT 1
                ) UPDATE dev_modbus_poll t SET lease_until = now() + interval '10 seconds', updated_at = now()
                  FROM due WHERE t.id = due.id
                RETURNING t.id, t.tenant_id, t.project_id, t.device_id, t.sub_device_id, t.project_key, t.gateway_key, t.property_key,
                          t.slave_address, t.function_code, t.register_address, t.data_type, t.byte_order,
                          t.scale, t."offset", t.polling_interval_ms, t.next_poll_at, t.lease_until,
                          t.attempt, t.status, t.request_id, t.created_at, t.updated_at
                """, this::map, candidateLimit).stream().findFirst();
    }

    /** @param candidateLimit 候选上限，必须为正数 */
    private static void requirePositiveCandidateLimit(int candidateLimit) {
        if (candidateLimit < 1) {
            throw new IllegalArgumentException("Modbus轮询候选上限必须为正数");
        }
    }

    /** @param rs 查询结果 @param rowNum 行号 @return 领域对象 */
    private ModbusPoll map(ResultSet rs, int rowNum) throws SQLException {
        return new ModbusPoll(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("project_id", UUID.class), rs.getObject("device_id", UUID.class),
                rs.getObject("sub_device_id", UUID.class), rs.getString("project_key"), rs.getString("gateway_key"),
                rs.getString("property_key"), rs.getInt("slave_address"),
                ModbusPointMapping.FunctionCode.valueOf(rs.getString("function_code")),
                rs.getInt("register_address"), ModbusPointMapping.DataType.valueOf(rs.getString("data_type")),
                ModbusPointMapping.ByteOrder.valueOf(rs.getString("byte_order")), rs.getBigDecimal("scale"),
                rs.getBigDecimal("offset"), rs.getInt("polling_interval_ms"),
                rs.getTimestamp("next_poll_at").toInstant(),
                rs.getTimestamp("lease_until") == null ? null : rs.getTimestamp("lease_until").toInstant(),
                rs.getInt("attempt"), ModbusPoll.Status.valueOf(rs.getString("status")),
                rs.getObject("request_id", UUID.class), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
