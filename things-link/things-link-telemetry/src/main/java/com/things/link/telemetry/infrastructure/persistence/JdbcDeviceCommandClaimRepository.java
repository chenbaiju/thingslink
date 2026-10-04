package com.things.link.telemetry.infrastructure.persistence;

import com.things.link.telemetry.domain.DeviceCommandClaimRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 以显式 SQL 领取命令并写入领取事实：命令状态推进与领取行在同一事务内完成。 */
@Repository
public class JdbcDeviceCommandClaimRepository implements DeviceCommandClaimRepository {

    /** 命令参数对象的反序列化目标。 */
    private static final TypeReference<Map<String, Object>> INPUT_TYPE = new TypeReference<>() {
    };

    /** JDBC 访问器。 */ private final JdbcTemplate jdbcTemplate;
    /** 解析命令参数对象。 */ private final ObjectMapper objectMapper;
    /** 命令事实仓储；待发量统计委托给它，避免两处口径漂移。 */
    private final JdbcDeviceCommandRepository commandRepository;

    /**
     * @param jdbcTemplate JDBC 访问器
     * @param objectMapper 统一 JSON 映射器
     * @param commandRepository 命令事实仓储；待发量与命令事实共用同一份 SQL
     */
    public JdbcDeviceCommandClaimRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                            JdbcDeviceCommandRepository commandRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.commandRepository = commandRepository;
    }

    /**
     * {@inheritDoc}
     *
     * <p>单条语句完成候选锁定、命令推进与领取行写入：{@code FOR UPDATE SKIP LOCKED} 让并发领取各自拿到不同命令，
     * 未领取到的请求不会阻塞；{@code attempt_no} 由 {@code attempt_count + 1} 推导，与命令计数严格同步。</p>
     */
    @Override
    public List<ClaimedCommand> claimDue(UUID projectId, UUID deviceId, Duration lease, int limit) {
        return jdbcTemplate.query("""
                WITH due AS (
                    SELECT command.id, command.attempt_count + 1 AS next_attempt
                      FROM ts_device_command command
                     WHERE command.project_id = ? AND command.target_device_id = ?
                       AND command.connection_device_id = ?
                       AND command.operation_type = 'COMMAND'
                       -- ACKNOWLEDGED 也必须可再领取：ACK 只是中间态，租约到期未收到业务结果时，
                       -- 领取形态的重投语义与推送形态的响应超时重投一致（attempt+1）。
                       AND command.status IN ('ACCEPTED', 'DISPATCHED', 'ACKNOWLEDGED')
                       AND command.attempt_count < command.max_attempts
                       AND (command.next_attempt_at IS NULL OR command.next_attempt_at <= now())
                       AND (command.attempt_count = 0 OR EXISTS (
                               SELECT 1 FROM ts_device_command_claim claim
                                WHERE claim.project_id = command.project_id
                                  AND claim.command_id = command.id
                                  AND claim.attempt_no = command.attempt_count))
                     ORDER BY command.accepted_at, command.id
                     FOR UPDATE OF command SKIP LOCKED
                     LIMIT ?
                ), leased AS (
                    UPDATE ts_device_command command
                       SET status = 'DISPATCHED', attempt_count = due.next_attempt,
                           dispatched_at = COALESCE(command.dispatched_at, now()),
                           next_attempt_at = now() + make_interval(secs => ?),
                           deadline_at = now() + make_interval(secs => ?),
                           updated_at = now()
                      FROM due WHERE command.id = due.id
                    RETURNING command.id, command.tenant_id, command.project_id, command.command_key,
                              command.request_payload, due.next_attempt, command.deadline_at
                ), claimed AS (
                    INSERT INTO ts_device_command_claim
                        (id, tenant_id, project_id, command_id, attempt_no, connection_device_id,
                         status, lease_expires_at, claimed_at)
                    SELECT gen_random_uuid(), leased.tenant_id, leased.project_id, leased.id, leased.next_attempt,
                           ?, 'CLAIMED', leased.deadline_at, now()
                      FROM leased
                    RETURNING command_id, lease_expires_at
                )
                SELECT claimed.command_id, leased.command_key, leased.request_payload, leased.next_attempt,
                       claimed.lease_expires_at
                  FROM claimed JOIN leased ON leased.id = claimed.command_id
                 ORDER BY leased.next_attempt
                """, this::map, projectId, deviceId, deviceId, limit,
                (double) lease.toSeconds(), (double) lease.toSeconds(), deviceId);
    }


    @Override
    public long countPending(UUID projectId, UUID deviceId) {
        // 待发量与命令事实同源：只保留一份 SQL，避免两处口径漂移。
        return commandRepository.countPending(projectId, deviceId);
    }

    @Override
    public Optional<StoredReply> findStoredReply(UUID projectId, UUID commandId, UUID replyMessageId) {
        return jdbcTemplate.query("""
                SELECT command.status, command.response_payload, command.failure_code
                  FROM ts_device_command command
                 WHERE command.project_id = ? AND command.id = ?
                   AND (EXISTS (SELECT 1 FROM ts_device_command_attempt attempt
                                 WHERE attempt.project_id = command.project_id
                                   AND attempt.command_id = command.id
                                   AND attempt.reply_message_id = ?)
                     OR EXISTS (SELECT 1 FROM ts_device_command_claim claim
                                 WHERE claim.project_id = command.project_id
                                   AND claim.command_id = command.id
                                   AND claim.reply_message_id = ?))
                """, (rows, index) -> new StoredReply(rows.getString("status"), rows.getString("response_payload"),
                        rows.getString("failure_code")),
                projectId, commandId, replyMessageId, replyMessageId).stream().findFirst();
    }

    /**
     * 映射一条领取结果。
     *
     * @param rows 查询结果
     * @param index 当前行号
     * @return 领取到的命令视图
     * @throws SQLException 列读取失败
     */
    private ClaimedCommand map(ResultSet rows, int index) throws SQLException {
        UUID commandId = rows.getObject("command_id", UUID.class);
        String requestJson = rows.getString("request_payload");
        Timestamp leaseExpiresAt = rows.getTimestamp("lease_expires_at");
        return new ClaimedCommand(commandId, rows.getString("command_key"), input(commandId, requestJson),
                rows.getInt("next_attempt"), leaseExpiresAt == null ? null : leaseExpiresAt.toInstant());
    }

    /** 参数对象必须是 JSON 对象；载荷由受理时校验，这里只做读取，不重复解释业务语义。 */
    private Map<String, Object> input(UUID commandId, String requestJson) {
        try {
            Map<String, Object> parsed = objectMapper.readValue(
                    requestJson == null || requestJson.isBlank() ? "{}" : requestJson, INPUT_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (JacksonException exception) {
            throw new IllegalStateException("命令参数对象不可读取 commandId=" + commandId, exception);
        }
    }
}
