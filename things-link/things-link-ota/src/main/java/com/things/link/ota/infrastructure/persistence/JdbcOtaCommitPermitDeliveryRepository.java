package com.things.link.ota.infrastructure.persistence;

import com.things.link.ota.domain.OtaCommitPermit;
import com.things.link.ota.domain.OtaCommitPermitDeliveryRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 固定提交许可独立交付预算，网络在调用方事务之外执行。 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcOtaCommitPermitDeliveryRepository implements OtaCommitPermitDeliveryRepository {
    /** 原许可全部字段来自受限查询，不用调用方覆盖范围。 */
    private static final String PROJECTION = """
            (s.permit_snapshot->>'id')::uuid AS p_id, (s.permit_snapshot->>'tenant_id')::uuid AS p_tenant_id, (s.permit_snapshot->>'project_id')::uuid AS p_project_id, (s.permit_snapshot->>'campaign_id')::uuid AS p_campaign_id, (s.permit_snapshot->>'job_id')::uuid AS p_job_id, (s.permit_snapshot->>'device_id')::uuid AS p_device_id, (s.permit_snapshot->>'attempt_no')::integer AS p_attempt_no, (s.permit_snapshot->>'credential_version')::bigint AS p_credential_version, (s.permit_snapshot->>'authorization_id')::uuid AS p_authorization_id, (s.permit_snapshot->>'manifest_sha256')::text AS p_manifest_sha256, (s.permit_snapshot->>'boot_id')::uuid AS p_boot_id, (s.permit_snapshot->>'health_receipt_id')::uuid AS p_health_receipt_id, (s.permit_snapshot->>'payload_hash')::text AS p_payload_hash, (s.permit_snapshot->>'created_at')::timestamptz AS p_created_at, (s.permit_snapshot->>'deadline_at')::timestamptz AS p_deadline_at, decode(substring(s.permit_snapshot->>'canonical' FROM 3),'hex') AS p_canonical
            """;
    /** 实际事务连接。 */
    private final JdbcTemplate jdbc;
    /** 注入数据平面。 */
    public JdbcOtaCommitPermitDeliveryRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    /** {@inheritDoc} */
    @Override public Optional<Claim> claimOne() {
        return jdbc.query("SELECT s.*, "+PROJECTION+" FROM ota_commit_claim_one() s",JdbcOtaCommitPermitDeliveryRepository::claim).stream().findFirst();
    }
    /** {@inheritDoc} */
    @Override public Optional<Claim> authoritativeClaim(UUID permitId,UUID token) {
        return jdbc.query("SELECT s.*, "+PROJECTION+" FROM ota_commit_authoritative_claim(?,?) s",
                JdbcOtaCommitPermitDeliveryRepository::claim,permitId,token).stream().findFirst();
    }
    /** {@inheritDoc} */
    @Override public Optional<Transport> authoritativeTransport(UUID id,UUID token) {
        return jdbc.query("SELECT s.*, "+PROJECTION+" FROM ota_commit_authoritative_transport(?,?) s",
                (r,row)->transport(r,permit(r)),id,token).stream().findFirst();
    }
    /** {@inheritDoc} */
    @Override public Optional<Transport> reserveSend(Claim c,String topic) {
        return jdbc.query("SELECT * FROM ota_commit_reserve(?,?,?,?,?)",(r,row)->new Transport(id(r,"id"),c.permit(),
                r.getInt("transport_no"),id(r,"reservation_token"),id(r,"delivery_lease_token"),at(r,"reserved_at"),topic),
                c.permit().id(),c.leaseToken(),c.revision(),topic,c.permit().canonical()).stream().findFirst();
    }
    /** {@inheritDoc} */
    @Override public boolean deferIneligible(Claim c,String reason) {
        return jdbc.update("""
                UPDATE ota_commit_delivery SET status='RETRY_WAIT',revision=revision+1,
                    next_attempt_at=clock_timestamp()+interval '5 seconds',updated_at=greatest(clock_timestamp(),updated_at),
                    reason=?,lease_token=NULL,lease_until=NULL WHERE event_id=? AND lease_token=? AND revision=?
                    AND lease_until>clock_timestamp() AND deadline_at>clock_timestamp() AND status IN ('WAITING','RETRY_WAIT')
                """,reason,c.permit().id(),c.leaseToken(),c.revision())==1;
    }
    /** {@inheritDoc} */
    @Override public boolean recordObservation(Transport t,String outcome,Integer httpStatus,String errorCode) {
        return jdbc.update("UPDATE ota_commit_transport SET outcome=?,http_status=?,error_code=?,observed_at=clock_timestamp()"
                +" WHERE id=? AND reservation_token=? AND tenant_id=? AND project_id=? AND event_id=? AND outcome IS NULL",
                outcome,httpStatus,errorCode,t.id(),t.reservationToken(),t.permit().tenantId(),t.permit().projectId(),t.permit().id())==1;
    }
    /** {@inheritDoc} */
    @Override public boolean settleCurrent(Claim c,UUID transportId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ota_commit_settle(?,?,?,?)",Boolean.class,c.permit().id(),c.leaseToken(),c.revision(),transportId));
    }
    /** {@inheritDoc} */
    @Override public boolean recoverExpired(Claim c) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ota_commit_recover(?,?,?)",Boolean.class,c.permit().id(),c.leaseToken(),c.revision()));
    }
    /** {@inheritDoc} */
    @Override public boolean exhaustDue(Claim c,String reason) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ota_commit_exhaust(?,?,?,?)",Boolean.class,c.permit().id(),c.leaseToken(),c.revision(),reason));
    }
    /** {@inheritDoc} */
    @Override public boolean pauseSecurity(Claim c,String reason) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ota_commit_pause(?,?,?,?)",Boolean.class,c.permit().id(),c.leaseToken(),c.revision(),reason));
    }
    /** 可信范围及当前交付能力。 */
    private static Claim claim(ResultSet r,int row) throws SQLException {
        return new Claim(permit(r),r.getLong("job_revision"),r.getLong("revision"),r.getString("status"),r.getInt("transport_count"),
                id(r,"lease_token"),at(r,"lease_until"),id(r,"active_transport_id"),r.getString("topic"));
    }
    /** 观察只携原预留能力。 */
    private static Transport transport(ResultSet r,OtaCommitPermit permit) throws SQLException {
        return new Transport(id(r,"id"),permit,r.getInt("transport_no"),id(r,"reservation_token"),id(r,"delivery_lease_token"),at(r,"reserved_at"),r.getString("topic"));
    }
    /** 固定许可字段防御复制由domain承担。 */
    private static OtaCommitPermit permit(ResultSet r) throws SQLException {
        return new OtaCommitPermit(id(r,"p_id"),id(r,"p_tenant_id"),id(r,"p_project_id"),id(r,"p_campaign_id"),id(r,"p_job_id"),id(r,"p_device_id"),
                r.getInt("p_attempt_no"),r.getLong("p_credential_version"),id(r,"p_authorization_id"),r.getString("p_manifest_sha256"),
                id(r,"p_boot_id"),id(r,"p_health_receipt_id"),r.getBytes("p_canonical"),r.getString("p_payload_hash"),at(r,"p_created_at"),at(r,"p_deadline_at"));
    }
    /** 标识读取。 */
    private static UUID id(ResultSet r,String column) throws SQLException { return r.getObject(column,UUID.class); }
    /** 可空数据库时间读取。 */
    private static Instant at(ResultSet r,String column) throws SQLException {
        var t=r.getTimestamp(column);return t==null?null:t.toInstant();
    }
}
