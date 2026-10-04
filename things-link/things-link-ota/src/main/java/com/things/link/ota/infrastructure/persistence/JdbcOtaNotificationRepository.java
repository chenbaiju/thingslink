package com.things.link.ota.infrastructure.persistence;

import com.things.link.ota.domain.OtaNotificationRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 本域独立通知交付仓储，所有数据库动作参加调用方短事务。 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcOtaNotificationRepository implements OtaNotificationRepository {
    /** 数据平面真实事务连接。 */
    private final JdbcTemplate jdbc;
    /** 注入事务连接。 */
    public JdbcOtaNotificationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    /** {@inheritDoc} */
    @Override public Optional<Claim> claimOne() {
        return jdbc.query("SELECT * FROM ota_notification_claim_one()", JdbcOtaNotificationRepository::claim)
                .stream().findFirst();
    }
    /** {@inheritDoc} */
    @Override public Optional<Claim> authoritativeClaim(UUID event, UUID token) {
        if (event == null || token == null) return Optional.empty();
        return jdbc.query("SELECT * FROM ota_notification_authoritative_claim(?,?)",
                JdbcOtaNotificationRepository::claim, event, token).stream().findFirst();
    }
    /** {@inheritDoc} */
    @Override public Optional<Transport> authoritativeTransport(UUID id, UUID token) {
        if (id == null || token == null) return Optional.empty();
        return jdbc.query("SELECT * FROM ota_notification_authoritative_transport(?,?)",
                JdbcOtaNotificationRepository::transport, id, token).stream().findFirst();
    }
    /** {@inheritDoc} */
    @Override public Optional<Transport> reserveSend(Claim c, String topic, byte[] canonical) {
        var reserved = jdbc.query("SELECT id,reservation_token FROM ota_notification_reserve(?,?,?,?,?)",
                (rs, row) -> new UUID[]{rs.getObject("id", UUID.class), rs.getObject("reservation_token", UUID.class)},
                c.eventId(), c.leaseToken(), c.revision(), topic, canonical).stream().findFirst();
        return reserved.flatMap(ids -> authoritativeTransport(ids[0], ids[1]));
    }
    /** {@inheritDoc} */
    @Override public boolean deferIneligible(Claim c, String reason) {
        return jdbc.update("""
                WITH observed AS MATERIALIZED(SELECT clock_timestamp() AS at_time)
                UPDATE ota_notification_delivery d SET status='RETRY_WAIT',revision=revision+1,
                    next_attempt_at=observed.at_time+interval '5 seconds',updated_at=greatest(observed.at_time,d.updated_at),
                    reason=?,lease_token=NULL,lease_until=NULL FROM observed
                WHERE d.event_id=? AND d.status IN ('WAITING','RETRY_WAIT')
                    AND ota_notification_send_allowed(d.event_id,?,?)
                """, reason, c.eventId(), c.leaseToken(), c.revision()) == 1;
    }
    /** {@inheritDoc} */
    @Override public boolean recordObservation(Transport t, String outcome, Integer httpStatus, String errorCode) {
        int changed = jdbc.update("UPDATE ota_notification_transport SET outcome=?,http_status=?,error_code=?,"
                + "observed_at=clock_timestamp() WHERE id=? AND reservation_token=? AND tenant_id=? AND project_id=?"
                + " AND event_id=? AND outcome IS NULL", outcome, httpStatus, errorCode, t.id(), t.reservationToken(),
                t.tenantId(), t.projectId(), t.eventId());
        return changed == 1;
    }
    /** {@inheritDoc} */
    @Override public boolean settleCurrent(Claim c, UUID transportId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ota_notification_settle(?,?,?,?)", Boolean.class,
                c.eventId(), c.leaseToken(), c.revision(), transportId));
    }
    /** {@inheritDoc} */
    @Override public boolean recoverExpired(Claim c) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ota_notification_recover(?,?,?)", Boolean.class,
                c.eventId(), c.leaseToken(), c.revision()));
    }
    /** {@inheritDoc} */
    @Override public boolean exhaustDue(Claim c, String reason) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ota_notification_exhaust(?,?,?,?)", Boolean.class,
                c.eventId(), c.leaseToken(), c.revision(), reason));
    }
    /** {@inheritDoc} */
    @Override public boolean pauseSecurity(Claim c, String reason) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ota_notification_pause(?,?,?,?)", Boolean.class,
                c.eventId(), c.leaseToken(), c.revision(), reason));
    }
    /** 原始通知身份与当前交付能力均来自数据库。 */
    private static Claim claim(ResultSet rs, int row) throws SQLException {
        return new Claim(rs.getObject("tenant_id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getObject("campaign_id", UUID.class), rs.getObject("job_id", UUID.class),
                rs.getObject("device_id", UUID.class), rs.getObject("firmware_id", UUID.class),
                rs.getObject("event_id", UUID.class), rs.getLong("credential_version"), rs.getInt("job_attempt_no"),
                rs.getString("manifest_sha256"), rs.getLong("revision"), rs.getString("status"), rs.getInt("transport_count"),
                rs.getObject("lease_token", UUID.class), rs.getTimestamp("lease_until").toInstant(),
                rs.getTimestamp("deadline_at").toInstant(), rs.getObject("active_transport_id", UUID.class),
                rs.getString("topic"), rs.getBytes("canonical"));
    }
    /** 传输观察能力不因过期被当前身份覆盖。 */
    private static Transport transport(ResultSet rs, int row) throws SQLException {
        return new Transport(rs.getObject("id", UUID.class), rs.getObject("event_id", UUID.class),
                rs.getObject("tenant_id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getObject("campaign_id", UUID.class), rs.getObject("job_id", UUID.class), rs.getInt("transport_no"),
                rs.getObject("reservation_token", UUID.class), rs.getObject("delivery_lease_token", UUID.class),
                rs.getTimestamp("deadline_at").toInstant(),
                rs.getTimestamp("reserved_at").toInstant(), rs.getString("topic"), rs.getBytes("canonical"));
    }
}
