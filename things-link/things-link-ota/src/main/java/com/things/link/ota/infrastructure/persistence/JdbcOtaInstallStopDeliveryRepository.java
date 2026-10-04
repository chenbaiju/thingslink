package com.things.link.ota.infrastructure.persistence;

import com.things.link.ota.domain.OtaInstallStopDeliveryRepository.Envelope;
import com.things.link.ota.domain.OtaInstallStopDeliveryRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 固定安装前原子停止操作及只读查询独立交付预算，网络在调用方事务之外执行。 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcOtaInstallStopDeliveryRepository implements OtaInstallStopDeliveryRepository {
    /** 原查询全部字段来自受限查询，不用调用方覆盖范围。 */
    private static final String PROJECTION = """
            (s.query_snapshot->>'id')::uuid AS p_id,
            (s.query_snapshot->>'tenant_id')::uuid AS p_tenant_id,
            (s.query_snapshot->>'project_id')::uuid AS p_project_id,
            (s.query_snapshot->>'campaign_id')::uuid AS p_campaign_id,
            (s.query_snapshot->>'job_id')::uuid AS p_job_id,
            (s.query_snapshot->>'device_id')::uuid AS p_device_id,
            (s.query_snapshot->>'operation_id')::uuid AS p_operation_id,
            (s.query_snapshot->>'attempt_no')::integer AS p_attempt_no,
            (s.query_snapshot->>'credential_version')::bigint AS p_credential_version,
            (s.query_snapshot->>'kind')::text AS p_kind,
            decode(substring(s.query_snapshot->>'canonical' FROM 3),'hex') AS p_canonical,
            (s.query_snapshot->>'payload_hash')::text AS p_payload_hash,
            (s.query_snapshot->>'deadline_at')::timestamptz AS p_deadline_at
            """;
    /** 实际事务连接。 */
    private final JdbcTemplate jdbc;
    /** 注入数据平面。 */
    public JdbcOtaInstallStopDeliveryRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    /** {@inheritDoc} */
    @Override public Optional<Claim> claimOne() {
        return jdbc.query("SELECT s.*, "+PROJECTION+" FROM ota_install_stop_claim_one() s",JdbcOtaInstallStopDeliveryRepository::claim).stream().findFirst();
    }
    /** {@inheritDoc} */
    @Override public Optional<Claim> authoritativeClaim(UUID permitId,UUID token) {
        return jdbc.query("SELECT s.*, "+PROJECTION+" FROM ota_install_stop_authoritative_claim(?,?) s",
                JdbcOtaInstallStopDeliveryRepository::claim,permitId,token).stream().findFirst();
    }
    /** {@inheritDoc} */
    @Override public Optional<Transport> authoritativeTransport(UUID id,UUID token) {
        return jdbc.query("SELECT s.*, "+PROJECTION+" FROM ota_install_stop_authoritative_transport(?,?) s",
                (r,row)->transport(r,query(r)),id,token).stream().findFirst();
    }
    /** {@inheritDoc} */
    @Override public Optional<Transport> reserveSend(Claim c,String topic) {
        return jdbc.query("SELECT * FROM ota_install_stop_reserve(?,?,?,?,?)",(r,row)->new Transport(id(r,"id"),c.envelope(),
                r.getInt("transport_no"),id(r,"reservation_token"),id(r,"delivery_lease_token"),at(r,"reserved_at"),topic),
                c.envelope().id(),c.leaseToken(),c.revision(),topic,c.envelope().canonical()).stream().findFirst();
    }
    /** {@inheritDoc} */
    @Override public boolean deferIneligible(Claim c,String reason) {
        return jdbc.update("""
                UPDATE ota_install_stop_delivery SET status='RETRY_WAIT',revision=revision+1,
                    next_attempt_at=clock_timestamp()+interval '5 seconds',updated_at=greatest(clock_timestamp(),updated_at),
                    reason=?,lease_token=NULL,lease_until=NULL WHERE event_id=? AND lease_token=? AND revision=?
                    AND lease_until>clock_timestamp() AND deadline_at>clock_timestamp() AND status IN ('WAITING','RETRY_WAIT')
                """,reason,c.envelope().id(),c.leaseToken(),c.revision())==1;
    }
    /** {@inheritDoc} */
    @Override public boolean recordObservation(Transport t,String outcome,Integer httpStatus,String errorCode) {
        return jdbc.update("UPDATE ota_install_stop_transport SET outcome=?,http_status=?,error_code=?,observed_at=clock_timestamp()"
                +" WHERE id=? AND reservation_token=? AND tenant_id=? AND project_id=? AND event_id=? AND outcome IS NULL",
                outcome,httpStatus,errorCode,t.id(),t.reservationToken(),t.envelope().tenantId(),t.envelope().projectId(),t.envelope().id())==1;
    }
    /** {@inheritDoc} */
    @Override public boolean settleCurrent(Claim c,UUID transportId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ota_install_stop_settle(?,?,?,?)",Boolean.class,c.envelope().id(),c.leaseToken(),c.revision(),transportId));
    }
    /** {@inheritDoc} */
    @Override public boolean recoverExpired(Claim c) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ota_install_stop_recover(?,?,?)",Boolean.class,c.envelope().id(),c.leaseToken(),c.revision()));
    }
    /** {@inheritDoc} */
    @Override public boolean exhaustDue(Claim c,String reason) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ota_install_stop_exhaust(?,?,?,?)",Boolean.class,c.envelope().id(),c.leaseToken(),c.revision(),reason));
    }
    /** 可信范围及当前交付能力。 */
    private static Claim claim(ResultSet r,int row) throws SQLException {
        return new Claim(query(r),r.getLong("revision"),r.getString("status"),r.getInt("transport_count"),
                id(r,"lease_token"),at(r,"lease_until"),id(r,"active_transport_id"),r.getString("topic"));
    }
    /** 观察只携原预留能力。 */
    private static Transport transport(ResultSet r,Envelope query) throws SQLException {
        return new Transport(id(r,"id"),query,r.getInt("transport_no"),id(r,"reservation_token"),id(r,"delivery_lease_token"),at(r,"reserved_at"),r.getString("topic"));
    }
    /** 固定查询字段防御复制由domain承担。 */
    private static Envelope query(ResultSet r) throws SQLException {
        return new Envelope(id(r,"p_id"),id(r,"p_tenant_id"),id(r,"p_project_id"),id(r,"p_campaign_id"),
                id(r,"p_job_id"),id(r,"p_device_id"),id(r,"p_operation_id"),r.getInt("p_attempt_no"),
                r.getLong("p_credential_version"),r.getString("p_kind"),r.getBytes("p_canonical"),
                r.getString("p_payload_hash"),at(r,"p_deadline_at"));
    }
    /** 标识读取。 */
    private static UUID id(ResultSet r,String column) throws SQLException { return r.getObject(column,UUID.class); }
    /** 可空数据库时间读取。 */
    private static Instant at(ResultSet r,String column) throws SQLException {
        var t=r.getTimestamp(column);return t==null?null:t.toInstant();
    }
}
