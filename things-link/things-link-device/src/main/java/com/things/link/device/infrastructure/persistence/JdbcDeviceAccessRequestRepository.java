package com.things.link.device.infrastructure.persistence;

import com.things.link.device.domain.DeviceAccessRequest;
import com.things.link.device.domain.DeviceAccessRequestRepository;
import com.things.link.shared.message.TransportProtocol;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 以显式 SQL 持久化接入受理事实。 */
@Repository
public class JdbcDeviceAccessRequestRepository implements DeviceAccessRequestRepository {

    /** 受理事实列清单，读取与 RETURNING 共用一份，避免两处漂移。 */
    private static final String COLUMNS =
            "device_id, message_id, tenant_id, project_id, protocol, payload_digest, received_at, accepted_at";

    /** 数据访问入口。 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate 数据访问入口
     */
    public JdbcDeviceAccessRequestRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<DeviceAccessRequest> find(UUID projectId, UUID deviceId, UUID messageId) {
        return jdbcTemplate.query("""
                SELECT %s FROM dev_access_request
                 WHERE project_id = ? AND device_id = ? AND message_id = ?
                """.formatted(COLUMNS), this::map, projectId, deviceId, messageId).stream().findFirst();
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code ON CONFLICT DO NOTHING} 而不是捕获唯一键异常：PostgreSQL 里失败的语句会中止整个事务，
     * 捕获异常后再读既有行只会拿到 "current transaction is aborted"，必须靠新事务或子事务规避。</p>
     */
    @Override
    public Optional<DeviceAccessRequest> insertIfAbsent(DeviceAccessRequest request) {
        List<DeviceAccessRequest> inserted = jdbcTemplate.query("""
                INSERT INTO dev_access_request
                    (device_id, message_id, tenant_id, project_id, protocol, payload_digest, received_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (device_id, message_id) DO NOTHING
                RETURNING %s
                """.formatted(COLUMNS), this::map,
                request.deviceId(), request.messageId(), request.tenantId(), request.projectId(),
                request.protocol().name(), request.payloadDigest(), Timestamp.from(request.receivedAt()));
        if (!inserted.isEmpty()) {
            return Optional.of(inserted.getFirst());
        }
        return Optional.empty();
    }

    @Override
    public int deleteAcceptedBefore(Instant before, int limit) {
        Integer deleted = jdbcTemplate.queryForObject(
                "SELECT public.dev_access_request_cleanup_batch(?, ?)", Integer.class,
                Timestamp.from(before), limit);
        return deleted == null ? 0 : deleted;
    }

    /**
     * 映射受理事实行。
     *
     * @param rows 查询结果
     * @param index 当前行号
     * @return 受理事实
     * @throws SQLException 列读取失败
     */
    private DeviceAccessRequest map(ResultSet rows, int index) throws SQLException {
        return new DeviceAccessRequest(
                rows.getObject("device_id", UUID.class),
                rows.getObject("message_id", UUID.class),
                rows.getObject("tenant_id", UUID.class),
                rows.getObject("project_id", UUID.class),
                TransportProtocol.valueOf(rows.getString("protocol")),
                rows.getString("payload_digest"),
                rows.getTimestamp("received_at").toInstant(),
                rows.getTimestamp("accepted_at") == null ? null : rows.getTimestamp("accepted_at").toInstant());
    }
}
